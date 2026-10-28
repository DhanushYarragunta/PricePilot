package com.pricepilot.schedule;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.TriggerSource;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.repo.ScrapeAttemptRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
public class ScheduleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduleDispatcher.class);

    /** How long a shutdown waits for in-flight fetches before abandoning them. */
    private static final long SHUTDOWN_GRACE_SECONDS = 20;

    private final ListingRepository listings;
    private final ScrapeAttemptRepository attempts;
    private final ListingRefreshService refresher;
    private final AppProperties props;

    private final ThreadPoolExecutor workers;
    /** Listings currently being fetched. See the class comment — this is what prevents double work. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong ticks = new AtomicLong();
    private volatile Instant lastTickAt;

    public ScheduleDispatcher(ListingRepository listings,
                              ScrapeAttemptRepository attempts,
                              ListingRefreshService refresher,
                              AppProperties props) {
        this.listings = listings;
        this.attempts = attempts;
        this.refresher = refresher;
        this.props = props;

        AppProperties.Scheduler cfg = props.getScheduler();
        // Threads are sized from the scrape concurrency cap plus one. PolitenessGate is the real
        // limiter on simultaneous requests; a couple of extra threads only means a worker is already
        // waiting at the gate when a slot frees, instead of the slot sitting idle until the next tick.
        int threads = Math.max(2, props.getScrape().getMaxConcurrency() + 1);
        // Numbered by the factory rather than by Thread.threadId(), which only exists from Java 19.
        // Stable short names are also far easier to follow in a thread dump than JVM thread ids.
        AtomicInteger threadNumber = new AtomicInteger(1);
        this.workers = new ThreadPoolExecutor(
                threads, threads,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(cfg.getBatchSize(), 8)),
                runnable -> {
                    Thread thread = new Thread(runnable, "pricepilot-scrape-" + threadNumber.getAndIncrement());
                    // Daemon threads so a stuck fetch can never prevent the JVM from exiting; the
                    // @PreDestroy hook below is what gives them a fair chance to finish first.
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);

        if (cfg.isEnabled()) {
            log.info("Adaptive scheduler enabled: tick every {}s, up to {} listings per tick, "
                     + "intervals {}-{} min, {} worker threads",
                    cfg.getTickSeconds(), cfg.getBatchSize(), cfg.getMinIntervalMinutes(),
                    cfg.getMaxIntervalMinutes(), threads);
        } else {
            log.warn("Scheduler is DISABLED (pricepilot.scheduler.enabled=false). Prices will only "
                     + "update when something asks for a refresh.");
        }
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
        if (!props.getScheduler().isEnabled()) {
            return;
        }
        ticks.incrementAndGet();
        lastTickAt = Instant.now();

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
        int batchSize = props.getScheduler().getBatchSize();
        // Over-fetch slightly, because some of what comes back may already be in flight. Without the
        // headroom, a single slow listing at the front of the queue could starve the rest.
        List<Listing> due = listings.findDue(Instant.now(), PageRequest.of(0, batchSize * 2));
        if (due.isEmpty()) {
            return;
        }

        int submitted = 0;
        int skipped = 0;
        for (Listing listing : due) {
            if (submitted >= batchSize) {
                break;
            }
            Long id = listing.getId();
            if (!inFlight.add(id)) {
                skipped++;
                continue;
            }
            try {
                workers.execute(() -> runOne(id));
                submitted++;
            } catch (RejectedExecutionException e) {
                // The queue is full: the box is already doing as much as it should. Release the
                // claim and leave the listing due — a later tick will take it.
                inFlight.remove(id);
                break;
            }
        }

        if (submitted > 0) {
            log.debug("Tick {}: {} due, {} submitted, {} already in flight",
                    ticks.get(), due.size(), submitted, skipped);
        }
    }

    private void runOne(Long listingId) {
        try {
            RefreshResult result = refresher.refresh(listingId, TriggerSource.SCHEDULER);
            if (result.isSuccess() && result.priceChanged()) {
                log.info("Listing {} ({}): {} -> {} [{}]", listingId, result.retailer(),
                        result.previousPrice(), result.price(), result.strategy());
            }
        } catch (RuntimeException e) {
            // refresh() is written not to throw. If it somehow does, the listing must still be
            // released from the in-flight set or it would never be checked again for the lifetime of
            // this process.
            log.error("Refresh of listing {} threw: {}", listingId, e.toString(), e);
        } finally {
            inFlight.remove(listingId);
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

    /**
     * Lets in-flight fetches finish before the JVM goes away.
     *
     * <p>Worth the twenty seconds. A fetch killed mid-flight leaves a listing claimed in a set that
     * is about to be discarded and, more importantly, leaves no attempt row — so a redeploy during a
     * busy minute would show up on the ops page as a handful of checks that simply never happened.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Stopping the scrape dispatcher; {} fetch(es) in flight", inFlight.size());
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Scrape workers did not finish within {}s; abandoning them",
                        SHUTDOWN_GRACE_SECONDS);
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
