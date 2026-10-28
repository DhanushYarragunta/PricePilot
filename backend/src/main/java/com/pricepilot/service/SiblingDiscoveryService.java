package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.discovery.DiscoveredListing;
import com.pricepilot.discovery.DiscoveryService;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.schedule.RefreshWriter;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UnsupportedUrlException;
import com.pricepilot.scrape.UrlCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Finds the same product at other retailers, in the background.
 *
 * <p>This is the feature that makes the app worth more than a bookmark: one product, four
 * storefronts, one chart. Tavily's role in it is strictly to suggest <em>which URLs might be the
 * same product</em>. Every candidate it returns is then fetched for real by {@link ScrapeEngine},
 * and a candidate whose page cannot be read is discarded rather than stored with a guessed price.
 * Tavily never contributes a number to the database.
 *
 * <h2>Why this runs asynchronously</h2>
 * A search call plus up to four live product-page fetches, each serialised behind the politeness
 * delay for its host, is thirty to sixty seconds of work. Doing it inside the "add product" request
 * would mean a user stares at a spinner for a minute to get a result they did not ask for, and a
 * proxy timeout would make a successful add look like a failure. So the add returns as soon as the
 * product has its first real price, and the extra retailers appear on the product page when they are
 * ready.
 *
 * <p>A separate bean from {@link ProductTrackingService} because {@code @Async}, like
 * {@code @Transactional}, is implemented with a proxy: calling an {@code @Async} method from another
 * method of the same bean runs it inline on the caller's thread, which would silently reinstate
 * exactly the minute-long request this class exists to avoid. The executor is bounded in
 * {@code application.yml} ({@code spring.task.execution}), so a burst of additions queues rather
 * than spawning unbounded threads.
 */
@Service
public class SiblingDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(SiblingDiscoveryService.class);

    /**
     * How many candidates to actually fetch, regardless of how many search returns.
     *
     * <p>Each one is a real request to a retailer. Four is enough to cover the retailers an Indian
     * shopper would realistically compare, and small enough that a single added product cannot turn
     * into a dozen requests.
     */
    private static final int MAX_CANDIDATES_TO_FETCH = 4;

    /**
     * A candidate is accepted only if its price lands inside these multiples of the price we already
     * have for the product.
     *
     * <p>This is the guard that keeps cross-retailer comparison meaningful. Search for "iPhone 15
     * 128GB" and the results will include the 256GB model, a case, a screen protector and a renewed
     * unit — all genuinely relevant to the query and none of them the same product. Title matching
     * alone cannot separate them reliably; price does, because an accessory is an order of magnitude
     * cheaper and a bigger variant is reliably dearer. The window is wide enough to allow a real
     * price difference between retailers, including a large sale, and narrow enough to reject a
     * different product.
     */
    private static final BigDecimal MIN_PRICE_RATIO = new BigDecimal("0.55");
    private static final BigDecimal MAX_PRICE_RATIO = new BigDecimal("1.80");

    private final DiscoveryService discovery;
    private final UrlCanonicalizer canonicalizer;
    private final ScrapeEngine engine;
    private final TrackingWriter writer;
    private final RefreshWriter refreshWriter;
    private final ProductRepository products;
    private final ListingRepository listings;
    private final PricePointRepository prices;
    private final AppProperties props;

    public SiblingDiscoveryService(DiscoveryService discovery,
                                   UrlCanonicalizer canonicalizer,
                                   ScrapeEngine engine,
                                   TrackingWriter writer,
                                   RefreshWriter refreshWriter,
                                   ProductRepository products,
                                   ListingRepository listings,
                                   PricePointRepository prices,
                                   AppProperties props) {
        this.discovery = discovery;
        this.canonicalizer = canonicalizer;
        this.engine = engine;
        this.writer = writer;
        this.refreshWriter = refreshWriter;
        this.products = products;
        this.listings = listings;
        this.prices = prices;
        this.props = props;
    }

    public boolean isAvailable() {
        return discovery.isAvailable();
    }

    /**
     * Kicks off discovery for a product and returns immediately.
     *
     * <p>Never throws, including into the async executor. Discovery is additive: a product tracked at
     * one retailer is a complete, working tracked product, so every failure here is logged and
     * forgotten rather than surfaced.
     */
    @Async
    public void discoverInBackground(Long productId) {
        try {
            Outcome outcome = discoverNow(productId);
            if (outcome.added() > 0) {
                log.info("Discovery added {} extra listing(s) for product {}",
                        outcome.added(), productId);
            }
        } catch (RuntimeException e) {
            log.warn("Background discovery for product {} failed: {}", productId, e.toString(), e);
        }
    }

    /**
     * Searches for the product elsewhere, fetches each candidate, and keeps the ones that check out.
     *
     * @return how many candidates were fetched and how many of them were kept
     */
    public Outcome discoverNow(Long productId) {
        if (!discovery.isAvailable()) {
            log.debug("Skipping discovery for product {}: no search key configured", productId);
            return Outcome.NOTHING;
        }

        Product product = products.findById(productId).orElse(null);
        if (product == null) {
            return Outcome.NOTHING;
        }

        int capacity = props.getLimits().getMaxListingsPerProduct()
                        - listings.findByProductId(productId).size();
        if (capacity <= 0) {
            return Outcome.NOTHING;
        }

        BigDecimal reference = referencePrice(productId);
        if (reference == null) {
            // No price of our own to compare against means no way to tell a sibling listing from an
            // accessory. Waiting for the next successful check is better than filling the comparison
            // table with things that are not this product.
            log.debug("Skipping discovery for product {}: no reference price yet", productId);
            return Outcome.NOTHING;
        }

        List<DiscoveredListing> candidates = discovery.findCandidates(product);
        if (candidates.isEmpty()) {
            return Outcome.NOTHING;
        }

        int added = 0;
        int fetched = 0;
        for (DiscoveredListing candidate : candidates) {
            if (added >= capacity || fetched >= MAX_CANDIDATES_TO_FETCH) {
                break;
            }
            fetched++;
            if (tryCandidate(productId, candidate, reference)) {
                added++;
            }
        }
        return new Outcome(added, fetched);
    }

    /**
     * What one discovery pass did.
     *
     * <p>Both numbers are reported because their difference is the informative part. "Fetched four
     * pages and kept one" says the price-plausibility filter rejected three things that were not this
     * product, which is the feature working; "added nothing" on its own is indistinguishable from a
     * search that returned no results at all, or from a broken button.
     *
     * @param added    listings stored
     * @param examined candidate pages actually fetched
     */
    public record Outcome(int added, int examined) {

        /** Nothing was searched for and nothing was fetched — a precondition stopped the pass. */
        public static final Outcome NOTHING = new Outcome(0, 0);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Fetches one candidate and stores it if the page agrees it is the same product.
     *
     * @return true when a listing was added
     */
    private boolean tryCandidate(Long productId, DiscoveredListing candidate, BigDecimal reference) {
        UrlCanonicalizer.Canonical canonical;
        try {
            // Re-canonicalised rather than trusting the URL on the candidate, because what is needed
            // here is the HostRule, and resolving it from the URL is the one way the host and its
            // rule cannot end up mismatched.
            canonical = canonicalizer.canonicalize(candidate.url());
        } catch (UnsupportedUrlException | IllegalArgumentException e) {
            log.debug("Discarding discovery candidate {}: {}", candidate.url(), e.getMessage());
            return false;
        }

        // Cheap re-check. Minutes may have passed since the search, and in the meantime another
        // user could have added this exact URL.
        if (writer.findExisting(canonical.hash()).isPresent()) {
            return false;
        }

        ScrapeResult result = engine.scrape(canonical.url(), canonical.rule());
        if (!result.isSuccess() || result.snapshot() == null || result.snapshot().price() == null) {
            log.debug("Discovery candidate {} did not yield a price: {} ({})",
                    canonical.url(), result.outcome(), result.message());
            return false;
        }

        BigDecimal price = result.snapshot().price();
        if (!isPlausibleSibling(price, reference)) {
            log.info("Rejecting discovery candidate {} at {}: price {} is too far from {} to be the "
                     + "same product", canonical.host(), canonical.retailer(), price, reference);
            return false;
        }

        Listing listing = writer.addListing(productId, canonical, result.snapshot()).orElse(null);
        if (listing == null) {
            return false;
        }
        // Same path as every other observation, so a discovered listing's first price point is
        // indistinguishable from one the scheduler wrote.
        refreshWriter.recordSuccess(listing.getId(), result, TriggerSource.DISCOVERY);
        return true;
    }

    private static boolean isPlausibleSibling(BigDecimal price, BigDecimal reference) {
        if (price.compareTo(BigDecimal.ZERO) <= 0 || reference.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return price.compareTo(reference.multiply(MIN_PRICE_RATIO)) >= 0
               && price.compareTo(reference.multiply(MAX_PRICE_RATIO)) <= 0;
    }

    /**
     * The price to judge candidates against: the cheapest current observation we already hold.
     *
     * <p>Cheapest rather than average, because {@code latestObservations} is already ordered by
     * price ascending and the lowest real price is the most conservative baseline — it makes the
     * accepted window start lower, which is the direction that risks admitting an accessory, so the
     * ratio floor is set with that in mind.
     */
    private BigDecimal referencePrice(Long productId) {
        List<PricePoint> latest = prices.latestObservations(productId);
        for (PricePoint point : latest) {
            if (point.getPrice() != null && point.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                return point.getPrice();
            }
        }
        return null;
    }
}
