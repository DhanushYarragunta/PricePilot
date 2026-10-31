package com.pricepilot.metrics;

import com.pricepilot.config.AppProperties;
import com.pricepilot.repo.*;
import com.pricepilot.schedule.ScheduleDispatcher;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.usage.BudgetGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpsOptimizationMetricsTest {
    @Test void actualSubmissionCountersRemainSeparateFromHistoricalCheckRowsAndWindow() {
        ScrapeAttemptRepository attempts = mock(ScrapeAttemptRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        when(attempts.countNetworkRequests(any(Instant.class))).thenReturn(200L);
        when(listings.countDue(any(Instant.class))).thenReturn(1200L);
        var telemetry = new ScrapeMetrics(new SimpleMeterRegistry());
        telemetry.retailerRequest();
        telemetry.browserActApiCall();
        telemetry.browserActApiCall();
        telemetry.requestSucceeded();
        telemetry.cacheHit();
        telemetry.duplicateSkipped();
        telemetry.scheduledDue();
        telemetry.scheduledCompleted(true);
        var service = new OpsMetricsService(attempts, listings, mock(TrackerRepository.class),
                mock(PricePointRepository.class), mock(DealAnalysisRepository.class),
                mock(AlertLogRepository.class), mock(DomainRules.class), mock(BudgetGuard.class),
                mock(ScheduleDispatcher.class), new AppProperties(), telemetry);

        OpsMetrics result = service.snapshot(7);

        assertThat(result.extraction().networkRequests()).isEqualTo(200); // legacy check rows
        assertThat(result.optimization().retailerRequests()).isEqualTo(1);
        assertThat(result.optimization().browserActApiCalls()).isEqualTo(2);
        assertThat(result.optimization().cacheHits()).isEqualTo(1);
        assertThat(result.optimization().duplicateRequestsSkipped()).isEqualTo(1);
        assertThat(result.optimization().since()).isEqualTo(telemetry.snapshot().since());
        assertThat(result.optimization().scheduledChecksCompletedOnTime()).isEqualTo(1);
        assertThat(result.scheduler().dueNow()).isEqualTo(1200); // not a capped page of 500
        assertThat(service.snapshot(30).optimization()).isEqualTo(result.optimization());
    }
}
