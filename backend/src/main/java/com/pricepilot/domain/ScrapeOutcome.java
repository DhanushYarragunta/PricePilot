package com.pricepilot.domain;

/**
 * Result of one fetch attempt. Recorded for every attempt so reliability can be
 * measured per host and per strategy rather than guessed at.
 */
public enum ScrapeOutcome {
    /** A usable price was extracted. */
    SUCCESS,
    /** The page was fetched fine, but no price could be found in it. Usually a
     *  parser problem (site redesign) rather than a blocking problem. */
    NO_PRICE,
    /** Anti-bot defences engaged: CAPTCHA page, 403, 429, or a known block marker. */
    BLOCKED,
    /** Any other non-2xx response, e.g. 404 for a delisted product. */
    HTTP_ERROR,
    /** Connection or read timeout. */
    TIMEOUT,
    /** Unexpected exception while fetching or parsing. */
    ERROR,
    /** No request was made because a recent-enough price was already on hand.
     *  Counted, because avoided requests are the point of the whole design. */
    SKIPPED_FRESH;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** Did we actually spend a network request on this attempt? */
    public boolean consumedRequest() {
        return this != SKIPPED_FRESH;
    }
}
