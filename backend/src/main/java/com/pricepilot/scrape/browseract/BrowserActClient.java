package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.scrape.PriceParser;
import com.pricepilot.usage.BudgetGuard;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * BrowserAct Workflow API: https://www.browseract.com/docs-api (verified 2026-10-29).
 * Product fields are our workflow's output contract, documented in docs/scraping-audit.md;
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
    private final HttpClient http;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    @Autowired
    public BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget) {
        this(props, mapper, budget, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), System::nanoTime, Thread::sleep);
    }

    BrowserActClient(AppProperties props, ObjectMapper mapper, BudgetGuard budget,
                     HttpClient http, LongSupplier nanoTime, Sleeper sleeper) {
        this.props = props;
        this.mapper = mapper;
        this.budget = budget;
        this.http = http;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public boolean isConfigured() {
        int limit = props.getBrowserAct().getMonthlyCallBudget();
        return props.getBrowserAct().isConfigured()
                && (limit > 0 || limit == -1);
    }

    /** One submission at most. Failed requests are never silently resubmitted. */
    public Optional<BrowserActResult> extract(String productUrl) {
        String requestedAsin = amazonAsin(productUrl);
        if (!isConfigured() || requestedAsin == null) return Optional.empty();
        var config = props.getBrowserAct();
        if (config.getTimeoutMs() <= 0) return Optional.empty();
        long deadline = nanoTime.getAsLong() + Duration.ofMillis(config.getTimeoutMs()).toNanos();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "workflow_id", config.getWorkflowId(),
                    "input_parameters", List.of(Map.of("name", "url", "value", productUrl))));
            if (!budget.tryConsumeCall(ApiProvider.BROWSERACT,
                    config.getMonthlyCallBudget())) return Optional.empty();
            JsonNode submitted = send(request("run-task", deadline)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build());
            String id = text(submitted, "id");
            if (id == null || id.length() > 200) return Optional.empty();
            String taskPath = "get-task?task_id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            while (remainingMillis(deadline) > 0) {
                JsonNode task = send(request(taskPath, deadline).GET().build());
                if (task == null || !id.equals(text(task, "id"))) return Optional.empty();
                String status = text(task, "status");
                if ("finished".equals(status)) return remainingMillis(deadline) > 0 ? parseOutput(task, productUrl) : Optional.empty();
                if (!"created".equals(status) && !"running".equals(status)) {
                    log.warn("BrowserAct workflow did not finish successfully; no price accepted");
                    return Optional.empty();
                }
                long remaining = remainingMillis(deadline);
                if (remaining <= 0) break;
                sleeper.sleep(Math.min(3000, remaining));
            }
            log.warn("BrowserAct deadline reached; no price accepted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("BrowserAct request interrupted; no price accepted");
        } catch (Exception e) {
            // Provider bodies and exception messages may contain credentials or extracted content.
            log.warn("BrowserAct request failed ({}); no price accepted", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    private long remainingMillis(long deadline) {
        return Math.max(0, Duration.ofNanos(deadline - nanoTime.getAsLong()).toMillis());
    }

    private HttpRequest.Builder request(String path, long deadline) throws java.net.http.HttpTimeoutException {
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) throw new java.net.http.HttpTimeoutException("BrowserAct deadline");
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + props.getBrowserAct().getApiKey())
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(Math.min(15000, remaining)));
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("BrowserAct returned HTTP {}; no price accepted", response.statusCode());
            return null;
        }
        if (response.body() == null || response.body().length() > MAX_BODY_CHARS) return null;
        return mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
    }

    Optional<BrowserActResult> parseOutput(JsonNode task, String productUrl) {
        try {
            String output = text(task.path("output"), "string");
            if (output == null || output.length() > MAX_BODY_CHARS) return Optional.empty();
            JsonNode data = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (data.isArray() && data.size() == 1) data = data.get(0);
            if (!data.isObject() || !"success".equals(text(data, "status"))) return Optional.empty();
            String expected = amazonAsin(productUrl);
            String asin = text(data, "asin");
            String returnedAsin = amazonAsin(text(data, "url"));
            if (expected == null || !expected.equals(asin) || !expected.equals(returnedAsin)) return Optional.empty();
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
