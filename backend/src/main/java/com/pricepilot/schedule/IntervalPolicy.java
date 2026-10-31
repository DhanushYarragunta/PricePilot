package com.pricepilot.schedule;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.ScrapeOutcome;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Works out when each listing should next be fetched.
 *
 * <p>This small class is where the project's largest efficiency claim actually lives, so it is worth
 * being precise about what it does. The naive design for a price tracker is to check everything on a
 * fixed clock — hourly, say. That is simple, and it is also wrong in both directions at once: a
 * product whose price has not moved in three weeks gets 504 pointless fetches, while a product in
 * the middle of a flash sale gets checked no more often than one that is not.
 *
 * <p>So the interval is per listing and it moves:
 *
 * <ul>
 *   <li><b>The price changed</b> — divide the interval by {@code speedup-divisor}. Prices move in
 *       bursts; a retailer that just repriced is likely to reprice again.
 *   <li><b>The price did not change</b> — multiply by {@code backoff-multiplier}. Each quiet check
 *       is evidence that this listing is quiet, and the interval drifts towards the daily maximum.
 *   <li><b>Someone is waiting on it</b> — a listing whose product has a target price or deal alert is
 *       pinned to the tight interval regardless of history. The whole point of an alert is to catch
 *       the drop, and backing off would miss it.
 *   <li><b>The fetch failed</b> — back off harder than a quiet success, because a failing listing is
 *       costing requests and returning nothing. Being blocked backs off hardest of all; hammering a
 *       host that just refused us is how an IP gets banned instead of throttled.
 * </ul>
 *
 * <p>Every computed time carries jitter. Without it, listings created in the same minute stay in
 * lockstep forever: they are all due at once, the dispatcher fires them together, and the load
 * arrives in spikes separated by idle gaps. Spreading each due time over a window breaks up that
 * convoy and keeps the request rate roughly flat.
 *
 * <p>Deliberately free of any dependency on the database or the clock beyond {@link Instant#now()},
 * so the arithmetic can be reasoned about — and tested — in isolation.
 */
@Component
public class IntervalPolicy {

    /**
     * Fraction of the interval used as a random spread on the due time. At 15% a six-hour interval
     * lands anywhere in a 54-minute window, which is more than enough to break up a convoy and far
     * too small to matter to a user.
     */
    private static final double JITTER_FRACTION = 0.15;

    /** Extra backoff on top of the normal unchanged-price backoff when a fetch fails. */
    private static final double FAILURE_BACKOFF = 1.5;

    /**
     * Backoff applied when a host actively refused us. Aggressive on purpose: a blocked listing
     * returns nothing, and retrying it soon is what converts a temporary block into a durable one.
     */
    private static final double BLOCKED_BACKOFF = 4.0;

    private final AppProperties props;

    public IntervalPolicy(AppProperties props) {
        this.props = props;
    }

    /**
     * What a uniform hourly schedule would have cost, used as the comparison baseline for the
     * load-reduction figure on the metrics page. Kept here beside the policy it is measured against,
     * so the claim cannot drift out of step with the thing making it.
     */
    public static final int BASELINE_INTERVAL_MINUTES = 60;

    /** Interval for a listing that has never been checked. */
    public int initialIntervalMinutes() {
        return clamp(props.getScheduler().getInitialIntervalMinutes());
    }

    /** The tight interval used when somebody is actively waiting on a price. */
    public int alertedIntervalMinutes() {
        return clamp(props.getScheduler().getAlertedIntervalMinutes());
    }

    /** The longest a listing is ever left unchecked. */
    public int maxIntervalMinutes() {
        return props.getScheduler().getMaxIntervalMinutes();
    }

    /**
     * Interval after a successful check.
     *
     * @param current  the listing's current interval in minutes
     * @param changed  whether this observation differed from the previous one
     * @param urgent   whether an active tracker wants tight polling on this product
     */
    public int afterSuccess(int current, boolean changed, boolean urgent) {
        AppProperties.Scheduler cfg = props.getScheduler();
        if (urgent) {
            // Pinned, not merely lowered. A user waiting for a price drop should get the tight
            // interval on the first check after setting the alert, not after several quiet ones.
            return clamp(cfg.getAlertedIntervalMinutes());
        }
        double next = changed
                ? current / Math.max(1.01, cfg.getSpeedupDivisor())
                : current * Math.max(1.01, cfg.getBackoffMultiplier());
        return clamp((int) Math.round(next));
    }

    /**
     * Interval after a failed check. Takes the outcome rather than a boolean because being blocked
     * and timing out call for different patience.
     */
    public int afterFailure(int current, ScrapeOutcome outcome) {
        double multiplier = outcome == ScrapeOutcome.BLOCKED
                ? BLOCKED_BACKOFF
                : Math.max(1.01, props.getScheduler().getBackoffMultiplier()) * FAILURE_BACKOFF;
        return clamp((int) Math.round(current * multiplier));
    }

    /**
     * The due time for an interval, jittered.
     *
     * <p>The jitter is one-sided — always later, never earlier — so that it can never pull a listing
     * inside the minimum interval and breach the politeness guarantee the minimum exists to provide.
     */
    public Instant dueAfter(int intervalMinutes) {
        return dueAfter(intervalMinutes, Instant.now());
    }

    /** Schedule from the actual observation time, including a reused observation's original age. */
    public Instant dueAfter(int intervalMinutes, Instant observedAt) {
        long base = Math.max(1, intervalMinutes) * 60L;
        long spread = (long) (base * JITTER_FRACTION);
        long seconds = spread <= 0 ? base : base + ThreadLocalRandom.current().nextLong(spread + 1);
        return observedAt.plusSeconds(seconds);
    }

    /**
     * A short, jittered delay for a listing that was skipped because its data was still fresh.
     *
     * <p>Not the full interval: nothing was learned, so the listing should come back round soon. But
     * not immediately either, or a freshly-skipped listing would spin through the dispatcher on
     * every tick and crowd out listings that actually need fetching.
     */
    public Instant dueAfterSkip() {
        int minutes = Math.max(5, props.getScheduler().getMinIntervalMinutes() / 4);
        return dueAfter(minutes);
    }

    /**
     * The status a listing should hold after a failure.
     *
     * <p>Three outcomes, by how final the failure is. A product page that has gone (404 or 410) is
     * {@code DEAD} immediately — the retailer has said the page does not exist, and no amount of
     * retrying changes that. Repeated failures of any other kind eventually reach
     * {@code max-consecutive-failures} and become {@code BLOCKED}, which keeps the listing visible
     * and explicable on the ops page instead of silently rotting. Everything else stays
     * {@code ACTIVE} with a longer interval.
     *
     * <p>{@code BLOCKED} is not the end: the status is cleared on the next success, and the
     * dispatcher still retries blocked listings occasionally, because retailers change their minds.
     */
    public ListingStatus statusAfterFailure(ScrapeOutcome outcome,
                                            int consecutiveFailures,
                                            boolean pageGone) {
        if (pageGone) {
            return ListingStatus.DEAD;
        }
        if (consecutiveFailures >= props.getScheduler().getMaxConsecutiveFailures()) {
            return outcome == ScrapeOutcome.HTTP_ERROR ? ListingStatus.DEAD : ListingStatus.BLOCKED;
        }
        return ListingStatus.ACTIVE;
    }

    /** Freshness window: a success newer than this makes a re-fetch pointless. */
    public Duration freshnessWindow() {
        return Duration.ofSeconds(props.getScrape().getFreshnessWindowSeconds());
    }

    private int clamp(int minutes) {
        AppProperties.Scheduler cfg = props.getScheduler();
        return Math.max(cfg.getMinIntervalMinutes(), Math.min(cfg.getMaxIntervalMinutes(), minutes));
    }
}
