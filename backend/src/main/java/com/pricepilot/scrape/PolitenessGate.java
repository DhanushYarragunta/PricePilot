package com.pricepilot.scrape;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The thing that keeps PricePilot's IP address welcome at the retailers it depends on.
 *
 * <p>Two independent limits are enforced:
 *
 * <ul>
 *   <li><b>Per host, one request at a time, spaced by a minimum delay.</b> A per-host lock is held
 *       across the whole request, so two scheduler threads can never hit Flipkart simultaneously
 *       no matter how the batch was ordered.
 *   <li><b>Globally, a small number of requests in flight.</b> A semaphore caps total concurrency,
 *       because the box this runs on is a t3.micro and because a burst of parallel requests from
 *       one IP is itself a bot signal.
 * </ul>
 *
 * <p>The delay is jittered. A request exactly every 4000ms is a machine; a request every
 * 4000–5600ms is less obviously one. This costs nothing and removes the most trivial detection
 * heuristic there is.
 *
 * <p>There is also a per-host cool-off: after a host blocks us, further requests to that host are
 * refused outright for a while rather than hammering it and deepening the block. That refusal is
 * recorded, so the /ops page shows honestly that the host was skipped rather than pretending the
 * check succeeded.
 */
@Component
public class PolitenessGate {

    private static final Logger log = LoggerFactory.getLogger(PolitenessGate.class);

    /** Upper bound on jitter, as a fraction of the configured delay. */
    private static final double JITTER_FRACTION = 0.4;

    /** How long a host stays in cool-off after blocking us. */
    private static final long COOLOFF_BASE_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long COOLOFF_MAX_MS = TimeUnit.HOURS.toMillis(6);

    /** Give up rather than queue forever when every slot is busy. */
    private static final long SLOT_WAIT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2);

    private final Semaphore globalSlots;
    private final AppProperties props;

    private final Map<String, ReentrantLock> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestAt = new ConcurrentHashMap<>();
    private final Map<String, Long> coolOffUntil = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveBlocks = new ConcurrentHashMap<>();

    public PolitenessGate(AppProperties props) {
        this.props = props;
        int permits = Math.max(1, props.getScrape().getMaxConcurrency());
        this.globalSlots = new Semaphore(permits, true);
        log.info("Politeness gate: {} concurrent fetches, {}ms minimum per-host delay",
                permits, props.getScrape().getMinDelayMs());
    }

    /**
     * Runs {@code body} under both limits.
     *
     * @throws ScrapeException with {@link ScrapeOutcome#BLOCKED} if the host is cooling off, or
     *                        {@link ScrapeOutcome#ERROR} if no slot became free in time
     */
    public <T> T execute(String host, long hostMinDelayMs, Callable<T> body) {
        long now = System.currentTimeMillis();
        Long until = coolOffUntil.get(host);
        if (until != null && until > now) {
            long secondsLeft = (until - now) / 1000;
            throw new ScrapeException(ScrapeOutcome.BLOCKED,
                    host + " is in cool-off for another " + secondsLeft + "s after blocking us");
        }

        ReentrantLock lock = hostLocks.computeIfAbsent(host, h -> new ReentrantLock(true));
        boolean locked = false;
        boolean acquired = false;
        try {
            locked = lock.tryLock(SLOT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new ScrapeException(ScrapeOutcome.ERROR,
                        "Timed out waiting for the " + host + " queue");
            }
            acquired = globalSlots.tryAcquire(SLOT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ScrapeException(ScrapeOutcome.ERROR,
                        "Timed out waiting for a free fetch slot");
            }

            waitForDelay(host, hostMinDelayMs);

            try {
                return body.call();
            } catch (ScrapeException | UnsupportedUrlException e) {
                throw e;
            } catch (Exception e) {
                throw new ScrapeException(ScrapeOutcome.ERROR,
                        "Fetch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        null, e);
            } finally {
                lastRequestAt.put(host, System.currentTimeMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException(ScrapeOutcome.ERROR, "Interrupted while waiting to fetch", null, e);
        } finally {
            if (acquired) {
                globalSlots.release();
            }
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void waitForDelay(String host, long hostMinDelayMs) throws InterruptedException {
        long configured = Math.max(hostMinDelayMs, props.getScrape().getMinDelayMs());
        Long last = lastRequestAt.get(host);
        long jitter = (long) (configured * JITTER_FRACTION * ThreadLocalRandom.current().nextDouble());
        long target = (last == null ? 0 : last) + configured + jitter;
        long waitMs = target - System.currentTimeMillis();
        if (last == null) {
            // First request to this host in this process: apply jitter only, not the full delay.
            waitMs = jitter;
        }
        if (waitMs > 0) {
            log.debug("Waiting {}ms before next request to {}", waitMs, host);
            Thread.sleep(waitMs);
        }
    }

    /**
     * Records that a host served us an anti-bot page. Cool-off doubles with each consecutive
     * block, so a host that is briefly annoyed recovers quickly and a host that has genuinely
     * blacklisted us is left alone.
     */
    public void recordBlocked(String host) {
        int blocks = consecutiveBlocks.merge(host, 1, Integer::sum);
        long coolOff = Math.min(COOLOFF_MAX_MS, COOLOFF_BASE_MS * (1L << Math.min(blocks - 1, 5)));
        coolOffUntil.put(host, System.currentTimeMillis() + coolOff);
        log.warn("{} blocked us ({} consecutive). Backing off for {} minutes.",
                host, blocks, coolOff / 60000);
    }

    /** Clears the cool-off ladder after a clean fetch. */
    public void recordSuccess(String host) {
        if (consecutiveBlocks.remove(host) != null) {
            log.info("{} is responding normally again.", host);
        }
        coolOffUntil.remove(host);
    }

    /** True when this host is currently being left alone. Used by the ops page. */
    public boolean isCoolingOff(String host) {
        Long until = coolOffUntil.get(host);
        return until != null && until > System.currentTimeMillis();
    }

    public Map<String, Long> coolOffs() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new ConcurrentHashMap<>();
        coolOffUntil.forEach((host, until) -> {
            if (until > now) {
                out.put(host, (until - now) / 1000);
            }
        });
        return out;
    }

    /** Manual override for the ops page, for when you have changed IP or fixed a rule. */
    public void clearCoolOff(String host) {
        coolOffUntil.remove(host);
        consecutiveBlocks.remove(host);
    }
}
