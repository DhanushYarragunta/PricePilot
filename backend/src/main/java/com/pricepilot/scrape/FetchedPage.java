package com.pricepilot.scrape;

/**
 * One successfully downloaded page.
 *
 * @param requestedUrl the canonical URL we asked for
 * @param finalUrl     where we ended up after redirects; differs from {@code requestedUrl} when a
 *                     retailer bounced us to a login wall or a regional storefront, which is
 *                     useful to see in the logs
 * @param status       HTTP status of the final response
 * @param html         decoded response body
 * @param bytes        size of the body in bytes, recorded so bandwidth can be reasoned about
 * @param latencyMs    wall-clock time for the whole fetch including redirects
 * @param source       whether this came straight from the retailer or via an unblocker provider
 */
public record FetchedPage(
        String requestedUrl,
        String finalUrl,
        int status,
        String html,
        int bytes,
        long latencyMs,
        FetchSource source) {

    public enum FetchSource {
        /** Fetched directly by this server. Free. */
        DIRECT,
        /** Fetched through a paid unblocker API. Costs a credit. */
        UNBLOCKER;

        public String suffix() {
            return this == UNBLOCKER ? "+unblocker" : "";
        }
    }

    public boolean redirected() {
        return finalUrl != null && !finalUrl.equals(requestedUrl);
    }
}
