package com.pricepilot.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static com.pricepilot.discovery.TavilyClient.SearchStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TavilyClientTest {
    private final AppProperties props = new AppProperties();
    private final BudgetGuard budget = mock(BudgetGuard.class);
    private final HttpClient http = mock(HttpClient.class);
    private TavilyClient client;

    @BeforeEach void setup() {
        props.getDiscovery().setApiKey("test-only-secret");
        props.getDiscovery().setMonthlyCallBudget(10);
        when(budget.tryConsumeCall(ApiProvider.TAVILY, 10)).thenReturn(true);
        client = new TavilyClient(props, budget, new ObjectMapper(), http);
    }

    @Test void notConfiguredAndBudgetExhaustedDoNotSendRequests() {
        props.getDiscovery().setApiKey("");
        assertThat(client.searchOutcome("hub", List.of("amazon.in")).status()).isEqualTo(NOT_CONFIGURED);
        props.getDiscovery().setApiKey("test-only-secret");
        when(budget.tryConsumeCall(ApiProvider.TAVILY, 10)).thenReturn(false);
        var outcome = client.searchOutcome("hub", List.of("amazon.in"));
        assertThat(outcome.status()).isEqualTo(BUDGET_EXHAUSTED);
        assertThat(outcome.warning()).contains("allowance");
        verifyNoInteractions(http);
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 429, 500})
    void providerFailureIsNotAnEmptySuccessfulSearch(int status) throws Exception {
        respond(status, "test-only-secret sensitive provider body");
        var outcome = client.searchOutcome("hub", List.of("amazon.in"));
        assertThat(outcome.status()).isEqualTo(PROVIDER_ERROR);
        assertThat(outcome.httpStatus()).isEqualTo(status);
        assertThat(outcome.hits()).isEmpty();
        assertThat(outcome.warning()).contains("HTTP " + status).doesNotContain("test-only-secret", "sensitive");
    }

    @Test void aSuccessfulEmptySearchHasNoFailureWarning() throws Exception {
        respond(200, "{\"results\":[]}");
        var outcome = client.searchOutcome("hub", List.of("amazon.in"));
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.hits()).isEmpty();
        assertThat(outcome.warning()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"not json", "{}", "null", "{\"results\":{}}"})
    void invalidResponseIsDistinctFromNoMatches(String body) throws Exception {
        respond(200, body);
        assertThat(client.searchOutcome("hub", List.of("amazon.in")).status()).isEqualTo(INVALID_RESPONSE);
    }

    @Test void timeoutAndConnectionErrorsAreReportedSeparately() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("sensitive text"));
        assertThat(client.searchOutcome("hub", List.of("amazon.in")).status()).isEqualTo(TIMEOUT);
        doThrow(new IOException("sensitive text")).when(http).send(any(), any(HttpResponse.BodyHandler.class));
        assertThat(client.searchOutcome("hub", List.of("amazon.in")).status()).isEqualTo(NETWORK_ERROR);
    }

    @Test void interruptionIsPreserved() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            assertThat(client.searchOutcome("hub", List.of("amazon.in")).status()).isEqualTo(INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void legacySearchStillReturnsHitsOnly() throws Exception {
        respond(200, "{\"results\":[{\"url\":\"https://www.amazon.in/dp/B000000001\",\"title\":\"USB-C hub\",\"score\":0.9}]}");
        assertThat(client.search("hub", List.of("amazon.in"))).singleElement()
                .satisfies(hit -> assertThat(hit.title()).isEqualTo("USB-C hub"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void respond(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
