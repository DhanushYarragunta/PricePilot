package com.pricepilot.scrape;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.usage.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The paid last resort: fetches a page through a commercial unblocker API.
 *
 * <p>Needed because of one problem that cannot be solved with better headers. Anti-bot services
 * fingerprint the TLS handshake — the cipher list, the extension order, the ALPN values — and the
 * JDK's HTTP client produces a handshake that does not look like Chrome's and cannot be made to.
 * Every header in {@link BrowserProfile} is correct and it still does not matter, because the
 * request was classified before a single header was read. On Amazon.in that is the difference
 * between a product page and a CAPTCHA.
 *
 * <p>An unblocker provider runs the request from a residential IP with a real browser TLS stack and
 * hands back the HTML. It is the same extraction pipeline afterwards — this class only changes where
 * the bytes come from, which is why {@link FetchedPage.FetchSource} records it. A price obtained
 * this way is still a real price read off a real product page, which is the constraint the whole
 * design is built around.
 *
 * <p>It is optional on purpose, and off by default. With no provider configured the whole class
 * becomes a no-op and the site runs entirely on free direct fetches — some Amazon listings will
 * report {@code BLOCKED} and every other retailer works. That is a deliberate trade: the project
 * must be runnable by someone who has not signed up for anything.
 *
 * <p>Three guards before any money is spent: the provider must be configured, the monthly call
 * budget must have room ({@link BudgetGuard#tryConsumeCall}), and JavaScript rendering stays off.
 * Rendering typically costs five to ten times a plain request and buys nothing here, because the
 * data this project needs is in the HTML source — the whole point of the embedded-JSON strategy is
 * that reading React's serialised props is as good as letting React run.
 */
@Component
public class UnblockerPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(UnblockerPageFetcher.class);

    private static final String SCRAPERAPI_ENDPOINT = "https://api.scraperapi.com/";
    private static final String SCRAPINGBEE_ENDPOINT = "https://app.scrapingbee.com/api/v1/";

    /** Providers take longer than a direct fetch because they are retrying behind the scenes. */
    private static final long EXTRA_TIMEOUT_MS = 45_000;

    private final AppProperties props;
    private final BudgetGuard budget;
    private final HttpClient client;

    public UnblockerPageFetcher(AppProperties props, BudgetGuard budget) {
        this.props = props;
        this.budget = budget;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** True when a provider key is present, so the engine can decide whether a fallback exists. */
    public boolean isAvailable() {
        return props.getScrape().isProviderConfigured();
    }

    /**
     * Fetches {@code url} through the configured provider.
     *
     * <p>Returns empty rather than throwing when the fallback is simply unavailable — not
     * configured, or out of budget. Those are normal operating states, not failures, and the caller
     * should report the original block rather than a second error about the fallback.
     *
     * <p>Throws {@link ScrapeException} only when the provider was actually called and the call
     * went wrong, because that is a real failure worth recording and alerting on.
     */
    public Optional<FetchedPage> fetch(String url, HostRule rule) {
        if (!isAvailable()) {
            return Optional.empty();
        }

        int callBudget = props.getScrape().getProviderMonthlyCallBudget();
        if (!budget.tryConsumeCall(ApiProvider.UNBLOCKER, callBudget)) {
            log.info("Skipping unblocker for {} — monthly call budget is used up", rule.host());
            return Optional.empty();
        }

        String provider = props.getScrape().getProvider().toLowerCase(Locale.ROOT).trim();
        URI endpoint;
        try {
            endpoint = switch (provider) {
                case "scraperapi" -> scraperApiUri(url);
                case "scrapingbee" -> scrapingBeeUri(url);
                default -> throw new ScrapeException(ScrapeOutcome.ERROR,
                        "Unknown SCRAPER_PROVIDER '" + provider + "'. Valid values are "
                        + "none, scraperapi, scrapingbee.");
            };
        } catch (ScrapeException e) {
            throw e;
        } catch (Exception e) {
            throw new ScrapeException(ScrapeOutcome.ERROR,
                    "Could not build the unblocker request URL: " + e.getMessage(), null, e);
        }

        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(Duration.ofMillis(props.getScrape().getTimeoutMs() + EXTRA_TIMEOUT_MS))
                .header("Accept", "text/html,*/*")
                .build();

        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            // The provider's own status code is about the provider, not the retailer, so the two
            // cases are separated here. Confusing them would make the ops page blame Amazon for a
            // billing problem.
            if (status == 401 || status == 403) {
                throw new ScrapeException(ScrapeOutcome.ERROR,
                        "The unblocker provider rejected our API key (HTTP " + status + "). Check "
                        + "SCRAPER_API_KEY, and whether the account still has credits.", status);
            }
            if (status == 429) {
                throw new ScrapeException(ScrapeOutcome.BLOCKED,
                        "The unblocker provider rate-limited us (HTTP 429). This is the provider's "
                        + "own concurrency limit, not the retailer's.", status);
            }
            if (status >= 500 || body.isBlank()) {
                // Providers return 500 after exhausting their internal retries, which means the
                // target really is defended rather than that the provider is broken.
                throw new ScrapeException(ScrapeOutcome.BLOCKED,
                        "The unblocker could not retrieve " + rule.host() + " (HTTP " + status
                        + "). The target is refusing even residential requests.", status);
            }

            log.info("Unblocker fetched {} in {}ms, {} bytes (provider call #{} this month)",
                    rule.host(), latencyMs, body.length(),
                    budget.callsThisMonth(ApiProvider.UNBLOCKER));

            return Optional.of(new FetchedPage(url, url, 200, body,
                    body.getBytes(StandardCharsets.UTF_8).length, latencyMs,
                    FetchedPage.FetchSource.UNBLOCKER));

        } catch (IOException e) {
            throw new ScrapeException(ScrapeOutcome.ERROR,
                    "Could not reach the unblocker provider: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScrapeException(ScrapeOutcome.ERROR,
                    "Interrupted while waiting on the unblocker", null, e);
        }
    }

    // ------------------------------------------------------------------ provider URLs

    /**
     * ScraperAPI. {@code country_code=in} matters more than it looks: Indian retailers show
     * different prices, and sometimes a different page entirely, to requests from outside India,
     * so a price fetched through a US exit node would be quietly wrong rather than obviously wrong.
     */
    private URI scraperApiUri(String target) {
        String params = "api_key=" + enc(props.getScrape().getProviderApiKey())
                + "&url=" + enc(target)
                + "&country_code=" + enc(props.getScrape().getProviderCountry())
                + "&render=false"
                + "&keep_headers=false";
        return URI.create(SCRAPERAPI_ENDPOINT + "?" + params);
    }

    /**
     * ScrapingBee. {@code premium_proxy=true} is what buys the residential IP; without it the
     * request goes out from a datacentre range and gets the same block a direct fetch would.
     */
    private URI scrapingBeeUri(String target) {
        String params = "api_key=" + enc(props.getScrape().getProviderApiKey())
                + "&url=" + enc(target)
                + "&render_js=false"
                + "&premium_proxy=true"
                + "&country_code=" + enc(props.getScrape().getProviderCountry());
        return URI.create(SCRAPINGBEE_ENDPOINT + "?" + params);
    }

    /**
     * The target URL must be percent-encoded as a single parameter value. Passing it raw is the
     * classic mistake: the retailer's own query string gets read as more provider parameters, and
     * the provider fetches a truncated URL.
     */
    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
