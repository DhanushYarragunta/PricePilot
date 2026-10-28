package com.pricepilot.scrape;

import java.util.List;
import java.util.Map;

/**
 * A coherent set of browser headers.
 *
 * <p>The point of grouping them is consistency. Sending a Chrome 126 user-agent alongside
 * {@code sec-ch-ua: "Chromium";v="120"} is a louder bot signal than sending no client hints at all,
 * because no real browser is ever internally inconsistent. Each profile below is a matched set
 * taken from a real browser.
 *
 * <p>A host always gets the same profile, chosen from its name. Rotating the user-agent between
 * requests to one retailer looks like several different browsers sharing one IP and one cookie jar,
 * which is itself suspicious. Consistency per host beats variety.
 */
public record BrowserProfile(
        String userAgent,
        String secChUa,
        String secChUaPlatform,
        String acceptLanguage) {

    private static final List<BrowserProfile> PROFILES = List.of(
            new BrowserProfile(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/126.0.0.0 Safari/537.36",
                    "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
                    "\"Windows\"",
                    "en-IN,en-GB;q=0.9,en;q=0.8"),
            new BrowserProfile(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0",
                    "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
                    "\"Windows\"",
                    "en-IN,en;q=0.9"),
            new BrowserProfile(
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/126.0.0.0 Safari/537.36",
                    "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"",
                    "\"macOS\"",
                    "en-IN,en-US;q=0.9,en;q=0.8"));

    /** Stable per host: the same retailer always sees the same browser. */
    public static BrowserProfile forHost(String host) {
        int index = Math.abs((host == null ? "" : host).hashCode() % PROFILES.size());
        return PROFILES.get(index);
    }

    /**
     * Headers for a top-level navigation to a page — which is what a price check is pretending to
     * be. {@code sec-fetch-*} values matter: a real address-bar navigation sends
     * {@code dest=document, mode=navigate, site=none, user=?1}, and sending the values an
     * XHR would send is a giveaway.
     *
     * <p>{@code Accept-Encoding} omits brotli and zstd on purpose. The JDK cannot decompress them,
     * and advertising support for an encoding you cannot read gets you an unreadable response body.
     */
    public Map<String, String> navigationHeaders(String referer) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("User-Agent", userAgent);
        h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                + "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        h.put("Accept-Language", acceptLanguage);
        h.put("Accept-Encoding", "gzip, deflate");
        h.put("sec-ch-ua", secChUa);
        h.put("sec-ch-ua-mobile", "?0");
        h.put("sec-ch-ua-platform", secChUaPlatform);
        h.put("Sec-Fetch-Dest", "document");
        h.put("Sec-Fetch-Mode", "navigate");
        h.put("Sec-Fetch-Site", referer == null ? "none" : "cross-site");
        h.put("Sec-Fetch-User", "?1");
        h.put("Upgrade-Insecure-Requests", "1");
        h.put("Cache-Control", "max-age=0");
        h.put("DNT", "1");
        if (referer != null) {
            h.put("Referer", referer);
        }
        return h;
    }
}
