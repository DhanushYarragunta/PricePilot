package com.pricepilot.schedule;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.ScrapeAttempt;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.ScrapeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The only place a scrape result is written to the database.
 *
 * <p>This is a separate bean from {@link ListingRefreshService} on purpose, and the reason is worth
 * stating plainly because it is a mistake that is easy to make and silent when made: Spring
 * implements {@code @Transactional} with a proxy, so a {@code @Transactional} method called from
 * another method <em>of the same bean</em> bypasses the proxy entirely and runs with no transaction
 * at all. Splitting the writes into their own bean means every call to them crosses a proxy
 * boundary and actually gets a transaction.
 *
 * <p>The split pays for itself a second way. Each method here opens a transaction, does a handful of
 * small statements, and commits — the scrape, which can take twenty seconds against a slow retailer,
 * happens in the caller with no database connection checked out. On a Hikari pool of eight on a
 * single small EC2 box, holding connections across HTTP calls to third parties is the difference
 * between a working app and one that deadlocks the moment a few listings are checked at once.
 *
 * <p>Every method re-reads the listing by id inside its transaction rather than accepting the
 * detached instance the caller scraped with. Minutes may have passed; the listing may have been
 * paused, deleted, or refreshed by a manual request in the meantime. Re-reading means the update is
 * applied to current state, and that a deleted listing is handled rather than resurrected.
 */
@Service
public class RefreshWriter {

    private static final Logger log = LoggerFactory.getLogger(RefreshWriter.class);

    private static final int TITLE_LIMIT = 500;
    private static final int IMAGE_URL_LIMIT = 1000;
    private static final int HOST_LIMIT = 160;
    private static final int STRATEGY_LIMIT = 48;

    private final ListingRepository listings;
    private final PricePointRepository prices;
    private final ScrapeAttemptRepository attempts;
    private final IntervalPolicy policy;

    public RefreshWriter(ListingRepository listings,
                         PricePointRepository prices,
                         ScrapeAttemptRepository attempts,
                         IntervalPolicy policy) {
        this.listings = listings;
        this.prices = prices;
        this.attempts = attempts;
        this.policy = policy;
    }

    // ------------------------------------------------------------------ success

    /**
     * Stores a successful observation and reschedules the listing.
     *
     * <p>A price point is appended on every success, not only when the price moved. The {@code
     * changed} flag records which it was. Storing only changes would make the table smaller and the
     * statistics wrong: a 30-day average over change events alone weights a price that held for
     * three weeks the same as one that held for an hour, and "how many observations back this
     * verdict" would stop meaning anything. The table grows by a few hundred rows a day, which is
     * nothing, and unlike scrape telemetry it is never pruned — the history is the product.
     */
    @Transactional
    public RefreshResult recordSuccess(Long listingId, ScrapeResult result, TriggerSource source) {
        Listing listing = listings.findWithProductById(listingId).orElse(null);
        if (listing == null) {
            return RefreshResult.vanished(listingId);
        }

        ProductSnapshot snapshot = result.snapshot();
        Long productId = listing.getProduct().getId();

        PricePoint previous = prices.findFirstByListingIdOrderByCapturedAtDesc(listingId).orElse(null);
        BigDecimal previousPrice = previous == null ? null : previous.getPrice();
        boolean priceChanged = previousPrice == null
                || previousPrice.compareTo(snapshot.price()) != 0;
        boolean cameBackInStock = previous != null && !previous.isInStock() && snapshot.inStock();

        // Asked before the new row is inserted, or the new observation would be compared against
        // itself and every check would look like a record low. Also requires a prior observation to
        // exist: the first price we ever see is trivially the lowest, and announcing it as an
        // all-time low would be true and useless.
        boolean allTimeLow = false;
        if (snapshot.inStock() && previous != null) {
            BigDecimal lowSoFar = prices.allTimeLow(productId).orElse(null);
            allTimeLow = lowSoFar == null || snapshot.price().compareTo(lowSoFar) < 0;
        }

        PricePoint point = new PricePoint();
        point.setListing(listing);
        point.setPrice(snapshot.price());
        point.setListPrice(snapshot.mrp());
        point.setCurrency(snapshot.currency() == null ? "INR" : snapshot.currency());
        point.setInStock(snapshot.inStock());
        point.setSourceStrategy(cap(result.strategy(), STRATEGY_LIMIT));
        point.setChanged(priceChanged);
        point.setCapturedAt(Instant.now());
        prices.save(point);

        boolean urgent = listings.hasUrgentTracker(productId);
        int interval = policy.afterSuccess(listing.getCheckIntervalMinutes(), priceChanged, urgent);
        Instant nextCheck = policy.dueAfter(interval);

        Instant now = Instant.now();
        listing.setLastCheckedAt(now);
        listing.setLastSuccessAt(now);
        listing.setLastStrategy(cap(result.strategy(), STRATEGY_LIMIT));
        listing.setConsecutiveFailures(0);
        listing.setCheckIntervalMinutes(interval);
        listing.setNextCheckAt(nextCheck);
        // A success clears a block. Retailers throttle rather than ban, and a listing that was
        // BLOCKED last week and answers today should go straight back to normal scheduling instead
        // of needing a human to notice and un-stick it.
        if (listing.getStatus() == ListingStatus.BLOCKED) {
            log.info("Listing {} ({}) is responding again; clearing BLOCKED",
                    listingId, listing.getRetailer());
            listing.setStatus(ListingStatus.ACTIVE);
        }
        applyListingTitle(listing, snapshot);
        enrichProduct(listing.getProduct(), snapshot);

        saveAttempt(listing, result, source);

        return new RefreshResult(
                listingId,
                productId,
                ScrapeOutcome.SUCCESS,
                listing.getRetailer(),
                snapshot.price(),
                previousPrice,
                snapshot.mrp(),
                snapshot.inStock(),
                cameBackInStock,
                priceChanged,
                allTimeLow,
                nextCheck,
                interval,
                result.strategy(),
                result.latencyMs(),
                result.paid(),
                null);
    }

    // ------------------------------------------------------------------ failure

    /**
     * Records a failed attempt, backs the interval off, and retires the listing if the failure is
     * terminal.
     *
     * <p>No price point is written. That is the point of keeping history append-only and
     * fetch-derived: a check that could not read a price leaves no trace in the price series, so a
     * month of Amazon blocking us shows up honestly as a gap and as a visible failure rate on the
     * ops page, rather than as a flat line implying the price never moved.
     */
    @Transactional
    public RefreshResult recordFailure(Long listingId, ScrapeResult result, TriggerSource source) {
        Listing listing = listings.findWithProductById(listingId).orElse(null);
        if (listing == null) {
            return RefreshResult.vanished(listingId);
        }

        int failures = listing.getConsecutiveFailures() + 1;
        int interval = policy.afterFailure(listing.getCheckIntervalMinutes(), result.outcome());
        Instant nextCheck = policy.dueAfter(interval);
        ListingStatus status = policy.statusAfterFailure(result.outcome(), failures, result.isGone());

        listing.setLastCheckedAt(Instant.now());
        listing.setConsecutiveFailures(failures);
        listing.setCheckIntervalMinutes(interval);
        listing.setNextCheckAt(nextCheck);
        if (status != listing.getStatus()) {
            log.warn("Listing {} ({}) -> {} after {} consecutive failures; last outcome {}: {}",
                    listingId, listing.getRetailer(), status, failures, result.outcome(),
                    result.message());
            listing.setStatus(status);
        }

        saveAttempt(listing, result, source);

        return new RefreshResult(
                listingId,
                listing.getProduct().getId(),
                result.outcome(),
                listing.getRetailer(),
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                nextCheck,
                interval,
                result.strategy(),
                result.latencyMs(),
                result.paid(),
                result.message());
    }

    // ------------------------------------------------------------------ skip

    /**
     * Records that a due check was deliberately not made because the stored price was still fresh.
     *
     * <p>Logged as an attempt rather than passed over in silence, because avoided requests are the
     * headline efficiency claim of this design and a claim that is not counted is just an opinion.
     * {@code SKIPPED_FRESH} is excluded from every success-rate denominator in
     * {@link ScrapeAttemptRepository}, precisely so counting it cannot flatter the reliability
     * numbers with work that never happened.
     *
     * <p>The interval is left alone — nothing was learned about how volatile this listing is, so
     * there is nothing to adapt to. Only the due time moves, and only a little.
     */
    @Transactional
    public RefreshResult recordSkip(Long listingId, ScrapeResult result, TriggerSource source) {
        Listing listing = listings.findWithProductById(listingId).orElse(null);
        if (listing == null) {
            return RefreshResult.vanished(listingId);
        }

        Instant nextCheck = policy.dueAfterSkip();
        listing.setNextCheckAt(nextCheck);

        saveAttempt(listing, result, source);

        PricePoint latest = prices.findFirstByListingIdOrderByCapturedAtDesc(listingId).orElse(null);
        return new RefreshResult(
                listingId,
                listing.getProduct().getId(),
                ScrapeOutcome.SKIPPED_FRESH,
                listing.getRetailer(),
                latest == null ? null : latest.getPrice(),
                null,
                latest == null ? null : latest.getListPrice(),
                latest != null && latest.isInStock(),
                false,
                false,
                false,
                nextCheck,
                listing.getCheckIntervalMinutes(),
                "cache",
                0,
                false,
                "Reused a price captured " + describeAge(latest) + " ago; no request was made");
    }

    // ------------------------------------------------------------------ scheduling helpers

    /**
     * Pushes a listing's due time out without recording an attempt.
     *
     * <p>For the case where the refresh could not even be started — the listing had been paused, or
     * the orchestration itself threw. Nothing was learned and nothing was fetched, so there is no
     * attempt to log, but the due time still has to move or the dispatcher will pick the same
     * listing up on every tick and starve everything behind it.
     */
    @Transactional
    public void deferWithoutAttempt(Long listingId, int minutes) {
        listings.findById(listingId).ifPresent(listing -> listing.setNextCheckAt(policy.dueAfter(minutes)));
    }

    /** Pins every active listing of a product to the tight interval and makes them due now. */
    @Transactional
    public void expedite(Long productId) {
        Instant now = Instant.now();
        for (Listing listing : listings.findByProductIdAndStatus(productId, ListingStatus.ACTIVE)) {
            listing.setCheckIntervalMinutes(policy.alertedIntervalMinutes());
            if (listing.getNextCheckAt() == null || listing.getNextCheckAt().isAfter(now)) {
                listing.setNextCheckAt(now);
            }
        }
    }

    /**
     * Takes a listing out of the rotation permanently.
     *
     * <p>For failures no amount of retrying will fix — a URL whose rule has been removed, a product
     * page the retailer has deleted. The row is kept rather than deleted so the price history it
     * produced stays intact and the dashboard can explain why the column stopped updating; only the
     * due time is pushed far out so a {@code DEAD} listing cannot cost anything even if something
     * later flips it back to active by mistake.
     */
    @Transactional
    public void retire(Long listingId, String reason) {
        listings.findById(listingId).ifPresent(listing -> {
            listing.setStatus(ListingStatus.DEAD);
            listing.setLastCheckedAt(Instant.now());
            listing.setCheckIntervalMinutes(policy.maxIntervalMinutes());
            listing.setNextCheckAt(policy.dueAfter(policy.maxIntervalMinutes()));
            log.info("Listing {} ({}) retired: {}", listingId, listing.getRetailer(), reason);
        });
    }

    // ------------------------------------------------------------------ internals

    private void saveAttempt(Listing listing, ScrapeResult result, TriggerSource source) {
        ScrapeAttempt attempt = new ScrapeAttempt();
        attempt.setListing(listing);
        attempt.setHost(cap(result.host(), HOST_LIMIT));
        attempt.setStrategy(cap(result.strategy() == null ? "none" : result.strategy(), STRATEGY_LIMIT));
        attempt.setOutcome(result.outcome());
        attempt.setHttpStatus(result.httpStatus());
        attempt.setLatencyMs(result.latencyMs());
        attempt.setBytes(result.bytes());
        attempt.setErrorMessage(result.message());
        attempt.setTriggerSource(source == null ? TriggerSource.SCHEDULER : source);
        attempts.save(attempt);
    }

    /** Keeps the listing's own title in step with the page, since that is this retailer's name for it. */
    private void applyListingTitle(Listing listing, ProductSnapshot snapshot) {
        String title = cap(snapshot.title(), TITLE_LIMIT);
        if (title != null && !title.isBlank() && !title.equals(listing.getTitle())) {
            listing.setTitle(title);
        }
    }

    /**
     * Fills in product details the original scrape missed — but never overwrites them.
     *
     * <p>The product's title and image belong to the listing it was created from. Letting every
     * subsequent scrape of every retailer rewrite them would mean the name on the dashboard changed
     * depending on which storefront happened to be checked last, and a secondary retailer's title is
     * usually the worse one. So this only ever fills a gap.
     */
    private void enrichProduct(Product product, ProductSnapshot snapshot) {
        if (product == null) {
            return;
        }
        if (isBlank(product.getTitle())) {
            String title = cap(snapshot.title(), TITLE_LIMIT);
            if (!isBlank(title)) {
                product.setTitle(title);
            }
        }
        if (isBlank(product.getImageUrl())) {
            String image = cap(snapshot.imageUrl(), IMAGE_URL_LIMIT);
            if (!isBlank(image)) {
                product.setImageUrl(image);
            }
        }
    }

    private static String describeAge(PricePoint point) {
        if (point == null || point.getCapturedAt() == null) {
            return "recently";
        }
        long minutes = java.time.Duration.between(point.getCapturedAt(), Instant.now()).toMinutes();
        if (minutes < 1) {
            return "under a minute";
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String cap(String value, int limit) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }
}
