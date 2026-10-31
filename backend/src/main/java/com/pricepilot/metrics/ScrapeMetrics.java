package com.pricepilot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-cardinality counters for this application process. Collect snapshots or Micrometer meters
 * regularly to retain measurements across restarts; these are not database-lifetime totals.
 *
 * <p>A retailer request means an attempted BrowserAct workflow submission. BrowserAct controls its
 * own browser activity, so this service cannot measure the number of HTTP requests to a retailer.
 * BrowserAct API calls include submissions and polling and are counted separately.
 */
@Component
public class ScrapeMetrics {
    private static final String PREFIX = "pricepilot.scrape.";
    private final Instant since;
    private final Count retailerRequests;
    private final Count browserActApiCalls;
    private final Count successfulRequests;
    private final Count failedRequests;
    private final Count cacheHits;
    private final Count duplicateRequestsSkipped;
    private final Count scheduledChecksDue;
    private final Count scheduledChecksCompleted;
    private final Count scheduledChecksCompletedOnTime;
    private final Count scheduledChecksFailed;
    private final Count scheduledChecksRejected;
    private final AtomicLong dueBacklog = new AtomicLong();
    private final AtomicLong overdueBacklog = new AtomicLong();

    @Autowired
    public ScrapeMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    ScrapeMetrics(MeterRegistry registry, Clock clock) {
        since = clock.instant();
        retailerRequests = counter(registry, "retailer_requests",
                "BrowserAct workflow submission attempts, not retailer HTTP page requests");
        browserActApiCalls = counter(registry, "browseract_api_calls",
                "Actual BrowserAct HTTP call attempts, including workflow submissions and polling");
        successfulRequests = counter(registry, "successful_requests",
                "Submitted BrowserAct workflows that returned a verified product price");
        failedRequests = counter(registry, "failed_requests",
                "Submitted BrowserAct workflows that did not return a verified product price");
        cacheHits = counter(registry, "cache_hits", "Logical checks served from a fresh verified observation");
        duplicateRequestsSkipped = counter(registry, "duplicate_requests_skipped",
                "Logical checks sharing in-progress work instead of starting duplicate work");
        scheduledChecksDue = counter(registry, "scheduled_checks_due", "Distinct due scheduled checks observed");
        scheduledChecksCompleted = counter(registry, "scheduled_checks_completed",
                "Due scheduled checks completed with a verified fresh observation");
        scheduledChecksCompletedOnTime = counter(registry, "scheduled_checks_completed_on_time",
                "Due scheduled checks completed with a verified fresh observation by their deadline");
        scheduledChecksFailed = counter(registry, "scheduled_checks_failed",
                "Due scheduled checks that finished without a verified fresh observation");
        scheduledChecksRejected = counter(registry, "scheduled_checks_rejected",
                "Scheduler submissions refused by its bounded executor, with listings left due");
        Gauge.builder(PREFIX + "scheduled_due_backlog", dueBacklog, AtomicLong::get)
                .description("Current count of listings whose scheduled check is due").register(registry);
        Gauge.builder(PREFIX + "scheduled_overdue_backlog", overdueBacklog, AtomicLong::get)
                .description("Current count of due listings past their refresh deadline").register(registry);
    }

    private static Count counter(MeterRegistry registry, String name, String description) {
        return new Count(Counter.builder(PREFIX + name).description(description).register(registry));
    }

    /** Call immediately before the run-task HTTP send, after all guards and budget reservation. */
    public void retailerRequest() { retailerRequests.increment(); }
    /** Call immediately before each BrowserAct HTTP send, including a failed transport attempt. */
    public void browserActApiCall() { browserActApiCalls.increment(); }
    public void requestSucceeded() { successfulRequests.increment(); }
    public void requestFailed() { failedRequests.increment(); }
    public void cacheHit() { cacheHits.increment(); }
    public void duplicateSkipped() { duplicateRequestsSkipped.increment(); }
    public void scheduledDue() { scheduledChecksDue.increment(); }
    public void scheduledCompleted(boolean onTime) {
        scheduledChecksCompleted.increment();
        if (onTime) scheduledChecksCompletedOnTime.increment();
    }
    public void scheduledFailed() { scheduledChecksFailed.increment(); }
    public void scheduledRejected() { scheduledChecksRejected.increment(); }
    public void updateBacklog(long due, long overdue) {
        long nonnegativeDue = Math.max(0, due);
        dueBacklog.set(nonnegativeDue);
        overdueBacklog.set(Math.max(0, Math.min(nonnegativeDue, overdue)));
    }

    /** Counters may advance while a snapshot is being read; compare samples over the same window. */
    public Snapshot snapshot() {
        return new Snapshot(since, "BrowserAct workflow submission attempts",
                count(retailerRequests), count(browserActApiCalls), count(successfulRequests),
                count(failedRequests), count(cacheHits), count(duplicateRequestsSkipped),
                count(scheduledChecksDue), count(scheduledChecksCompleted),
                count(scheduledChecksCompletedOnTime), count(scheduledChecksFailed),
                count(scheduledChecksRejected), dueBacklog.get(), overdueBacklog.get());
    }

    private static long count(Count counter) { return counter.total.sum(); }

    /** Keep process totals even if an export registry is disabled or resets its step counters. */
    private static final class Count {
        private final Counter exported;
        private final LongAdder total = new LongAdder();

        private Count(Counter exported) { this.exported = exported; }
        private void increment() {
            total.increment();
            exported.increment();
        }
    }

    public record Snapshot(
            Instant since,
            String requestUnit,
            long retailerRequests,
            long browserActApiCalls,
            long successfulRequests,
            long failedRequests,
            long cacheHits,
            long duplicateRequestsSkipped,
            long scheduledChecksDue,
            long scheduledChecksCompleted,
            long scheduledChecksCompletedOnTime,
            long scheduledChecksFailed,
            long scheduledChecksRejected,
            long dueBacklog,
            long overdueBacklog) { }
}
