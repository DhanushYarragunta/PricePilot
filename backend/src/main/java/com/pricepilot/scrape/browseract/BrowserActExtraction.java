package com.pricepilot.scrape.browseract;

import com.pricepilot.domain.ScrapeOutcome;

/**
 * One workflow attempt, including failures that must not trigger another submission.
 * The result is present only on success. Messages contain no provider response content.
 * Submitted means a metered submission was attempted, not that a task or price was returned.
 */
public record BrowserActExtraction(
        BrowserActResult result,
        ScrapeOutcome outcome,
        String message,
        boolean submitted
) { }
