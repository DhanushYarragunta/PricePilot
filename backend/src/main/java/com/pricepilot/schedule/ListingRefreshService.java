package com.pricepilot.schedule;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.alerts.AlertService;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UnsupportedUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Refreshes one listing, end to end.
 *
 * <p>Everything that checks a price goes through here — the scheduler's tick, the manual refresh
 * button, and the first fetch when a product is added — so the freshness rule, the telemetry, the
 * adaptive rescheduling, the alerts and the AI analysis happen in the same order no matter what
 * started it. Four call sites doing their own version of this is how a system ends up with prices
 * that were recorded but never alerted on.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. The sequence is: read what is needed, release
 * the connection, spend up to twenty seconds fetching a retailer page, then write in one short
 * transaction via {@link RefreshWriter}. A transaction wrapped around the whole method would pin a
 * pool connection for the duration of the fetch, and with eight connections on one small EC2 box a
 * dozen concurrent checks would exhaust the pool and take the web UI down with them. The failure
 * would look like a database problem and would in fact be this method.
 *
 * <p>It never throws. A failure to refresh one listing is an ordinary event — and one listing's bad
 * day must not stop the dispatcher processing the rest of the batch.
 */
@Service
public class ListingRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ListingRefreshService.class);

    /**
     * Freshness window applied to a human pressing refresh. Far shorter than the scheduler's, since
     * the point of the button is to see current data, but not zero: it collapses a double-click and
     * two people refreshing the same product at the same moment into one fetch, and neither of them
     * can tell the difference between a price read now and one read forty seconds ago.
     */
    private static final Duration MANUAL_FRESHNESS = Duration.ofSeconds(60);

    /** How long to defer a listing that was not in a fit state to check. */
    private static final int DEFER_MINUTES = 60;

    private final ListingRepository listings;
    private final ScrapeEngine engine;
    private final RefreshWriter writer;
    private final AlertService alerts;
    private final DealAnalyzer analyzer;
    private final AppProperties props;
    private final ScrapeMetrics metrics;
    private final ConcurrentHashMap<Long, CompletableFuture<RefreshResult>> inFlight = new ConcurrentHashMap<>();

    public ListingRefreshService(ListingRepository listings,
                                 ScrapeEngine engine,
                                 RefreshWriter writer,
                                 AlertService alerts,
                                 DealAnalyzer analyzer,
                                 AppProperties props) {
        this(listings, engine, writer, alerts, analyzer, props, null);
    }

    @Autowired
    public ListingRefreshService(ListingRepository listings, ScrapeEngine engine, RefreshWriter writer,
                                 AlertService alerts, DealAnalyzer analyzer, AppProperties props,
                                 ScrapeMetrics metrics) {
        this.listings = listings;
        this.engine = engine;
        this.writer = writer;
        this.alerts = alerts;
        this.analyzer = analyzer;
        this.props = props;
        this.metrics = metrics;
    }

    /**
     * Checks one listing and does everything that follows from the result.
     *
     * @param listingId the listing to refresh
     * @param source    what triggered it; recorded on the attempt row and used to pick the freshness
     *                  window
     * @return what happened, never {@code null}
     */
    public RefreshResult refresh(Long listingId, TriggerSource source) {
        Instant requestedAt = Instant.now();
        while (true) {
            CompletableFuture<RefreshResult> owner = new CompletableFuture<>();
            CompletableFuture<RefreshResult> existing = inFlight.putIfAbsent(listingId, owner);
            if (existing != null) {
                try {
                    long waitMs = Math.max(1000L, (long) props.getBrowserAct().getTimeoutMs()
                            + props.getScrape().getTimeoutMs() + 60000L);
                    RefreshResult shared = existing.get(waitMs, TimeUnit.MILLISECONDS);
                    if (!shared.isSuccess() && !shared.isSkipped()) {
                        recordDuplicate();
                        return sharedCopy(shared);
                    }
                    Listing current = listings.findWithProductById(listingId).orElse(null);
                    if (current == null) return RefreshResult.vanished(listingId);
                    Instant observedAt = current.getLastSuccessAt();
                    boolean capturedDuringThisRequest = observedAt != null && !observedAt.isBefore(requestedAt)
                            && !observedAt.isAfter(Instant.now());
                    // Scheduler cache policy can be more permissive than a manual request. Waiting
                    // on its future must not silently weaken this caller's freshness requirement.
                    if (!capturedDuringThisRequest && !isStillFresh(current, source)) {
                        inFlight.remove(listingId, existing);
                        continue;
                    }
                    recordDuplicate();
                    return sharedCopy(shared);
                } catch (InterruptedException e) {
                    recordDuplicate();
                    Thread.currentThread().interrupt();
                    return sharedWaitFailure(listingId, "Interrupted while waiting for the shared refresh");
                } catch (ExecutionException | TimeoutException e) {
                    recordDuplicate();
                    return sharedWaitFailure(listingId, "The shared refresh did not complete; previous price preserved");
                }
            }
            try {
                RefreshResult result = refreshOwned(listingId, source).withCompletedAt(Instant.now());
                // The persisted observation is available before optional mail/AI follow-ups finish.
                owner.complete(result);
                inFlight.remove(listingId, owner);
                runFollowUps(result);
                return result;
            } catch (RuntimeException | Error e) {
                owner.completeExceptionally(e);
                throw e;
            } finally {
                inFlight.remove(listingId, owner);
            }
        }
    }

    private void recordDuplicate() {
        if (metrics != null) metrics.duplicateSkipped();
    }

    private static RefreshResult sharedCopy(RefreshResult shared) {
        boolean reusedPrice = shared.isSuccess() || shared.isSkipped();
        return new RefreshResult(shared.listingId(), shared.productId(),
                reusedPrice ? ScrapeOutcome.SKIPPED_FRESH : shared.outcome(),
                shared.retailer(), shared.price(), null, shared.mrp(), shared.inStock(),
                false, false, false, shared.nextCheckAt(), shared.intervalMinutes(), "shared", 0, false,
                reusedPrice ? "Shared a refresh already in progress; no additional submission was made" : shared.message(),
                shared.completedAt());
    }

    private static RefreshResult sharedWaitFailure(Long listingId, String message) {
        return new RefreshResult(listingId, null, ScrapeOutcome.ERROR, null, null, null, null,
                false, false, false, false, null, 0, "shared", 0, false, message);
    }

    private RefreshResult refreshOwned(Long listingId, TriggerSource source) {
        Listing listing = listings.findWithProductById(listingId).orElse(null);
        if (listing == null) {
            log.debug("Listing {} vanished before it could be refreshed", listingId);
            return RefreshResult.vanished(listingId);
        }

        // Paused and dead listings can still be handed here by a stale batch or a manual request.
        // Pushing the due time out keeps them from being re-picked every tick.
        if (!listing.isActive() && listing.getStatus() != ListingStatus.BLOCKED
                && source == TriggerSource.SCHEDULER) {
            log.debug("Skipping listing {}: status is {}", listingId, listing.getStatus());
            writer.deferWithoutAttempt(listingId, DEFER_MINUTES);
            return new RefreshResult(listingId, listing.getProduct().getId(), ScrapeOutcome.SKIPPED_FRESH,
                    listing.getRetailer(), null, null, null, false, false, false, false,
                    null, listing.getCheckIntervalMinutes(), "none", 0, false,
                    "Listing is " + listing.getStatus() + "; no check was made");
        }

        // A manual refresh may have rescheduled a listing after this scheduler job was queued.
        // Do not spend another provider submission merely because the queue contains stale work.
        if (source == TriggerSource.SCHEDULER && listing.getNextCheckAt() != null
                && listing.getNextCheckAt().isAfter(Instant.now())) {
            return new RefreshResult(listingId, listing.getProduct().getId(), ScrapeOutcome.SKIPPED_FRESH,
                    listing.getRetailer(), null, null, null, false, false, false, false,
                    listing.getNextCheckAt(), listing.getCheckIntervalMinutes(), "none", 0, false,
                    "The listing was rescheduled before this check started; no request was made");
        }

        if (isStillFresh(listing, source)) {
            if (metrics != null) metrics.cacheHit();
            ScrapeResult skipped = ScrapeResult.skippedFresh(hostOf(listing), listing.getUrl());
            return writer.recordSkip(listingId, skipped, source);
        }

        ScrapeResult result;
        try {
            // scrapeRaw rather than scrape(url, host), because Listing.retailer holds a rule key
            // like "amazon_in" rather than a hostname, and scrapeRaw resolves the rule from the URL
            // itself. The stored URL is already canonical, so the canonicalisation is a no-op — but
            // it is the one call that cannot get the host/rule pairing wrong.
            Duration maxAge = source == TriggerSource.MANUAL ? MANUAL_FRESHNESS
                    : source == TriggerSource.SCHEDULER ? Duration.ZERO
                    : Duration.ofSeconds(Math.max(0, props.getScrape().getFreshnessWindowSeconds()));
            result = engine.scrapeRaw(listing.getUrl(), maxAge);
        } catch (UnsupportedUrlException e) {
            // The URL stored against this listing is no longer one we can handle: a rule was
            // removed, or the retailer restructured its URLs. Retiring it is right — retrying
            // forever would burn a slot in every batch and never succeed.
            log.warn("Retiring listing {} — its URL is no longer supported: {}", listingId, e.getMessage());
            writer.retire(listingId, "URL is no longer supported: " + e.getMessage());
            return new RefreshResult(listingId, listing.getProduct().getId(), ScrapeOutcome.ERROR,
                    listing.getRetailer(), null, null, null, false, false, false, false,
                    null, listing.getCheckIntervalMinutes(), "none", 0, false, e.getMessage());
        } catch (RuntimeException e) {
            // The engine is built not to throw for scraping failures, so anything arriving here is a
            // bug rather than a retailer problem. Record it as an attempt so it shows up on the ops
            // page instead of only in the logs, and let the listing back off like any other failure.
            log.error("Unexpected error scraping listing {} ({}): {}",
                    listingId, listing.getUrl(), e.toString(), e);
            ScrapeResult crash = ScrapeResult.aborted(ScrapeOutcome.ERROR, hostOf(listing),
                    listing.getUrl(), null, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return writer.recordFailure(listingId, crash, source);
        }

        RefreshResult refresh = result.isSuccess()
                ? writer.recordSuccess(listingId, result, source)
                : writer.recordFailure(listingId, result, source);

        // Everything past this point is after the commit, and none of it may be allowed to undo the
        // observation that was just stored. A price we fetched is worth keeping even if the email
        // server is down and Claude is out of budget.
        return refresh;
    }

    /**
     * Alerts and AI analysis, each independently guarded.
     *
     * <p>Order matters: alerts first. They are the time-critical half — a price drop is worth
     * knowing about now — and they depend only on data already written. Analysis is the slow,
     * paid, optional half, so it goes last and is allowed to fail silently.
     */
    private void runFollowUps(RefreshResult refresh) {
        if (!refresh.isSuccess() || refresh.productId() == null) {
            return;
        }

        try {
            alerts.evaluate(refresh);
        } catch (RuntimeException e) {
            log.error("Alert evaluation failed for product {} after refreshing listing {}: {}",
                    refresh.productId(), refresh.listingId(), e.toString(), e);
        }

        // Only when the price actually moved. Re-asking Claude for an opinion on an unchanged price
        // would spend money to be told the same thing again; DealAnalyzer.analyseIfDue applies its
        // own age and delta gates on top of this.
        if (!refresh.priceChanged()) {
            return;
        }
        try {
            // A verdict only comes back when an analysis actually ran, which is what makes it safe
            // to notify on: the alert fires on a fresh opinion about a price that just changed, not
            // on a cached one being re-read.
            analyzer.analyseIfDue(refresh.productId())
                    .ifPresent(analysis -> alerts.dealDetected(refresh.productId(), analysis));
        } catch (RuntimeException e) {
            // analyseIfDue is documented not to throw. Belt and braces: AI is a feature, price
            // tracking is the product, and nothing about the former may break the latter.
            log.error("Deal analysis failed for product {}: {}", refresh.productId(), e.toString(), e);
        }
    }

    /**
     * Whether a fetch can be skipped because what we already have is new enough.
     *
     * <p>This is the mechanism that makes popular products cheap: fifty users watching one Flipkart
     * URL share one listing, and whichever of them causes a check first, the rest are served the
     * same observation. The skip is recorded as an attempt with outcome
     * {@link ScrapeOutcome#SKIPPED_FRESH} so the avoided requests are counted rather than claimed.
     */
    private boolean isStillFresh(Listing listing, TriggerSource source) {
        Instant last = listing.getLastSuccessAt();
        if (last == null || last.isAfter(Instant.now())) {
            return false;
        }
        Duration window = switch (source) {
            case SCHEDULER -> Duration.ofSeconds(props.getScrape().getFreshnessWindowSeconds());
            case MANUAL -> MANUAL_FRESHNESS;
            // A listing being added or discovered has no useful history yet, and the person adding
            // it is waiting on the answer. Always fetch.
            case ONBOARD, DISCOVERY -> Duration.ZERO;
        };
        if (window.isZero()) {
            return false;
        }
        return last.isAfter(Instant.now().minus(window));
    }

    /**
     * Best-effort host for telemetry when no fetch happened.
     *
     * <p>Read off the stored URL rather than from the rules, because this is used on paths where the
     * rule could not be resolved in the first place. The {@code host} column only needs to be good
     * enough to group attempts by retailer on the ops page.
     */
    private static String hostOf(Listing listing) {
        String url = listing.getUrl();
        if (url == null) {
            return listing.getRetailer() == null ? "unknown" : listing.getRetailer();
        }
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        return host.isBlank() ? "unknown" : host;
    }
}
