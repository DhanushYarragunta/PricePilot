package com.pricepilot.service;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.alerts.AlertService;
import com.pricepilot.config.AppProperties;
import com.pricepilot.discovery.DiscoveryService;
import com.pricepilot.domain.AlertLog;
import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.ScrapeAttempt;
import com.pricepilot.domain.Tracker;
import com.pricepilot.repo.AlertLogRepository;
import com.pricepilot.repo.DealAnalysisRepository;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import com.pricepilot.repo.TrackerRepository;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.web.ApiException;
import com.pricepilot.web.dto.ApiViews;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The read side of the application: turns stored rows into the views the UI renders.
 *
 * <p>Separated from the write services on purpose. Everything here is a {@code readOnly} transaction
 * that touches no third-party service and changes nothing, which makes it the one layer that can be
 * called freely, retried safely, and — when a page is slow — profiled without worrying about what it
 * might have half-finished. {@link ProductTrackingService} fetches and writes; this assembles.
 *
 * <h2>Query budget</h2>
 * The dashboard runs a fixed five queries no matter how many products the user tracks. That is the
 * whole reason the batched {@code ...ForProducts} repository methods exist. The lazy, obvious version
 * — loop the trackers, ask four questions per card — is indistinguishable from this one with three
 * rows of test data and falls over at forty, which is below the per-user limit the app itself
 * advertises. Anything added to the dashboard card from here on should be added to a batched query,
 * not to the loop.
 *
 * <h2>Honesty rules encoded here</h2>
 * Two decisions in this class matter more than they look. A {@code DEAD} listing never contributes a
 * price, because its page no longer exists and its last reading is therefore a number about the past
 * being presented as the price now. And {@code lastCheckedAt} is read from the listing rather than
 * from the newest price point, because skipped, blocked and unchanged checks write no price point —
 * so the newest observation can be far older than the last time the retailer was actually asked.
 * Getting either of those backwards would make the app quietly misleading in exactly the way a price
 * tracker cannot afford.
 */
@Service
public class DashboardService {

    /** How many check-log rows the product page shows. Enough to see a pattern, not a wall of text. */
    private static final int CHECKS_SHOWN = 25;

    /** How many superseded verdicts to show under the current one. */
    private static final int VERDICT_HISTORY_SHOWN = 6;

    /**
     * Backstop on the size of one chart series, after run-length compression.
     *
     * <p>Compression alone handles the normal case completely, so reaching this cap requires a price
     * that genuinely moved hundreds of times inside the window. In that situation the newest points
     * are the ones worth having, so the cap keeps the tail rather than thinning the whole series.
     */
    private static final int MAX_SERIES_POINTS = 600;

    /**
     * How far the current price may drift from the analysed price before the verdict is marked stale.
     *
     * <p>Not zero, because a rupee of movement does not invalidate an opinion and flagging it would
     * leave every card permanently stale, which trains people to ignore the flag. Not large either:
     * past a couple of percent, Claude was reasoning about a different price than the one on screen.
     */
    private static final BigDecimal STALE_TOLERANCE = new BigDecimal("0.02");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Sorts a listing with no price yet to the end, rather than dropping it from the list. */
    private static final BigDecimal MAX_SORT_PRICE = BigDecimal.valueOf(Long.MAX_VALUE);

    private final TrackerRepository trackers;
    private final ListingRepository listings;
    private final PricePointRepository prices;
    private final DealAnalysisRepository analyses;
    private final AlertLogRepository alerts;
    private final ScrapeAttemptRepository attempts;
    private final DealAnalyzer analyzer;
    private final DiscoveryService discovery;
    private final AlertService alertService;
    private final DomainRules rules;
    private final AppProperties props;

    public DashboardService(TrackerRepository trackers,
                            ListingRepository listings,
                            PricePointRepository prices,
                            DealAnalysisRepository analyses,
                            AlertLogRepository alerts,
                            ScrapeAttemptRepository attempts,
                            DealAnalyzer analyzer,
                            DiscoveryService discovery,
                            AlertService alertService,
                            DomainRules rules,
                            AppProperties props) {
        this.trackers = trackers;
        this.listings = listings;
        this.prices = prices;
        this.analyses = analyses;
        this.alerts = alerts;
        this.attempts = attempts;
        this.analyzer = analyzer;
        this.discovery = discovery;
        this.alertService = alertService;
        this.rules = rules;
        this.props = props;
    }

    // ------------------------------------------------------------------ dashboard

    /**
     * Every product this user tracks, as dashboard cards.
     *
     * <p>Untracked products are included with {@code active = false} rather than filtered out. The
     * tracker row is deactivated, never deleted, so the history survives an untrack; showing it means
     * a user who removed something by accident can see it is still there and turn it back on, instead
     * of concluding the data is gone.
     */
    @Transactional(readOnly = true)
    public List<ApiViews.TrackedProduct> dashboard(Long userId) {
        List<Tracker> rows = trackers.findByUser(userId);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> productIds = rows.stream().map(t -> t.getProduct().getId()).distinct().toList();
        int windowDays = props.getLimits().getHistoryWindowDays();
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        Map<Long, CurrentPrice> current = currentPrices(productIds);
        Map<Long, ListingSummary> summaries = listingSummaries(productIds);
        Map<Long, BigDecimal[]> lowHigh = lowHighByProduct(productIds);
        Map<Long, BigDecimal> windowStart = windowStartByProduct(productIds, since);
        Map<Long, DealAnalysis> verdicts = latestVerdicts(productIds);

        List<ApiViews.TrackedProduct> cards = new ArrayList<>(rows.size());
        for (Tracker tracker : rows) {
            Product product = tracker.getProduct();
            Long productId = product.getId();
            CurrentPrice price = current.get(productId);
            ListingSummary summary = summaries.get(productId);
            BigDecimal[] bounds = lowHigh.get(productId);
            BigDecimal low = bounds == null ? null : bounds[0];
            BigDecimal high = bounds == null ? null : bounds[1];
            BigDecimal from = price == null ? null : price.price();
            boolean inStock = price != null && price.inStock();

            cards.add(new ApiViews.TrackedProduct(
                    productId,
                    tracker.getId(),
                    product.getTitle(),
                    product.getImageUrl(),
                    product.getCurrency(),
                    from,
                    price == null ? null : price.retailer(),
                    price == null ? null : price.listingId(),
                    inStock,
                    summary == null ? 0 : summary.liveListings(),
                    low,
                    high,
                    changePercent(windowStart.get(productId), from),
                    windowDays,
                    atAllTimeLow(from, low, inStock),
                    tracker.getTargetPrice(),
                    targetMet(tracker.getTargetPrice(), from, inStock),
                    tracker.isNotifyOnDeal(),
                    tracker.getNote(),
                    verdictView(verdicts.get(productId), from),
                    summary == null ? null : summary.lastCheckedAt(),
                    tracker.isActive()));
        }

        // Active first, then newest first inside each group. The repository already orders by
        // creation date, and List.sort is stable, so sorting on the flag alone preserves that.
        cards.sort(Comparator.comparing((ApiViews.TrackedProduct c) -> c.active()).reversed());
        return cards;
    }

    // ------------------------------------------------------------------ product detail

    /**
     * The full product page.
     *
     * <p>Requires a tracker row, active or not. Products are shared between users — two people
     * tracking the same phone share one product and one scraping history, which is what keeps the
     * request count down — so product ids are not private, but who tracks what is. Gating on the
     * tracker means a guessed id reveals nothing, and it does so with a 404 rather than a 403, for
     * the reason recorded on {@link ApiException#notYours}.
     */
    @Transactional(readOnly = true)
    public ApiViews.ProductDetail productDetail(Long userId, Long productId) {
        Tracker tracker = trackers.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> ApiException.notYours("product"));
        Product product = tracker.getProduct();

        int windowDays = props.getLimits().getHistoryWindowDays();
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        // Loaded first so every listing is in the persistence context. The price points below walk
        // pricePoint.getListing(), which then resolves from that context instead of issuing a query
        // per point — the one place in this class where an N+1 would be invisible in the code.
        List<Listing> productListings = listings.findByProductId(productId);
        Map<Long, PricePoint> latestByListing = new HashMap<>();
        for (PricePoint point : prices.latestObservations(productId)) {
            latestByListing.putIfAbsent(point.getListing().getId(), point);
        }

        List<ApiViews.ListingDetail> listingViews = new ArrayList<>(productListings.size());
        for (Listing listing : productListings) {
            listingViews.add(listingView(listing, latestByListing.get(listing.getId())));
        }
        listingViews.sort(listingOrder());

        BigDecimal current = cheapestUsablePrice(productListings, latestByListing);
        DealAnalysis latest = analyses.findFirstByProductIdOrderByCreatedAtDesc(productId).orElse(null);

        return new ApiViews.ProductDetail(
                productId,
                product.getTitle(),
                product.getImageUrl(),
                product.getCurrency(),
                tracker.getId(),
                tracker.getTargetPrice(),
                targetMet(tracker.getTargetPrice(), current, anyInStock(latestByListing, productListings)),
                tracker.isNotifyOnDeal(),
                tracker.getNote(),
                tracker.isActive(),
                listingViews,
                series(productId, productListings, since),
                windowDays,
                stats(productId, current, since, windowDays),
                verdictView(latest, current),
                verdictHistory(productId, latest),
                recentChecks(productId),
                analyzer.isEnabled(),
                discovery.isAvailable());
    }

    // ------------------------------------------------------------------ alerts

    /** The user's alert feed, newest first. */
    @Transactional(readOnly = true)
    public List<ApiViews.Alert> alertFeed(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<AlertLog> rows = alerts.feedForUser(userId, PageRequest.of(0, capped));
        List<ApiViews.Alert> views = new ArrayList<>(rows.size());
        for (AlertLog row : rows) {
            Product product = row.getTracker().getProduct();
            views.add(new ApiViews.Alert(
                    row.getId(),
                    product.getId(),
                    product.getTitle(),
                    row.getKind() == null ? null : row.getKind().name(),
                    ApiViews.labelFor(row.getKind()),
                    row.getPrice(),
                    row.getMessage(),
                    row.isDelivered(),
                    row.getDeliveryError(),
                    row.getCreatedAt()));
        }
        return views;
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * What this deployment can do, for the frontend to read once at startup.
     *
     * <p>Not transactional and not user-specific: every value is configuration. The point of the
     * endpoint is that a deployment without a Claude key, a Tavily key or an SMTP host is a fully
     * working price tracker with fewer buttons, rather than a price tracker with buttons that return
     * errors.
     */
    public ApiViews.Capabilities capabilities() {
        List<String> retailers = rules.all().stream()
                .map(HostRule::retailer)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        AppProperties.Limits limits = props.getLimits();
        String inviteCode = props.getAuth().getSignupInviteCode();
        return new ApiViews.Capabilities(
                analyzer.isEnabled(),
                discovery.isAvailable(),
                alertService.emailEnabled(),
                // Whether a code is needed, never the code itself. The sign-up form has to show or
                // hide a field; it has no business knowing what goes in it.
                inviteCode != null && !inviteCode.isBlank(),
                retailers,
                limits.getMaxTrackedProductsPerUser(),
                limits.getMaxListingsPerProduct(),
                limits.getManualRefreshPerHour(),
                limits.getHistoryWindowDays(),
                props.getScheduler().isEnabled());
    }

    // ------------------------------------------------------------------ verdict view

    /**
     * Wraps a stored analysis for the UI, or returns {@code null} when there is none.
     *
     * <p>{@code null} rather than an empty-but-present object, so the frontend renders "not analysed
     * yet" from the absence instead of having to recognise a placeholder verdict.
     *
     * <p>Public and static because {@code ProductController} builds the same view after an on-demand
     * re-analysis, and it lives in a different package. Sharing the one method is what guarantees the
     * verdict card the user sees after pressing "analyse" is identical in shape, labelling and
     * staleness rule to the one the dashboard renders a second later — two implementations of this
     * mapping would differ eventually, and the difference would look like the analysis itself changing
     * its mind.
     */
    public static ApiViews.Verdict verdictView(DealAnalysis analysis, BigDecimal currentPrice) {
        if (analysis == null) {
            return null;
        }
        return new ApiViews.Verdict(
                analysis.getVerdict() == null ? null : analysis.getVerdict().name(),
                ApiViews.labelFor(analysis.getVerdict()),
                analysis.getVerdict() != null && analysis.getVerdict().isFavourable(),
                confidencePercent(analysis.getConfidence()),
                analysis.getHeadline(),
                analysis.getReasoning(),
                analysis.isFakeDiscount(),
                analysis.getSuggestedPrice(),
                analysis.getAnalysedPrice(),
                isStale(analysis.getAnalysedPrice(), currentPrice),
                analysis.getCreatedAt(),
                analysis.getModel());
    }

    /**
     * Whether the price has moved far enough since the analysis for the verdict to be out of date.
     *
     * <p>Shown rather than used to hide the verdict. An opinion about a price that has since changed
     * is still informative — "this was overpriced at ₹52,000 and it is ₹48,000 now" is exactly what a
     * shopper wants to know — as long as nothing pretends it is current.
     */
    private static boolean isStale(BigDecimal analysedPrice, BigDecimal currentPrice) {
        if (analysedPrice == null || currentPrice == null) {
            // No analysed price means an older row from before that column was populated, and no
            // current price means nothing is being quoted for the verdict to disagree with.
            return analysedPrice == null && currentPrice != null;
        }
        if (analysedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal drift = currentPrice.subtract(analysedPrice).abs()
                .divide(analysedPrice, 4, RoundingMode.HALF_UP);
        return drift.compareTo(STALE_TOLERANCE) > 0;
    }

    private static Integer confidencePercent(BigDecimal confidence) {
        if (confidence == null) {
            return null;
        }
        return confidence.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // ------------------------------------------------------------------ batched loaders

    /**
     * The price to headline per product: cheapest in stock, falling back to cheapest at all.
     *
     * <p>Preferring an in-stock listing is the difference between a useful number and a cruel one. A
     * sold-out retailer frequently leaves a low price on the page, so sorting on price alone would
     * reliably headline a figure nobody can pay — and would fire a target-price alert for it. When
     * nothing is in stock the cheapest row is still shown, with {@code inStock} false, because "₹999
     * at Croma, out of stock" is information and a blank is not.
     */
    private Map<Long, CurrentPrice> currentPrices(List<Long> productIds) {
        Map<Long, CurrentPrice> inStock = new HashMap<>();
        Map<Long, CurrentPrice> anyStock = new HashMap<>();
        // Rows arrive cheapest first, so the first row kept for a product is the cheapest of its kind.
        for (Object[] row : prices.latestForProducts(productIds)) {
            ListingStatus status = (ListingStatus) row[3];
            if (status == ListingStatus.DEAD) {
                continue;
            }
            Long productId = (Long) row[0];
            CurrentPrice price = new CurrentPrice(
                    (Long) row[1],
                    (String) row[2],
                    (BigDecimal) row[4],
                    Boolean.TRUE.equals(row[5]),
                    (Instant) row[6]);
            if (price.price() == null) {
                continue;
            }
            anyStock.putIfAbsent(productId, price);
            if (price.inStock()) {
                inStock.putIfAbsent(productId, price);
            }
        }
        anyStock.forEach(inStock::putIfAbsent);
        return inStock;
    }

    private Map<Long, ListingSummary> listingSummaries(List<Long> productIds) {
        Map<Long, ListingSummary> byProduct = new HashMap<>();
        for (Object[] row : listings.summaryForProducts(productIds)) {
            byProduct.put((Long) row[0], new ListingSummary(
                    row[1] == null ? 0 : ((Number) row[1]).intValue(),
                    (Instant) row[2]));
        }
        return byProduct;
    }

    private Map<Long, BigDecimal[]> lowHighByProduct(List<Long> productIds) {
        Map<Long, BigDecimal[]> byProduct = new HashMap<>();
        for (Object[] row : prices.lowHighForProducts(productIds)) {
            byProduct.put((Long) row[0], new BigDecimal[]{(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        return byProduct;
    }

    private Map<Long, BigDecimal> windowStartByProduct(List<Long> productIds, Instant since) {
        Map<Long, BigDecimal> byProduct = new HashMap<>();
        for (Object[] row : prices.earliestInWindowForProducts(productIds, since)) {
            // putIfAbsent because two listings sharing the earliest timestamp both match; either is
            // an equally valid answer to "what did this cost when the window opened".
            byProduct.putIfAbsent((Long) row[0], (BigDecimal) row[1]);
        }
        return byProduct;
    }

    private Map<Long, DealAnalysis> latestVerdicts(List<Long> productIds) {
        Map<Long, DealAnalysis> byProduct = new HashMap<>();
        for (DealAnalysis analysis : analyses.latestForProducts(productIds)) {
            byProduct.putIfAbsent(analysis.getProduct().getId(), analysis);
        }
        return byProduct;
    }

    // ------------------------------------------------------------------ detail assembly

    private ApiViews.ListingDetail listingView(Listing listing, PricePoint latest) {
        BigDecimal price = latest == null ? null : latest.getPrice();
        BigDecimal listPrice = latest == null ? null : latest.getListPrice();
        return new ApiViews.ListingDetail(
                listing.getId(),
                listing.getRetailer(),
                listing.getUrl(),
                listing.getStatus() == null ? null : listing.getStatus().name(),
                statusNote(listing),
                listing.isPrimary(),
                price,
                listPrice,
                claimedDiscountPercent(price, listPrice),
                latest != null && latest.isInStock(),
                latest == null ? null : latest.getCapturedAt(),
                listing.getLastCheckedAt(),
                listing.getLastSuccessAt(),
                listing.getNextCheckAt(),
                listing.getCheckIntervalMinutes(),
                listing.getLastStrategy(),
                listing.getConsecutiveFailures());
    }

    /**
     * Why a listing is not behaving normally, in the user's terms.
     *
     * <p>An empty note for a healthy listing, so the UI shows a badge only when there is something to
     * say. The alternative — "Active" on every row — is noise that trains people not to read the one
     * row that matters.
     */
    private String statusNote(Listing listing) {
        ListingStatus status = listing.getStatus();
        if (status == null) {
            return null;
        }
        return switch (status) {
            case ACTIVE -> listing.getConsecutiveFailures() > 0
                    ? "The last " + listing.getConsecutiveFailures()
                      + " check(s) failed. Retrying with a longer gap."
                    : null;
            case PAUSED -> "Paused. This retailer is not being checked for now.";
            case BLOCKED -> "This retailer is refusing automated requests. Checks have been slowed "
                            + "right down and will resume on their own.";
            case DEAD -> "This product page no longer exists, so its price is no longer counted.";
        };
    }

    /**
     * The discount the retailer claims, from its own struck-through price.
     *
     * <p>Reported as the retailer's claim, not as a fact — the whole point of the fake-discount check
     * in the analysis is that an inflated MRP makes this number meaningless. Showing it next to a
     * real price history is what lets a reader judge it.
     */
    private static Integer claimedDiscountPercent(BigDecimal price, BigDecimal listPrice) {
        if (price == null || listPrice == null || listPrice.compareTo(price) <= 0
            || listPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return listPrice.subtract(price).multiply(HUNDRED)
                .divide(listPrice, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Primary first, then live before retired, then cheapest — the order a reader expects.
     *
     * <p>Every lambda carries an explicit parameter type. {@code thenComparing} is overloaded on both
     * {@code Comparator} and {@code Function}, and an implicitly typed lambda leaves the compiler
     * unable to choose between them; naming the type makes the lambda explicitly typed and resolves it.
     */
    private static Comparator<ApiViews.ListingDetail> listingOrder() {
        return Comparator
                .comparing((ApiViews.ListingDetail l) -> !l.primary())
                .thenComparing((ApiViews.ListingDetail l) -> ListingStatus.DEAD.name().equals(l.status()))
                .thenComparing((ApiViews.ListingDetail l) ->
                        l.price() == null ? MAX_SORT_PRICE : l.price());
    }

    private ApiViews.PriceStats stats(Long productId, BigDecimal current, Instant since, int windowDays) {
        BigDecimal low = prices.allTimeLow(productId).orElse(null);
        BigDecimal high = prices.allTimeHigh(productId).orElse(null);
        // valueOf, not the BigDecimal(double) constructor: the constructor preserves the full binary
        // expansion, so an average of 1999.9 would arrive with twenty decimal places of noise.
        BigDecimal average = prices.averageSince(productId, since)
                .map(BigDecimal::valueOf)
                .map(value -> value.setScale(2, RoundingMode.HALF_UP))
                .orElse(null);
        Integer vsAverage = changePercent(average, current);
        return new ApiViews.PriceStats(
                current,
                low,
                high,
                average,
                windowDays,
                prices.countForProduct(productId),
                prices.firstObservedAt(productId).orElse(null),
                // Negated so positive reads as "cheaper than it usually is", which is the direction a
                // shopper cares about; changePercent itself is negative for a fall.
                negate(vsAverage),
                // Positive means dearer than the best price ever seen.
                changePercent(low, current));
    }

    /**
     * One chart line per listing, run-length compressed.
     *
     * <p>Every successful check writes a price point whether or not the price moved, which is right
     * for the scraping record — it is the evidence the check happened — and wrong for a chart: thirty
     * days of hourly checks across six retailers is a few thousand points describing perhaps a dozen
     * actual price changes.
     *
     * <p>So consecutive points carrying the same price and stock state collapse to their endpoints.
     * This is lossless for a step chart, which is the correct shape for a price anyway: a price is a
     * value that holds until it changes, not a quantity that drifts between samples. Every real
     * movement survives at full fidelity, and the first and last readings are always kept so the line
     * spans the window the page claims to show.
     */
    private List<ApiViews.Series> series(Long productId, List<Listing> productListings, Instant since) {
        Map<Long, String> retailerByListing = new HashMap<>();
        for (Listing listing : productListings) {
            retailerByListing.put(listing.getId(), listing.getRetailer());
        }

        Map<Long, List<PricePoint>> grouped = new LinkedHashMap<>();
        for (PricePoint point : prices.historyForProduct(productId, since)) {
            grouped.computeIfAbsent(point.getListing().getId(), id -> new ArrayList<>()).add(point);
        }

        List<ApiViews.Series> result = new ArrayList<>(grouped.size());
        grouped.forEach((listingId, points) -> result.add(new ApiViews.Series(
                listingId,
                retailerByListing.get(listingId),
                compress(points))));
        return result;
    }

    private static List<ApiViews.PricePoint> compress(List<PricePoint> points) {
        List<ApiViews.PricePoint> kept = new ArrayList<>();
        PricePoint previous = null;
        for (int i = 0; i < points.size(); i++) {
            PricePoint point = points.get(i);
            boolean first = i == 0;
            boolean last = i == points.size() - 1;
            boolean moved = previous == null
                            || point.isInStock() != previous.isInStock()
                            || !equalPrice(point.getPrice(), previous.getPrice());
            if (first || last || moved) {
                kept.add(new ApiViews.PricePoint(
                        point.getCapturedAt(), point.getPrice(), point.isInStock()));
            }
            previous = point;
        }
        if (kept.size() > MAX_SERIES_POINTS) {
            return List.copyOf(kept.subList(kept.size() - MAX_SERIES_POINTS, kept.size()));
        }
        return kept;
    }

    private static boolean equalPrice(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        // compareTo, not equals: 1999 and 1999.00 are the same price and differ only in scale, which
        // varies with which extraction strategy read the page.
        return a.compareTo(b) == 0;
    }

    private List<ApiViews.VerdictHistoryEntry> verdictHistory(Long productId, DealAnalysis latest) {
        List<DealAnalysis> all = analyses.history(productId);
        List<ApiViews.VerdictHistoryEntry> entries = new ArrayList<>();
        for (DealAnalysis analysis : all) {
            if (latest != null && analysis.getId() != null && analysis.getId().equals(latest.getId())) {
                continue; // shown in full as the current verdict
            }
            if (entries.size() >= VERDICT_HISTORY_SHOWN) {
                break;
            }
            entries.add(new ApiViews.VerdictHistoryEntry(
                    analysis.getCreatedAt(),
                    analysis.getVerdict() == null ? null : analysis.getVerdict().name(),
                    ApiViews.labelFor(analysis.getVerdict()),
                    analysis.getAnalysedPrice(),
                    analysis.getHeadline()));
        }
        return entries;
    }

    private List<ApiViews.CheckAttempt> recentChecks(Long productId) {
        List<ScrapeAttempt> rows = attempts.recentForProduct(productId, PageRequest.of(0, CHECKS_SHOWN));
        List<ApiViews.CheckAttempt> views = new ArrayList<>(rows.size());
        for (ScrapeAttempt attempt : rows) {
            views.add(new ApiViews.CheckAttempt(
                    attempt.getCreatedAt(),
                    attempt.getListing() == null ? null : attempt.getListing().getRetailer(),
                    attempt.getHost(),
                    attempt.getOutcome() == null ? null : attempt.getOutcome().name(),
                    ApiViews.labelFor(attempt.getOutcome()),
                    attempt.getStrategy(),
                    attempt.getHttpStatus(),
                    attempt.getLatencyMs() == null ? 0 : attempt.getLatencyMs(),
                    attempt.getTriggerSource() == null ? null : attempt.getTriggerSource().name(),
                    attempt.getErrorMessage()));
        }
        return views;
    }

    // ------------------------------------------------------------------ small calculations

    /** Cheapest usable price on the product page, under the same in-stock rule as the dashboard. */
    private static BigDecimal cheapestUsablePrice(List<Listing> productListings,
                                                  Map<Long, PricePoint> latestByListing) {
        BigDecimal cheapestInStock = null;
        BigDecimal cheapestAny = null;
        for (Listing listing : productListings) {
            if (listing.getStatus() == ListingStatus.DEAD) {
                continue;
            }
            PricePoint point = latestByListing.get(listing.getId());
            if (point == null || point.getPrice() == null) {
                continue;
            }
            BigDecimal price = point.getPrice();
            if (cheapestAny == null || price.compareTo(cheapestAny) < 0) {
                cheapestAny = price;
            }
            if (point.isInStock() && (cheapestInStock == null || price.compareTo(cheapestInStock) < 0)) {
                cheapestInStock = price;
            }
        }
        return cheapestInStock != null ? cheapestInStock : cheapestAny;
    }

    private static boolean anyInStock(Map<Long, PricePoint> latestByListing, List<Listing> productListings) {
        for (Listing listing : productListings) {
            if (listing.getStatus() == ListingStatus.DEAD) {
                continue;
            }
            PricePoint point = latestByListing.get(listing.getId());
            if (point != null && point.isInStock()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the product is at its cheapest ever right now.
     *
     * <p>Requires stock, because the all-time low is computed from in-stock observations only, and
     * labelling an unavailable listing "lowest ever" would be a badge on something unbuyable.
     */
    private static boolean atAllTimeLow(BigDecimal current, BigDecimal low, boolean inStock) {
        return inStock && current != null && low != null && current.compareTo(low) <= 0;
    }

    private static boolean targetMet(BigDecimal target, BigDecimal current, boolean inStock) {
        return target != null && current != null && inStock && current.compareTo(target) <= 0;
    }

    /**
     * Movement from one price to another as a whole percentage, negative for a fall.
     *
     * <p>Rounded here rather than in the browser because the same figure appears on the card, in the
     * alert email and in the verdict prompt, and a number that disagrees with itself across those
     * three places is worse than a slightly coarse one.
     */
    private static Integer changePercent(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return to.subtract(from).multiply(HUNDRED)
                .divide(from, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private static Integer negate(Integer value) {
        return value == null ? null : -value;
    }

    // ------------------------------------------------------------------ internal carriers

    private record CurrentPrice(Long listingId, String retailer, BigDecimal price,
                                boolean inStock, Instant observedAt) {
    }

    private record ListingSummary(int liveListings, Instant lastCheckedAt) {
    }
}
