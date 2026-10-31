package com.pricepilot.service;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.schedule.ListingRefreshService;
import com.pricepilot.schedule.RefreshResult;
import com.pricepilot.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualRefreshJobsTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ListingRefreshService refresher = mock(ListingRefreshService.class);
    private final QueueExecutor executor = new QueueExecutor();
    private final MutableClock clock = new MutableClock();
    private final ManualRefreshJobs jobs = new ManualRefreshJobs(listings, refresher, executor, clock,
            2, Duration.ofSeconds(60), 0);

    @AfterEach void close() { jobs.stop(); }

    @Test void startsWithoutRunningRetailerCallsAndSharesOneProductJob() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1), listing(2)));
        var first = jobs.start(7L);
        var duplicate = jobs.start(7L);
        assertThat(first.jobId()).isEqualTo(duplicate.jobId());
        assertThat(first.status()).isEqualTo(ManualRefreshJobs.Status.QUEUED);
        assertThat(first.terminal()).isFalse();
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.checked()).isZero();
        assertThat(first.results()).isEmpty();
        assertThat(executor.tasks).hasSize(1);
        verify(listings, times(1)).findByProductIdAndStatus(7L, ListingStatus.ACTIVE);
        verifyNoInteractions(refresher);
    }

    @Test void reportsIncrementalProgressThenPartialOutcomeUsingOriginalListingService() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1), listing(2)));
        var first = jobs.start(7L);
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.SUCCESS, true));
        when(refresher.refresh(2L, TriggerSource.MANUAL)).thenAnswer(call -> {
            var progress = jobs.status(7L, first.jobId());
            assertThat(progress.status()).isEqualTo(ManualRefreshJobs.Status.RUNNING);
            assertThat(progress.checked()).isEqualTo(1);
            assertThat(progress.succeeded()).isEqualTo(1);
            return result(2, ScrapeOutcome.NO_PRICE, true);
        });
        executor.runAll();
        var complete = jobs.status(7L, first.jobId());
        assertThat(complete.status()).isEqualTo(ManualRefreshJobs.Status.PARTIAL);
        assertThat(complete.terminal()).isTrue();
        assertThat(complete.checked()).isEqualTo(2);
        assertThat(complete.succeeded()).isEqualTo(1);
        assertThat(complete.failed()).isEqualTo(1);
        assertThat(complete.requestsMade()).isEqualTo(2);
        assertThat(complete.results().get(1).price()).isNull();
        assertThat(complete.results().get(1).message()).contains("No valid price").doesNotContain("raw provider");
        assertThat(complete.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        var ordered = inOrder(refresher);
        ordered.verify(refresher).refresh(1L, TriggerSource.MANUAL);
        ordered.verify(refresher).refresh(2L, TriggerSource.MANUAL);
    }

    @Test void failedZeroRequestChecksAreNotMisreportedAsReusedPrices() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.ERROR, false));
        var queued = jobs.start(7L);
        executor.runAll();
        var finished = jobs.status(7L, queued.jobId());
        assertThat(finished.status()).isEqualTo(ManualRefreshJobs.Status.FAILED);
        assertThat(finished.message()).doesNotContain("reused");
        assertThat(finished.succeeded()).isZero();
        assertThat(finished.failed()).isEqualTo(1);
    }

    @Test void freshnessReuseIsCompletedWithoutClaimingAnotherSubmission() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.SKIPPED_FRESH, false));
        var queued = jobs.start(7L);
        executor.runAll();
        var finished = jobs.status(7L, queued.jobId());
        assertThat(finished.status()).isEqualTo(ManualRefreshJobs.Status.COMPLETED);
        assertThat(finished.skipped()).isEqualTo(1);
        assertThat(finished.succeeded()).isZero();
        assertThat(finished.requestsMade()).isZero();
    }

    @Test void zeroPaidCallsAloneDoesNotClaimEveryObservationWasReused() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.SUCCESS, false));
        var first = jobs.start(7L);
        executor.runAll();
        assertThat(jobs.status(7L, first.jobId()).message()).doesNotContain("reused");
    }

    @Test void unexpectedListingFailureIsSafeAndDoesNotDiscardOtherResults() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1), listing(2)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenThrow(new IllegalStateException("secret raw provider content"));
        when(refresher.refresh(2L, TriggerSource.MANUAL)).thenReturn(result(2, ScrapeOutcome.SUCCESS, true));
        var queued = jobs.start(7L);
        executor.runAll();
        var finished = jobs.status(7L, queued.jobId());
        assertThat(finished.status()).isEqualTo(ManualRefreshJobs.Status.PARTIAL);
        assertThat(finished.results()).hasSize(2);
        assertThat(finished.results().get(0).message()).doesNotContain("secret", "raw provider content");
        assertThat(finished.results().get(0).price()).isNull();
    }

    @Test void noActiveListingsDoesNotCreateAJob() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of());
        assertThatThrownBy(() -> jobs.start(7L)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(400));
        assertThat(executor.tasks).isEmpty();
        verifyNoInteractions(refresher);
    }

    @Test void fullExecutorRefusesAdmissionAndReleasesProductClaimForLaterRetry() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        executor.reject = true;
        assertThatThrownBy(() -> jobs.start(7L)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_queue_full"));
        executor.reject = false;
        assertThat(jobs.start(7L).status()).isEqualTo(ManualRefreshJobs.Status.QUEUED);
        assertThat(executor.tasks).hasSize(1);
        verifyNoInteractions(refresher);
    }

    @Test void configuredExecutorHasABoundedQueueAndNeverRunsOverflowOnTheRequestThread() throws Exception {
        var service = new ManualRefreshJobs(listings, refresher, 1, 1, 10, 60, 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(listings.findByProductIdAndStatus(anyLong(), eq(ListingStatus.ACTIVE))).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenAnswer(call -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return result(1, ScrapeOutcome.SUCCESS, true);
        });
        try {
            service.start(7L);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.start(8L).status()).isEqualTo(ManualRefreshJobs.Status.QUEUED);
            assertThatThrownBy(() -> service.start(9L)).isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_queue_full"));
            verify(refresher, times(1)).refresh(1L, TriggerSource.MANUAL);
        } finally { service.stop(); release.countDown(); }
    }

    @Test void registryNeverEvictsActiveJobsToAdmitMoreWork() {
        when(listings.findByProductIdAndStatus(anyLong(), eq(ListingStatus.ACTIVE))).thenReturn(List.of(listing(1)));
        var first = jobs.start(7L);
        jobs.start(8L);
        assertThatThrownBy(() -> jobs.start(9L)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_queue_full"));
        assertThat(jobs.start(7L).jobId()).isEqualTo(first.jobId());
        assertThat(executor.tasks).hasSize(2);
    }

    @Test void terminalStatusExpiresButActiveJobDoesNotLoseDeduplication() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.SUCCESS, true));
        var first = jobs.start(7L);
        clock.now = clock.now.plusSeconds(120);
        assertThat(jobs.start(7L).jobId()).isEqualTo(first.jobId());
        executor.runAll();
        clock.now = clock.now.plusSeconds(61);
        assertUnknown(() -> jobs.status(7L, first.jobId()));
    }

    @Test void pollingAtCapacityDoesNotEvictAnUnexpiredCompletedStatus() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenReturn(result(1, ScrapeOutcome.SUCCESS, true));
        var first = jobs.start(7L);
        executor.runAll();
        when(listings.findByProductIdAndStatus(8L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(2)));
        jobs.start(8L);
        assertThat(jobs.status(7L, first.jobId()).status()).isEqualTo(ManualRefreshJobs.Status.COMPLETED);
        assertThat(jobs.status(7L, first.jobId()).status()).isEqualTo(ManualRefreshJobs.Status.COMPLETED);
    }

    @Test void jobCannotBeReadUnderAnotherProductAndRestartUnknownDoesNotResubmit() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        var first = jobs.start(7L);
        assertUnknown(() -> jobs.status(8L, first.jobId()));
        var restarted = new ManualRefreshJobs(listings, refresher, new QueueExecutor(), clock, 2, Duration.ofMinutes(1), 0);
        try { assertUnknown(() -> restarted.status(7L, first.jobId())); }
        finally { restarted.stop(); }
        verifyNoInteractions(refresher);
        verify(listings, times(1)).findByProductIdAndStatus(7L, ListingStatus.ACTIVE);
    }

    @Test void shutdownCancelsQueuedJobsAndClosesAdmissionWithoutCallingProviders() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        var first = jobs.start(7L);
        jobs.stop();
        executor.runAll();
        var stopped = jobs.status(7L, first.jobId());
        assertThat(stopped.status()).isEqualTo(ManualRefreshJobs.Status.CANCELLED);
        assertThat(stopped.checked()).isZero();
        assertThat(stopped.terminal()).isTrue();
        assertThatThrownBy(() -> jobs.start(7L)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_stopping"));
        verifyNoInteractions(refresher);
        assertThat(jobs.isRunning()).isFalse();
    }

    @Test void contextCloseRefusesNewWorkBeforeTheLaterLifecycleDrainStarts() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        var first = jobs.start(7L);
        jobs.closeAdmission();
        assertThat(jobs.isRunning()).isTrue(); // Web-server drain can still be in its earlier phase.
        assertThatThrownBy(() -> jobs.start(7L)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_stopping"));
        executor.runAll();
        verifyNoInteractions(refresher);
        assertThat(jobs.status(7L, first.jobId()).status()).isEqualTo(ManualRefreshJobs.Status.CANCELLED);
        jobs.stop();
        assertThat(jobs.isRunning()).isFalse();
    }

    @Test void springContextClosePublishesAdmissionStopBeforeTheWebDrainPhase() {
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1)));
        CountDownLatch webDrainChecked = new CountDownLatch(1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("manualRefreshJobs", ManualRefreshJobs.class, () -> jobs);
            context.registerBean("simulatedWebDrain", SmartLifecycle.class, () -> new SmartLifecycle() {
                private boolean running = true;
                @Override public void start() { running = true; }
                @Override public void stop() {
                    assertThatThrownBy(() -> jobs.start(7L)).isInstanceOf(ApiException.class)
                            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("refresh_stopping"));
                    running = false;
                    webDrainChecked.countDown();
                }
                @Override public boolean isRunning() { return running; }
                @Override public int getPhase() { return Integer.MAX_VALUE; }
            });
            context.refresh();
            jobs.start(7L);
        }
        assertThat(webDrainChecked.getCount()).isZero();
        assertThat(jobs.isRunning()).isFalse();
        verifyNoInteractions(refresher);
    }

    @Test void shutdownAllowsCurrentListingToFinishButDoesNotStartTheNextOne() throws Exception {
        ThreadPoolExecutor real = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        var service = new ManualRefreshJobs(listings, refresher, real, clock, 2, Duration.ofMinutes(1), 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(List.of(listing(1), listing(2)));
        when(refresher.refresh(1L, TriggerSource.MANUAL)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return result(1, ScrapeOutcome.SUCCESS, true);
        });
        try {
            var first = service.start(7L);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.stop(drained::countDown);
            assertThat(drained.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
            var stopped = service.status(7L, first.jobId());
            assertThat(stopped.status()).isEqualTo(ManualRefreshJobs.Status.CANCELLED);
            assertThat(stopped.checked()).isEqualTo(1);
            assertThat(stopped.succeeded()).isEqualTo(1);
            verify(refresher, never()).refresh(2L, TriggerSource.MANUAL);
            assertThat(service.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        } finally { release.countDown(); service.stop(); real.shutdownNow(); }
    }

    @Test void concurrentStartsRaceWithoutSubmittingDuplicateWork() throws Exception {
        CountDownLatch bothReading = new CountDownLatch(2), release = new CountDownLatch(1);
        when(listings.findByProductIdAndStatus(7L, ListingStatus.ACTIVE)).thenAnswer(call -> {
            bothReading.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return List.of(listing(1));
        });
        AtomicReference<ManualRefreshJobs.Snapshot> one = new AtomicReference<>(), two = new AtomicReference<>();
        Thread a = new Thread(() -> one.set(jobs.start(7L))), b = new Thread(() -> two.set(jobs.start(7L)));
        a.start(); b.start();
        assertThat(bothReading.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown(); a.join(2000); b.join(2000);
        assertThat(one.get()).isNotNull();
        assertThat(two.get().jobId()).isEqualTo(one.get().jobId());
        assertThat(executor.tasks).hasSize(1);
    }

    private static void assertUnknown(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).satisfies(error -> {
            assertThat(((ApiException) error).getCode()).isEqualTo("refresh_job_unknown");
            assertThat(error.getMessage()).contains("server restarted", "no new refresh");
        });
    }

    private static Listing listing(long id) {
        Listing listing = new Listing(); listing.setId(id); listing.setRetailer("Retailer " + id); return listing;
    }

    private static RefreshResult result(long id, ScrapeOutcome outcome, boolean paid) {
        return new RefreshResult(id, 7L, outcome, "Retailer " + id,
                outcome == ScrapeOutcome.SUCCESS ? new BigDecimal("100.00") : null,
                new BigDecimal("120.00"), null, true, false, outcome == ScrapeOutcome.SUCCESS, false,
                Instant.parse("2026-10-31T18:00:00Z"), 60, "browseract", 5, paid, "raw provider content");
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject, shutdown;
        @Override public void execute(Runnable task) {
            if (reject || shutdown) throw new RejectedExecutionException();
            tasks.add(task);
        }
        void runAll() { var copy = List.copyOf(tasks); tasks.clear(); copy.forEach(Runnable::run); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; var copy = List.copyOf(tasks); tasks.clear(); return copy; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
