package com.pricepilot.scrape;

import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.rules.HostRule;
import org.jsoup.nodes.Document;

import java.util.Locale;

/**
 * Decides whether what came back is a product page or an anti-bot page.
 *
 * <p>This matters more than it sounds. A CAPTCHA page is served with HTTP 200 and parses as
 * perfectly valid HTML — it simply has no price in it. Without this check, every block would be
 * recorded as {@code NO_PRICE}, the metrics would blame the extraction rules, and the obvious fix
 * (a rule change) would be the wrong one. Distinguishing "they refused us" from "our selectors are
 * stale" is the difference between a dashboard that tells you what to fix and one that misleads
 * you.
 *
 * <p>Detection is deliberately conservative. A false positive sends a healthy host into cool-off
 * and stops tracking working products, so a page is only called blocked on strong evidence:
 * an explicit marker string, or a suspiciously small body that contains none of the structure a
 * real product page has.
 */
public final class BlockDetector {

    private BlockDetector() {
    }

    /** Below this, a 200 response is not a real product page. Amazon's CAPTCHA page is ~2KB. */
    private static final int SUSPICIOUSLY_SMALL_BYTES = 6_000;

    /** How much of the body to scan for marker strings. Markers always appear early. */
    private static final int SCAN_LIMIT = 200_000;

    /**
     * Maps an HTTP status onto an outcome.
     *
     * <p>403, 429 and 503 are treated as blocks rather than server errors. That is not a guess:
     * 503 is specifically what Amazon returns when it wants a human, and 429 is a rate limit, which
     * is a block by another name. Calling these {@code HTTP_ERROR} would hide the single most
     * important signal the system has.
     */
    public static ScrapeOutcome classifyStatus(int status) {
        if (status >= 200 && status < 300) {
            return ScrapeOutcome.SUCCESS;
        }
        if (status == 403 || status == 429 || status == 503 || status == 451) {
            return ScrapeOutcome.BLOCKED;
        }
        return ScrapeOutcome.HTTP_ERROR;
    }

    /** 404/410 mean the product page itself is gone, so the listing should be retired. */
    public static boolean isGone(int status) {
        return status == 404 || status == 410;
    }

    /**
     * @param page the fetched response
     * @param doc  the parsed document, or {@code null} if parsing has not happened yet
     * @param rule the host's rule, which may add host-specific marker strings
     * @return a human-readable reason when the page looks like a block, otherwise {@code null}
     */
    public static String blockReason(FetchedPage page, Document doc, HostRule rule) {
        if (page == null || page.html() == null) {
            return null;
        }

        String haystack = page.html().length() > SCAN_LIMIT
                ? page.html().substring(0, SCAN_LIMIT)
                : page.html();
        String lower = haystack.toLowerCase(Locale.ROOT);

        for (String marker : rule.blockMarkers()) {
            if (lower.contains(marker)) {
                return "anti-bot marker in page: \"" + marker + "\"";
            }
        }

        if (doc != null) {
            String title = doc.title();
            if (title != null && !title.isBlank()) {
                String t = title.toLowerCase(Locale.ROOT);
                if (t.contains("captcha")
                        || t.contains("robot")
                        || t.contains("access denied")
                        || t.contains("attention required")
                        || t.contains("are you a human")
                        || t.equals("just a moment...")
                        || t.contains("security check")
                        || t.contains("403 forbidden")) {
                    return "anti-bot page title: \"" + title.trim() + "\"";
                }
            }
        }

        // A tiny 200 response with no product structure at all. Real product pages are large and
        // carry at least one of these; interstitials carry none.
        if (page.bytes() > 0 && page.bytes() < SUSPICIOUSLY_SMALL_BYTES) {
            boolean hasProductStructure = lower.contains("application/ld+json")
                    || lower.contains("og:title")
                    || lower.contains("itemprop")
                    || lower.contains("add to cart")
                    || lower.contains("add to bag")
                    || lower.contains("buy now");
            if (!hasProductStructure) {
                return "response was only " + page.bytes() + " bytes with no product markup";
            }
        }

        return null;
    }
}
