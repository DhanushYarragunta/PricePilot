package com.pricepilot.web;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.DealAnalysis;
import com.pricepilot.schedule.RefreshResult;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.AccountService;
import com.pricepilot.service.DashboardService;
import com.pricepilot.service.ProductTrackingService;
import com.pricepilot.service.SiblingDiscoveryService;
import com.pricepilot.web.dto.ApiRequests;
import com.pricepilot.web.dto.ApiViews;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Products: adding them, reading them, and asking for work to be done on them now.
 *
 * <p>Everything a user does to a product passes through here, and every one of those actions begins
 * with the same two steps — establish who is calling, and establish that they track this product.
 * The second is not optional anywhere: products are shared rows, so a product id is guessable, and
 * without the check a guessed id would be enough to spend someone else's Claude budget or to make
 * this server hammer a retailer on demand.
 *
 * <h2>Which endpoints cost money</h2>
 * Three of them do real, metered work: {@code /refresh} sends live requests to retailers,
 * {@code /analyse} spends Claude tokens, and {@code /discover} spends a Tavily call plus up to four
 * more retailer requests. All three are rate limited per user, and all three report what remains of
 * the allowance so the UI can grey out a button before it fails rather than after.
 *
 * <h2>Where prices come from</h2>
 * Nothing in this file accepts a price. {@code POST /api/products} takes a URL; the price in the
 * response was read from that page by this server, moments earlier, during the request. There is no
 * code path — here or anywhere else — by which a client-supplied number, or a number from a search
 * engine's summary of a page, becomes a stored price.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final String REFRESH_ACTION = "refresh";
    private static final String ANALYSIS_ACTION = "analysis";
    private static final String DISCOVERY_ACTION = "discovery";

    /**
     * Separate, tighter limits for the two endpoints that spend money rather than bandwidth.
     *
     * <p>Claude analysis is the expensive one per call, and re-analysing the same product twice in an
     * hour tells the user nothing new unless the price moved — which the scheduler already notices on
     * its own. Discovery is capped harder still because one call fans out into several fetches.
     */
    private static final int ANALYSES_PER_HOUR = 10;
    private static final int DISCOVERIES_PER_HOUR = 5;

    private final ProductTrackingService tracking;
    private final DashboardService dashboard;
    private final AccountService accounts;
    private final DealAnalyzer analyzer;
    private final SiblingDiscoveryService discovery;
    private final RateLimiter limiter;
    private final AppProperties props;

    public ProductController(ProductTrackingService tracking,
                             DashboardService dashboard,
                             AccountService accounts,
                             DealAnalyzer analyzer,
                             SiblingDiscoveryService discovery,
                             RateLimiter limiter,
                             AppProperties props) {
        this.tracking = tracking;
        this.dashboard = dashboard;
        this.accounts = accounts;
        this.analyzer = analyzer;
        this.discovery = discovery;
        this.limiter = limiter;
        this.props = props;
    }

    // ------------------------------------------------------------------ reading

    /** The dashboard: every product this user tracks. */
    @GetMapping
    public List<ApiViews.TrackedProduct> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return dashboard.dashboard(caller.id());
    }

    /** One product in full: every retailer, the chart, the statistics, the verdict, the check log. */
    @GetMapping("/{productId}")
    public ApiViews.ProductDetail detail(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @PathVariable Long productId) {
        return dashboard.productDetail(caller.id(), productId);
    }

    // ------------------------------------------------------------------ adding

    /**
     * Starts tracking the product at a URL.
     *
     * <p>Synchronous, and slow on purpose — typically two to twenty seconds, because the page is
     * being fetched and parsed while the caller waits. The alternative, accepting the URL and
     * reporting success immediately, would mean telling someone their product was added and then
     * discovering half a minute later that the retailer blocked us or the link was a category page.
     * Failing inside the request lets the error name the actual problem while the user is still
     * looking at the box they pasted into.
     *
     * <p>Finding the same product at other retailers happens afterwards, in the background, because
     * that part is genuinely optional: a product tracked at one retailer is already complete.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiViews.Added track(@AuthenticationPrincipal AuthenticatedUser caller,
                                @Valid @RequestBody ApiRequests.Track request) {
        AppUser user = accounts.require(caller);
        // Counted against the refresh allowance, not a separate one. The limit's job is to cap how
        // many live requests one user can cause this server to send to retailers, and adding a
        // product sends exactly as many as refreshing one — a second bucket would just double the
        // real ceiling while looking like two smaller limits.
        limiter.check(user.getId(), REFRESH_ACTION, props.getLimits().getManualRefreshPerHour());
        ProductTrackingService.Added added = tracking.track(
                user,
                request.url(),
                request.targetPrice(),
                request.notifyOnDealOrDefault(),
                request.note());
        return addedView(added);
    }

    /** Adds another retailer's page for a product already being tracked. */
    @PostMapping("/{productId}/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiViews.Added addRetailer(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long productId,
                                      @Valid @RequestBody ApiRequests.AddRetailer request) {
        AppUser user = accounts.require(caller);
        tracking.requireTracking(user.getId(), productId);
        limiter.check(user.getId(), REFRESH_ACTION, props.getLimits().getManualRefreshPerHour());
        return addedView(tracking.addRetailer(user, productId, request.url()));
    }

    /** Chooses the retailer whose price represents this product in list views. */
    @PostMapping("/{productId}/listings/{listingId}/primary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void makePrimary(@AuthenticationPrincipal AuthenticatedUser caller,
                            @PathVariable Long productId,
                            @PathVariable Long listingId) {
        tracking.setPrimaryListing(caller.id(), productId, listingId);
    }

    // ------------------------------------------------------------------ work on demand

    /**
     * Checks every active listing of this product right now.
     *
     * <p>The button that makes the whole app feel real: prices on screen change while you watch,
     * because they are being read off the retailers' pages during the request. Counted against the
     * same hourly allowance as adding a product, since both do the same thing to the same hosts.
     */
    @PostMapping("/{productId}/refresh")
    public ApiViews.RefreshSummary refresh(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable Long productId) {
        int limit = props.getLimits().getManualRefreshPerHour();
        tracking.requireTracking(caller.id(), productId);
        limiter.check(caller.id(), REFRESH_ACTION, limit);

        List<RefreshResult> results = tracking.refreshNow(productId);
        List<ApiViews.RefreshReport> reports = new ArrayList<>(results.size());
        int succeeded = 0;
        int requests = 0;
        for (RefreshResult result : results) {
            reports.add(reportView(result));
            if (result.isSuccess()) {
                succeeded++;
            }
            if (result.consumedRequest()) {
                requests++;
            }
        }
        return new ApiViews.RefreshSummary(
                productId,
                results.size(),
                succeeded,
                requests,
                limiter.remaining(caller.id(), REFRESH_ACTION, limit),
                reports,
                refreshMessage(results.size(), succeeded, requests));
    }

    /**
     * Asks Claude for a fresh verdict on this product.
     *
     * <p>Forces the analysis rather than deferring to the scheduler's "is it due?" rule, because this
     * is the user explicitly asking. {@link DealAnalyzer#analyseNow} throws when there is not enough
     * price history to reason about, when the monthly budget is spent, or when no API key is
     * configured — all three of which the exception handler turns into a message that says which it
     * was. Failing with a reason beats returning last week's verdict as though it were new.
     */
    @PostMapping("/{productId}/analyse")
    public ApiViews.Verdict analyse(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @PathVariable Long productId) {
        tracking.requireTracking(caller.id(), productId);
        limiter.check(caller.id(), ANALYSIS_ACTION, ANALYSES_PER_HOUR);
        DealAnalysis analysis = analyzer.analyseNow(productId);
        // The analysed price is the price the verdict was formed on, so a verdict returned from here
        // is never stale by construction — passing it as the current price says exactly that.
        return DashboardService.verdictView(analysis, analysis.getAnalysedPrice());
    }

    /**
     * Looks for this product at other retailers, now, and waits for the answer.
     *
     * <p>Synchronous here, unlike the automatic pass that runs after a product is added. The
     * difference is that someone pressed a button and is watching: a background job that silently
     * adds nothing is indistinguishable from a broken button, whereas "searched and found nothing
     * that matched on price" is a real answer.
     */
    @PostMapping("/{productId}/discover")
    public ApiViews.Discovered discover(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable Long productId) {
        tracking.requireTracking(caller.id(), productId);
        if (!discovery.isAvailable()) {
            throw ApiException.unavailable(
                    "Finding other retailers is switched off on this deployment, because no search "
                    + "API key is configured. You can still add retailers by pasting their links.");
        }
        limiter.check(caller.id(), DISCOVERY_ACTION, DISCOVERIES_PER_HOUR);
        SiblingDiscoveryService.Outcome outcome = discovery.discoverNow(productId);
        return new ApiViews.Discovered(
                productId,
                outcome.added(),
                outcome.examined(),
                limiter.remaining(caller.id(), DISCOVERY_ACTION, DISCOVERIES_PER_HOUR),
                discoveryMessage(outcome));
    }

    // ------------------------------------------------------------------ mapping

    private static ApiViews.Added addedView(ProductTrackingService.Added added) {
        return new ApiViews.Added(
                added.productId(),
                added.trackerId(),
                added.title(),
                added.retailer(),
                added.fetched(),
                added.notice());
    }

    private static ApiViews.RefreshReport reportView(RefreshResult result) {
        return new ApiViews.RefreshReport(
                result.listingId(),
                result.retailer(),
                result.outcome() == null ? null : result.outcome().name(),
                ApiViews.labelFor(result.outcome()),
                result.isSuccess(),
                result.isSkipped(),
                result.price(),
                result.previousPrice(),
                result.dropPercent(),
                result.inStock(),
                result.allTimeLow(),
                result.nextCheckAt(),
                result.message());
    }

    /**
     * One sentence summarising a refresh, written to be shown as a toast.
     *
     * <p>Distinguishes "checked but served from the freshness window" from "checked over the network",
     * because a refresh that spent no requests still did its job and saying "nothing happened" would
     * be wrong. The freshness window exists precisely so that two people pressing refresh within a
     * minute of each other do not produce two requests to the same retailer.
     */
    private static String refreshMessage(int checked, int succeeded, int requests) {
        if (checked == 0) {
            return "There was nothing to check.";
        }
        if (requests == 0) {
            return "Every price was checked moments ago, so the current figures were reused.";
        }
        if (succeeded == 0) {
            return "None of the retailers could be read this time. The reasons are listed above, and "
                   + "scheduled checks will keep trying.";
        }
        if (succeeded == checked) {
            return "Checked " + checked + " retailer" + (checked == 1 ? "" : "s") + " just now.";
        }
        return succeeded + " of " + checked + " retailers could be read just now.";
    }

    /**
     * One sentence summarising a discovery pass.
     *
     * <p>The three cases read very differently to a user, and collapsing them into "found nothing"
     * would hide the only one that is actually a dead end. Fetching pages and keeping none means the
     * price filter rejected them — accessories and other variants, which is the filter working.
     * Fetching none at all means the search itself came back empty, or the product has no price of
     * ours to compare candidates against yet.
     */
    private static String discoveryMessage(SiblingDiscoveryService.Outcome outcome) {
        if (outcome.added() > 0) {
            return "Added " + outcome.added() + " more retailer"
                   + (outcome.added() == 1 ? "" : "s") + ".";
        }
        if (outcome.examined() > 0) {
            return "Checked " + outcome.examined() + " other listing"
                   + (outcome.examined() == 1 ? "" : "s")
                   + ", but none of them was confirmed as this exact product. Pages whose price is "
                   + "far from this one are rejected, because they are usually an accessory or a "
                   + "different variant.";
        }
        return "No other retailer was found selling this product. This can also happen when there is "
               + "not enough price history yet to tell a matching listing from an accessory — the "
               + "search runs again as the product is checked.";
    }
}
