package com.pricepilot.web.dto;

import com.pricepilot.domain.AlertKind;
import com.pricepilot.domain.DealVerdict;
import com.pricepilot.domain.ScrapeOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Every response shape the API returns.
 *
 * <p>Views, not entities. Serialising JPA entities straight out of a controller is the shortcut that
 * costs the most later: it exposes the schema as the public contract so a column cannot be renamed
 * without breaking clients, it leaks fields nobody asked for — other users' ids, password hashes one
 * refactor away from being included — and it triggers lazy loads during serialisation, which fail
 * outside a transaction and succeed inside one, giving a bug that depends on where the call came
 * from. Everything here is a flat record of values, assembled deliberately.
 *
 * <p>Two conventions worth stating, both aimed at the frontend. Enums are sent as their name plus a
 * human label, so the UI can switch on a stable identifier while showing text that can be reworded
 * without a code change. And percentages are pre-computed here as integers rather than left to the
 * client, because the rounding rule is part of the claim being made: a price "down 12%" should mean
 * the same thing on the dashboard, in the email and in a screenshot.
 */
public final class ApiViews {

    private ApiViews() {
    }

    // ------------------------------------------------------------------ identity

    public record User(Long id, String email, String displayName, String role, boolean admin) {
    }

    /** What a successful register or login returns. */
    public record Session(String token, long expiresInSeconds, User user) {
    }

    // ------------------------------------------------------------------ verdicts

    /**
     * A stored Claude verdict, ready to render.
     *
     * @param verdict    the enum name, for styling and sorting
     * @param label      the same thing in words
     * @param favourable whether this verdict counts as "worth buying", using the identical rule the
     *                   metrics page counts with, so the dashboard and the published figure can never
     *                   disagree
     * @param stale      true when the price has moved since this verdict was formed. Shown rather
     *                   than hidden: a visibly out-of-date opinion is more useful than one presented
     *                   as current when it is not
     * @param reasoning  Claude's own explanation. Included in full because an unexplained verdict is
     *                   an oracle, and an oracle is not something a person can sensibly disagree with
     */
    public record Verdict(
            String verdict,
            String label,
            boolean favourable,
            Integer confidencePercent,
            String headline,
            String reasoning,
            boolean fakeDiscount,
            BigDecimal suggestedPrice,
            BigDecimal analysedPrice,
            boolean stale,
            Instant createdAt,
            String model) {
    }

    /** One earlier verdict, for the "this was a WAIT last week" strip. */
    public record VerdictHistoryEntry(
            Instant createdAt,
            String verdict,
            String label,
            BigDecimal analysedPrice,
            String headline) {
    }

    // ------------------------------------------------------------------ prices

    /** One point on a chart. Deliberately tiny — a long history is thousands of these. */
    public record PricePoint(Instant at, BigDecimal price, boolean inStock) {
    }

    /** One retailer's line on the comparison chart. */
    public record Series(Long listingId, String retailer, List<PricePoint> points) {
    }

    /**
     * Statistics for one product.
     *
     * @param vsAveragePercent how far the current price sits below the windowed average, positive
     *                         meaning cheaper than usual
     * @param vsLowPercent     how far above the all-time low it sits, positive meaning dearer than
     *                         the best price ever seen
     * @param observations     how many real price readings back these figures. Published alongside
     *                         them on purpose: an average over four readings and an average over four
     *                         hundred deserve different amounts of trust, and only one of them is
     *                         worth acting on
     */
    public record PriceStats(
            BigDecimal current,
            BigDecimal allTimeLow,
            BigDecimal allTimeHigh,
            BigDecimal averageInWindow,
            int windowDays,
            long observations,
            Instant trackingSince,
            Integer vsAveragePercent,
            Integer vsLowPercent) {
    }

    // ------------------------------------------------------------------ listings

    /**
     * One retailer's page for a product, with its current price and its scraping health.
     *
     * <p>The scheduling and failure fields are exposed to the user, not kept for operators. A column
     * that has stopped updating is the main way a price tracker misleads people, and "Amazon.in has
     * blocked our last 3 checks, next attempt in 4 hours" is a far better answer than a stale number
     * presented as current.
     */
    public record ListingDetail(
            Long listingId,
            String retailer,
            String url,
            String status,
            String statusNote,
            boolean primary,
            BigDecimal price,
            BigDecimal listPrice,
            Integer claimedDiscountPercent,
            boolean inStock,
            Instant observedAt,
            Instant lastCheckedAt,
            Instant lastSuccessAt,
            Instant nextCheckAt,
            int checkIntervalMinutes,
            String lastStrategy,
            int consecutiveFailures) {
    }

    // ------------------------------------------------------------------ products

    /**
     * A dashboard card.
     *
     * @param fromPrice     the cheapest current price across every retailer, which is the number a
     *                      shopper actually cares about
     * @param targetMet     whether {@code fromPrice} has reached the user's target
     * @param windowChangePercent movement since the start of the window, negative for a fall
     */
    public record TrackedProduct(
            Long productId,
            Long trackerId,
            String title,
            String imageUrl,
            String currency,
            BigDecimal fromPrice,
            String fromRetailer,
            Long fromListingId,
            boolean inStock,
            int retailerCount,
            BigDecimal allTimeLow,
            BigDecimal allTimeHigh,
            Integer windowChangePercent,
            int windowDays,
            boolean atAllTimeLow,
            BigDecimal targetPrice,
            boolean targetMet,
            boolean notifyOnDeal,
            String note,
            Verdict verdict,
            Instant lastCheckedAt,
            boolean active) {
    }

    /** Everything the product page shows. */
    public record ProductDetail(
            Long productId,
            String title,
            String imageUrl,
            String currency,
            Long trackerId,
            BigDecimal targetPrice,
            boolean targetMet,
            boolean notifyOnDeal,
            String note,
            boolean tracking,
            List<ListingDetail> listings,
            List<Series> series,
            int seriesDays,
            PriceStats stats,
            Verdict verdict,
            List<VerdictHistoryEntry> verdictHistory,
            List<CheckAttempt> recentChecks,
            boolean aiAvailable,
            boolean discoveryAvailable) {
    }

    // ------------------------------------------------------------------ transparency

    /**
     * One recorded scrape attempt, shown on the product page.
     *
     * <p>Exposing the raw check log to the person using the app is an unusual choice and a deliberate
     * one. It is the difference between "trust us" and "here is every request we made, what it
     * returned, which extraction strategy read the price and how long it took" — which is also,
     * conveniently, the most direct evidence that the prices are really scraped rather than guessed.
     */
    public record CheckAttempt(
            Instant at,
            String retailer,
            String host,
            String outcome,
            String outcomeLabel,
            String strategy,
            Integer httpStatus,
            int latencyMs,
            String trigger,
            String error) {
    }

    // ------------------------------------------------------------------ alerts

    public record Alert(
            Long id,
            Long productId,
            String productTitle,
            String kind,
            String kindLabel,
            BigDecimal price,
            String message,
            boolean delivered,
            String deliveryError,
            Instant createdAt) {
    }

    // ------------------------------------------------------------------ actions

    /** A tracker's alert preferences after a change, so the UI re-renders from the stored truth. */
    public record TrackerSettings(
            Long trackerId,
            Long productId,
            BigDecimal targetPrice,
            boolean notifyOnDeal,
            String note,
            boolean active) {
    }

    /** What tracking a URL produced. */
    public record Added(
            Long productId,
            Long trackerId,
            String title,
            String retailer,
            boolean fetched,
            String notice) {
    }

    /** The outcome of one listing's manual refresh. */
    public record RefreshReport(
            Long listingId,
            String retailer,
            String outcome,
            String outcomeLabel,
            boolean success,
            boolean skipped,
            BigDecimal price,
            BigDecimal previousPrice,
            Integer dropPercent,
            boolean inStock,
            boolean allTimeLow,
            Instant nextCheckAt,
            String message) {
    }

    /**
     * The whole manual refresh, summarised.
     *
     * @param requestsMade how many of the checks actually sent a request. Distinguished from
     *                     {@code checked} because a refresh that served three listings from the
     *                     freshness window did real work and spent nothing, and saying so is more
     *                     honest than reporting four checks
     */
    public record RefreshSummary(
            Long productId,
            int checked,
            int succeeded,
            int requestsMade,
            int refreshRemaining,
            List<RefreshReport> results,
            String message) {
    }

    /** Process-local background work; terminal state never implies that every retailer succeeded. */
    public record RefreshJob(
            String jobId,
            Long productId,
            String status,
            boolean terminal,
            int total,
            int checked,
            int succeeded,
            int skipped,
            int failed,
            int requestsMade,
            int refreshRemaining,
            List<RefreshReport> results,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant expiresAt,
            String message) {
    }

    /**
     * The outcome of looking for a product at other retailers.
     *
     * @param added     how many new retailers were confirmed and stored
     * @param examined  how many candidate pages were actually fetched. Reported because the gap
     *                  between the two numbers is the interesting part: "looked at 4 pages, kept 1"
     *                  says the price-plausibility filter is doing its job, whereas "found nothing"
     *                  alone reads like a broken feature
     * @param remaining what is left of this hour's discovery allowance
     */
    public record Discovered(
            Long productId,
            int added,
            int examined,
            int remaining,
            String message) {
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * What this deployment can currently do.
     *
     * <p>Fetched once at startup by the frontend so it can hide what is not configured instead of
     * offering a button that returns an error. Every integration in this project is optional by
     * design — no Claude key means no verdict cards and a fully working price tracker — and this is
     * the endpoint that makes that degradation visible rather than confusing.
     */
    public record Capabilities(
            boolean aiEnabled,
            boolean searchEnabled,
            boolean emailEnabled,
            boolean inviteRequired,
            List<String> supportedRetailers,
            int maxTrackedProducts,
            int maxRetailersPerProduct,
            int manualRefreshPerHour,
            int historyWindowDays,
            boolean schedulerEnabled) {
    }

    // ------------------------------------------------------------------ enum labelling

    /**
     * Human wording for a verdict.
     *
     * <p>Lives here rather than on the enum so the domain does not carry presentation text, and so
     * the UI never has to turn {@code STRONG_BUY} into prose itself — doing it in two places is how
     * the dashboard and the alert email end up describing the same verdict differently.
     */
    public static String labelFor(DealVerdict verdict) {
        if (verdict == null) {
            return "Not analysed";
        }
        return switch (verdict) {
            case STRONG_BUY -> "Strong buy";
            case GOOD_VALUE -> "Good value";
            case FAIR -> "Fairly priced";
            case WAIT -> "Worth waiting";
            case OVERPRICED -> "Overpriced";
            case INSUFFICIENT_DATA -> "Not enough history yet";
        };
    }

    public static String labelFor(AlertKind kind) {
        if (kind == null) {
            return "Alert";
        }
        return switch (kind) {
            case TARGET_HIT -> "Target price reached";
            case DEAL_DETECTED -> "Good deal detected";
            case ALL_TIME_LOW -> "Lowest price yet";
            case BACK_IN_STOCK -> "Back in stock";
        };
    }

    /**
     * Human wording for a check outcome.
     *
     * <p>Written for the person reading the check log on a product page, so it says what happened in
     * terms they can act on. "Blocked by the retailer" is a fact about the world; "ERROR" is a fact
     * about our enum.
     */
    public static String labelFor(ScrapeOutcome outcome) {
        if (outcome == null) {
            return "Unknown";
        }
        return switch (outcome) {
            case SUCCESS -> "Price read";
            case NO_PRICE -> "No price on the page";
            case BLOCKED -> "Blocked by the retailer";
            case HTTP_ERROR -> "Retailer returned an error";
            case TIMEOUT -> "Retailer did not respond in time";
            case ERROR -> "Check failed";
            case SKIPPED_FRESH -> "Skipped — recent price reused";
        };
    }
}
