package com.pricepilot.web;

import com.pricepilot.service.DashboardService;
import com.pricepilot.web.dto.ApiViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment can do, readable before anyone signs in.
 *
 * <p>Every integration in this project is optional: no Claude key means a working price tracker with
 * no verdict cards, no Tavily key means no "find other retailers" button, no SMTP host means alerts
 * are recorded but not emailed. That design is only worth having if the frontend can tell which
 * features exist — otherwise graceful degradation shows up as buttons that return errors, which is
 * worse than a missing feature because the user cannot tell it apart from a bug.
 *
 * <p>Unauthenticated because two of its consumers run before a token exists: the sign-up form needs
 * to know whether an invite code is required, and the landing page lists the supported retailers to
 * explain what can be pasted. Everything returned is configuration — flags, caps and display names.
 * No price, no product, no user, and never the invite code itself, only whether one is needed.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final DashboardService dashboard;

    public MetaController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * Feature flags, limits and the retailer list.
     *
     * <p>The limits are published rather than left to be discovered by hitting them. A user who can
     * see "40 products, 6 retailers each, 30 manual refreshes an hour" understands the shape of the
     * app; one who finds out by being refused mid-task reads the same rule as a malfunction.
     *
     * <p>The retailer list comes from the loaded extraction rules, not from a hardcoded array, so it
     * cannot claim support for a site whose rules are missing — and adding a site to
     * {@code domain-rules.yml} makes it appear here without a code change.
     */
    @GetMapping("/capabilities")
    public ApiViews.Capabilities capabilities() {
        return dashboard.capabilities();
    }
}
