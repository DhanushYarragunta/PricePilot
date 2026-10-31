package com.pricepilot.metrics;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiUsage;
import com.pricepilot.domain.DealVerdict;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.repo.AlertLogRepository;
import com.pricepilot.repo.DealAnalysisRepository;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import com.pricepilot.repo.TrackerRepository;
import com.pricepilot.schedule.IntervalPolicy;
import com.pricepilot.schedule.ScheduleDispatcher;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.usage.BudgetGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines historical recorded checks with explicitly scoped process-local submission counters.
 *
 * <p>This service exists because of a specific failure mode. A project that claims "78% extraction
 * accuracy across 10+ sites" on a CV has to be able to answer "how do you know?" — and the only
 * answer that survives follow-up questions is a page that recomputes it from telemetry on request.
 * So every figure traces to a query over real rows, the denominators are chosen deliberately and
 * documented, and the comparison baselines are named rather than implied.
 *
 * <p>Read-only and transactional purely for connection reuse: ten short queries sharing one
 * connection rather than taking and returning ten. It runs on demand from the ops endpoint, not on a
 * timer, so it costs nothing when nobody is looking. The queries are aggregates over a table that
 * gains a few thousand rows a month and is pruned nightly, which is comfortably inside what Postgres
 * will do without an index scan becoming interesting.
 */
@Service
@Transactional(readOnly = true)
public class OpsMetricsService {

    /** Trailing window for the rate figures. Long enough to be stable, short enough to be current. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private static final int MINUTES_PER_DAY = 1440;

    private final ScrapeAttemptRepository attempts;
    private final ListingRepository listings;
    private final TrackerRepository trackers;
    private final PricePointRepository prices;
    private final DealAnalysisRepository analyses;
    private final AlertLogRepository alerts;
    private final DomainRules rules;
    private final BudgetGuard budget;
    private final ScheduleDispatcher dispatcher;
    private final AppProperties props;
    private final ScrapeMetrics scrapeMetrics;

    public OpsMetricsService(ScrapeAttemptRepository attempts,
                             ListingRepository listings,
                             TrackerRepository trackers,
                             PricePointRepository prices,
                             DealAnalysisRepository analyses,
                             AlertLogRepository alerts,
                             DomainRules rules,
                             BudgetGuard budget,
                             ScheduleDispatcher dispatcher,
                             AppProperties props,
                             ScrapeMetrics scrapeMetrics) {
        this.attempts = attempts;
        this.listings = listings;
        this.trackers = trackers;
        this.prices = prices;
        this.analyses = analyses;
        this.alerts = alerts;
        this.rules = rules;
        this.budget = budget;
        this.dispatcher = dispatcher;
        this.props = props;
        this.scrapeMetrics = scrapeMetrics;
    }

    public OpsMetrics snapshot() {
        return snapshot(DEFAULT_WINDOW_DAYS);
    }

    public OpsMetrics snapshot(int windowDays) {
        // Clamped to the retention window. Asking for ninety days of rates when telemetry is pruned
        // at forty-five would not fail — it would quietly compute over a partially empty table and
        // report a lower request count than really occurred.
        int days = Math.max(1, Math.min(windowDays, props.getScheduler().getAttemptRetentionDays()));
        Instant since = Instant.now().minus(Duration.ofDays(days));

        long networkRequests = attempts.countNetworkRequests(since);
        long skipped = attempts.countSkippedFresh(since);
        List<OpsMetrics.OutcomeStat> outcomes = outcomes(since);
        long successes = outcomes.stream()
                .filter(o -> "SUCCESS".equals(o.outcome()))
                .mapToLong(OpsMetrics.OutcomeStat::count)
                .sum();

        return new OpsMetrics(
                Instant.now(),
                days,
                coverage(),
                new OpsMetrics.Extraction(
                        networkRequests,
                        successes,
                        percent(successes, networkRequests),
                        round(attempts.averageSuccessLatencyMs(since), 0)),
                load(days, networkRequests, skipped),
                verdicts(),
                alertHealth(),
                schedulerHealth(),
                hosts(since),
                strategies(since),
                outcomes,
                spend(),
                scrapeMetrics.snapshot());
    }

    // ------------------------------------------------------------------ blocks

    private OpsMetrics.Coverage coverage() {
        return new OpsMetrics.Coverage(
                rules.all().size(),
                trackers.countDistinctWatchedProducts(),
                trackers.countActive(),
                listings.count(),
                listings.countByStatus(ListingStatus.ACTIVE),
                listings.countByStatus(ListingStatus.PAUSED),
                listings.countByStatus(ListingStatus.BLOCKED),
                listings.countByStatus(ListingStatus.DEAD),
                prices.countAll());
    }

    /**
     * Legacy estimate retained for API compatibility, not a measured before/after request reduction.
     *
     * <p>Baseline: the current count of active listings, checked once every
     * {@link IntervalPolicy#BASELINE_INTERVAL_MINUTES}. Historical attempt rows include checks
     * refused before a submission, so they cannot reconstruct actual outbound requests. Accurate
     * BrowserAct submission counts are exposed separately in optimization, scoped to this process.
     *
     * <p>The listing population can change during the window, further limiting this estimate.
     * No active listings means there is no useful comparison, so this legacy field stays zero.
     */
    private OpsMetrics.Load load(int days, long networkRequests, long skipped) {
        long activeListings = listings.countByStatus(ListingStatus.ACTIVE);
        double averageInterval = listings.averageActiveIntervalMinutes();

        long checksPerDayAtBaseline =
                Math.round((double) MINUTES_PER_DAY / IntervalPolicy.BASELINE_INTERVAL_MINUTES);
        long baselinePerDay = activeListings * checksPerDayAtBaseline;
        long actualPerDay = Math.round((double) networkRequests / days);

        double reduction = baselinePerDay <= 0
                ? 0
                : (1.0 - ((double) actualPerDay / baselinePerDay)) * 100.0;

        long totalChecks = networkRequests + skipped;

        return new OpsMetrics.Load(
                round(averageInterval, 1),
                IntervalPolicy.BASELINE_INTERVAL_MINUTES,
                baselinePerDay,
                actualPerDay,
                round(reduction, 1),
                skipped,
                percent(skipped, totalChecks));
    }

    /**
     * The verdict distribution, over the newest analysis per product.
     *
     * <p>{@code INSUFFICIENT_DATA} is counted in the denominator on purpose. It is a real outcome —
     * a product with two days of history that the analysis honestly declined to judge — and dropping
     * it would quietly raise the favourable percentage by excluding the cases where the system had
     * nothing useful to say.
     */
    private OpsMetrics.Verdicts verdicts() {
        List<Object[]> rows = analyses.currentVerdictDistribution();
        long total = 0;
        long favourable = 0;
        for (Object[] row : rows) {
            long count = asLong(row[1]);
            total += count;
            if (row[0] instanceof DealVerdict verdict && verdict.isFavourable()) {
                favourable += count;
            }
        }

        List<OpsMetrics.CountStat> distribution = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            long count = asLong(row[1]);
            distribution.add(new OpsMetrics.CountStat(
                    String.valueOf(row[0]), count, percent(count, total)));
        }
        distribution.sort((a, b) -> Long.compare(b.count(), a.count()));

        return new OpsMetrics.Verdicts(
                total,
                favourable,
                percent(favourable, total),
                analyses.countFakeDiscounts(),
                distribution);
    }

    private OpsMetrics.SchedulerHealth schedulerHealth() {
        AppProperties.Scheduler cfg = props.getScheduler();
        long dueNow = listings.countDue(Instant.now());
        return new OpsMetrics.SchedulerHealth(
                dispatcher.isEnabled(),
                cfg.getTickSeconds(),
                cfg.getBatchSize(),
                dispatcher.inFlightCount(),
                dispatcher.tickCount(),
                dispatcher.lastTickAt(),
                dueNow);
    }

    private List<OpsMetrics.HostStat> hosts(Instant since) {
        List<OpsMetrics.HostStat> stats = new ArrayList<>();
        for (Object[] row : attempts.successRateByHost(since)) {
            long total = asLong(row[1]);
            long ok = asLong(row[2]);
            stats.add(new OpsMetrics.HostStat(
                    String.valueOf(row[0]), total, ok, percent(ok, total)));
        }
        return stats;
    }

    private List<OpsMetrics.StrategyStat> strategies(Instant since) {
        List<OpsMetrics.StrategyStat> stats = new ArrayList<>();
        for (Object[] row : attempts.successRateByStrategy(since)) {
            long total = asLong(row[1]);
            long ok = asLong(row[2]);
            stats.add(new OpsMetrics.StrategyStat(
                    String.valueOf(row[0]), total, ok, percent(ok, total),
                    round(asDouble(row[3]), 0)));
        }
        return stats;
    }

    private List<OpsMetrics.OutcomeStat> outcomes(Instant since) {
        List<Object[]> rows = attempts.outcomeBreakdown(since);
        long total = rows.stream().mapToLong(row -> asLong(row[1])).sum();
        List<OpsMetrics.OutcomeStat> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            long count = asLong(row[1]);
            stats.add(new OpsMetrics.OutcomeStat(
                    String.valueOf(row[0]), count, percent(count, total)));
        }
        stats.sort((a, b) -> Long.compare(b.count(), a.count()));
        return stats;
    }

    private List<OpsMetrics.SpendStat> spend() {
        List<OpsMetrics.SpendStat> stats = new ArrayList<>();
        for (ApiUsage usage : budget.thisMonth()) {
            stats.add(new OpsMetrics.SpendStat(
                    usage.getProvider().name(),
                    usage.getCalls(),
                    usage.getInputTokens(),
                    usage.getOutputTokens(),
                    // Router prices can change independently of the underlying model. The
                    // aggregate cannot distinguish unpriced calls from priced ones, so it must
                    // not present a partial/zero estimate as the router's complete bill.
                    "OPENAI_COMPATIBLE".equals(usage.getProvider().name()) ? null
                            : usage.getCostUsd() == null ? BigDecimal.ZERO
                            : usage.getCostUsd().setScale(4, RoundingMode.HALF_UP)));
        }
        return stats;
    }

    /** Alert delivery health. Raised counts what the app recorded; delivered counts what SMTP took. */
    private OpsMetrics.Alerts alertHealth() {
        long raised = alerts.countAll();
        long delivered = alerts.countDelivered();
        return new OpsMetrics.Alerts(raised, delivered, percent(delivered, raised));
    }

    // ------------------------------------------------------------------ arithmetic

    private static double percent(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return round((double) part * 100.0 / total, 1);
    }

    private static double round(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Aggregate columns come back as some {@code Number}, but which one depends on the dialect and
     * the function — {@code COUNT} is a {@code Long}, {@code SUM} over a CASE can be an
     * {@code Integer} or a {@code BigInteger}, and {@code AVG} is a {@code Double}. Reading them
     * through {@code Number} rather than casting to a guessed type is what stops this class breaking
     * on a Postgres upgrade.
     */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }
}
