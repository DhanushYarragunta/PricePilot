package com.pricepilot.scrape;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.scrape.browseract.BrowserActClient;
import com.pricepilot.scrape.browseract.BrowserActExtraction;
import com.pricepilot.scrape.browseract.BrowserActResult;
import com.pricepilot.scrape.rules.DomainRules;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScrapeReuseTest {
    private static final Instant START = Instant.parse("2026-10-31T00:00:00Z");
    private static final String AMAZON = "https://www.amazon.in/dp/B07CR28HLL";
    private static final String AMAZON_ALIAS = "https://amazon.in/Hub/gp/product/B07CR28HLL?tag=affiliate";
    private static final String VARIANT = "https://www.amazon.in/dp/B000000001";
    private static final String FLIPKART = "https://www.flipkart.com/hub/p/itm123?pid=HUB123";
    private static final String FLIPKART_VARIANT = "https://www.flipkart.com/hub/p/itm123?pid=HUB456";
    private static final List<String> RETAILERS = List.of(AMAZON, FLIPKART,
            "https://www.croma.com/hub/p/12345", "https://www.reliancedigital.in/hub/p/12345",
            "https://www.vijaysales.com/hub/12345", "https://www.tatacliq.com/hub/p-mp00000012345",
            "https://www.jiomart.com/p/electronics/hub/12345", "https://www.snapdeal.com/product/hub/12345",
            "https://www.myntra.com/shirts/example/12345/buy", "https://www.ajio.com/example/p/12345",
            "https://www.nykaa.com/example/p/12345", "https://www.sangeethamobiles.com/product/example/12345",
            "https://www.poorvika.com/example-product");

    private static BrowserActExtraction valid(boolean stock) {
        return new BrowserActExtraction(new BrowserActResult("Verified USB C Hub", new BigDecimal("1609"),
                new BigDecimal("3999"), "INR", stock, null, null, "success"), ScrapeOutcome.SUCCESS, null, true);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static final class Fixture {
        final AppProperties props = new AppProperties();
        final DomainRules rules = new DomainRules(props, new DefaultResourceLoader());
        final PolitenessGate gate = mock(PolitenessGate.class);
        final BrowserActClient browser = mock(BrowserActClient.class);
        final UrlCanonicalizer canonicalizer = mock(UrlCanonicalizer.class);
        final ScrapeMetrics metrics = new ScrapeMetrics(new SimpleMeterRegistry());
        final MutableClock clock = new MutableClock();
        final Map<String, UrlCanonicalizer.Canonical> urls = new HashMap<>();
        final ScrapeEngine engine;

        Fixture() throws Exception {
            rules.reload();
            // Canonicalization is real, but DNS is outside these provider/cache unit tests.
            // Precompute on this thread; static Mockito mocks do not propagate to worker threads.
            try (var ignored = mockStatic(UrlGuard.class)) {
                var actual = new UrlCanonicalizer(rules);
                var inputs = new ArrayList<>(RETAILERS);
                inputs.addAll(List.of(AMAZON_ALIAS, VARIANT, FLIPKART_VARIANT));
                for (String input : inputs) {
                    var canonical = actual.canonicalize(input);
                    urls.put(input, canonical);
                    urls.put(canonical.url(), canonical);
                }
            }
            when(canonicalizer.canonicalize(anyString())).thenAnswer(call -> {
                var value = urls.get(call.getArgument(0));
                if (value == null) throw new UnsupportedUrlException("Unsupported test input");
                return value;
            });
            when(gate.execute(anyString(), anyLong(), any())).thenAnswer(call ->
                    ((Callable<?>) call.getArgument(2)).call());
            when(browser.extract(anyString(), any())).thenReturn(valid(true));
            engine = new ScrapeEngine(rules, gate, canonicalizer, browser, props, metrics, clock);
        }
    }

    @Test void freshCanonicalAliasesReusePriceStockAndOriginalObservationTimeWithoutPayingAgain() throws Exception {
        var f = new Fixture();
        when(f.browser.extract(anyString(), any())).thenReturn(valid(false));
        ScrapeResult original = f.engine.scrapeRaw(AMAZON);
        f.clock.advance(Duration.ofMinutes(2));
        ScrapeResult reused = f.engine.scrapeRaw(AMAZON_ALIAS);
        assertThat(reused.isSuccess()).isTrue();
        assertThat(reused.cacheHit()).isTrue();
        assertThat(reused.snapshot()).isEqualTo(original.snapshot());
        assertThat(reused.snapshot().inStock()).isFalse();
        assertThat(reused.observedAt()).isEqualTo(START);
        assertThat(reused.paid()).isFalse();
        assertThat(reused.source()).isNull();
        assertThat(original.paid()).isTrue();
        verify(f.browser, times(1)).extract(anyString(), any());
        verify(f.gate, times(1)).execute(anyString(), anyLong(), any());
        assertThat(f.metrics.snapshot().cacheHits()).isEqualTo(1);
    }

    @Test void manualMaxAgeAndTtlBoundReuseWithoutExtendingObservationTime() throws Exception {
        var f = new Fixture();
        f.engine.scrapeRaw(AMAZON);
        f.clock.advance(Duration.ofSeconds(59));
        assertThat(f.engine.scrapeRaw(AMAZON, Duration.ofSeconds(60)).cacheHit()).isTrue();
        f.clock.advance(Duration.ofSeconds(1));
        var manual = f.engine.scrapeRaw(AMAZON, Duration.ofSeconds(60));
        assertThat(manual.reused()).isFalse();
        assertThat(manual.observedAt()).isEqualTo(START.plusSeconds(60));
        f.clock.advance(Duration.ofSeconds(900));
        assertThat(f.engine.scrapeRaw(AMAZON, Duration.ofDays(1)).reused()).isFalse();
        assertThat(f.engine.scrapeRaw(AMAZON, Duration.ZERO).reused()).isFalse();
        verify(f.browser, times(4)).extract(anyString(), any());
    }

    @Test void clockRollbackDoesNotMakeAnObservationFromTheFutureFresh() throws Exception {
        var f = new Fixture();
        f.clock.advance(Duration.ofNanos(123456789));
        var original = f.engine.scrapeRaw(AMAZON);
        assertThat(original.observedAt()).isEqualTo(START.plusNanos(123456000));
        assertThat(original.reusedAs(ScrapeResult.Reuse.CACHE).observedAt()).isEqualTo(original.observedAt());
        f.clock.advance(Duration.ofSeconds(-1));
        var afterRollback = f.engine.scrapeRaw(AMAZON);
        assertThat(afterRollback.reused()).isFalse();
        assertThat(afterRollback.observedAt()).isBefore(original.observedAt());
        verify(f.browser, times(2)).extract(anyString(), any());
    }

    @Test void distinctAsinsAndFlipkartVariantIdsNeverSharePrices() throws Exception {
        var f = new Fixture();
        for (String url : List.of(AMAZON, VARIANT, FLIPKART, FLIPKART_VARIANT)) {
            assertThat(f.engine.scrapeRaw(url).reused()).isFalse();
        }
        verify(f.browser, times(4)).extract(anyString(), any());
        assertThat(f.urls.get(AMAZON).hash()).isNotEqualTo(f.urls.get(VARIANT).hash());
        assertThat(f.urls.get(FLIPKART).hash()).isNotEqualTo(f.urls.get(FLIPKART_VARIANT).hash());
    }

    @Test void mismatchedRetailerCannotReadAnotherRetailersCache() throws Exception {
        var f = new Fixture();
        f.engine.scrapeRaw(AMAZON);
        var mismatch = f.engine.scrape(AMAZON, f.rules.forHost("croma.com"));
        assertThat(mismatch.isSuccess()).isFalse();
        assertThat(mismatch.snapshot()).isNull();
        verify(f.browser, times(1)).extract(anyString(), any());
        assertThat(f.metrics.snapshot().cacheHits()).isZero();
    }

    @Test void invalidAndFailedObservationsAreNeverCachedAndExpiredPricesAreNotServedAsFresh() throws Exception {
        var f = new Fixture();
        var invalid = new BrowserActExtraction(new BrowserActResult("Hub", BigDecimal.ZERO,
                null, "INR", true, null, null, "success"), ScrapeOutcome.SUCCESS, null, true);
        var failed = new BrowserActExtraction(null, ScrapeOutcome.TIMEOUT, "Timed out", true);
        when(f.browser.extract(anyString(), any())).thenReturn(invalid, failed, valid(true), failed, valid(false));
        assertThat(f.engine.scrapeRaw(AMAZON).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(f.engine.scrapeRaw(AMAZON).outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(f.engine.scrapeRaw(AMAZON).isSuccess()).isTrue();
        f.clock.advance(Duration.ofSeconds(901));
        var unsuccessful = f.engine.scrapeRaw(AMAZON);
        assertThat(unsuccessful.snapshot()).isNull();
        assertThat(unsuccessful.observedAt()).isNull();
        assertThat(f.engine.scrapeRaw(AMAZON).snapshot().inStock()).isFalse();
        verify(f.browser, times(5)).extract(anyString(), any());
        assertThat(f.metrics.snapshot().cacheHits()).isZero();
    }

    @Test void concurrentFollowersShareOneSubmissionIncludingFreshOnlyCaller() throws Exception {
        var f = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(f.browser.extract(anyString(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Provider not released");
            return valid(true);
        });
        var ownerResult = new CompletableFuture<ScrapeResult>();
        Thread owner = new Thread(() -> ownerResult.complete(f.engine.scrapeRaw(AMAZON)));
        owner.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var followerResults = new ArrayList<CompletableFuture<ScrapeResult>>();
        var followers = new ArrayList<Thread>();
        try {
            for (int i = 0; i < 5; i++) {
                var result = new CompletableFuture<ScrapeResult>();
                var thread = new Thread(() -> result.complete(f.engine.scrapeRaw(AMAZON_ALIAS, Duration.ZERO)));
                followerResults.add(result); followers.add(thread); thread.start();
                awaitTimedWait(thread);
            }
            verify(f.browser, times(1)).extract(anyString(), any());
        } finally {
            release.countDown();
        }
        var original = ownerResult.get(2, TimeUnit.SECONDS);
        for (var pending : followerResults) {
            var shared = pending.get(2, TimeUnit.SECONDS);
            assertThat(shared.shared()).isTrue();
            assertThat(shared.observedAt()).isEqualTo(original.observedAt());
            assertThat(shared.snapshot()).isEqualTo(original.snapshot());
            assertThat(shared.paid()).isFalse();
        }
        owner.join(2000);
        for (var thread : followers) thread.join(2000);
        assertThat(f.metrics.snapshot().duplicateRequestsSkipped()).isEqualTo(5);
        assertThat(f.metrics.snapshot().cacheHits()).isZero();
    }

    @Test void cancellingOneFollowerDoesNotCancelOwnerOrOtherConsumers() throws Exception {
        var f = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(f.browser.extract(anyString(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Provider not released");
            return valid(true);
        });
        var ownerResult = new CompletableFuture<ScrapeResult>();
        var followerResult = new CompletableFuture<ScrapeResult>();
        var interrupted = new CompletableFuture<Boolean>();
        Thread owner = new Thread(() -> ownerResult.complete(f.engine.scrapeRaw(AMAZON)));
        Thread follower = new Thread(() -> {
            followerResult.complete(f.engine.scrapeRaw(AMAZON));
            interrupted.complete(Thread.currentThread().isInterrupted());
        });
        owner.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            follower.start();
            awaitTimedWait(follower);
            follower.interrupt();
            assertThat(followerResult.get(2, TimeUnit.SECONDS).outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
            assertThat(interrupted.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ownerResult.isDone()).isFalse();
        } finally { release.countDown(); }
        assertThat(ownerResult.get(2, TimeUnit.SECONDS).isSuccess()).isTrue();
        assertThat(f.engine.scrapeRaw(AMAZON).cacheHit()).isTrue();
        verify(f.browser, times(1)).extract(anyString(), any());
        owner.join(2000); follower.join(2000);
    }

    /** Same deterministic URL/retailer/time workload, with and without shared reuse state. */
    @Test void controlledThirteenRetailerWorkloadMeasuresCallsCoverageAndFailureWithoutLiveClaims() throws Exception {
        var baseline = new Fixture();
        var optimized = new Fixture();
        for (var f : List.of(baseline, optimized)) {
            doAnswer(call -> {
                f.metrics.retailerRequest();
                f.metrics.browserActApiCall();
                f.metrics.requestSucceeded();
                return valid(true);
            }).when(f.browser).extract(anyString(), any());
        }
        for (int round = 0; round < 10; round++) {
            for (String url : RETAILERS) {
                // A fresh coordinator models the old engine's lack of shared cache/singleflight.
                var noReuse = new ScrapeEngine(baseline.rules, baseline.gate, baseline.canonicalizer,
                        baseline.browser, baseline.props, baseline.metrics, baseline.clock);
                var before = noReuse.scrapeRaw(url);
                var after = optimized.engine.scrapeRaw(url);
                assertThat(after.snapshot()).isEqualTo(before.snapshot());
                assertThat(after.observedAt()).isEqualTo(before.observedAt());
            }
        }
        baseline.clock.advance(Duration.ofSeconds(901));
        optimized.clock.advance(Duration.ofSeconds(901));
        for (String url : RETAILERS) {
            assertThat(baseline.engine.scrapeRaw(url).reused()).isFalse();
            assertThat(optimized.engine.scrapeRaw(url).reused()).isFalse();
        }
        for (var f : List.of(baseline, optimized)) {
            doAnswer(call -> {
                f.metrics.retailerRequest(); f.metrics.browserActApiCall(); f.metrics.requestFailed();
                return new BrowserActExtraction(null, ScrapeOutcome.NO_PRICE, "No verified price", true);
            }).when(f.browser).extract(anyString(), any());
            assertThat(f.engine.scrapeRaw(VARIANT).isSuccess()).isFalse();
        }
        var before = baseline.metrics.snapshot();
        var after = optimized.metrics.snapshot();
        assertThat(before.retailerRequests()).isEqualTo(144);
        assertThat(after.retailerRequests()).isEqualTo(27);
        assertThat(after.cacheHits()).isEqualTo(117);
        assertThat(before.failedRequests()).isEqualTo(1);
        assertThat(after.failedRequests()).isEqualTo(1);
        assertThat(RETAILERS.stream().map(url -> optimized.urls.get(url).rule().host()).distinct()).hasSize(13);
        System.out.println("CONTROLLED MOCK WORKLOAD: 13 retailers; 144 checks each; baseline submissions=144; "
                + "optimized submissions=27; cache hits=117; failures=1 each. No live provider calls.");
    }

    private static void awaitTimedWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }
}
