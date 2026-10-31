package com.pricepilot.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Every number this project claims about itself, in one shape.
 *
 * <p>The point of this record is accountability. Each figure here is computed from rows the system
 * wrote while doing real work — {@code scrape_attempt} for reliability, {@code listing} for
 * scheduling, {@code deal_analysis} for verdicts, {@code api_usage} for spend. Nothing is a
 * constant, and nothing is rounded in a flattering direction. If the extraction success rate drops
 * to 61% then that is what the dashboard says.
 *
 * <p>It is a record of records rather than a flat bag of fields so the JSON nests sensibly and the
 * frontend can render each block as its own card without a translation layer.
 *
 * @param generatedAt when this snapshot was computed
 * @param windowDays  the trailing window the rate figures cover
 */
public record OpsMetrics(
        Instant generatedAt,
        int windowDays,
        Coverage coverage,
        Extraction extraction,
        Load load,
        Verdicts verdicts,
        Alerts alerts,
        SchedulerHealth scheduler,
        List<HostStat> hosts,
        List<StrategyStat> strategies,
        List<OutcomeStat> outcomes,
        List<SpendStat> spend,
        ScrapeMetrics.Snapshot optimization) {

    /**
     * How much there is to track and how much of it is healthy.
     *
     * @param supportedSites     retailers with an extraction rule loaded
     * @param trackedProducts    products at least one person is watching
     * @param activeTrackers     active watch relationships across all users
     * @param blockedListings    listings a retailer is currently refusing; these back off hard
     *                           rather than hammering, and recover on their own
     * @param deadListings       listings retired for good — a removed product page, or a URL whose
     *                           retailer is no longer supported
     * @param priceObservations  rows in the price history; the asset the product accumulates
     */
    public record Coverage(
            int supportedSites,
            long trackedProducts,
            long activeTrackers,
            long totalListings,
            long activeListings,
            long pausedListings,
            long blockedListings,
            long deadListings,
            long priceObservations) {
    }

    /**
     * Whether the scraper actually works, measured over the window.
     *
     * <p>{@code SKIPPED_FRESH} attempts are excluded from both numerator and denominator throughout.
     * A check served from a recent observation made no network request and so cannot have succeeded
     * or failed at extraction; counting those as successes would inflate this number by exactly the
     * amount of caching done, which would be measuring the wrong thing twice.
     *
     * @param networkRequests     legacy field: recorded non-skipped checks, which may fail before
     *                            submission; use optimization for actual BrowserAct submissions
     * @param successes           attempts that returned a usable price
     * @param successRatePercent  {@code successes / networkRequests}, 0 when nothing was attempted
     * @param averageLatencyMs    mean wall-clock time of a successful fetch and parse
     */
    public record Extraction(
            long networkRequests,
            long successes,
            double successRatePercent,
            double averageLatencyMs) {
    }

    /**
     * What adaptive scheduling saves against polling everything on a fixed hourly cycle.
     *
     * <p>The honest reading of {@code loadReductionPercent}: the baseline is <em>modelled</em> — the
     * requests that checking today's active listings once an hour would cost — while the actual
     * figure counts historical check rows over the window, including pre-submission failures.
     * It cannot establish actual network savings. It is a comparison against a
     * named alternative, not against a previous version of this system, and it is stated that way
     * everywhere it appears.
     *
     * <p>It can go negative. A burst of manual refreshes can push actual usage above the hourly
     * baseline, and when that happens the number is shown as it is rather than floored at zero,
     * because a dashboard that cannot report bad news is not worth reading.
     *
     * @param averageIntervalMinutes  mean current check interval across active listings
     * @param baselineIntervalMinutes the fixed interval being compared against
     * @param dedupedRequests         checks served from a recent observation instead of a fetch —
     *                                what shared listings and the freshness window save
     * @param dedupSharePercent       share of all checks that needed no network request
     */
    public record Load(
            double averageIntervalMinutes,
            int baselineIntervalMinutes,
            long baselineRequestsPerDay,
            long actualRequestsPerDay,
            double loadReductionPercent,
            long dedupedRequests,
            double dedupSharePercent) {
    }

    /**
     * What Claude concluded about the products being tracked.
     *
     * <p>Computed over the newest verdict per product, so a product analysed six times counts once.
     * Without that, a single volatile product could dominate the distribution.
     *
     * @param analysed          products with at least one verdict
     * @param favourable        verdicts rating the current price worth acting on
     * @param fakeDiscountsFound times the analysis found an inflated "was" price — a struck-through
     *                           MRP the product had not genuinely sold at
     */
    public record Verdicts(
            long analysed,
            long favourable,
            double favourablePercent,
            long fakeDiscountsFound,
            List<CountStat> distribution) {
    }

    /**
     * Whether alerts reached anyone.
     *
     * <p>{@code raised} and {@code delivered} diverging is the signal that email is misconfigured.
     * They are reported separately rather than as a single "alerts sent" figure because the in-app
     * feed and the inbox are genuinely different channels: with SMTP switched off entirely,
     * {@code raised} keeps climbing and {@code delivered} stays at zero, and that is working as
     * intended rather than a fault.
     *
     * @param deliveryRatePercent share of raised alerts the mail server accepted
     */
    public record Alerts(
            long raised,
            long delivered,
            double deliveryRatePercent) {
    }

    /**
     * Signs of life from the background loop.
     *
     * <p>{@code lastTickAt} is the field worth watching. If it stops moving while {@code enabled} is
     * true, the scheduler thread has died and prices are quietly going stale — which otherwise looks
     * identical to a period when no prices happened to change.
     *
     * @param dueNow listings whose check time has passed; a persistently large number means the
     *               batch size is too small for the number of listings being tracked
     */
    public record SchedulerHealth(
            boolean enabled,
            int tickSeconds,
            int batchSize,
            int inFlight,
            long ticksSinceStart,
            Instant lastTickAt,
            long dueNow) {
    }

    /** Per-retailer reliability. This is what makes "works on N sites" a checkable claim. */
    public record HostStat(
            String host,
            long attempts,
            long successes,
            double successRatePercent) {
    }

    /**
     * Per-strategy reliability.
     *
     * <p>The most useful table in the system during development: it says which extraction method is
     * carrying the load and which is dead weight. A strategy near the end of the chain with a high
     * attempt count means the ones before it are failing more than they should.
     */
    public record StrategyStat(
            String strategy,
            long attempts,
            long successes,
            double successRatePercent,
            double averageLatencyMs) {
    }

    /** How attempts ended — the failure shape, not just the failure count. */
    public record OutcomeStat(
            String outcome,
            long count,
            double sharePercent) {
    }

    /** A verdict or other label and how often it occurred. */
    public record CountStat(
            String label,
            long count,
            double sharePercent) {
    }

    /**
     * Money and quota, per provider, for the current month.
     *
     * <p>On the ops page mainly to answer "is this about to stop working?" — every paid dependency
     * here runs on a capped free or prepaid allowance, and the app degrades rather than fails when
     * one runs out.
     */
    public record SpendStat(
            String provider,
            int calls,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd) {
    }
}
