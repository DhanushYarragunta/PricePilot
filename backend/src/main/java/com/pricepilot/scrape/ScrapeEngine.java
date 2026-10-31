package com.pricepilot.scrape;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.scrape.browseract.BrowserActClient;
import com.pricepilot.scrape.browseract.BrowserActExtraction;
import com.pricepilot.scrape.browseract.BrowserActPriceExtractor;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * The only entry point for live price extraction. Every retailer uses BrowserAct;
 * no direct HTTP, HTML extractor or alternate provider is wired into this service.
 *
 * <p>URL guards, per-host throttling and shared snapshot validation still apply.
 * Failures return telemetry without a snapshot so the existing persistence path
 * records the attempt and keeps the last valid price and its original timestamp.
 */
@Service
public class ScrapeEngine {
    private static final Logger log = LoggerFactory.getLogger(ScrapeEngine.class);

    private final DomainRules rules;
    private final PolitenessGate gate;
    private final UrlCanonicalizer canonicalizer;
    private final BrowserActClient browserActClient;
    private final AppProperties props;
    private final ScrapeMetrics metrics;
    private final Clock clock;
    private final Cache<String, ScrapeResult> observations;
    private final ConcurrentHashMap<String, CompletableFuture<ScrapeResult>> inFlight = new ConcurrentHashMap<>();

    /** Retained for focused callers/tests that do not need application telemetry. */
    public ScrapeEngine(DomainRules rules, PolitenessGate gate,
                        UrlCanonicalizer canonicalizer, BrowserActClient browserActClient) {
        this(rules, gate, canonicalizer, browserActClient, new AppProperties(), null, Clock.systemUTC());
    }

    @Autowired
    public ScrapeEngine(DomainRules rules, PolitenessGate gate, UrlCanonicalizer canonicalizer,
                        BrowserActClient browserActClient, AppProperties props, ScrapeMetrics metrics) {
        this(rules, gate, canonicalizer, browserActClient, props, metrics, Clock.systemUTC());
        log.info("Scrape engine uses BrowserAct only for all {} configured retailers", rules.all().size());
    }

    ScrapeEngine(DomainRules rules, PolitenessGate gate, UrlCanonicalizer canonicalizer,
                 BrowserActClient browserActClient, AppProperties props, ScrapeMetrics metrics, Clock clock) {
        this.rules = rules;
        this.gate = gate;
        this.canonicalizer = canonicalizer;
        this.browserActClient = browserActClient;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.observations = Caffeine.newBuilder().maximumSize(props.getScrape().getCacheMaxEntries())
                .expireAfterWrite(configuredMaxAge()).build();
    }

    /** Scrapes an existing canonical product URL using its retailer rule. */
    public ScrapeResult scrape(String url, String host) {
        return scrape(url, rules.forHost(host));
    }

    /** Rejects unsafe input before it reaches a provider or consumes a submission. */
    public ScrapeResult scrapeRaw(String rawUrl) {
        return scrapeRaw(rawUrl, configuredMaxAge());
    }

    /** Callers with tighter freshness requirements can shorten, but never extend, the cache TTL. */
    public ScrapeResult scrapeRaw(String rawUrl, Duration maxAge) {
        UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(rawUrl);
        return coordinated(canonical, canonical.rule(), maxAge);
    }

    public ScrapeResult scrape(String url, HostRule rule) {
        return scrape(url, rule, configuredMaxAge());
    }

    public ScrapeResult scrape(String url, HostRule rule, Duration maxAge) {
        UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(url);
        if (rule == null || canonical.rule() == null || !rule.host().equals(canonical.rule().host())) {
            return result(ScrapeOutcome.ERROR, null, rule == null ? "unknown" : rule.host(), url,
                    System.nanoTime(), false, "The product URL does not match this retailer; no check was made.");
        }
        return coordinated(canonical, rule, maxAge);
    }

    /** Process-wide coalescing runs before throttling, so waiting duplicates consume no slots. */
    private ScrapeResult coordinated(UrlCanonicalizer.Canonical canonical, HostRule rule, Duration requestedMaxAge) {
        String key = canonical.hash();
        Duration maximum = configuredMaxAge();
        Duration allowed = requestedMaxAge == null ? maximum : requestedMaxAge;
        if (allowed.isNegative()) allowed = Duration.ZERO;
        if (allowed.compareTo(maximum) > 0) allowed = maximum;
        ScrapeResult cached = fresh(key, allowed);
        if (cached != null) return cached;

        CompletableFuture<ScrapeResult> pending = new CompletableFuture<>();
        CompletableFuture<ScrapeResult> existing = inFlight.putIfAbsent(key, pending);
        if (existing != null) {
            try {
                // Cancelling a waiter must not cancel a provider task another caller still needs.
                ScrapeResult shared = existing.get(Math.max(1L, props.getBrowserAct().getTimeoutMs())
                        + TimeUnit.MINUTES.toMillis(4), TimeUnit.MILLISECONDS);
                // A concurrent caller can have won only a cache re-check, rather than an actual
                // scrape. Do not let that race bypass this caller's stricter freshness requirement.
                if (shared.cacheHit() && !freshEnough(shared, allowed)) {
                    inFlight.remove(key, existing);
                    return coordinated(canonical, rule, allowed);
                }
                if (metrics != null) metrics.duplicateSkipped();
                return shared.reusedAs(ScrapeResult.Reuse.SHARED);
            } catch (InterruptedException e) {
                if (metrics != null) metrics.duplicateSkipped();
                Thread.currentThread().interrupt();
                return sharedFailure(canonical, ScrapeOutcome.TIMEOUT, "Interrupted while waiting for a shared check.");
            } catch (TimeoutException e) {
                if (metrics != null) metrics.duplicateSkipped();
                return sharedFailure(canonical, ScrapeOutcome.TIMEOUT, "The shared check did not finish before the time limit.");
            } catch (ExecutionException e) {
                if (metrics != null) metrics.duplicateSkipped();
                return sharedFailure(canonical, ScrapeOutcome.ERROR, "The shared check failed; previous price preserved.");
            }
        }
        try {
            // A leader may have completed between our initial cache read and registration.
            cached = fresh(key, allowed);
            if (cached != null) {
                pending.complete(cached);
                return cached;
            }
            ScrapeResult result = extract(canonical.url(), rule);
            if (result.isSuccess() && result.snapshot() != null && result.snapshot().isVerified()
                    && result.observedAt() != null) observations.put(key, result);
            pending.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            pending.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, pending);
        }
    }

    private Duration configuredMaxAge() {
        return Duration.ofSeconds(Math.max(0, props.getScrape().getFreshnessWindowSeconds()));
    }

    private ScrapeResult fresh(String key, Duration maxAge) {
        if (maxAge.isZero()) return null;
        ScrapeResult saved = observations.getIfPresent(key);
        if (!freshEnough(saved, maxAge)) return null;
        if (metrics != null) metrics.cacheHit();
        return saved.reusedAs(ScrapeResult.Reuse.CACHE);
    }

    private boolean freshEnough(ScrapeResult saved, Duration maxAge) {
        if (maxAge.isZero() || saved == null || saved.observedAt() == null || !saved.isSuccess()
                || saved.snapshot() == null || !saved.snapshot().isVerified()) return false;
        Duration age = Duration.between(saved.observedAt(), clock.instant());
        return !age.isNegative() && age.compareTo(maxAge) < 0;
    }

    private ScrapeResult sharedFailure(UrlCanonicalizer.Canonical canonical, ScrapeOutcome outcome, String message) {
        return result(outcome, null, canonical.rule().host(), canonical.url(), System.nanoTime(), false, message)
                .reusedAs(ScrapeResult.Reuse.SHARED);
    }

    private ScrapeResult extract(String url, HostRule rule) {
        String host = rule.host();
        long started = System.nanoTime();
        BrowserActExtraction extraction;
        try {
            // Old rules may still name HTML strategies. They cannot enable a fallback.
            extraction = gate.execute("browseract:" + host, rule.minDelayMs(),
                    () -> browserActClient.extract(url, rule));
        } catch (ScrapeException e) {
            log.warn("BrowserAct check could not run for {} ({})", host, e.outcome());
            return result(e.outcome(), null, host, url, started, false,
                    "BrowserAct check could not run; previous price preserved.");
        } catch (RuntimeException e) {
            // Exception text may include credentials or untrusted provider content.
            log.warn("BrowserAct check failed for {} ({})", host, e.getClass().getSimpleName());
            return result(ScrapeOutcome.ERROR, null, host, url, started, false,
                    "BrowserAct check failed; previous price preserved.");
        }

        if (extraction == null) {
            return result(ScrapeOutcome.ERROR, null, host, url, started, false,
                    "BrowserAct returned no result; previous price preserved.");
        }
        if (extraction.outcome() != ScrapeOutcome.SUCCESS) {
            return result(extraction.outcome(), null, host, url, started, extraction.submitted(),
                    extraction.message());
        }
        ProductSnapshot snapshot = extraction.result() == null ? null
                : BrowserActPriceExtractor.snapshot(extraction.result());
        if (snapshot == null || !snapshot.isVerified()) {
            return result(ScrapeOutcome.NO_PRICE, null, host, url, started, extraction.submitted(),
                    "BrowserAct price, MRP, currency or title failed validation; previous price preserved.");
        }
        return result(ScrapeOutcome.SUCCESS, snapshot, host, url, started, extraction.submitted(), null);
    }

    private ScrapeResult result(ScrapeOutcome outcome, ProductSnapshot snapshot,
                                       String host, String url, long started, boolean submitted,
                                       String message) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        // A provider HTTP status is not a retailer status (404 would retire the listing).
        return new ScrapeResult(outcome, snapshot, host, url, null, elapsed, 0,
                submitted ? FetchedPage.FetchSource.BROWSERACT : null, "browseract", message,
                outcome == ScrapeOutcome.SUCCESS ? clock.instant() : null, ScrapeResult.Reuse.NONE);
    }
}
