package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.schedule.ScheduleDispatcher;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The operations needed to keep a scraping deployment alive without a redeploy.
 *
 * <p>Scrapers rot. A retailer reorganises its markup, the price selector stops matching, every check
 * for that host records {@code NO_PRICE}, and after enough consecutive failures those listings retire
 * themselves to {@code BLOCKED} or {@code DEAD}. That self-protection is correct — it stops the app
 * hammering a site it cannot read — but it leaves two jobs to be done once the rule is fixed: load the
 * new rule, and bring back the listings that gave up while the rule was wrong. Without both, fixing
 * {@code domain-rules.yml} requires a rebuild and the dead listings stay dead regardless.
 *
 * <p>So the rules file is read from disk at runtime and the listing state is editable. Both are
 * admin-only, both are logged with the caller, and neither can invent data: reviving a listing only
 * makes it eligible to be checked again, and whether a price appears still depends entirely on what
 * the retailer's page says when it is next fetched.
 */
@Service
public class AdminOpsService {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsService.class);

    private final DomainRules rules;
    private final ListingRepository listings;
    private final ScheduleDispatcher dispatcher;
    private final AppProperties props;

    public AdminOpsService(DomainRules rules,
                           ListingRepository listings,
                           ScheduleDispatcher dispatcher,
                           AppProperties props) {
        this.rules = rules;
        this.listings = listings;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    // ------------------------------------------------------------------ rules

    /**
     * Re-reads the extraction rules from disk.
     *
     * <p>The reason this exists is the shortest path from "Flipkart changed its markup" to "prices are
     * being read again": edit the YAML on the server, call this, watch the next check succeed. The
     * alternative is a rebuild and a container restart, during which the scheduler is down and every
     * in-flight check is lost.
     *
     * <p>{@link DomainRules#reload()} swaps the rule map atomically and keeps the old rules if the new
     * file fails to parse, so a typo degrades to "nothing changed" rather than to a running instance
     * with no rules at all — the {@code IllegalStateException} it throws in that case reaches the
     * caller as a 500 with a reference id, and the log line carries the parse error.
     *
     * <p>The response repeats the configured rules path alongside the summary, because the most common
     * mistake here is editing a file the container cannot see: the reload then succeeds, reports the
     * rules it already had, and looks like a fix that did not take.
     */
    public RulesReloaded reloadRules() {
        String summary = rules.reload();
        List<HostRule> loaded = rules.all();
        List<String> retailers = loaded.stream()
                .map(HostRule::retailer)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        log.info("Extraction rules reloaded by an admin: {}", summary);
        return new RulesReloaded(props.getScrape().getRulesPath(), summary, loaded.size(), retailers);
    }

    /**
     * What a reload loaded.
     *
     * @param rulesPath where the rules were read from, so a reload that read a file other than the
     *                  one being edited is visible in the response rather than mysterious
     * @param summary   {@code DomainRules}' own description of what it parsed
     * @param hostRules how many host rules are now in effect
     * @param retailers their display names
     */
    public record RulesReloaded(String rulesPath, String summary, int hostRules,
                                List<String> retailers) {
    }

    // ------------------------------------------------------------------ listings

    /**
     * Brings a retired listing back into the rotation.
     *
     * <p>Clears the failure count, returns the status to {@code ACTIVE}, resets the interval to the
     * initial value and makes the listing due immediately. The failure count has to be cleared along
     * with the status, or the very next failure lands on top of the old tally and retires the listing
     * again on its first stumble — the fix would appear not to have worked.
     *
     * <p>Being due immediately is the point: an admin reviving a listing is testing a rule change and
     * needs the answer now, not at the next interval. One listing checked out of turn is a single
     * extra request, which the politeness gate serialises like any other.
     */
    @Transactional
    public ListingState reviveListing(Long listingId) {
        Listing listing = require(listingId);
        ListingStatus previous = listing.getStatus();
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setConsecutiveFailures(0);
        listing.setCheckIntervalMinutes(props.getScheduler().getInitialIntervalMinutes());
        listing.setNextCheckAt(Instant.now());
        log.info("Listing {} ({}) revived from {} by an admin",
                listingId, listing.getRetailer(), previous);
        return stateOf(listing, previous);
    }

    /**
     * Stops checking a listing, without retiring it permanently.
     *
     * <p>The manual counterpart to the automatic backoff, for when a retailer is visibly struggling or
     * is sending obviously wrong prices and the right move is to stop asking rather than to keep
     * recording nonsense. {@code PAUSED} is deliberately distinct from {@code DEAD}: the page still
     * exists and the listing's history stays on the chart with an explanatory badge, so this is
     * reversible with {@link #reviveListing} and reads as a decision rather than a failure.
     */
    @Transactional
    public ListingState pauseListing(Long listingId) {
        Listing listing = require(listingId);
        ListingStatus previous = listing.getStatus();
        listing.setStatus(ListingStatus.PAUSED);
        // Left where it is rather than cleared to null: the column is NOT NULL, and a paused listing
        // is excluded by the due query on status regardless of what its next check time says.
        log.info("Listing {} ({}) paused from {} by an admin",
                listingId, listing.getRetailer(), previous);
        return stateOf(listing, previous);
    }

    /** A listing's scheduling state after an admin change. */
    public record ListingState(Long listingId, String retailer, String url, String previousStatus,
                               String status, int consecutiveFailures, int checkIntervalMinutes,
                               Instant nextCheckAt) {
    }

    // ------------------------------------------------------------------ scheduler

    /**
     * Whether the background scheduler is actually running, and when it last ran.
     *
     * <p>The one question that matters most on a live deployment and that no other endpoint answers.
     * Every price on screen comes from a check, and every automatic check comes from this loop; if it
     * has stopped, the app keeps serving prices that look current and quietly stops being a price
     * tracker. {@code lastTickAt} going stale is the signal, which is why it is exposed as a timestamp
     * rather than as a boolean somebody has to interpret.
     */
    public SchedulerState schedulerState() {
        return new SchedulerState(
                dispatcher.isEnabled(),
                dispatcher.tickCount(),
                dispatcher.lastTickAt(),
                dispatcher.inFlightCount(),
                listings.countByStatus(ListingStatus.ACTIVE),
                listings.countByStatus(ListingStatus.PAUSED),
                listings.countByStatus(ListingStatus.BLOCKED),
                listings.countByStatus(ListingStatus.DEAD));
    }

    /**
     * Live scheduler state.
     *
     * @param enabled   whether the loop is switched on at all in configuration
     * @param ticks     how many sweeps have run since this process started
     * @param lastTickAt when the most recent sweep ran, or {@code null} if none has yet
     * @param inFlight  checks currently running
     */
    public record SchedulerState(boolean enabled, long ticks, Instant lastTickAt, int inFlight,
                                 long activeListings, long pausedListings, long blockedListings,
                                 long deadListings) {
    }

    // ------------------------------------------------------------------ internals

    private Listing require(Long listingId) {
        return listings.findById(listingId)
                .orElseThrow(() -> ApiException.notFound("That listing"));
    }

    private static ListingState stateOf(Listing listing, ListingStatus previous) {
        return new ListingState(
                listing.getId(),
                listing.getRetailer(),
                listing.getUrl(),
                previous == null ? null : previous.name(),
                listing.getStatus().name(),
                listing.getConsecutiveFailures(),
                listing.getCheckIntervalMinutes(),
                listing.getNextCheckAt());
    }
}
