package com.pricepilot.schedule;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import com.pricepilot.metrics.ScrapeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The clock. Wakes up on a fixed delay, asks which listings are due, and hands them to
 * {@link ListingRefreshService}.
 *
 * <p>There is deliberately very little logic here. "Which listing should be checked when" is
 * {@link IntervalPolicy}; "what happens when one is checked" is the refresh service. This class only
 * decides how much work to start at once and makes sure the same listing is never started twice.
 *
 * <p>Four things protect a single small EC2 instance from this loop:
 *
 * <ul>
 *   <li><b>A batch cap.</b> Each tick takes at most {@code batch-size} listings, so a backlog drains
 *       steadily instead of arriving all at once. A hundred overdue listings is not an emergency; it
 *       is nine ticks.
 *   <li><b>A small bounded pool.</b> Sized from the scrape concurrency limit, with a bounded queue.
 *       When the queue is full, submissions are dropped rather than buffered — the listings stay due
 *       and get picked up by a later tick, which is backpressure that costs nothing.
 *   <li><b>An in-flight set.</b> A listing's {@code next_check_at} is only rewritten once its fetch
 *       finishes, so until then it still looks due. Without this set, a tick arriving mid-fetch would
 *       queue the same listing again and send two requests to the same retailer for the same page.
 *   <li><b>Graceful shutdown.</b> In-flight fetches get a chance to finish and record their result,
 *       so a deploy does not leave listings looking permanently overdue with no attempt logged.
 * </ul>
 *
 * <p>Switched off entirely by {@code SCHEDULER_ENABLED=false}, which is how the dev profile avoids
 * sending requests to retailers every time the app is started on a laptop.
 */
@Component
public class ScheduleDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    private final long shutdownGraceSeconds;
    private volatile boolean stopping;
    private volatile boolean running = true;
    private boolean stopInitiated;

    private final ListingRepository listings;
    private final ScrapeAttemptRepository attempts;
    private final ListingRefreshService refresher;
    private final AppProperties props;
    private final ScrapeMetrics metrics;
    private final Clock clock;

    private final ExecutorService workers;
    /** Listings currently being fetched. See the class comment — this is what prevents double work. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong ticks = new AtomicLong();
    private volatile Instant lastTickAt;
    /** Retained only while a cycle is due or executing, rather than for the full process history. */
    private final ConcurrentHashMap<CycleKey, DueCycle> dueCycles = new ConcurrentHashMap<>();

    @Autowired
    public ScheduleDispatcher(ListingRepository listings,
                              ScrapeAttemptRepository attempts,
                              ListingRefreshService refresher,
                              AppProperties props, ScrapeMetrics metrics,
                              @Value("${pricepilot.scheduler.shutdown-grace-seconds:400}") long shutdownGraceSeconds) {
        this(listings, attempts, refresher, props, metrics, createWorkers(props), Clock.systemUTC(), shutdownGraceSeconds);
    }

    ScheduleDispatcher(ListingRepository listings, ScrapeAttemptRepository attempts,
                       ListingRefreshService refresher, AppProperties props, ScrapeMetrics metrics,
                       ExecutorService workers, Clock clock) {
        this(listings, attempts, refresher, props, metrics, workers, clock, 400);
    }

    ScheduleDispatcher(ListingRepository listings, ScrapeAttemptRepository attempts,
                       ListingRefreshService refresher, AppProperties props, ScrapeMetrics metrics,
                       ExecutorService workers, Clock clock, long shutdownGraceSeconds) {
        if (shutdownGraceSeconds < 0 || shutdownGraceSeconds > 600) throw new IllegalArgumentException("Invalid scheduler shutdown grace.");
        this.listings = listings;
        this.attempts = attempts;
        this.refresher = refresher;
        this.props = props;
        this.metrics = metrics;
        this.workers = workers;
        this.clock = clock;
        this.shutdownGraceSeconds = shutdownGraceSeconds;

        AppProperties.Scheduler cfg = props.getScheduler();
        if (cfg.isEnabled()) {
            log.info("Adaptive scheduler enabled: tick every {}s, up to {} listings per tick, intervals {}-{} min",
                    cfg.getTickSeconds(), cfg.getBatchSize(), cfg.getMinIntervalMinutes(), cfg.getMaxIntervalMinutes());
        } else {
            log.warn("Scheduler is DISABLED (pricepilot.scheduler.enabled=false). Prices will only update when something asks for a refresh.");
        }
    }

    private static ExecutorService createWorkers(AppProperties props) {
        AppProperties.Scheduler cfg = props.getScheduler();
        // Threads are sized from the scrape concurrency cap plus one. PolitenessGate is the real
        // limiter on simultaneous requests; a couple of extra threads only means a worker is already
        // waiting at the gate when a slot frees, instead of the slot sitting idle until the next tick.
        int threads = Math.max(2, props.getScrape().getMaxConcurrency() + 1);
        // Numbered by the factory rather than by Thread.threadId(), which only exists from Java 19.
        // Stable short names are also far easier to follow in a thread dump than JVM thread ids.
        AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                threads, threads,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(cfg.getBatchSize(), 8)),
                runnable -> {
                    Thread thread = new Thread(runnable, "pricepilot-scrape-" + threadNumber.getAndIncrement());
                    // Daemon threads so a stuck fetch can never prevent the JVM from exiting; the
                    // Lifecycle shutdown below gives already-running checks a chance to finish.
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        return workers;
    }

    // ------------------------------------------------------------------ the tick

    /**
     * One pass of the loop.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: the gap is measured from the end of the
     * previous tick, so a slow tick delays the next one instead of having it queue up behind.
     *
     * <p>The initial delay gives the application time to finish starting — Flyway, the domain rules,
     * the connection pool — because sending a request to a retailer while the context is still coming
     * up turns a slow startup into a failed one.
     */
    @Scheduled(
            initialDelayString = "${pricepilot.scheduler.startup-delay-seconds:45}",
            fixedDelayString = "${pricepilot.scheduler.tick-seconds:120}",
            timeUnit = TimeUnit.SECONDS)
    public void tick() {
        if (stopping || !props.getScheduler().isEnabled()) {
            return;
        }
        ticks.incrementAndGet();
        lastTickAt = clock.instant();

        try {
            dispatchDueListings();
        } catch (RuntimeException e) {
            // A throw here would be swallowed by Spring's scheduler and, worse, would not stop the
            // next tick — leaving a loop that fails silently forever. Logging it loudly is the only
            // way this becomes visible.
            log.error("Scheduler tick failed: {}", e.toString(), e);
        }
    }

    private void dispatchDueListings() {
        int batchSize = Math.max(1, props.getScheduler().getBatchSize());
        Instant cutoff = clock.instant();
        // The lightweight full scan includes the tail beyond the admission cap. Counting only
        // submitted jobs would hide the very backlog and missed freshness windows we must measure.
        List<ListingRepository.DueListing> due = listings.findDueSchedule(cutoff);
        Set<CycleKey> current = new HashSet<>();
        long overdue = 0;
        for (ListingRepository.DueListing listing : due) {
            CycleKey key = new CycleKey(listing.getId(), listing.getDueAt());
            current.add(key);
            DueCycle cycle = dueCycles.computeIfAbsent(key, ignored -> {
                metrics.scheduledDue();
                return new DueCycle(key, key.dueAt().plusSeconds(Math.max(0,
                        props.getScheduler().getFreshnessGraceSeconds())));
            });
            if (cutoff.isAfter(cycle.deadline)) overdue++;
        }
        // A manual refresh can fulfill an observed due cycle before the worker gets to it.
        // Only the original persisted observation time can establish that; moving nextCheckAt
        // after a failure, pausing or deleting a listing never counts as a completed fresh check.
        dueCycles.forEach((key, cycle) -> {
            if (!current.contains(key) && !inFlight.contains(key.listingId())) {
                settle(cycle, clock.instant(), true);
                dueCycles.remove(key, cycle);
            }
        });
        metrics.updateBacklog(current.size(), overdue);

        int submitted = 0;
        int skipped = 0;
        for (ListingRepository.DueListing listing : due) {
            if (stopping || submitted >= batchSize) {
                break;
            }
            Long id = listing.getId();
            if (!inFlight.add(id)) {
                skipped++;
                metrics.duplicateSkipped();
                continue;
            }
            try {
                DueCycle cycle = dueCycles.get(new CycleKey(id, listing.getDueAt()));
                workers.execute(() -> runOne(cycle));
                submitted++;
            } catch (RejectedExecutionException e) {
                // The queue is full: the box is already doing as much as it should. Release the
                // claim and leave the listing due — a later tick will take it.
                inFlight.remove(id);
                metrics.scheduledRejected();
                break;
            }
        }

        if (submitted > 0) {
            log.debug("Tick {}: {} due, {} submitted, {} already in flight",
                    ticks.get(), due.size(), submitted, skipped);
        }
    }

    private void runOne(DueCycle cycle) {
        Long listingId = cycle.key.listingId();
        try {
            // Queued checks must not start spending credits after shutdown closes admission.
            if (stopping) return;
            RefreshResult result = refresher.refresh(listingId, TriggerSource.SCHEDULER);
            settle(cycle, result.completedAt() == null ? clock.instant() : result.completedAt(), false);
            if (result.isSuccess() && result.priceChanged()) {
                log.info("Listing {} ({}): {} -> {} [{}]", listingId, result.retailer(),
                        result.previousPrice(), result.price(), result.strategy());
            }
        } catch (RuntimeException e) {
            // A failed database write may leave this exact cycle due. Keep it pending so a later
            // retry can still fulfill its original deadline instead of permanently marking it done.
            // refresh() is written not to throw. If it somehow does, the listing must still be
            // released from the in-flight set or it would never be checked again for the lifetime of
            // this process.
            log.error("Refresh of listing {} threw: {}", listingId, e.toString(), e);
        } finally {
            inFlight.remove(listingId);
        }
    }

    private void settle(DueCycle cycle, Instant completedAt, boolean noLongerDue) {
        if (cycle.settled.get() != 0) return;
        Instant observedAt = listings.lastSuccessAt(cycle.key.listingId()).orElse(null);
        if (observedAt != null && !observedAt.isBefore(cycle.key.dueAt())
                && !observedAt.isAfter(clock.instant())) {
            if (cycle.settled.compareAndSet(0, 1)) {
                metrics.scheduledCompleted(!observedAt.isAfter(cycle.deadline)
                        && !completedAt.isAfter(cycle.deadline));
            }
        } else if (noLongerDue || !listings.nextCheckAt(cycle.key.listingId())
                .map(cycle.key.dueAt()::equals).orElse(false)) {
            fail(cycle);
        }
    }

    private void fail(DueCycle cycle) {
        if (cycle.settled.compareAndSet(0, 2)) metrics.scheduledFailed();
    }

    private record CycleKey(Long listingId, Instant dueAt) { }

    private static final class DueCycle {
        private final CycleKey key;
        private final Instant deadline;
        private final AtomicInteger settled = new AtomicInteger();
        private DueCycle(CycleKey key, Instant deadline) {
            this.key = key;
            this.deadline = deadline;
        }
    }

    // ------------------------------------------------------------------ housekeeping

    /**
     * Nightly telemetry pruning.
     *
     * <p>{@code scrape_attempt} gains a row for every check of every listing, which is the price of
     * being able to measure reliability rather than assert it. Unbounded, it would eventually fill
     * the disk of a small instance and take Postgres down with it. The retention window is kept
     * comfortably wider than the longest window the metrics page reports over, so pruning can never
     * change a published number.
     *
     * <p>Price history is explicitly <em>not</em> pruned. That is the data the whole product exists
     * to accumulate.
     */
    @Scheduled(cron = "${pricepilot.scheduler.cleanup-cron:0 40 3 * * *}")
    @Transactional
    public void pruneOldAttempts() {
        int days = props.getScheduler().getAttemptRetentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        try {
            int removed = attempts.deleteOlderThan(cutoff);
            if (removed > 0) {
                log.info("Pruned {} scrape attempt row(s) older than {} days", removed, days);
            }
        } catch (RuntimeException e) {
            log.error("Could not prune scrape telemetry: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ lifecycle & introspection

    /** Drains concurrently with manual jobs in one lifecycle phase, before database teardown. */
    @Override public synchronized void stop(Runnable callback) {
        if (stopInitiated) { callback.run(); return; }
        stopInitiated = true;
        closeAdmission();
        log.info("Stopping the scrape dispatcher; {} fetch(es) in flight", inFlight.size());
        workers.shutdown();
        Thread drain = new Thread(() -> {
            try {
                if (!workers.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                    log.warn("Scrape workers did not finish within {}s; interrupting remaining checks", shutdownGraceSeconds);
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                running = false;
                callback.run();
            }
        }, "pricepilot-scheduler-drain");
        drain.setDaemon(true);
        drain.start();
    }

    @Override public void stop() {
        CountDownLatch done = new CountDownLatch(1);
        stop(done::countDown);
        try { done.await(shutdownGraceSeconds + 1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /** Retained for focused callers; Spring uses the asynchronous lifecycle callback. */
    public void shutdown() { stop(); }
    @EventListener(ContextClosedEvent.class)
    public void closeAdmission() { stopping = true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public boolean isRunning() { return running; }
    @Override public void start() {
        if (workers.isShutdown()) throw new IllegalStateException("Stopped scheduler workers cannot restart in this process.");
        running = true;
    }

    /** How many fetches are happening right now — shown on the ops page as a sign of life. */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** Ticks since startup, so a stalled scheduler is obvious rather than merely quiet. */
    public long tickCount() {
        return ticks.get();
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    public boolean isEnabled() {
        return props.getScheduler().isEnabled();
    }
}
