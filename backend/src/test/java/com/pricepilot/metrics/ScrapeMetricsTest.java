package com.pricepilot.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScrapeMetricsTest {
    @Test void submissionsPollingReuseAndScheduleOutcomesHaveSeparateDenominators() {
        var registry = new SimpleMeterRegistry();
        Instant start = Instant.parse("2026-10-31T00:00:00Z");
        var metrics = new ScrapeMetrics(registry, Clock.fixed(start, ZoneOffset.UTC));
        metrics.retailerRequest();
        metrics.browserActApiCall();
        metrics.browserActApiCall();
        metrics.browserActApiCall();
        metrics.requestSucceeded();
        metrics.retailerRequest();
        metrics.browserActApiCall();
        metrics.requestFailed();
        metrics.cacheHit();
        metrics.cacheHit();
        metrics.duplicateSkipped();
        metrics.scheduledDue();
        metrics.scheduledDue();
        metrics.scheduledDue();
        metrics.scheduledCompleted(true);
        metrics.scheduledCompleted(false);
        metrics.scheduledFailed();
        metrics.scheduledRejected();
        metrics.updateBacklog(10, 3);

        var result = metrics.snapshot();
        assertThat(result.since()).isEqualTo(start);
        assertThat(result.requestUnit()).isEqualTo("BrowserAct workflow submission attempts");
        assertThat(result.retailerRequests()).isEqualTo(2);
        assertThat(result.browserActApiCalls()).isEqualTo(4);
        assertThat(result.successfulRequests()).isEqualTo(1);
        assertThat(result.failedRequests()).isEqualTo(1);
        assertThat(result.cacheHits()).isEqualTo(2);
        assertThat(result.duplicateRequestsSkipped()).isEqualTo(1);
        assertThat(result.scheduledChecksDue()).isEqualTo(3);
        assertThat(result.scheduledChecksCompleted()).isEqualTo(2);
        assertThat(result.scheduledChecksCompletedOnTime()).isEqualTo(1);
        assertThat(result.scheduledChecksFailed()).isEqualTo(1);
        assertThat(result.scheduledChecksRejected()).isEqualTo(1);
        assertThat(result.dueBacklog()).isEqualTo(10);
        assertThat(result.overdueBacklog()).isEqualTo(3);
        assertThat(registry.get("pricepilot.scrape.retailer_requests").counter().count()).isEqualTo(2);
        assertThat(registry.get("pricepilot.scrape.browseract_api_calls").counter().count()).isEqualTo(4);
        assertThat(registry.get("pricepilot.scrape.scheduled_overdue_backlog").gauge().value()).isEqualTo(3);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }

    @Test void concurrentChecksDoNotLoseCountsOrCreateUnboundedMeters() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new ScrapeMetrics(registry);
        int meterCount = registry.getMeters().size();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 1000; i++) tasks.add(() -> {
                metrics.cacheHit();
                metrics.duplicateSkipped();
                return null;
            });
            for (var future : pool.invokeAll(tasks)) future.get();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(metrics.snapshot().cacheHits()).isEqualTo(1000);
        assertThat(metrics.snapshot().duplicateRequestsSkipped()).isEqualTo(1000);
        assertThat(metrics.snapshot().retailerRequests()).isZero();
        assertThat(registry.getMeters()).hasSize(meterCount);
    }

    @Test void gaugesAreCurrentValuesAndFreshInstancesHaveNoHistoricCounts() {
        var metrics = new ScrapeMetrics(new SimpleMeterRegistry());
        metrics.updateBacklog(5, 2);
        metrics.updateBacklog(1, 7);
        assertThat(metrics.snapshot().dueBacklog()).isEqualTo(1);
        assertThat(metrics.snapshot().overdueBacklog()).isEqualTo(1);
        metrics.updateBacklog(-1, -2);
        assertThat(metrics.snapshot().dueBacklog()).isZero();
        assertThat(metrics.snapshot().overdueBacklog()).isZero();
        assertThat(new ScrapeMetrics(new SimpleMeterRegistry()).snapshot().retailerRequests()).isZero();
    }

    @Test void snapshotRetainsProcessTotalsWhenMetricsExportHasNoRegistry() {
        var metrics = new ScrapeMetrics(new CompositeMeterRegistry());
        metrics.retailerRequest();
        metrics.browserActApiCall();
        metrics.requestFailed();
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(1);
    }
}
