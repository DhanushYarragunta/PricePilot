package com.pricepilot.schedule;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduleDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ScrapeAttemptRepository attempts = mock(ScrapeAttemptRepository.class);
    private final ListingRefreshService refresher = mock(ListingRefreshService.class);
    private final AppProperties props = new AppProperties();
    private final ScrapeMetrics metrics = new ScrapeMetrics(new SimpleMeterRegistry());
    private final QueueExecutor executor = new QueueExecutor();
    private final ScheduleDispatcher dispatcher = new ScheduleDispatcher(listings, attempts, refresher,
            props, metrics, executor, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void allDueCyclesIncludingTailAndQueueRejectionStayInTheDenominatorOnce() {
        props.getScheduler().setBatchSize(1);
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(
                due(1, NOW.minusSeconds(400)), due(2, NOW.minusSeconds(60)), due(3, NOW.minusSeconds(20))));
        executor.reject = true;
        dispatcher.tick(); dispatcher.tick();
        var rejected = metrics.snapshot();
        assertThat(rejected.scheduledChecksDue()).isEqualTo(3);
        assertThat(rejected.scheduledChecksRejected()).isEqualTo(2);
        assertThat(rejected.dueBacklog()).isEqualTo(3);
        assertThat(rejected.overdueBacklog()).isEqualTo(1);
        assertThat(rejected.scheduledChecksCompleted()).isZero();
        executor.reject = false;
        dispatcher.tick(); dispatcher.tick();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(3);
        assertThat(executor.tasks).hasSize(2); // One tick can admit another listing despite the first in flight.
        assertThat(metrics.snapshot().duplicateRequestsSkipped()).isEqualTo(1);
    }

    @Test void lateSuccessAndFailureAreNotCountedAsOnTimeCompletions() {
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(
                due(1, NOW.minusSeconds(400)), due(2, NOW.minusSeconds(60))));
        when(refresher.refresh(1L, TriggerSource.SCHEDULER)).thenReturn(result(1, ScrapeOutcome.SUCCESS));
        when(refresher.refresh(2L, TriggerSource.SCHEDULER)).thenReturn(result(2, ScrapeOutcome.TIMEOUT));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(10)));
        when(listings.lastSuccessAt(2L)).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(2);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isZero();
        assertThat(metrics.snapshot().scheduledChecksFailed()).isEqualTo(1);
    }

    @Test void sharedCheckCountsOnlyWhenOriginalPersistedObservationFulfillsTheDueCycle() {
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(
                due(1, NOW.minusSeconds(60)), due(2, NOW.minusSeconds(60))));
        when(refresher.refresh(anyLong(), eq(TriggerSource.SCHEDULER)))
                .thenAnswer(call -> result(call.getArgument(0), ScrapeOutcome.SKIPPED_FRESH));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(10)));
        when(listings.lastSuccessAt(2L)).thenReturn(Optional.of(NOW.minusSeconds(120)));
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksFailed()).isEqualTo(1);
    }

    @Test void manuallyFulfilledTailIsCountedButPausedOrFailedReschedulingIsNot() {
        props.getScheduler().setBatchSize(1);
        when(listings.findDueSchedule(NOW)).thenReturn(
                List.of(due(1, NOW.minusSeconds(60)), due(2, NOW.minusSeconds(60)), due(3, NOW.minusSeconds(60))),
                List.of(due(1, NOW.minusSeconds(60))));
        when(listings.lastSuccessAt(2L)).thenReturn(Optional.of(NOW.minusSeconds(5)));
        when(listings.lastSuccessAt(3L)).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        dispatcher.tick(); dispatcher.tick();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(3);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksFailed()).isEqualTo(1);
        assertThat(metrics.snapshot().dueBacklog()).isEqualTo(1);
    }

    @Test void newDueTimeCreatesANewCycleWhileRepeatedTicksDoNot() {
        when(listings.findDueSchedule(NOW)).thenReturn(
                List.of(due(1, NOW.minusSeconds(400))), List.of(), List.of(due(1, NOW.minusSeconds(30))));
        when(refresher.refresh(1L, TriggerSource.SCHEDULER)).thenReturn(result(1, ScrapeOutcome.SUCCESS));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(100)));
        dispatcher.tick(); executor.runAll(); dispatcher.tick(); dispatcher.tick();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(2);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isZero();
    }

    @Test void timelyObservationWithLatePersistenceIsLateButSlowFollowUpsAfterTimelyCommitAreNot() {
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(
                due(1, NOW.minusSeconds(400)), due(2, NOW.minusSeconds(400))));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(150)));
        when(listings.lastSuccessAt(2L)).thenReturn(Optional.of(NOW.minusSeconds(150)));
        when(refresher.refresh(1L, TriggerSource.SCHEDULER))
                .thenReturn(result(1, ScrapeOutcome.SUCCESS).withCompletedAt(NOW));
        when(refresher.refresh(2L, TriggerSource.SCHEDULER))
                .thenReturn(result(2, ScrapeOutcome.SUCCESS).withCompletedAt(NOW.minusSeconds(120)));
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(2);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isEqualTo(1);
    }

    @Test void disabledSchedulerDoesNotReadOrCountDueWork() {
        props.getScheduler().setEnabled(false);
        dispatcher.tick();
        verifyNoInteractions(listings, refresher);
        assertThat(metrics.snapshot().scheduledChecksDue()).isZero();
    }

    @Test void persistenceExceptionLeavesTheSameDueCyclePendingForASuccessfulRetry() {
        Instant dueAt = NOW.minusSeconds(60);
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(due(1, dueAt)));
        when(refresher.refresh(1L, TriggerSource.SCHEDULER))
                .thenThrow(new IllegalStateException("Synthetic persistence failure"))
                .thenReturn(result(1, ScrapeOutcome.SUCCESS).withCompletedAt(NOW));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(10)));
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isZero();
        assertThat(metrics.snapshot().scheduledChecksFailed()).isZero();
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompletedOnTime()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksFailed()).isZero();
    }

    @Test void unfulfilledCycleStaysPendingWhileDueAndFailsOnlyWhenRescheduled() {
        Instant dueAt = NOW.minusSeconds(60);
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(due(1, dueAt)), List.of());
        when(refresher.refresh(1L, TriggerSource.SCHEDULER)).thenReturn(result(1, ScrapeOutcome.TIMEOUT));
        when(listings.lastSuccessAt(1L)).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        when(listings.nextCheckAt(1L)).thenReturn(Optional.of(dueAt));
        dispatcher.tick(); executor.runAll();
        assertThat(metrics.snapshot().scheduledChecksFailed()).isZero();
        dispatcher.tick();
        assertThat(metrics.snapshot().scheduledChecksFailed()).isEqualTo(1);
        assertThat(metrics.snapshot().scheduledChecksCompleted()).isZero();
        assertThat(metrics.snapshot().scheduledChecksDue()).isEqualTo(1);
    }

    @Test void shutdownStopsQueuedChecksBeforeTheyReachTheProviderAndUsesSharedDrainPhase() throws Exception {
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(due(1, NOW.minusSeconds(60))));
        var stopping = new ScheduleDispatcher(listings, attempts, refresher, props, metrics, executor,
                Clock.fixed(NOW, ZoneOffset.UTC), 0);
        stopping.tick();
        CountDownLatch stopped = new CountDownLatch(1);
        stopping.stop(stopped::countDown);
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        executor.runAll();
        stopping.tick();
        verifyNoInteractions(refresher);
        assertThat(stopping.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(stopping.isRunning()).isFalse();
        assertThat(stopping.inFlightCount()).isZero();
        verify(listings, times(1)).findDueSchedule(NOW);
    }

    @Test void contextCloseStopsAdmissionBeforeWebAndBackgroundDrainPhases() throws Exception {
        when(listings.findDueSchedule(NOW)).thenReturn(List.of(due(1, NOW.minusSeconds(60))));
        var stopping = new ScheduleDispatcher(listings, attempts, refresher, props, metrics, executor,
                Clock.fixed(NOW, ZoneOffset.UTC), 0);
        stopping.tick();
        stopping.closeAdmission();
        assertThat(stopping.isRunning()).isTrue();
        executor.runAll();
        stopping.tick();
        verifyNoInteractions(refresher);
        verify(listings, times(1)).findDueSchedule(NOW);
        stopping.stop();
        assertThat(stopping.isRunning()).isFalse();
    }

    private static ListingRepository.DueListing due(long id, Instant time) {
        return new ListingRepository.DueListing() {
            public Long getId() { return id; }
            public Instant getDueAt() { return time; }
        };
    }

    private static RefreshResult result(long id, ScrapeOutcome outcome) {
        return new RefreshResult(id, 100L, outcome, "Amazon.in", null, null, null, false,
                false, false, false, NOW.plusSeconds(3600), 60, "browseract", 0, true, null);
    }

    private static class QueueExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean reject, shutdown;
        public void execute(Runnable task) {
            if (reject || shutdown) throw new RejectedExecutionException();
            tasks.add(task);
        }
        void runAll() { List<Runnable> copy = List.copyOf(tasks); tasks.clear(); copy.forEach(Runnable::run); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        public boolean awaitTermination(long time, TimeUnit unit) { return isTerminated(); }
    }
}
