package com.pricepilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiClientTest {
    private final AppProperties props = new AppProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BudgetGuard budget = mock(BudgetGuard.class);
    private final ClaudeClient anthropic = mock(ClaudeClient.class);
    private final HttpClient http = mock(HttpClient.class);
    private AiClient client;
    private static final ApiProvider PROVIDER = ApiProvider.OPENAI_COMPATIBLE;
    private static final String RESPONSE = """
            {"model":"gpt-6.1-sol","choices":[{"message":{"content":"{}"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1000,"completion_tokens":300,"prompt_tokens_details":{"cached_tokens":200}}}
            """;

    @BeforeEach void setup() {
        props.getAi().setProvider("openai-compatible");
        props.getAi().setApiKey("test-only-key");
        props.getAi().setBaseUrl("https://gateway.example/v1/");
        props.getAi().setModel("gpt-6.1-sol");
        when(budget.hasUsdBudget(PROVIDER, new BigDecimal("5.00"))).thenReturn(true);
        when(budget.tryConsumeCall(PROVIDER, 100)).thenReturn(true);
        client = new AiClient(props, budget, mapper, anthropic, http);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);
        when(r.body()).thenReturn(body);
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of("retry-after", java.util.List.of("0")), (a,b) -> true));
        return r;
    }

    @Test void compatibleRequestUsesBearerAndReasoningParametersWithoutAnthropicPrefill() throws Exception {
        doReturn(response(200, RESPONSE)).when(http).send(any(), any(HttpResponse.BodyHandler.class));
        AiResponse result = client.complete("Return JSON", "Product data", "{");
        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().uri()).isEqualTo(URI.create("https://gateway.example/v1/chat/completions"));
        assertThat(sent.getValue().headers().firstValue("Authorization")).contains("Bearer test-only-key");
        assertThat(sent.getValue().headers().firstValue("x-api-key")).isEmpty();
        var body = mapper.readTree(client.requestBody("Return JSON", "Product data", "{"));
        assertThat(body.path("model").asText()).isEqualTo("gpt-6.1-sol");
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.path("messages")).hasSize(2);
        assertThat(result.text()).isEqualTo("{}");
        assertThat(result.totalInputTokens()).isEqualTo(1000);
        assertThat(result.cacheReadTokens()).isEqualTo(200);
        assertThat(result.costUsd()).isNull();
        verify(budget).recordSpend(PROVIDER, 1000, 300, null);
        verifyNoInteractions(anthropic);
    }

    @Test void handlesRootOrVersionedBaseWithoutDuplicatingV1() {
        assertThat(AiClient.endpoint("https://gateway.example/")).hasToString("https://gateway.example/v1/chat/completions");
        assertThat(AiClient.endpoint("https://gateway.example/v1")).hasToString("https://gateway.example/v1/chat/completions");
    }

    @Test void computesOnlyExplicitGatewayRatesAndCountsCachedInputOnce() throws Exception {
        props.getAi().setInputUsdPerMillion(new BigDecimal("2"));
        props.getAi().setOutputUsdPerMillion(new BigDecimal("10"));
        props.getAi().setCachedInputUsdPerMillion(new BigDecimal("0.2"));
        doReturn(response(200, RESPONSE)).when(http).send(any(), any(HttpResponse.BodyHandler.class));
        AiResponse result = client.complete("JSON", "product", null);
        assertThat(result.costUsd()).isEqualByComparingTo("0.004640");
        verify(budget).recordSpend(PROVIDER, 1000, 300, new BigDecimal("0.004640"));
    }

    @Test void refusesBeforeHttpWhenAtomicCallReservationFails() {
        when(budget.tryConsumeCall(PROVIDER,100)).thenReturn(false);
        assertThatThrownBy(() -> client.complete("s", "u", null)).isInstanceOf(AiException.class)
                .hasMessageContaining("allowance");
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void doesNotRetryAuthenticationFailuresOrLeakProviderResponse(int status) throws Exception {
        doReturn(response(status, "test-only-key and provider-private-error"))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        AiException failure = catchThrowableOfType(() -> client.complete("s", "u", null), AiException.class);
        assertThat(failure).hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining("test-only-key").hasMessageNotContaining("provider-private-error");
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.getHttpStatus()).isEqualTo(status);
        assertThat(failure.getMessage()).contains(status == 401 ? "AI_API_KEY" : "permissions");
        assertThat(failure.getMessage()).contains(status == 401 ? "AI_BASE_URL" : "AI_MODEL");
        verify(http, times(1)).send(any(), any());
        verify(budget, times(1)).tryConsumeCall(PROVIDER,100);
        verify(budget, never()).recordSpend(any(), anyLong(), anyLong(), any());
        verifyNoInteractions(anthropic);
    }

    @Test void configuredValuesTrimOnlySurroundingWhitespaceWithoutChangingGatewayOrModel() throws Exception {
        props.getAi().setApiKey(" \ttest-only-key\r\n");
        props.getAi().setAnthropicApiKey("unrelated-key");
        props.getAi().setBaseUrl(" https://gateway.example/v1/ ");
        props.getAi().setModel(" gpt-6.1-sol ");
        doReturn(response(200, RESPONSE)).when(http).send(any(), any(HttpResponse.BodyHandler.class));

        client.complete("s", "u", null);

        var sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().uri()).hasToString("https://gateway.example/v1/chat/completions");
        assertThat(sent.getValue().headers().firstValue("Authorization")).contains("Bearer test-only-key");
        assertThat(mapper.readTree(client.requestBody("s", "u", null)).path("model").asText()).isEqualTo("gpt-6.1-sol");
        verifyNoInteractions(anthropic);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "test key", "test\nkey", "test\tkey"})
    void invalidEffectiveKeyNeverConsumesAnInferenceCall(String key) {
        props.getAi().setApiKey(key);
        assertThatThrownBy(() -> client.complete("s", "u", null)).isInstanceOf(AiException.class)
                .hasMessage("AI is not configured. Set the provider, API key, base URL and model on the server.");
        verifyNoInteractions(http, budget, anthropic);
    }

    @Test void transientRetryReservesAnotherCall() throws Exception {
        doReturn(response(503,"private upstream body"), response(200,RESPONSE))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThat(client.complete("s","u",null).text()).isEqualTo("{}");
        verify(budget,times(2)).tryConsumeCall(PROVIDER,100);
    }

    @Test void interruptedCallIsNeverRetriedAndPreservesInterrupt() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> client.complete("s","u",null)).isInstanceOf(AiException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(http,times(1)).send(any(),any());
        } finally { Thread.interrupted(); }
    }

    @Test void reportsTruncationAndAccountsForBilledReply() throws Exception {
        doReturn(response(200,RESPONSE.replace("\"stop\"", "\"length\"")))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThat(client.complete("s","u",null).truncated()).isTrue();
        verify(budget).recordSpend(PROVIDER,1000,300,null);
    }

    @Test void malformedResponseDoesNotExposeItsContents() throws Exception {
        doReturn(response(200,"private-provider-data {broken"))
                .when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> client.complete("s","u",null)).isInstanceOf(AiException.class)
                .hasMessage("AI provider returned invalid JSON.").hasNoCause();
        verify(http,times(1)).send(any(),any());
    }

    @Test void delegatesLegacyAnthropicWithoutChangingItsJsonPrefixContract() {
        props.getAi().setProvider("anthropic");
        when(anthropic.complete("s","u","{")).thenReturn(new ClaudeResponse("{}","claude",1,2,0,0,BigDecimal.ONE,"end_turn"));
        assertThat(client.complete("s","u","{").text()).isEqualTo("{}");
        verifyNoInteractions(http);
    }
}
