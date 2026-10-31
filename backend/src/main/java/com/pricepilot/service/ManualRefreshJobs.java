package com.pricepilot.service;

import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.schedule.ListingRefreshService;
import com.pricepilot.schedule.RefreshResult;
import com.pricepilot.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded process-local manual refresh jobs. Only ListingRefreshService writes observations,
 * spends provider budget, and invokes the existing alert/analysis paths.
 *
 * Authorization belongs to the controller on every start and status read. Jobs are shared by
 * product, so authorized trackers join the same work without submitting another paid check.
 * A restart loses job status, not persisted observations; unknown IDs never imply success.
 */
@Service
public class ManualRefreshJobs implements SmartLifecycle {
    public enum Status { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }
    public record Snapshot(String jobId, Long productId, Status status, boolean terminal, int total,
                           int checked, int succeeded, int skipped, int failed, int requestsMade,
                           List<RefreshResult> results, Instant createdAt, Instant startedAt,
                           Instant completedAt, Instant expiresAt, String message) { }
    private record Target(Long listingId, String retailer) { }
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Long productId;
        final List<Target> targets;
        final Instant createdAt;
        final List<RefreshResult> results = new ArrayList<>();
        Status status = Status.QUEUED;
        Instant startedAt, completedAt, expiresAt;
        Job(Long productId, List<Target> targets, Instant createdAt) {
            this.productId = productId;
            this.targets = targets;
            this.createdAt = createdAt;
        }
    }

    private final ListingRepository listings;
    private final ListingRefreshService refresher;
    private final ExecutorService workers;
    private final Clock clock;
    private final int maximumJobs;
    private final Duration retention;
    private final long shutdownGraceSeconds;
    private final Object lock = new Object();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<Long, Job> inFlight = new HashMap<>();
    private volatile boolean accepting = true;
    private volatile boolean running = true;
    private boolean stopInitiated;

    @Autowired
    public ManualRefreshJobs(ListingRepository listings, ListingRefreshService refresher,
                             @Value("${pricepilot.manual-refresh.workers:1}") int threads,
                             @Value("${pricepilot.manual-refresh.queue-capacity:8}") int queueCapacity,
                             @Value("${pricepilot.manual-refresh.max-jobs:200}") int maximumJobs,
                             @Value("${pricepilot.manual-refresh.retention-seconds:3600}") long retentionSeconds,
                             @Value("${pricepilot.manual-refresh.shutdown-grace-seconds:400}") long shutdownGraceSeconds) {
        this(listings, refresher, pool(threads, queueCapacity), Clock.systemUTC(), maximumJobs,
                Duration.ofSeconds(retentionSeconds), shutdownGraceSeconds);
    }

    ManualRefreshJobs(ListingRepository listings, ListingRefreshService refresher, ExecutorService workers,
                      Clock clock, int maximumJobs, Duration retention, long shutdownGraceSeconds) {
        if (maximumJobs < 1 || maximumJobs > 10000 || retention == null || retention.isNegative() || retention.isZero()
                || retention.compareTo(Duration.ofDays(1)) > 0 || shutdownGraceSeconds < 0 || shutdownGraceSeconds > 600) {
            workers.shutdownNow();
            throw new IllegalArgumentException("Invalid manual refresh job limits.");
        }
        this.listings = listings;
        this.refresher = refresher;
        this.workers = workers;
        this.clock = clock;
        this.maximumJobs = maximumJobs;
        this.retention = retention;
        this.shutdownGraceSeconds = shutdownGraceSeconds;
    }

    private static ExecutorService pool(int threads, int queueCapacity) {
        if (threads < 1 || threads > 4 || queueCapacity < 1 || queueCapacity > 100) {
            throw new IllegalArgumentException("Invalid manual refresh worker limits.");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "pricepilot-manual-refresh-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Returns immediately after admission; a duplicate start joins the active product job. */
    public Snapshot start(Long productId) {
        synchronized (lock) {
            requireAccepting();
            prune();
            Job current = inFlight.get(productId);
            if (current != null) return snapshot(current);
        }
        // Short repository read only; no transaction or attached entity crosses into a worker.
        List<Target> targets = listings.findByProductIdAndStatus(productId, ListingStatus.ACTIVE).stream()
                .map(listing -> new Target(listing.getId(), listing.getRetailer())).toList();
        if (targets.isEmpty()) throw ApiException.badRequest("There are no active retailer listings to refresh.");
        synchronized (lock) {
            requireAccepting();
            prune();
            Job current = inFlight.get(productId);
            if (current != null) return snapshot(current);
            if (jobs.size() >= maximumJobs) {
                var oldest = jobs.values().stream().filter(job -> terminal(job.status)).findFirst();
                oldest.ifPresent(job -> jobs.remove(job.id));
            }
            if (jobs.size() >= maximumJobs) throw busy();
            Job job = new Job(productId, targets, clock.instant());
            jobs.put(job.id, job);
            inFlight.put(productId, job);
            try {
                workers.execute(() -> run(job));
            } catch (RejectedExecutionException rejected) {
                jobs.remove(job.id);
                inFlight.remove(productId, job);
                throw busy();
            }
            return snapshot(job);
        }
    }

    public Snapshot status(Long productId, String jobId) {
        synchronized (lock) {
            prune();
            Job job = jobs.get(jobId);
            if (job == null || !job.productId.equals(productId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "refresh_job_unknown",
                        "This refresh status is no longer available. It may have expired or the server restarted. "
                                + "Reload the product to see its saved prices; no new refresh was started.");
            }
            return snapshot(job);
        }
    }

    private void requireAccepting() {
        if (!accepting) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "refresh_stopping",
                "The server is restarting and cannot start another refresh yet. Please try again shortly.");
    }

    private static ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "refresh_queue_full",
                "The refresh queue is full. No new check was started; please try again shortly.");
    }

    private void prune() {
        Instant now = clock.instant();
        jobs.values().removeIf(job -> job.expiresAt != null && !job.expiresAt.isAfter(now));
        // Retain active jobs unconditionally. Expiring active work could admit a duplicate paid job.
    }

    private void run(Job job) {
        synchronized (lock) {
            if (!accepting || terminal(job.status)) { finish(job, Status.CANCELLED); return; }
            job.status = Status.RUNNING;
            job.startedAt = clock.instant();
        }
        try {
            for (Target target : job.targets) {
                synchronized (lock) {
                    if (!accepting || terminal(job.status) || Thread.currentThread().isInterrupted()) {
                        finish(job, Status.CANCELLED);
                        return;
                    }
                }
                RefreshResult result;
                try {
                    result = refresher.refresh(target.listingId(), TriggerSource.MANUAL);
                    if (result == null || result.outcome() == null || !target.listingId().equals(result.listingId())
                            || (result.productId() != null && !job.productId.equals(result.productId()))) {
                        result = failed(job, target);
                    }
                } catch (RuntimeException failure) {
                    // Database/provider exception messages can contain sensitive or untrusted data.
                    result = failed(job, target);
                }
                synchronized (lock) {
                    if (terminal(job.status)) return;
                    job.results.add(safeResult(result));
                }
            }
            synchronized (lock) {
                if (!terminal(job.status)) {
                    long failed = job.results.stream().filter(result -> !result.isSuccess() && !result.isSkipped()).count();
                    finish(job, failed == 0 ? Status.COMPLETED : failed == job.targets.size() ? Status.FAILED : Status.PARTIAL);
                }
            }
        } finally {
            synchronized (lock) {
                // A fatal worker failure must not leave a product permanently labelled RUNNING.
                if (!terminal(job.status)) finish(job, Status.FAILED);
            }
        }
    }

    private RefreshResult failed(Job job, Target target) {
        return new RefreshResult(target.listingId(), job.productId, ScrapeOutcome.ERROR, target.retailer(),
                null, null, null, false, false, false, false, null, 0, "none", 0, false,
                "This listing's refresh could not be confirmed. Reload the product to see its saved price.", clock.instant());
    }

    private static RefreshResult safeResult(RefreshResult result) {
        String message = switch (result.outcome()) {
            case SUCCESS -> "A verified price observation was saved or reused.";
            case SKIPPED_FRESH -> "A recent verified price was reused.";
            case TIMEOUT -> "The retailer check timed out. The last saved price is unchanged.";
            case NO_PRICE -> "No valid price for this product was verified. The last saved price is unchanged.";
            case BLOCKED -> "The retailer check was blocked. The last saved price is unchanged.";
            default -> "This listing's refresh could not be confirmed. Reload the product to see its saved price.";
        };
        return new RefreshResult(result.listingId(), result.productId(), result.outcome(), result.retailer(),
                result.price(), result.previousPrice(), result.mrp(), result.inStock(), result.cameBackInStock(),
                result.priceChanged(), result.allTimeLow(), result.nextCheckAt(), result.intervalMinutes(), result.strategy(),
                result.latencyMs(), result.paid(), message, result.completedAt());
    }

    private void finish(Job job, Status status) {
        if (terminal(job.status)) return;
        job.status = status;
        job.completedAt = clock.instant();
        job.expiresAt = job.completedAt.plus(retention);
        inFlight.remove(job.productId, job);
    }

    private static boolean terminal(Status status) { return status != Status.QUEUED && status != Status.RUNNING; }

    private Snapshot snapshot(Job job) {
        int successes = (int) job.results.stream().filter(RefreshResult::isSuccess).count();
        int skipped = (int) job.results.stream().filter(RefreshResult::isSkipped).count();
        int failed = job.results.size() - successes - skipped;
        int requests = (int) job.results.stream().filter(RefreshResult::consumedRequest).count();
        String message = switch (job.status) {
            case QUEUED -> "Your retailer refresh is queued.";
            case RUNNING -> "Checked " + job.results.size() + " of " + job.targets.size() + " retailers.";
            case COMPLETED -> skipped == job.targets.size() ? "Recent verified prices were reused for all retailers."
                    : "Completed checks for " + job.targets.size() + " retailers.";
            case PARTIAL -> (successes + skipped) + " of " + job.targets.size()
                    + " retailer checks succeeded or reused a recent observation. Other saved prices are unchanged.";
            case FAILED -> "The retailer refresh did not complete successfully. Reload the product to see its saved prices.";
            case CANCELLED -> "This refresh stopped during server shutdown. Some checks may be incomplete; reload the product to see its saved prices.";
        };
        return new Snapshot(job.id, job.productId, job.status, terminal(job.status), job.targets.size(), job.results.size(),
                successes, skipped, failed, requests, List.copyOf(job.results), job.createdAt, job.startedAt,
                job.completedAt, job.expiresAt, message);
    }

    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public boolean isRunning() { return running; }
    @Override public void start() {
        if (workers.isShutdown()) throw new IllegalStateException("Stopped refresh workers cannot restart in this process.");
        running = true;
    }

    /** Context close is published before web-server drain; stop new paid work immediately. */
    @EventListener(ContextClosedEvent.class)
    public void closeAdmission() {
        synchronized (lock) {
            accepting = false;
            for (Job job : jobs.values()) if (job.status == Status.QUEUED) finish(job, Status.CANCELLED);
        }
    }

    @Override public void stop(Runnable callback) {
        synchronized (lock) {
            if (stopInitiated) { callback.run(); return; }
            stopInitiated = true;
            closeAdmission();
            workers.shutdown();
        }
        Thread drain = new Thread(() -> {
            try {
                if (!workers.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) workers.shutdownNow();
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                synchronized (lock) {
                    for (Job job : jobs.values()) if (!terminal(job.status)) finish(job, Status.CANCELLED);
                    running = false;
                }
                callback.run();
            }
        }, "pricepilot-manual-refresh-drain");
        drain.setDaemon(true);
        drain.start();
    }

    @Override public void stop() {
        CountDownLatch done = new CountDownLatch(1);
        stop(done::countDown);
        try { done.await(shutdownGraceSeconds + 1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
