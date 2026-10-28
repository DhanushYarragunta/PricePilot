package com.pricepilot.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-caller, per-action request cap held in memory.
 *
 * <p>Guards the two endpoints where one impatient user costs real money or real goodwill: manual
 * price refreshes, which send live requests to retailers, and AI analysis, which spends Claude
 * credits. Both are deliberately expensive operations exposed behind a button, which is exactly the
 * combination that needs a ceiling.
 *
 * <h2>Why a fixed window, in memory</h2>
 * It is the honest fit for the deployment. One EC2 instance means in-memory state is complete state;
 * Redis would add a service to run, monitor and pay for in order to coordinate between one process
 * and itself. Caffeine's expiry does the window accounting, so there is no sweeper thread and no
 * unbounded map.
 *
 * <p>Two consequences, stated rather than hidden. A restart clears every counter, so a determined
 * user could reset their limit by waiting for a deploy — harmless here, where the limit protects a
 * budget rather than a secret. And a fixed window permits a burst across a boundary: thirty requests
 * at 10:59 and thirty more at 11:00. A sliding window would prevent that at the cost of storing a
 * timestamp per request. For a limit whose job is "stop someone holding down F5", the simpler
 * structure is the right call.
 *
 * <p>If this ever runs on more than one instance, this class is wrong and the limit becomes
 * per-instance. That is a deliberate trade recorded here so it is noticed at the point it starts to
 * matter.
 */
@Component
public class RateLimiter {

    /**
     * Keys expire a window after they are created, which is what makes the window fixed rather than
     * sliding: the count resets when the key is evicted, not gradually.
     */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(10_000)
            .build();

    /**
     * Counts one use of {@code action} by {@code userId} and throws if they are over the limit.
     *
     * @throws ApiException with status 429 when the limit has been reached
     */
    public void check(Long userId, String action, int limitPerHour) {
        count(String.valueOf(userId), action, limitPerHour,
                "You have used all " + limitPerHour + " " + action
                + " requests for this hour. Scheduled checks are still running in the "
                + "background, so prices will keep updating on their own.");
    }

    /**
     * The same counter keyed by something other than a user id, with its own message.
     *
     * <p>Exists for the endpoints that have to be limited <em>before</em> anyone is authenticated —
     * login and registration — where the only identifier available is the client address. Those need
     * different wording too: telling a failed login that "scheduled checks are still running" would be
     * nonsense.
     *
     * @param caller an opaque key for whoever is calling; keys from different callers must not collide
     * @throws ApiException with status 429 when the limit has been reached
     */
    public void check(String caller, String action, int limitPerHour, String message) {
        count(caller, action, limitPerHour, message);
    }

    private void count(String caller, String action, int limitPerHour, String message) {
        if (limitPerHour <= 0) {
            return;
        }
        String key = action + ":" + caller;
        AtomicInteger used = counters.get(key, k -> new AtomicInteger());
        if (used.incrementAndGet() > limitPerHour) {
            throw ApiException.rateLimited(message);
        }
    }

    /** How many of this hour's allowance is left, so the UI can disable a button before it fails. */
    public int remaining(Long userId, String action, int limitPerHour) {
        AtomicInteger count = counters.getIfPresent(action + ":" + userId);
        int used = count == null ? 0 : count.get();
        return Math.max(0, limitPerHour - used);
    }
}
