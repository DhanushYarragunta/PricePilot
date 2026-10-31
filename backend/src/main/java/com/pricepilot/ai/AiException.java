package com.pricepilot.ai;

/**
 * Something went wrong talking to the configured AI provider.
 *
 * <p>Separate from the scraping exceptions because the consequences are different. A scraping
 * failure means a price is missing, which is the core of the product. An AI failure means a verdict
 * is missing, which is an enhancement — the price history is still collected, the charts still
 * draw, the alerts still fire. Nothing in this package is ever allowed to fail a request that could
 * otherwise have succeeded.
 *
 * @param retryable whether trying again later might work, used to decide between "come back to this
 *                  product on the next pass" and "stop asking"
 */
public class AiException extends RuntimeException {

    private final boolean retryable;
    private final Integer httpStatus;
    private final long retryAfterMs;

    public AiException(String message, boolean retryable, Integer httpStatus) {
        this(message, retryable, httpStatus, 0L);
    }

    /**
     * @param retryAfterMs how long the API asked us to wait, from its {@code retry-after} header, or
     *                     zero when it did not say. Obeying it is strictly better than guessing:
     *                     retrying early on a rate limit just earns another 429
     */
    public AiException(String message, boolean retryable, Integer httpStatus, long retryAfterMs) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
    }

    public AiException(String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterMs = 0L;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** Zero when the API gave no {@code retry-after} hint, in which case the caller backs off. */
    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
