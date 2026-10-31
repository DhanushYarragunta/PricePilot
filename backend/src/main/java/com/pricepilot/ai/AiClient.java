package com.pricepilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Selects the configured provider. Gateway requests use the Chat Completions text protocol;
 * browsing and retailer requests are performed separately by PricePilot, never assumed to be
 * included with a model key. Secrets and provider response bodies are not logged or surfaced.
 */
@Component
public class AiClient {
    private static final ApiProvider GATEWAY = ApiProvider.OPENAI_COMPATIBLE;
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private final AppProperties props;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final ClaudeClient anthropic;
    private final HttpClient http;

    @Autowired
    public AiClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper, ClaudeClient anthropic) {
        this(props, budget, mapper, anthropic, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());
    }

    AiClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper, ClaudeClient anthropic, HttpClient http) {
        this.props = props;
        this.budget = budget;
        this.mapper = mapper;
        this.anthropic = anthropic;
        this.http = http;
    }

    public boolean isEnabled() { return props.getAi().isConfigured(); }
    public String providerLabel() { return props.getAi().isAnthropic() ? "Anthropic" : "OpenAI-compatible"; }
    public String model() { return props.getAi().getModel(); }
    public long timeoutMs() { return props.getAi().getTimeoutMs(); }

    public boolean hasBudget() {
        if (!isEnabled()) return false;
        AppProperties.Ai ai = props.getAi();
        if (ai.isAnthropic()) return anthropic.hasBudget();
        return ai.getMonthlyCallBudget() > 0
                && budget.callsThisMonth(GATEWAY) < ai.getMonthlyCallBudget()
                && budget.hasUsdBudget(GATEWAY, ai.getMonthlyBudgetUsd());
    }

    public AiResponse complete(String system, String user, String prefill) {
        if (!isEnabled()) {
            throw new AiException("AI is not configured. Set the provider, API key, base URL and model on the server.", false, null);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new AiException("AI request was interrupted.", false, null);
        }
        if (props.getAi().isAnthropic()) return AiResponse.from(anthropic.complete(system, user, prefill));
        if (!hasBudget()) {
            throw new AiException("The monthly AI allowance is used up or disabled.", false, null);
        }

        AppProperties.Ai ai = props.getAi();
        HttpRequest request = HttpRequest.newBuilder(endpoint(ai.getBaseUrl()))
                .timeout(Duration.ofMillis(ai.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ai.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(system, user, prefill), StandardCharsets.UTF_8))
                .build();

        // Each actual HTTP attempt consumes a slot, including failures. A retry never bypasses
        // the atomic call reservation. Unknown gateway pricing is bounded by this call cap.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new AiException("AI request was interrupted.", false, null);
            if (!budget.tryConsumeCall(GATEWAY, ai.getMonthlyCallBudget())) {
                throw new AiException("The monthly AI call allowance is used up.", false, null);
            }
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException("AI request was interrupted.", false, null);
            } catch (IOException e) {
                // A connection failure can occur after inference was billed. Do not submit again.
                throw new AiException("Could not reach the AI provider. Please try again later.", true, null);
            }
            int status = response.statusCode();
            if (status == 200) return parse(response.body());
            boolean retryable = status == 429 || status >= 500 && status < 600;
            if (!retryable || attempt == 1) {
                throw new AiException(httpErrorMessage(status), retryable, status);
            }
            long waitMs = response.headers().firstValue("retry-after").map(value -> {
                try { return Math.min(5L, Math.max(0L, Long.parseLong(value.trim()))) * 1000L; }
                catch (NumberFormatException e) { return 1000L; }
            }).orElse(1000L);
            try { Thread.sleep(waitMs); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException("AI request was interrupted.", false, null);
            }
        }
        throw new AiException("AI provider is unavailable.", true, null);
    }

    private static String httpErrorMessage(int status) {
        return switch (status) {
            case 401 -> "AI provider rejected authentication (HTTP 401). Check that the server's AI_API_KEY is active "
                    + "for the configured AI_BASE_URL, then restart the backend after updating its environment.";
            case 403 -> "AI provider denied access (HTTP 403). Check the gateway account and token permissions, "
                    + "including access to the configured AI_MODEL.";
            default -> "AI provider returned HTTP " + status + ". Check its availability and your server configuration.";
        };
    }

    static URI endpoint(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        return URI.create(base + (base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions"));
    }

    String requestBody(String system, String user, String prefill) {
        AppProperties.Ai ai = props.getAi();
        ObjectNode root = mapper.createObjectNode();
        root.put("model", ai.getModel());
        root.put("max_completion_tokens", ai.getMaxTokens());
        String effort = ai.getReasoningEffort();
        if (effort != null && !effort.isBlank()) root.put("reasoning_effort", effort.trim());
        ArrayNode messages = root.putArray("messages");
        String rubric = system == null ? "" : system;
        if (prefill != null && !prefill.isBlank()) rubric += "\nReturn only the complete JSON object, with no markdown fences.";
        messages.addObject().put("role", "system").put("content", rubric);
        messages.addObject().put("role", "user").put("content", user == null ? "" : user);
        // Reasoning models do not accept the Anthropic prefill/cache-control or temperature=0.
        return root.toString();
    }

    private AiResponse parse(String body) {
        JsonNode root;
        try { root = mapper.readTree(body == null ? "" : body); }
        catch (Exception e) { throw new AiException("AI provider returned invalid JSON.", false, 200); }
        if (root == null || !root.isObject()) throw new AiException("AI provider returned an invalid response.", false, 200);

        JsonNode usage = root.path("usage");
        long promptTokens = Math.max(0L, usage.path("prompt_tokens").asLong(0));
        long output = Math.max(0L, usage.path("completion_tokens").asLong(0));
        long cached = Math.min(promptTokens, Math.max(0L, usage.path("prompt_tokens_details").path("cached_tokens").asLong(0)));
        BigDecimal cost = usage.has("prompt_tokens") && usage.has("completion_tokens")
                ? estimatedCost(promptTokens, output, cached) : null;
        budget.recordSpend(GATEWAY, promptTokens, output, cost);

        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (!message.path("refusal").isMissingNode() && !message.path("refusal").isNull()
                && !message.path("refusal").asText().isBlank()) {
            throw new AiException("The AI provider could not answer this request.", false, 200);
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";
        String stop = choice.path("finish_reason").asText(null);
        if (text.isBlank()) {
            throw new AiException("length".equals(stop)
                    ? "AI used its output allowance before completing the answer. Increase AI_MAX_TOKENS."
                    : "AI provider returned no answer.", false, 200);
        }
        String model = root.path("model").asText(props.getAi().getModel());
        return new AiResponse(text, model, promptTokens - cached, output, 0, cached, cost, stop);
    }

    private BigDecimal estimatedCost(long input, long output, long cached) {
        AppProperties.Ai ai = props.getAi();
        if (!ai.hasConfiguredPricing()) return null;
        BigDecimal cacheRate = ai.getCachedInputUsdPerMillion();
        if (cacheRate == null || cacheRate.signum() < 0) cacheRate = ai.getInputUsdPerMillion();
        return BigDecimal.valueOf(input - cached).multiply(ai.getInputUsdPerMillion())
                .add(BigDecimal.valueOf(cached).multiply(cacheRate))
                .add(BigDecimal.valueOf(output).multiply(ai.getOutputUsdPerMillion()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
