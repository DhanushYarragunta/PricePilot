package com.pricepilot.web;

import com.pricepilot.metrics.OpsMetrics;
import com.pricepilot.metrics.OpsMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The numbers this project claims about itself, computed from its own records.
 *
 * <p>Public — no token required — and that is the whole point of the endpoint. A resume says "78%
 * extraction accuracy across 10+ retailers"; this URL says what the figure is right now, over which
 * window, out of how many attempts, broken down by host and by extraction strategy. Anyone reading the
 * claim can check it in a browser, and if the number has moved it moves here too.
 *
 * <p>Making it readable without authentication is a deliberate trade. What it exposes is aggregate
 * operational data — success rates, counts, API spend — and nothing user-identifying: no emails, no
 * titles, no URLs, no indication of who tracks what. {@link OpsMetrics} is a purpose-built record
 * rather than an entity, so there is no association anyone could serialise their way into.
 *
 * <h2>Why these figures are trustworthy</h2>
 * Every rate is a ratio of rows the system wrote while doing real work, with denominators chosen
 * before the numbers were known. {@code SKIPPED_FRESH} attempts are excluded from success rates
 * because a check that did not happen is neither a success nor a failure. The scrape-load reduction is
 * measured against a fixed, documented hourly baseline and is allowed to come out negative. None of
 * these is a constant in the source, and that is the only reason quoting them is honest.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final OpsMetricsService metrics;

    public MetricsController(OpsMetricsService metrics) {
        this.metrics = metrics;
    }

    /**
     * The current snapshot.
     *
     * <p>The window is adjustable because a rate over seven days and a rate over ninety answer
     * different questions: the first is "is scraping working today", the second is "has it held up".
     * Zero means "use the configured default", so the common call needs no parameter;
     * {@link OpsMetricsService} clamps the value, and the window it actually used comes back inside
     * the response so a chart can never label itself with a window it was not given.
     */
    @GetMapping
    public OpsMetrics snapshot(@RequestParam(defaultValue = "0") int windowDays) {
        return windowDays <= 0 ? metrics.snapshot() : metrics.snapshot(windowDays);
    }
}
