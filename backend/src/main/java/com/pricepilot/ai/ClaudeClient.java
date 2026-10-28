package com.pricepilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small, direct client for the Claude Messages API.
 *
 * <p>Hand-rolled on the JDK HTTP client rather than pulled from an SDK, for two reasons that matter
 * on a box this size. It keeps the dependency list short — fewer transitive jars, a smaller image, a
 * faster cold start — and it means the token accounting is read straight out of the response by code
 * that is visible here, rather than being hidden behind an abstraction. Since the spend cap is a
 * feature of this project, that visibility is worth the eighty lines.
 *
 * <p>Three deliberate choices in the request shape:
 *
 * <ul>
 *   <li><b>The system prompt is cached.</b> It carries the rubric, which is long, identical on every
 *       call, and otherwise re-billed every time. Prompt caching only engages once the cached
 *       segment passes the model's minimum length, so on short prompts this is a no-op rather than a
 *       saving — {@link ClaudeResponse#cacheReadTokens()} is recorded so the ops page shows which
 *       of the two is actually happening instead of assuming.
 *   <li><b>Temperature zero.</b> The task is classification against a rubric, not writing. Two runs
 *       over the same price history should not disagree, because a verdict that flickers between
 *       GOOD_VALUE and FAIR on identical data is worse than useless to someone deciding whether to
 *       buy.
 *   <li><b>An assistant prefill.</b> The reply is pre-started with an opening brace, which commits
 *       the model to JSON from its first token. This is far more reliable than asking for JSON in
 *       the prompt and then stripping markdown fences and apologies off the front of the answer.
 * </ul>
 *
 * <p>Retries cover 429 and 5xx, including 529 (the API's "overloaded" status), with jittered
 * exponential backoff and {@code retry-after} honoured when present. A 400 or 401 is never retried:
 * a malformed request or a bad key will be exactly as malformed the second time.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_HONOURED_RETRY_AFTER_MS = 30_000;

    /** The API's own "we are overloaded, try again" status. Not a standard HTTP code. */
    private static final int OVERLOADED = 529;

    private final AppProperties props;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ClaudeClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper) {
        this.props = props;
        this.budget = budget;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        AppProperties.Ai ai = props.getAi();
        if (!ai.isConfigured()) {
            log.warn("ANTHROPIC_API_KEY is not set. Deal analysis is disabled; price tracking, "
                     + "charts and alerts all work without it.");
        } else if (!ClaudePricing.isKnownModel(ai.getModel())) {
            log.warn("Model '{}' has no entry in ClaudePricing, so cost will be estimated at Haiku "
                     + "rates. Add it to the rate table to make the spend figures exact.",
                    ai.getModel());
        }
    }

    public boolean isEnabled() {
        return props.getAi().isConfigured();
    }

    /**
     * Checks the monthly dollar budget without spending anything. Call this before assembling an
     * expensive prompt — there is no point gathering price history for an analysis that cannot run.
     */
    public boolean hasBudget() {
        return isEnabled() && budget.hasUsdBudget(ApiProvider.ANTHROPIC, props.getAi().getMonthlyBudgetUsd());
    }

    /**
     * Sends one message and returns the reply.
     *
     * @param system  the cached rubric
     * @param user    the per-product payload
     * @param prefill text the assistant's reply is forced to begin with, or {@code null} for none.
     *                It is prepended to the returned text, because the API returns only what it
     *                generated after the prefill
     * @throws AiException on any failure. Callers treat this as "no verdict this time", never as a
     *                     reason to fail the surrounding operation
     */
    public ClaudeResponse complete(String system, String user, String prefill) {
        AppProperties.Ai ai = props.getAi();
        if (!ai.isConfigured()) {
            throw new AiException("Claude is not configured (ANTHROPIC_API_KEY is empty)", false, null);
        }
        if (!budget.hasUsdBudget(ApiProvider.ANTHROPIC, ai.getMonthlyBudgetUsd())) {
            throw new AiException("Monthly Claude budget of $" + ai.getMonthlyBudgetUsd()
                                  + " is used up. Analysis resumes next month, or when "
                                  + "AI_MONTHLY_BUDGET_USD is raised.", false, null);
        }

        String body = requestBody(ai, system, user, prefill);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ai.getBaseUrl() + "/v1/messages"))
                .timeout(Duration.ofMillis(ai.getTimeoutMs()))
                .header("content-type", "application/json")
                .header("x-api-key", ai.getApiKey())
                .header("anthropic-version", ai.getApiVersion())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        AiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return send(request, prefill);
            } catch (AiException e) {
                last = e;
                if (!e.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                long wait = waitFor(e, attempt);
                log.info("Claude call failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, MAX_ATTEMPTS, wait, e.getMessage());
                sleep(wait);
            }
        }
        throw last != null ? last : new AiException("Claude call failed with no recorded cause", false, null);
    }

    // ------------------------------------------------------------------ request / response

    private String requestBody(AppProperties.Ai ai, String system, String user, String prefill) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", ai.getModel());
        root.put("max_tokens", ai.getMaxTokens());
        // Classification, not prose. See the class comment.
        root.put("temperature", 0);

        // The system prompt is sent as a content block rather than a bare string specifically so it
        // can carry cache_control. The string form has nowhere to put it.
        ArrayNode systemBlocks = root.putArray("system");
        ObjectNode systemBlock = systemBlocks.addObject();
        systemBlock.put("type", "text");
        systemBlock.put("text", system);
        systemBlock.putObject("cache_control").put("type", "ephemeral");

        ArrayNode messages = root.putArray("messages");
        ObjectNode userTurn = messages.addObject();
        userTurn.put("role", "user");
        userTurn.put("content", user);

        if (prefill != null && !prefill.isEmpty()) {
            ObjectNode assistantTurn = messages.addObject();
            assistantTurn.put("role", "assistant");
            assistantTurn.put("content", prefill);
        }

        return root.toString();
    }

    private ClaudeResponse send(HttpRequest request, String prefill) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AiException("Could not reach the Claude API: " + e.getMessage(), true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted while calling Claude", false, null, e);
        }

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();

        if (status != 200) {
            throw new AiException("Claude API returned HTTP " + status + ": " + errorMessage(body),
                    isRetryableStatus(status), status, retryAfterMs(response));
        }

        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (Exception e) {
            throw new AiException("Claude API returned a body that is not JSON", true, status, e);
        }

        StringBuilder text = new StringBuilder();
        if (prefill != null) {
            // The API returns only what came after the prefill, so it has to go back on the front
            // or the JSON will be missing its opening brace.
            text.append(prefill);
        }
        JsonNode content = json.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        if (text.length() == (prefill == null ? 0 : prefill.length())) {
            throw new AiException("Claude returned no text content", true, status);
        }

        JsonNode usage = json.path("usage");
        long input = usage.path("input_tokens").asLong(0);
        long output = usage.path("output_tokens").asLong(0);
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        String servingModel = json.path("model").asText(props.getAi().getModel());
        String stopReason = json.path("stop_reason").asText(null);

        BigDecimal cost = ClaudePricing.cost(servingModel, input, output, cacheWrite, cacheRead);

        // Recorded before the caller does anything with the reply. If parsing the verdict throws,
        // the money has still been spent and the budget has to know about it.
        budget.recordCall(ApiProvider.ANTHROPIC);
        budget.recordSpend(ApiProvider.ANTHROPIC, input + cacheWrite + cacheRead, output, cost);

        log.debug("Claude {}: {} in (+{} cache write, {} cache read), {} out, ${}",
                servingModel, input, cacheWrite, cacheRead, output, cost.toPlainString());

        return new ClaudeResponse(text.toString(), servingModel, input, output,
                cacheWrite, cacheRead, cost, stopReason);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 429 and 5xx are worth another attempt; 529 is the API's overloaded signal and is the most
     * worth retrying of all. A 400 or 401 is not retried — the request will be just as wrong, and
     * the key just as invalid, a second later.
     */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == OVERLOADED || (status >= 500 && status < 600);
    }

    /** Pulls {@code error.message} out of an API error body, falling back to the raw text. */
    private String errorMessage(String body) {
        try {
            JsonNode error = mapper.readTree(body).path("error");
            String message = error.path("message").asText(null);
            String type = error.path("type").asText(null);
            if (message != null && !message.isBlank()) {
                return (type == null ? "" : type + ": ") + message;
            }
        } catch (Exception ignored) {
            // Not JSON. The raw body below is more useful than a parse complaint.
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static long backoffMs(int attempt) {
        long base = 1000L * (1L << Math.min(attempt - 1, 4));
        return Math.min(MAX_HONOURED_RETRY_AFTER_MS,
                base + ThreadLocalRandom.current().nextLong(250, 750));
    }

    /**
     * How long to wait before the next attempt. The API's own {@code retry-after} wins when it sent
     * one — it knows when the rate limit window resets and we do not — capped so a long hint cannot
     * hold a scheduler worker hostage. Otherwise, jittered exponential backoff.
     */
    private static long waitFor(AiException e, int attempt) {
        long hint = e.getRetryAfterMs();
        if (hint > 0) {
            return Math.min(hint, MAX_HONOURED_RETRY_AFTER_MS);
        }
        return backoffMs(attempt);
    }

    /**
     * Reads {@code retry-after}, which the API sends as whole seconds. The HTTP-date form of the
     * header is not parsed; it is not what this API sends, and guessing wrong would be worse than
     * falling back to backoff.
     */
    private static long retryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .map(String::trim)
                .map(value -> {
                    try {
                        return Math.max(0L, (long) (Double.parseDouble(value) * 1000));
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted while backing off", false, null, e);
        }
    }
}
