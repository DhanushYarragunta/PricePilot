package com.pricepilot.web;

import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.DashboardService;
import com.pricepilot.web.dto.ApiViews;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The alert feed: what this app has told the user, and whether the telling worked.
 *
 * <p>Every alert ever sent is listed, including the ones that failed to send, with the delivery error
 * attached. That is an unusual thing to show a user and it is the right thing here: an alert system
 * whose failures are invisible is worse than none, because it replaces "I should check the price" with
 * a false belief that something is watching. If SMTP is misconfigured, the feed says so on the screen
 * where the user is looking for the email that never arrived.
 *
 * <p>Read-only by design. There is no "mark as read" and no delete, because the feed is a log of
 * things that happened rather than an inbox — and a log the user can edit is not evidence of anything.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private static final int DEFAULT_LIMIT = 50;

    private final DashboardService dashboard;

    public AlertController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * The user's alerts, newest first.
     *
     * @param limit how many to return. Clamped to 1–100 inside {@link DashboardService}, rather than
     *              validated here, so the bound lives with the query that has to pay for it
     */
    @GetMapping
    public List<ApiViews.Alert> feed(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return dashboard.alertFeed(caller.id(), limit);
    }
}
