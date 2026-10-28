package com.pricepilot.scrape;

import com.pricepilot.domain.ScrapeOutcome;

/**
 * A fetch that failed in a way we can classify.
 *
 * <p>Carrying the {@link ScrapeOutcome} on the exception is what makes the reliability metrics
 * meaningful: "blocked by anti-bot" and "the retailer's server was down" and "we parsed the page
 * fine but found no price" are three completely different problems, and lumping them together as
 * "failed" would hide which one is actually happening.
 */
public class ScrapeException extends RuntimeException {

    private final ScrapeOutcome outcome;
    private final Integer httpStatus;

    public ScrapeException(ScrapeOutcome outcome, String message) {
        this(outcome, message, null, null);
    }

    public ScrapeException(ScrapeOutcome outcome, String message, Integer httpStatus) {
        this(outcome, message, httpStatus, null);
    }

    public ScrapeException(ScrapeOutcome outcome, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
        this.httpStatus = httpStatus;
    }

    public ScrapeOutcome outcome() {
        return outcome;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
