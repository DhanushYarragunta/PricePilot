package com.pricepilot.web;

import com.pricepilot.metrics.OpsMetrics;
import com.pricepilot.metrics.OpsMetricsService;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.AdminOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints: the things needed to keep this alive without SSH and a redeploy.
 *
 * <p>Scoped to exactly one problem — a scraper stops working because a retailer changed its markup.
 * Diagnosing and fixing that needs four things, and this controller is those four: see which hosts are
 * failing and with which strategy ({@code /diagnostics}), confirm the background loop is still running
 * ({@code /scheduler}), load an edited rules file ({@code /rules/reload}), and bring back the listings
 * that retired themselves while the rule was broken ({@code /listings/{id}/revive}).
 *
 * <p>Everything under {@code /api/admin/**} requires {@code ROLE_ADMIN}, enforced in
 * {@code SecurityConfig} by path rather than by an annotation on each method. A path rule cannot be
 * forgotten on a method added later, which is the failure mode worth designing against here: these are
 * the endpoints that change how the server behaves.
 *
 * <p>Nothing here can create or alter a price. Reloading rules changes how future pages are read;
 * reviving a listing changes when it is next read. Both only ever affect what happens at the next real
 * fetch, so no amount of admin access can put a number into the price history that a retailer's page
 * did not produce. Each call is logged with the admin's id, because operator actions that leave no
 * trace are indistinguishable from the bug they were meant to fix.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminOpsService ops;
    private final OpsMetricsService metrics;

    public AdminController(AdminOpsService ops, OpsMetricsService metrics) {
        this.ops = ops;
        this.metrics = metrics;
    }

    /**
     * Is the scheduler alive, and what state are the listings in?
     *
     * <p>The first thing to look at when prices stop moving, because it separates the two very
     * different causes: the loop has stopped (nothing is being checked at all) or the loop is fine and
     * the extraction is failing (plenty of checks, no prices). The public metrics page shows the
     * second; only this shows the first.
     */
    @GetMapping("/scheduler")
    public AdminOpsService.SchedulerState scheduler() {
        return ops.schedulerState();
    }

    /**
     * The same figures as the public metrics endpoint, over a chosen window.
     *
     * <p>Duplicated here on purpose. The per-host and per-strategy breakdowns are what identify a
     * broken rule — "amazon.in: 4% success, strategy css" is the diagnosis — and an operator should
     * not have to leave the admin surface to see them. The window defaults wider than the public one
     * because the question being asked is "when did this start", which needs history either side of
     * the break.
     */
    @GetMapping("/diagnostics")
    public OpsMetrics diagnostics(@RequestParam(defaultValue = "30") int windowDays) {
        return metrics.snapshot(windowDays);
    }

    /**
     * Re-reads {@code domain-rules.yml} from disk.
     *
     * <p>The fast path for a broken selector: edit the mounted YAML, call this, revive the affected
     * listings, watch them succeed. No rebuild, no restart, and the scheduler never stops.
     */
    @PostMapping("/rules/reload")
    public AdminOpsService.RulesReloaded reloadRules(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        log.info("Admin {} requested a rules reload", caller.id());
        return ops.reloadRules();
    }

    /**
     * Returns a retired listing to active and makes it due immediately.
     *
     * <p>Necessary after a rule fix because the automatic retirement is permanent by design: a listing
     * that failed its way to {@code DEAD} will never be checked again on its own, no matter how
     * correct the new rule is. This is the only way back.
     */
    @PostMapping("/listings/{listingId}/revive")
    public AdminOpsService.ListingState revive(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable Long listingId) {
        log.info("Admin {} is reviving listing {}", caller.id(), listingId);
        return ops.reviveListing(listingId);
    }

    /**
     * Stops checking a listing until it is revived.
     *
     * <p>For a retailer that is up but wrong — serving a placeholder price, or a page that parses to a
     * number that is not the price. Recording those is worse than recording nothing, because they end
     * up in the average and in the all-time low, and a bad all-time low poisons every later verdict.
     */
    @PostMapping("/listings/{listingId}/pause")
    public AdminOpsService.ListingState pause(@AuthenticationPrincipal AuthenticatedUser caller,
                                              @PathVariable Long listingId) {
        log.info("Admin {} is pausing listing {}", caller.id(), listingId);
        return ops.pauseListing(listingId);
    }
}
