package com.pricepilot.scrape;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.rules.HostRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Fetches a page directly from the retailer. This is the free path, and the one that does the work
 * for every host except Amazon.
 *
 * <p>Things here that exist specifically because the naive version does not survive real retailers:
 *
 * <ul>
 *   <li><b>A shared cookie jar.</b> Retailers set a session cookie on first contact and expect it
 *       back. A client that discards cookies looks like a thousand brand-new visitors from one IP.
 *   <li><b>Manual redirect following.</b> Two reasons. The server-side request forgery guard has to
 *       run on every hop, not just the first — otherwise a URL that redirects to the EC2 metadata
 *       endpoint walks straight through it. And knowing the final URL is what reveals a retailer
 *       quietly bouncing us to a login wall.
 *   <li><b>Manual decompression.</b> The JDK HTTP client does not decompress responses, so a client
 *       that advertises gzip and then parses the raw bytes finds no price and blames the selectors.
 *   <li><b>Charset sniffing.</b> Indian storefronts are inconsistent about declaring encodings, and
 *       decoding Latin-1 bytes as UTF-8 mangles the rupee sign the price parser is looking for.
 *   <li><b>Retry with Retry-After.</b> When a host says "come back in 30 seconds", arguing makes
 *       the block worse.
 * </ul>
 *
 * <p>One limitation worth being honest about: TLS fingerprinting. Sophisticated anti-bot services
 * identify clients by the shape of the TLS handshake, which the JDK cannot disguise. No amount of
 * header work defeats that, which is precisely why the unblocker provider exists as a fallback.
 */
@Component
public class HttpPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpPageFetcher.class);

    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_HONOURED_RETRY_AFTER_MS = 60_000;

    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9_:.\\-]+)",
            Pattern.CASE_INSENSITIVE);

    private final AppProperties props;
    private final HttpClient client;

    public HttpPageFetcher(AppProperties props) {
        this.props = props;
        long timeout = props.getScrape().getTimeoutMs();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                // Followed by hand so the URL guard sees every hop. See the class comment.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.min(12_000, Math.max(3_000, timeout))))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    /**
     * Fetches a URL, retrying transient refusals.
     *
     * <p>Returns the page even when the status is not 2xx, because the body of a 403 or 503 is
     * usually the anti-bot page itself and {@link BlockDetector} needs to read it. Deciding what a
     * response means is the engine's job, not the fetcher's.
     */
    public FetchedPage fetch(String url, HostRule rule) {
        int maxAttempts = Math.max(1, props.getScrape().getMaxRetries() + 1);
        ScrapeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                FetchedPage page = fetchOnce(url, rule);
                if (shouldRetryStatus(page.status()) && attempt < maxAttempts) {
                    long wait = backoffMs(attempt, null);
                    log.info("{} returned HTTP {} (attempt {}/{}), retrying in {}ms",
                            url, page.status(), attempt, maxAttempts, wait);
                    sleep(wait);
                    continue;
                }
                return page;
            } catch (ScrapeException e) {
                last = e;
                boolean retryable = e.outcome() == ScrapeOutcome.TIMEOUT
                        || e.outcome() == ScrapeOutcome.ERROR;
                if (!retryable || attempt == maxAttempts) {
                    throw e;
                }
                long wait = backoffMs(attempt, null);
                log.info("Fetch of {} failed with {} (attempt {}/{}), retrying in {}ms: {}",
                        url, e.outcome(), attempt, maxAttempts, wait, e.getMessage());
                sleep(wait);
            }
        }
        throw last != null ? last
                : new ScrapeException(ScrapeOutcome.ERROR, "Fetch failed with no recorded cause");
    }

    // ------------------------------------------------------------------ one attempt

    private FetchedPage fetchOnce(String url, HostRule rule) {
        long started = System.nanoTime();
        String current = url;
        String referer = "https://www.google.com/";

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri;
            try {
                uri = new URI(current);
            } catch (Exception e) {
                throw new ScrapeException(ScrapeOutcome.ERROR,
                        "Retailer redirected us to an unreadable URL: " + current);
            }
            UrlGuard.assertFetchable(uri);

            HttpResponse<InputStream> response = send(uri, rule, hop == 0 ? referer : refererFor(current));
            int status = response.statusCode();

            if (isRedirect(status)) {
                String location = response.headers().firstValue("location").orElse(null);
                drain(response);
                if (location == null || location.isBlank()) {
                    throw new ScrapeException(ScrapeOutcome.HTTP_ERROR,
                            "HTTP " + status + " with no Location header", status);
                }
                if (hop == MAX_REDIRECTS) {
                    throw new ScrapeException(ScrapeOutcome.HTTP_ERROR,
                            "Too many redirects (" + MAX_REDIRECTS + ")", status);
                }
                referer = current;
                current = uri.resolve(location).toString();
                log.debug("Redirect {} -> {}", status, current);
                continue;
            }

            byte[] raw = readBody(response);
            byte[] body = decompress(raw, response.headers().firstValue("content-encoding").orElse(null));
            Charset charset = detectCharset(response, body);
            String html = new String(body, charset);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;

            if (isRateLimited(status)) {
                // Surface Retry-After to the caller through the log; the retry loop handles waiting.
                retryAfterMs(response).ifPresent(ms ->
                        log.info("{} asked us to wait {}ms", uri.getHost(), ms));
            }

            return new FetchedPage(url, current, status, html, body.length, latencyMs,
                    FetchedPage.FetchSource.DIRECT);
        }
        throw new ScrapeException(ScrapeOutcome.ERROR, "Redirect loop exhausted");
    }

    private HttpResponse<InputStream> send(URI uri, HostRule rule, String referer) {
        BrowserProfile profile = BrowserProfile.forHost(rule.host());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofMillis(props.getScrape().getTimeoutMs()));
        for (Map.Entry<String, String> e : profile.navigationHeaders(referer).entrySet()) {
            b.header(e.getKey(), e.getValue());
        }

        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ScrapeException(ScrapeOutcome.TIMEOUT,
                    "Timed out after " + props.getScrape().getTimeoutMs() + "ms", null, e);
        } catch (IOException e) {
            // Connection reset is how some anti-bot layers refuse a handshake they dislike, so the
            // message is kept rather than flattened into a generic failure.
            throw new ScrapeException(ScrapeOutcome.ERROR,
                    "Network error: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException(ScrapeOutcome.ERROR, "Interrupted during fetch", null, e);
        }
    }

    // ------------------------------------------------------------------ body handling

    private byte[] readBody(HttpResponse<InputStream> response) {
        int cap = props.getScrape().getMaxResponseBytes();
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > cap) {
                    throw new ScrapeException(ScrapeOutcome.ERROR,
                            "Response exceeded the " + (cap / 1024) + "KB limit. Either the page is "
                            + "unusually large or SCRAPE_MAX_RESPONSE_BYTES is set too low.");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ScrapeException(ScrapeOutcome.ERROR,
                    "Failed while reading the response body: " + e.getMessage(), null, e);
        }
    }

    /**
     * Decompresses by what the bytes actually are, not only by what the header claims. Some CDNs
     * mislabel encodings, and a wrong guess yields binary soup that looks exactly like a blocked
     * page to everything downstream.
     */
    private static byte[] decompress(byte[] raw, String contentEncoding) {
        if (raw.length == 0) {
            return raw;
        }
        boolean gzipMagic = raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
        String encoding = contentEncoding == null ? "" : contentEncoding.toLowerCase().trim();

        if (gzipMagic || encoding.contains("gzip")) {
            byte[] result = tryInflate(raw, true, false);
            if (result != null) {
                return result;
            }
        }
        if (encoding.contains("deflate")) {
            // zlib-wrapped first, then raw deflate. Servers disagree about which "deflate" means.
            byte[] zlib = tryInflate(raw, false, false);
            if (zlib != null) {
                return zlib;
            }
            byte[] rawDeflate = tryInflate(raw, false, true);
            if (rawDeflate != null) {
                return rawDeflate;
            }
        }
        return raw;
    }

    private static byte[] tryInflate(byte[] raw, boolean gzip, boolean nowrap) {
        try (InputStream source = new ByteArrayInputStream(raw);
             InputStream in = gzip
                     ? new GZIPInputStream(source)
                     : new InflaterInputStream(source, new Inflater(nowrap))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 3);
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Content-Type first, then a {@code <meta charset>} sniff, then UTF-8. The sniff decodes the
     * head of the document as Latin-1, which never fails and is safe because charset declarations
     * are pure ASCII.
     */
    private static Charset detectCharset(HttpResponse<InputStream> response, byte[] body) {
        Optional<String> contentType = response.headers().firstValue("content-type");
        if (contentType.isPresent()) {
            String ct = contentType.get();
            int idx = ct.toLowerCase().indexOf("charset=");
            if (idx >= 0) {
                String name = ct.substring(idx + 8).trim();
                int semi = name.indexOf(';');
                if (semi > 0) {
                    name = name.substring(0, semi);
                }
                name = name.replace("\"", "").replace("'", "").trim();
                Charset cs = safeCharset(name);
                if (cs != null) {
                    return cs;
                }
            }
        }

        int sniffLength = Math.min(body.length, 4096);
        String head = new String(body, 0, sniffLength, StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET.matcher(head);
        if (m.find()) {
            Charset cs = safeCharset(m.group(1));
            if (cs != null) {
                return cs;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring unsupported charset '{}'", name);
            return null;
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isRateLimited(int status) {
        return status == 429 || status == 503;
    }

    /** Retry 429/503 and genuine server errors; never retry a 403, which is a decision. */
    private static boolean shouldRetryStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static Optional<Long> retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").flatMap(v -> {
            try {
                long seconds = Long.parseLong(v.trim());
                return Optional.of(Math.min(seconds * 1000, MAX_HONOURED_RETRY_AFTER_MS));
            } catch (NumberFormatException e) {
                // The HTTP-date form. Not worth parsing; the default backoff covers it.
                return Optional.empty();
            }
        });
    }

    /** Exponential with jitter, so a batch of failures does not retry in lockstep. */
    private static long backoffMs(int attempt, Long retryAfterMs) {
        if (retryAfterMs != null) {
            return retryAfterMs;
        }
        long base = 1500L * (1L << Math.min(attempt - 1, 4));
        return base + ThreadLocalRandom.current().nextLong(500, 1500);
    }

    private static String refererFor(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return null;
        }
    }

    private static void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.readAllBytes();
        } catch (IOException ignored) {
            // Closing is best-effort; the connection will be discarded either way.
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException(ScrapeOutcome.ERROR, "Interrupted while backing off", null, e);
        }
    }
}
