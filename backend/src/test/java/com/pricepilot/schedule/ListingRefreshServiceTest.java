package com.pricepilot.schedule;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.alerts.AlertService;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.*;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.scrape.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingRefreshServiceTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ScrapeEngine engine = mock(ScrapeEngine.class);
    private final RefreshWriter writer = mock(RefreshWriter.class);
    private final AlertService alerts = mock(AlertService.class);
    private final DealAnalyzer analyzer = mock(DealAnalyzer.class);
    private final AppProperties props = new AppProperties();
    private final ScrapeMetrics metrics = spy(new ScrapeMetrics(new SimpleMeterRegistry()));
    private final ListingRefreshService service = new ListingRefreshService(
            listings, engine, writer, alerts, analyzer, props, metrics);
    private Listing listing;

    @BeforeEach void setup() {
        Product product = new Product(); product.setId(1L);
        listing = new Listing(); listing.setId(2L); listing.setProduct(product); listing.setUrl(URL);
        listing.setRetailer("Amazon.in"); listing.setStatus(ListingStatus.ACTIVE);
        listing.setLastSuccessAt(Instant.now().minusSeconds(7200));
        listing.setNextCheckAt(Instant.now().minusSeconds(60));
        when(listings.findWithProductById(2L)).thenReturn(Optional.of(listing));
    }

    @Test void simultaneousManualAndScheduledRefreshesShareOneWriteAndAlertEvaluation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        ScrapeResult scraped = success();
        when(engine.scrapeRaw(eq(URL), any(Duration.class))).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return scraped;
        });
        RefreshResult persisted = refresh(ScrapeOutcome.SUCCESS);
        when(writer.recordSuccess(2L, scraped, TriggerSource.MANUAL)).thenAnswer(call -> {
            listing.setLastSuccessAt(Instant.now()); return persisted;
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshResult> owner = threads.submit(() -> service.refresh(2L, TriggerSource.MANUAL));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<RefreshResult> duplicate = threads.submit(() -> {
                waiter.set(Thread.currentThread()); return service.refresh(2L, TriggerSource.SCHEDULER);
            });
            awaitWaiting(waiter);
            release.countDown();
            assertThat(owner.get(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            RefreshResult shared = duplicate.get(3, TimeUnit.SECONDS);
            assertThat(shared.isSkipped()).isTrue();
            assertThat(shared.paid()).isFalse();
            assertThat(shared.priceChanged()).isFalse();
            verify(engine, times(1)).scrapeRaw(URL, Duration.ofSeconds(60));
            verify(writer, times(1)).recordSuccess(2L, scraped, TriggerSource.MANUAL);
            verify(alerts, times(1)).evaluate(argThat(value -> value.isSuccess() && value.completedAt() != null));
            assertThat(metrics.snapshot().duplicateRequestsSkipped()).isEqualTo(1);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void manualDatabaseCacheHitDoesNotCallTheScraperOrFollowUps() {
        listing.setLastSuccessAt(Instant.now().minusSeconds(20));
        when(writer.recordSkip(eq(2L), any(), eq(TriggerSource.MANUAL))).thenReturn(refresh(ScrapeOutcome.SKIPPED_FRESH));
        assertThat(service.refresh(2L, TriggerSource.MANUAL).isSkipped()).isTrue();
        assertThat(metrics.snapshot().cacheHits()).isEqualTo(1);
        verifyNoInteractions(engine, alerts, analyzer);
    }

    @Test void queuedSchedulerWorkRescheduledByManualRefreshNeverScrapesAgain() {
        listing.setNextCheckAt(Instant.now().plusSeconds(3600));
        assertThat(service.refresh(2L, TriggerSource.SCHEDULER).isSkipped()).isTrue();
        verifyNoInteractions(engine, writer, alerts, analyzer);
        assertThat(metrics.snapshot().cacheHits()).isZero();
    }

    @Test void dueBlockedListingIsRetriedAndSchedulerDoesNotReuseAPreDueEngineCache() {
        listing.setStatus(ListingStatus.BLOCKED);
        ScrapeResult failure = ScrapeResult.aborted(ScrapeOutcome.TIMEOUT, "amazon.in", URL, null, 0, "Timeout");
        when(engine.scrapeRaw(URL, Duration.ZERO)).thenReturn(failure);
        when(writer.recordFailure(2L, failure, TriggerSource.SCHEDULER)).thenReturn(refresh(ScrapeOutcome.TIMEOUT));
        assertThat(service.refresh(2L, TriggerSource.SCHEDULER).isSuccess()).isFalse();
        verify(engine).scrapeRaw(URL, Duration.ZERO);
        verify(writer, never()).deferWithoutAttempt(anyLong(), anyInt());
        verifyNoInteractions(alerts, analyzer);
    }

    @Test void engineCacheReplayPersistedAsSkipCannotTriggerAlertsOrAnalysis() {
        ScrapeResult reused = success().reusedAs(ScrapeResult.Reuse.CACHE);
        when(engine.scrapeRaw(URL, Duration.ofSeconds(60))).thenReturn(reused);
        when(writer.recordSuccess(2L, reused, TriggerSource.MANUAL)).thenReturn(refresh(ScrapeOutcome.SKIPPED_FRESH));
        assertThat(service.refresh(2L, TriggerSource.MANUAL).isSkipped()).isTrue();
        verifyNoInteractions(alerts, analyzer);
        assertThat(metrics.snapshot().cacheHits()).isZero(); // The engine owns this cache event.
    }

    @Test void completionTimeIsRecordedAfterPersistenceAndBeforeOptionalFollowUps() {
        ScrapeResult scraped = success();
        when(engine.scrapeRaw(URL, Duration.ofSeconds(60))).thenReturn(scraped);
        java.util.concurrent.atomic.AtomicReference<Instant> transactionReturned = new java.util.concurrent.atomic.AtomicReference<>();
        when(writer.recordSuccess(2L, scraped, TriggerSource.MANUAL)).thenAnswer(call -> {
            transactionReturned.set(Instant.now());
            return refresh(ScrapeOutcome.SUCCESS);
        });
        doAnswer(call -> {
            RefreshResult committed = call.getArgument(0);
            assertThat(committed.completedAt()).isNotNull().isAfterOrEqualTo(transactionReturned.get())
                    .isBeforeOrEqualTo(Instant.now());
            return null;
        }).when(alerts).evaluate(any());
        RefreshResult result = service.refresh(2L, TriggerSource.MANUAL);
        assertThat(result.completedAt()).isAfterOrEqualTo(transactionReturned.get());
        verify(alerts).evaluate(result);
    }

    @Test void manualFollowerRechecksWhenSchedulerOwnerOnlyReusedAnOlderObservation() throws Exception {
        listing.setLastSuccessAt(Instant.now().minusSeconds(600));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        when(writer.recordSkip(eq(2L), any(), eq(TriggerSource.SCHEDULER))).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return refresh(ScrapeOutcome.SKIPPED_FRESH);
        });
        ScrapeResult scraped = success();
        when(engine.scrapeRaw(URL, Duration.ofSeconds(60))).thenReturn(scraped);
        when(writer.recordSuccess(2L, scraped, TriggerSource.MANUAL)).thenAnswer(call -> {
            listing.setLastSuccessAt(Instant.now()); return refresh(ScrapeOutcome.SUCCESS);
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshResult> scheduled = threads.submit(() -> service.refresh(2L, TriggerSource.SCHEDULER));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<RefreshResult> manual = threads.submit(() -> {
                waiter.set(Thread.currentThread()); return service.refresh(2L, TriggerSource.MANUAL);
            });
            awaitWaiting(waiter); release.countDown();
            assertThat(scheduled.get(3, TimeUnit.SECONDS).isSkipped()).isTrue();
            assertThat(manual.get(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            verify(engine, times(1)).scrapeRaw(URL, Duration.ofSeconds(60));
            assertThat(metrics.snapshot().duplicateRequestsSkipped()).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @Test void slowFollowUpsDoNotKeepACompletedRefreshAvailableToALaterStaleCaller() throws Exception {
        ScrapeResult scraped = success();
        when(engine.scrapeRaw(URL, Duration.ofSeconds(60))).thenReturn(scraped);
        when(writer.recordSuccess(2L, scraped, TriggerSource.MANUAL)).thenAnswer(call -> {
            listing.setLastSuccessAt(Instant.now()); return refresh(ScrapeOutcome.SUCCESS);
        });
        CountDownLatch inFollowUps = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger alertCalls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (alertCalls.incrementAndGet() == 1) {
                inFollowUps.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(alerts).evaluate(any());
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshResult> first = threads.submit(() -> service.refresh(2L, TriggerSource.MANUAL));
            assertThat(inFollowUps.await(3, TimeUnit.SECONDS)).isTrue();
            // Simulate observation expiry while an optional follow-up remains busy.
            listing.setLastSuccessAt(Instant.now().minusSeconds(90));
            Future<RefreshResult> second = threads.submit(() -> service.refresh(2L, TriggerSource.MANUAL));
            assertThat(second.get(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            release.countDown(); assertThat(first.get(3, TimeUnit.SECONDS).isSuccess()).isTrue();
            verify(engine, times(2)).scrapeRaw(URL, Duration.ofSeconds(60));
            assertThat(metrics.snapshot().duplicateRequestsSkipped()).isZero();
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    private static ScrapeResult success() {
        return new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1500"), null, "INR", "USB C Hub", null, true, "browseract"),
                "amazon.in", URL, null, 100, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", null);
    }

    @Test void joiningASubmittedFailureDoesNotClaimASecondOutboundRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        ScrapeResult failure = new ScrapeResult(ScrapeOutcome.TIMEOUT, null, "amazon.in", URL, null,
                100, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", "Timed out");
        when(engine.scrapeRaw(eq(URL), any(Duration.class))).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return failure;
        });
        when(writer.recordFailure(2L, failure, TriggerSource.MANUAL)).thenReturn(refresh(ScrapeOutcome.TIMEOUT));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefreshResult> owner = threads.submit(() -> service.refresh(2L, TriggerSource.MANUAL));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<RefreshResult> follower = threads.submit(() -> {
                waiter.set(Thread.currentThread()); return service.refresh(2L, TriggerSource.MANUAL);
            });
            awaitWaiting(waiter); release.countDown();
            RefreshResult original = owner.get(3, TimeUnit.SECONDS), shared = follower.get(3, TimeUnit.SECONDS);
            assertThat(original.consumedRequest()).isTrue();
            assertThat(shared.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
            assertThat(shared.consumedRequest()).isFalse();
            assertThat(shared.completedAt()).isEqualTo(original.completedAt());
            assertThat(metrics.snapshot().duplicateRequestsSkipped()).isEqualTo(1);
            verify(engine, times(1)).scrapeRaw(URL, Duration.ofSeconds(60));
            verify(writer, times(1)).recordFailure(2L, failure, TriggerSource.MANUAL);
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    private static void awaitWaiting(java.util.concurrent.atomic.AtomicReference<Thread> waiter) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            Thread thread = waiter.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("The follower did not begin waiting on the in-progress refresh");
    }

    private static RefreshResult refresh(ScrapeOutcome outcome) {
        return new RefreshResult(2L, 1L, outcome, "Amazon.in", new BigDecimal("1500"), null,
                null, true, false, false, false, Instant.now().plusSeconds(3600), 60,
                "browseract", 100, true, null);
    }
}
