package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.Tracker;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.schedule.ListingRefreshService;
import com.pricepilot.schedule.RefreshResult;
import com.pricepilot.schedule.RefreshWriter;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Adding a product to track, and refreshing one on demand.
 *
 * <p>This is where the project's central constraint is enforced in code: <b>a price only ever enters
 * the database because this server fetched the retailer's page and read it.</b> Nothing here accepts
 * a price from the caller, and nothing here reads one from a search result. Pasting a URL causes a
 * real HTTP request to a real product page, and if that request fails, no product is created and the
 * user is told what went wrong. The alternative — creating the product anyway and filling the price
 * in from a search snippet — would make the app feel more reliable and make its data worthless,
 * because a snippet price is whatever a search index last happened to see, with no way to tell
 * whether that was ten minutes or ten weeks ago.
 *
 * <p>Deliberately not {@code @Transactional}: the fetch in the middle of {@link #track} can take
 * twenty seconds, and holding one of eight pooled connections for that long is how a handful of
 * concurrent "add product" clicks takes the whole site down. Writes go through
 * {@link TrackingWriter} and {@link RefreshWriter}, each of which opens a short transaction of its
 * own.
 */
@Service
public class ProductTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ProductTrackingService.class);

    private final UrlCanonicalizer canonicalizer;
    private final ScrapeEngine engine;
    private final TrackingWriter writer;
    private final RefreshWriter refreshWriter;
    private final ListingRefreshService refreshService;
    private final SiblingDiscoveryService discovery;
    private final ListingRepository listings;
    private final AppProperties props;

    public ProductTrackingService(UrlCanonicalizer canonicalizer,
                                  ScrapeEngine engine,
                                  TrackingWriter writer,
                                  RefreshWriter refreshWriter,
                                  ListingRefreshService refreshService,
                                  SiblingDiscoveryService discovery,
                                  ListingRepository listings,
                                  AppProperties props) {
        this.canonicalizer = canonicalizer;
        this.engine = engine;
        this.writer = writer;
        this.refreshWriter = refreshWriter;
        this.refreshService = refreshService;
        this.discovery = discovery;
        this.listings = listings;
        this.props = props;
    }

    /**
     * Tracks the product at {@code rawUrl} for {@code user}.
     *
     * <p>Three paths, in order of how often they happen. The URL is already tracked by someone — the
     * common case for a popular phone — so the existing listing and its whole price history are
     * reused and no request is sent to the retailer at all. The URL is new, so it is fetched once,
     * and the product, listing and first price point are created from what the page said. Or the
     * fetch fails, and nothing is created.
     *
     * @return what was added, including whether a fetch was needed
     */
    public Added track(AppUser user, String rawUrl, BigDecimal targetPrice,
                       boolean notifyOnDeal, String note) {
        UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(rawUrl);

        TrackingWriter.ExistingListing existing = writer.findExisting(canonical.hash()).orElse(null);
        if (existing != null) {
            Tracker tracker = writer.trackFor(
                    user, existing.productId(), targetPrice, notifyOnDeal, note);
            log.info("User {} joined existing product {} via {}",
                    user.getId(), existing.productId(), canonical.host());
            return new Added(existing.productId(), tracker.getId(), existing.productTitle(),
                    canonical.retailer(), false, messageForExisting(existing));
        }

        // New URL. One fetch, right now, with the user waiting on it — which is also the honest
        // moment to discover that a retailer is blocking us, rather than letting a product sit on
        // the dashboard with a permanently empty chart.
        ScrapeResult result = fetchOrExplain(canonical);

        Listing listing;
        try {
            listing = writer.createProductAndListing(canonical, result.snapshot());
        } catch (DataIntegrityViolationException e) {
            // Two people added the same new URL within the same second. The other request's product
            // and listing are committed; ours rolled back whole. Join theirs.
            log.info("Lost the race to create {}; joining the listing that won", canonical.url());
            TrackingWriter.ExistingListing winner = writer.findExisting(canonical.hash())
                    .orElseThrow(() -> ApiException.conflict("add_conflict",
                            "That product could not be added just now. Please try again."));
            Tracker tracker = writer.trackFor(
                    user, winner.productId(), targetPrice, notifyOnDeal, note);
            return new Added(winner.productId(), tracker.getId(), winner.productTitle(),
                    canonical.retailer(), false, null);
        }

        Long productId = listing.getProduct().getId();
        String title = listing.getProduct().getTitle();

        // Stores the observation we already have in hand, rather than fetching the same page twice.
        // Going through RefreshWriter rather than inserting a price point directly means the first
        // observation is written by exactly the same code as every later one — same price point,
        // same attempt row, same adaptive interval — so the first point in a chart is never a
        // special case, and the onboarding fetch is counted in the ops metrics like any other.
        refreshWriter.recordSuccess(listing.getId(), result, TriggerSource.ONBOARD);

        Tracker tracker = writer.trackFor(user, productId, targetPrice, notifyOnDeal, note);

        // Finding the same product at other retailers needs a search call plus a real fetch per
        // candidate, which is far too slow to keep the user waiting for. It runs in the background
        // and the extra retailers appear on the product page when they are ready.
        discovery.discoverInBackground(productId);

        return new Added(productId, tracker.getId(), title, canonical.retailer(), result.paid(), null);
    }

    /**
     * Re-checks every active listing of a product now, as far as the freshness window allows.
     *
     * <p>Sequential rather than parallel, and that is not laziness. {@code PolitenessGate} serialises
     * requests per host anyway, so firing four fetches at once would simply queue three of them while
     * holding three more threads. Sequential also keeps the request's own latency honest: the caller
     * learns how long checking four retailers actually takes.
     *
     * @return one result per listing, in the order they were checked
     */
    public List<RefreshResult> refreshNow(Long productId) {
        List<Listing> active = listings.findByProductIdAndStatus(productId, ListingStatus.ACTIVE);
        if (active.isEmpty()) {
            // Either the product does not exist or every listing has been retired. Both deserve an
            // explanation rather than an empty array that looks like a frontend bug.
            throw ApiException.badRequest(
                    "There is nothing to refresh for this product — none of its listings are "
                    + "currently active.");
        }
        return active.stream()
                .map(listing -> refreshService.refresh(listing.getId(), TriggerSource.MANUAL))
                .toList();
    }

    /** Adds another retailer's URL to a product the user is already tracking. */
    public Added addRetailer(AppUser user, Long productId, String rawUrl) {
        UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(rawUrl);

        TrackingWriter.ExistingListing existing = writer.findExisting(canonical.hash()).orElse(null);
        if (existing != null) {
            if (existing.productId().equals(productId)) {
                throw ApiException.conflict("already_tracked",
                        "That URL is already one of this product's listings.");
            }
            // The same URL cannot belong to two products: it is one page with one price history.
            // Merging products is a genuinely hard problem and guessing wrong corrupts both
            // histories, so this refuses and explains instead.
            throw ApiException.conflict("url_belongs_elsewhere",
                    "That URL is already tracked as a different product (\""
                    + existing.productTitle() + "\"). Track that one instead.");
        }

        ScrapeResult result = fetchOrExplain(canonical);
        Listing listing = writer.addListing(productId, canonical, result.snapshot())
                .orElseThrow(() -> ApiException.limitReached(
                        "This product already has the maximum of "
                        + props.getLimits().getMaxListingsPerProduct() + " retailers."));

        refreshWriter.recordSuccess(listing.getId(), result, TriggerSource.ONBOARD);
        return new Added(productId, null, listing.getTitle(), canonical.retailer(), result.paid(), null);
    }

    /**
     * Fails unless this user tracks this product.
     *
     * <p>Delegated so that controllers depend on this one application service rather than reaching
     * past it into the transactional writer. The rule itself lives in {@link TrackingWriter}, beside
     * the other ownership checks.
     */
    public void requireTracking(Long userId, Long productId) {
        writer.requireTracking(userId, productId);
    }

    /**
     * Chooses which retailer's price represents the product in list views.
     *
     * <p>Authorisation first, then the change. Doing it in that order matters: the writer's own method
     * only verifies that the listing belongs to the product, which would otherwise let anyone who
     * knows a product id rearrange someone else's listings.
     */
    public void setPrimaryListing(Long userId, Long productId, Long listingId) {
        writer.requireTracking(userId, productId);
        writer.setPrimaryListing(productId, listingId);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Fetches a candidate page and turns any failure into an explanation worth reading.
     *
     * <p>The messages are specific because the outcomes call for genuinely different responses from
     * the user. "Blocked" means try again later or try another retailer; "no price" means the URL is
     * probably a search or category page rather than a product page; a 404 means the link is dead.
     * One generic "could not add product" would leave all three looking like the same bug.
     *
     * <p>One known gap, recorded rather than hidden: a failure here writes no {@code scrape_attempt}
     * row, because that table is keyed to a listing and no listing exists yet. Failed onboarding
     * fetches therefore do not appear in the host success-rate table on the ops page. The alternative
     * — creating a listing in order to have something to attach telemetry to — would leave a dead
     * product row behind for every mistyped URL, which is a worse trade.
     */
    private ScrapeResult fetchOrExplain(UrlCanonicalizer.Canonical canonical) {
        ScrapeResult result = engine.scrape(canonical.url(), canonical.rule());
        if (result.isSuccess() && result.snapshot() != null && result.snapshot().price() != null) {
            return result;
        }

        log.info("Rejected {} while adding a product: {} ({})",
                canonical.url(), result.outcome(), result.message());

        String retailer = canonical.retailer();
        ScrapeOutcome outcome = result.outcome();
        throw switch (outcome) {
            case BLOCKED -> ApiException.unavailable(
                    retailer + " is currently blocking automated requests for this page. This is "
                    + "usually temporary — try again in a few minutes, or add the same product from "
                    + "a different retailer.");
            case NO_PRICE -> ApiException.badRequest(
                    "That page loaded, but no price could be found on it. Make sure the link points "
                    + "at a single product rather than a search, category or offer page.");
            case TIMEOUT -> ApiException.unavailable(
                    retailer + " did not respond in time. Please try again in a moment.");
            case HTTP_ERROR -> result.isGone()
                    ? ApiException.badRequest(
                            "That product page no longer exists on " + retailer + ".")
                    : ApiException.unavailable(
                            retailer + " returned an error for that page"
                            + (result.httpStatus() == null ? "" : " (HTTP " + result.httpStatus() + ")")
                            + ". Please try again later.");
            // SUCCESS reaches this switch only when the snapshot came back without a price, which
            // from the user's point of view is the same situation as NO_PRICE.
            case SUCCESS -> ApiException.badRequest(
                    "That page could not be read as a product page. Please check the link.");
            case ERROR, SKIPPED_FRESH -> ApiException.unavailable(
                    "That page could not be read just now. Please try again in a moment.");
        };
    }

    private static String messageForExisting(TrackingWriter.ExistingListing existing) {
        return switch (existing.status()) {
            case ACTIVE -> null;
            case PAUSED -> "This listing's checks are paused, so its price may be out of date.";
            case BLOCKED -> "This retailer is currently blocking our checks, so this listing's "
                            + "price may be out of date. It will resume automatically.";
            case DEAD -> "This listing is no longer being checked — the retailer's page appears to "
                         + "have been removed. Its past price history is still available.";
        };
    }

    /**
     * What tracking a URL produced.
     *
     * @param productId the product now being tracked
     * @param trackerId the caller's tracker, or {@code null} when a retailer was added to a product
     *                  rather than a product being tracked
     * @param title     the product's name as the retailer states it
     * @param retailer  which retailer the submitted URL belongs to
     * @param fetched   true when a live request was sent; false when an existing listing was reused.
     *                  Surfaced to the UI, because "reused an existing price history" is a feature
     *                  worth showing rather than an absence worth hiding
     * @param notice    something the user should know about the listing's state, or {@code null}
     */
    public record Added(Long productId, Long trackerId, String title, String retailer,
                        boolean fetched, String notice) {
    }
}
