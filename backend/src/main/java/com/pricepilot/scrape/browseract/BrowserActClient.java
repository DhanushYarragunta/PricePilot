package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.usage.BudgetGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * BrowserAct Workflow API: https://www.browseract.com/docs-api (verified 2026-10-29).
 * Product fields are our workflow's output contract, documented in README.md (BrowserAct workflow);
 * they are not fields supplied automatically by BrowserAct.
 */
@Component
public class BrowserActClient {
    private static final Logger log = LoggerFactory.getLogger(BrowserActClient.class);
    private static final String BASE = "https://api.browseract.com/v2/workflow/";
    // Amazon may return a descriptive product slug before the product route.
    private static final Pattern AMAZON_PATH = Pattern.compile("/(?:[^/]+/)?(?:dp|gp/product)/([A-Z0-9]{10})(?:/.*)?");
    private static final int MAX_BODY_CHARS = 1_048_576;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final BudgetGuard budget;
    private final UrlCanonicalizer canonicalizer;
    private final HttpClient http;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final ScrapeMetrics metrics;

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    @Autowired
    public BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget,
                            UrlCanonicalizer canonicalizer, ScrapeMetrics metrics) {
        this(props, mapper, budget, canonicalizer, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), System::nanoTime, Thread::sleep, metrics);
    }

    public BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget,
                            UrlCanonicalizer canonicalizer) {
        this(props, mapper, budget, canonicalizer, new ScrapeMetrics(new SimpleMeterRegistry()));
    }

    BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget,
                     UrlCanonicalizer canonicalizer, HttpClient http, LongSupplier nanoTime, Sleeper sleeper) {
        this(props, mapper, budget, canonicalizer, http, nanoTime, sleeper,
                new ScrapeMetrics(new SimpleMeterRegistry()));
    }

    BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget,
                     UrlCanonicalizer canonicalizer, HttpClient http, LongSupplier nanoTime,
                     Sleeper sleeper, ScrapeMetrics metrics) {
        this.props = props;
        this.mapper = mapper;
        this.budget = budget;
        this.canonicalizer = canonicalizer;
        this.http = http;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.metrics = metrics;
    }

    public boolean isConfigured() {
        int limit = props.getBrowserAct().getMonthlyCallBudget();
        return props.getBrowserAct().isConfigured() && props.getBrowserAct().getTimeoutMs() > 0
                && (limit > 0 || limit == -1);
    }

    /** One submission at most. Failed requests are never silently resubmitted. */
    public BrowserActExtraction extract(String productUrl, HostRule rule) {
        if (rule == null || !isConfigured()) {
            return failure(ScrapeOutcome.ERROR, "BrowserAct is not configured or submissions are disabled.", false);
        }
        var config = props.getBrowserAct();
        long deadline = nanoTime.getAsLong() + Duration.ofMillis(config.getTimeoutMs()).toNanos();
        boolean submitted = false;
        boolean succeeded = false;
        try {
            UrlCanonicalizer.Canonical requested = canonicalProduct(productUrl, rule);
            if (requested == null) {
                return failure(ScrapeOutcome.ERROR, "The product URL does not match this retailer or is not safe to check.", false);
            }
            String body = mapper.writeValueAsString(Map.of(
                    "workflow_id", config.getWorkflowId().trim(),
                    "input_parameters", List.of(Map.of("name", "url", "value", requested.url()))));
            // Build and validate configuration before reserving a metered submission.
            HttpRequest submission = request("run-task", deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!budget.tryConsumeCall(ApiProvider.BROWSERACT,
                    config.getMonthlyCallBudget())) {
                return failure(ScrapeOutcome.ERROR, "The BrowserAct submission budget is exhausted.", false);
            }
            // A budget lock can take time. Never start a paid task after cancellation or use a
            // request timeout calculated before that wait. The local reservation stays conservative.
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            submission = request("run-task", deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            submitted = true;
            JsonNode submissionResponse = send(submission);
            String id = text(submissionResponse, "id");
            if (id == null || id.length() > 200) {
                return failure(ScrapeOutcome.ERROR, "BrowserAct did not return a valid task identifier.", true);
            }
            String taskPath = "get-task?task_id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            while (remainingMillis(deadline) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                JsonNode task = send(request(taskPath, deadline).GET().build());
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (remainingMillis(deadline) <= 0) break;
                if (!id.equals(text(task, "id"))) {
                    return failure(ScrapeOutcome.ERROR, "BrowserAct returned an invalid task response.", true);
                }
                String status = text(task, "status");
                if ("finished".equals(status)) {
                    Optional<BrowserActResult> result = parseOutput(task, requested, rule);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (remainingMillis(deadline) <= 0) break;
                    succeeded = result.isPresent();
                    return result.map(value -> new BrowserActExtraction(value, ScrapeOutcome.SUCCESS,
                                    "Product price verified by BrowserAct.", true))
                            .orElseGet(() -> failure(ScrapeOutcome.NO_PRICE,
                                    "BrowserAct finished without a valid price for this product.", true));
                }
                if (!"created".equals(status) && !"running".equals(status)) {
                    log.warn("BrowserAct workflow did not finish successfully; no price accepted");
                    return failure(ScrapeOutcome.ERROR, "The BrowserAct workflow did not finish successfully.", true);
                }
                long remaining = remainingMillis(deadline);
                if (remaining <= 0) break;
                sleeper.sleep(Math.min(3000, remaining));
            }
            log.warn("BrowserAct deadline reached; no price accepted");
            return failure(ScrapeOutcome.TIMEOUT, "BrowserAct did not finish before the time limit.", submitted);
        } catch (HttpTimeoutException e) {
            return failure(ScrapeOutcome.TIMEOUT, "BrowserAct did not finish before the time limit.", submitted);
        } catch (ProviderHttpException e) {
            // The provider API status says nothing about whether the retailer's page still exists.
            return failure(ScrapeOutcome.ERROR, "BrowserAct API returned HTTP " + e.status + ".", submitted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("BrowserAct request interrupted; no price accepted");
            return failure(ScrapeOutcome.TIMEOUT, "The BrowserAct request was interrupted.", submitted);
        } catch (Exception e) {
            // Provider bodies and exception messages may contain credentials or extracted content.
            log.warn("BrowserAct request failed ({}); no price accepted", e.getClass().getSimpleName());
            return failure(ScrapeOutcome.ERROR, "The BrowserAct request failed; no price was accepted.", submitted);
        } finally {
            // Failures before submission (validation, disabled config, exhausted budget) are not
            // outbound failures. A submitted workflow contributes exactly one terminal outcome.
            if (submitted) {
                if (succeeded) metrics.requestSucceeded();
                else metrics.requestFailed();
            }
        }
    }

    private static BrowserActExtraction failure(ScrapeOutcome outcome, String message, boolean submitted) {
        return new BrowserActExtraction(null, outcome, message, submitted);
    }

    private long remainingMillis(long deadline) {
        return Math.max(0, Duration.ofNanos(deadline - nanoTime.getAsLong()).toMillis());
    }

    private HttpRequest.Builder request(String path, long deadline) throws HttpTimeoutException {
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) throw new HttpTimeoutException("BrowserAct deadline");
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + props.getBrowserAct().getApiKey())
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(Math.min(15000, remaining)));
    }

    private JsonNode send(HttpRequest request) throws Exception {
        if ("POST".equals(request.method()) && request.uri().getPath().endsWith("/run-task")) {
            metrics.retailerRequest();
        }
        metrics.browserActApiCall();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("BrowserAct returned HTTP {}; no price accepted", response.statusCode());
            throw new ProviderHttpException(response.statusCode());
        }
        if (response.body() == null || response.body().length() > MAX_BODY_CHARS) {
            throw new IllegalStateException("Invalid BrowserAct response size");
        }
        return mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
    }

    Optional<BrowserActResult> parseOutput(JsonNode task, String productUrl) {
        try {
            UrlCanonicalizer.Canonical requested = canonicalizer.canonicalize(productUrl);
            return parseOutput(task, productUrl, requested.rule());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    Optional<BrowserActResult> parseOutput(JsonNode task, String productUrl, HostRule rule) {
        UrlCanonicalizer.Canonical requested = canonicalProduct(productUrl, rule);
        return requested == null ? Optional.empty() : parseOutput(task, requested, rule);
    }

    private Optional<BrowserActResult> parseOutput(JsonNode task, UrlCanonicalizer.Canonical requested, HostRule rule) {
        try {
            String output = text(task.path("output"), "string");
            if (output == null || output.length() > MAX_BODY_CHARS) return Optional.empty();
            JsonNode data = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (data.isArray() && data.size() == 1) data = data.get(0);
            if (!data.isObject() || !"success".equals(text(data, "status"))) return Optional.empty();
            String returnedUrl = text(data, "url");
            UrlCanonicalizer.Canonical returned = canonicalProduct(returnedUrl, rule);
            if (returned == null || !requested.hash().equals(returned.hash())) return Optional.empty();
            if ("https".equalsIgnoreCase(URI.create(requested.url()).getScheme())
                    && !"https".equalsIgnoreCase(URI.create(returnedUrl).getScheme())) return Optional.empty();
            String asin = null;
            if (isAmazon(rule)) {
                String expected = amazonAsin(requested.url());
                asin = text(data, "asin");
                if (expected == null || !expected.equals(asin)
                        || !expected.equals(amazonAsin(returnedUrl))) return Optional.empty();
            }
            String title = text(data, "title");
            if (title == null || title.length() < 3 || title.length() > 500) return Optional.empty();
            if (!"INR".equals(text(data, "currency"))) return Optional.empty();
            String availability = text(data, "availability");
            if (!"in_stock".equals(availability) && !"out_of_stock".equals(availability)) return Optional.empty();
            BigDecimal selling = amount(data.get("selling_price"));
            if (selling == null || selling.compareTo(new BigDecimal("5000000")) > 0) return Optional.empty();
            JsonNode rawMrp = data.get("mrp");
            BigDecimal mrp = amount(rawMrp);
            if (rawMrp != null && !rawMrp.isNull() && mrp == null) return Optional.empty();
            // Contradictions invalidate the result. Never swap fields or infer a discount.
            if (mrp != null && mrp.compareTo(selling) < 0) return Optional.empty();
            String image = text(data, "image_url");
            if (image != null && !validImage(image)) image = null;
            return Optional.of(new BrowserActResult(title, selling, mrp, "INR",
                    "in_stock".equals(availability), asin, image, "success"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private UrlCanonicalizer.Canonical canonicalProduct(String url, HostRule rule) {
        if (url == null || rule == null) return null;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) return null;
            int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            if (uri.getPort() != -1 && uri.getPort() != defaultPort) return null;
            if (isAmazon(rule) && amazonAsin(url) == null) return null;
            UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(url);
            return canonical != null && canonical.rule() != null
                    && rule.host().equals(canonical.rule().host()) ? canonical : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isAmazon(HostRule rule) {
        return "amazon.in".equals(rule.host());
    }

    private static final class ProviderHttpException extends Exception {
        private final int status;

        private ProviderHttpException(int status) { this.status = status; }
    }

    private static BigDecimal amount(JsonNode node) {
        return node != null && (node.isNumber() || node.isTextual())
                ? PriceParser.parseAmount(node.asText()).orElse(null) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) return null;
        String value = node.path(field).textValue().trim();
        return value.isEmpty() ? null : value;
    }

    static String amazonAsin(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            String host = uri.getHost();
            if (!"amazon.in".equalsIgnoreCase(host) && !"www.amazon.in".equalsIgnoreCase(host)) return null;
            var match = AMAZON_PATH.matcher(uri.getPath());
            return match.matches() ? match.group(1) : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private static boolean validImage(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 1000 && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}
