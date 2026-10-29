package com.pricepilot.scrape;

import com.pricepilot.domain.ScrapeOutcome;

/**
 * Everything one scrape attempt produced: the data if it worked, the diagnosis if it did not, and
 * the telemetry either way.
 *
 * <p>Deliberately not an exception-or-value pair. A failed scrape is an ordinary, expected event in
 * this system — retailers go down, selectors rot, anti-bot pages appear — and it still has to be
 * recorded with as much detail as a success, because the failure pattern is what tells us which
 * rule to fix. Returning a result for both cases means the calling code has exactly one path and
 * cannot forget to log the bad one.
 *
 * @param outcome    what happened, and the field the ops metrics group by
 * @param snapshot   the extracted data; {@code null} for anything other than {@link ScrapeOutcome#SUCCESS}
 * @param host       the retailer host, so attempts can be grouped without re-parsing the URL
 * @param finalUrl   where the fetch actually ended up, which may differ from where it started
 * @param httpStatus the retailer's status code, or {@code null} if we never got one
 * @param latencyMs  wall-clock time for the fetch
 * @param bytes      size of the decoded body
 * @param source     direct or via the paid unblocker; {@code null} when no request was made
 * @param strategy   which extraction strategy supplied the price, suffixed {@code +unblocker} when
 *                   the bytes were paid for. Stored on every attempt row, which is what makes the
 *                   per-strategy success table a measurement
 * @param message    human-readable detail, kept short enough for the 500-character column
 */
public record ScrapeResult(
        ScrapeOutcome outcome,
        ProductSnapshot snapshot,
        String host,
        String finalUrl,
        Integer httpStatus,
        int latencyMs,
        int bytes,
        FetchedPage.FetchSource source,
        String strategy,
        String message) {

    /** Column width in {@code scrape_attempt.error_message}. */
    private static final int MESSAGE_LIMIT = 480;

    public boolean isSuccess() {
        return outcome.isSuccess();
    }

    /**
     * True when the retailer says this product no longer exists. The caller retires the listing
     * instead of retrying it forever, which is the difference between a dashboard that stays tidy
     * and one that slowly fills with dead rows.
     */
    public boolean isGone() {
        return httpStatus != null && BlockDetector.isGone(httpStatus);
    }

    public boolean paid() {
        return source == FetchedPage.FetchSource.UNBLOCKER || source == FetchedPage.FetchSource.BROWSERACT;
    }

    public static ScrapeResult success(ProductSnapshot snapshot, String host, FetchedPage page) {
        return new ScrapeResult(
                ScrapeOutcome.SUCCESS,
                snapshot,
                host,
                page.finalUrl(),
                page.status(),
                (int) page.latencyMs(),
                page.bytes(),
                page.source(),
                snapshot.strategy() + page.source().suffix(),
                null);
    }

    /** A page we fetched and parsed successfully, which simply had no price in it. */
    public static ScrapeResult noPrice(String host, FetchedPage page, String message) {
        return new ScrapeResult(
                ScrapeOutcome.NO_PRICE,
                null,
                host,
                page.finalUrl(),
                page.status(),
                (int) page.latencyMs(),
                page.bytes(),
                page.source(),
                "none" + page.source().suffix(),
                trim(message));
    }

    /** A fetch that happened but went wrong: block, HTTP error, unparseable response. */
    public static ScrapeResult failure(ScrapeOutcome outcome, String host, FetchedPage page, String message) {
        return new ScrapeResult(
                outcome,
                null,
                host,
                page.finalUrl(),
                page.status(),
                (int) page.latencyMs(),
                page.bytes(),
                page.source(),
                "none" + page.source().suffix(),
                trim(message));
    }

    /** A fetch that never produced a response at all: timeout, DNS failure, cool-off refusal. */
    public static ScrapeResult aborted(ScrapeOutcome outcome, String host, String url,
                                       Integer httpStatus, int latencyMs, String message) {
        return new ScrapeResult(outcome, null, host, url, httpStatus, latencyMs, 0,
                null, "none", trim(message));
    }

    /**
     * Recorded when a check was due but the stored price is still inside the freshness window, so
     * no request was made. Counted separately from everything else because including it in a
     * success rate would flatter the number with requests that never happened — and that
     * distinction is the whole reason the scrape-load reduction figure is defensible.
     */
    public static ScrapeResult skippedFresh(String host, String url) {
        return new ScrapeResult(ScrapeOutcome.SKIPPED_FRESH, null, host, url, null, 0, 0,
                null, "none", "Price is still within the freshness window");
    }

    private static String trim(String message) {
        if (message == null) {
            return null;
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() <= MESSAGE_LIMIT ? single : single.substring(0, MESSAGE_LIMIT - 3) + "...";
    }
}
