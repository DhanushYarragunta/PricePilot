package com.pricepilot.scrape;

/**
 * Thrown when a URL must not be fetched.
 *
 * <p>Separate from a fetch failure: this is a refusal, not an error. The message is written to be
 * shown directly to the user, because the usual cause is a pasted search-results link rather than
 * a product link.
 */
public class UnsupportedUrlException extends RuntimeException {

    public UnsupportedUrlException(String message) {
        super(message);
    }
}
