package com.pricepilot.scrape;

import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Reduces the many URLs that point at one product to a single canonical form, and hashes it.
 *
 * <p>This is the component that makes the system cheap to run. These are all the same iPhone:
 *
 * <pre>
 *   https://www.amazon.in/Apple-iPhone-15-128-GB/dp/B0CHX2F5QT?ref_=ast_sto_dp&amp;th=1
 *   https://amazon.in/dp/B0CHX2F5QT/
 *   https://www.amazon.in/gp/product/B0CHX2F5QT?tag=someaffiliate-21
 * </pre>
 *
 * <p>All three canonicalise to {@code https://www.amazon.in/dp/B0CHX2F5QT} and therefore to one
 * {@code url_hash}, one {@code listing} row, and one fetch — no matter how many users are watching
 * it. Without this, every user who added the same trending phone would cost another request
 * against the same retailer, which is both wasteful and the fastest way to get an IP banned.
 *
 * <p>The hash ignores a leading {@code www.} so that hosts which answer on both forms still
 * deduplicate.
 */
@Component
public class UrlCanonicalizer {

    private static final Logger log = LoggerFactory.getLogger(UrlCanonicalizer.class);

    private final DomainRules domainRules;

    public UrlCanonicalizer(DomainRules domainRules) {
        this.domainRules = domainRules;
    }

    /**
     * @param url      the canonical URL to fetch and store
     * @param host     lowercase hostname, {@code www.} preserved
     * @param hash     SHA-256 hex of the dedup key
     * @param rule     the extraction rule that applies to this host
     */
    public record Canonical(String url, String host, String hash, HostRule rule) {

        public String retailer() {
            return rule.retailer();
        }
    }

    /**
     * @throws UnsupportedUrlException when the URL is malformed, not a web link, or points
     *                                 somewhere the server must not fetch
     */
    public Canonical canonicalize(String rawUrl) {
        URI uri = parse(rawUrl);
        UrlGuard.assertFetchable(uri);

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        HostRule rule = domainRules.forHost(host);

        String scheme = uri.getScheme().equalsIgnoreCase("http") ? "http" : "https";
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery();

        // A host-specific canonical form, when one is configured, wins outright. This is what
        // collapses Amazon's slug-and-ref URLs down to /dp/<ASIN>.
        if (rule.canonicalRegex() != null) {
            Matcher m = rule.canonicalRegex().matcher(path);
            if (m.find()) {
                String captured = m.groupCount() >= 1 ? m.group(1) : m.group();
                if (rule.canonicalFormat() != null && !rule.canonicalFormat().isBlank()) {
                    String rebuilt = String.format(rule.canonicalFormat(), captured);
                    URI rebuiltUri = parse(rebuilt);
                    String rebuiltHost = rebuiltUri.getHost().toLowerCase(Locale.ROOT);
                    String rebuiltPath = rebuiltUri.getRawPath();
                    return new Canonical(
                            rebuilt,
                            rebuiltHost,
                            sha256Hex(dedupKey(rebuiltHost, rebuiltPath, null)),
                            rule);
                }
                path = captured;
                query = null;
            } else {
                if ("amazon.in".equals(rule.host())) {
                    throw new UnsupportedUrlException("Use an Amazon.in product URL containing /dp/ASIN or /gp/product/ASIN.");
                }
                log.debug("canonical-regex for {} did not match path {} — falling back to "
                        + "generic normalisation", rule.host(), path);
            }
        }

        path = normalisePath(path);
        String cleanQuery = cleanQuery(query, rule);

        if ("/".equals(path) && cleanQuery.isEmpty()) {
            throw new UnsupportedUrlException(
                    "That looks like a homepage, not a product page. Open the product and copy "
                    + "the URL from the address bar.");
        }

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host).append(path);
        if (!cleanQuery.isEmpty()) {
            sb.append('?').append(cleanQuery);
        }
        String canonicalUrl = sb.toString();

        return new Canonical(
                canonicalUrl,
                host,
                sha256Hex(dedupKey(host, path, cleanQuery)),
                rule);
    }

    // ------------------------------------------------------------------ internals

    private static URI parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new UnsupportedUrlException("Paste a product page URL to start tracking it.");
        }
        String s = rawUrl.trim();

        // Users paste from chat apps, which sometimes wrap the link.
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            s = "https://" + s;
        }
        // Spaces are never legal in a URL but are common in copied text.
        s = s.replace(" ", "%20");

        try {
            URI uri = new URI(s);
            if (uri.getHost() == null) {
                // Handles the "https://Amazon.in/dp/X" style input where the authority parsed but
                // the host did not, e.g. because of an underscore in the hostname.
                throw new UnsupportedUrlException(
                        "That URL could not be read. Paste the full link from your browser's "
                        + "address bar.");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new UnsupportedUrlException(
                    "That URL could not be read: " + e.getReason()
                    + ". Paste the full link from your browser's address bar.");
        }
    }

    /** Drops a trailing slash and collapses duplicate slashes, but never empties the path. */
    private static String normalisePath(String path) {
        String p = path.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
    }

    /**
     * Strips tracking parameters, or keeps only the identifying ones when the host declares them,
     * then sorts what is left so that parameter order stops mattering.
     */
    private static String cleanQuery(String rawQuery, HostRule rule) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        Set<String> keep = new HashSet<>(rule.keepQueryParams());
        Set<String> strip = new HashSet<>(rule.stripQueryParams());

        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);

            if (!keep.isEmpty()) {
                if (keep.contains(name)) {
                    kept.add(pair);
                }
                continue;
            }
            if (strip.contains(name) || name.startsWith("utm_")) {
                continue;
            }
            kept.add(pair);
        }
        Collections.sort(kept);
        return String.join("&", kept);
    }

    /**
     * The dedup key deliberately ignores the scheme and a leading {@code www.}, because
     * {@code http://amazon.in/dp/X} and {@code https://www.amazon.in/dp/X} are the same product.
     */
    private static String dedupKey(String host, String path, String query) {
        String h = host.startsWith("www.") ? host.substring(4) : host;
        String p = path == null || path.isEmpty() ? "/" : path;
        String q = query == null ? "" : query;
        return h + "|" + p + "|" + q;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
