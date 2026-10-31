package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.metrics.ScrapeMetrics;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.UrlGuard;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.usage.BudgetGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserActTelemetryTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private final AppProperties props = new AppProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BudgetGuard budget = mock(BudgetGuard.class);
    private final HttpClient http = mock(HttpClient.class);
    private final AtomicLong now = new AtomicLong();
    private final ScrapeMetrics metrics = new ScrapeMetrics(new SimpleMeterRegistry());
    private MockedStatic<UrlGuard> urlGuard;
    private BrowserActClient client;
    private HostRule amazon;

    @BeforeEach void setup() {
        urlGuard = mockStatic(UrlGuard.class);
        var rules = new DomainRules(props, new DefaultResourceLoader());
        rules.reload();
        amazon = rules.forHost("amazon.in");
        props.getBrowserAct().setApiKey("test-only-key");
        props.getBrowserAct().setWorkflowId("test-workflow");
        props.getBrowserAct().setMonthlyCallBudget(10);
        props.getBrowserAct().setTimeoutMs(10_000);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT, 10)).thenReturn(true);
        client = new BrowserActClient(props, mapper, budget, new UrlCanonicalizer(rules), http,
                now::get, ms -> now.addAndGet(ms * 1_000_000), metrics);
    }

    @AfterEach void cleanup() { urlGuard.close(); }

    private String task(boolean valid) {
        var product = mapper.createObjectNode();
        product.put("title", "USB C Hub").put("selling_price", 1609).put("currency", "INR")
                .put("availability", "in_stock").put("asin", valid ? "B07CR28HLL" : "B000000001")
                .put("url", URL).put("status", "success");
        var task = mapper.createObjectNode();
        task.put("id", "task-1").put("status", "finished");
        task.putObject("output").put("string", product.toString());
        return task.toString();
    }

    private HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public String body() { return body; }
            public HttpRequest request() { return null; }
            public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
            }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("https://api.browseract.com"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    @Test void metersImmediatelyBeforeEachSendAndCountsPollingSeparately() throws Exception {
        var calls = new AtomicInteger();
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
            assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(call);
            assertThat(metrics.snapshot().successfulRequests()).isZero();
            assertThat(metrics.snapshot().failedRequests()).isZero();
            HttpRequest request = invocation.getArgument(0);
            assertThat(request.method()).isEqualTo(call == 1 ? "POST" : "GET");
            return switch (call) {
                case 1 -> response(200, "{\"id\":\"task-1\"}");
                case 2, 3 -> response(200, "{\"id\":\"task-1\",\"status\":\"running\"}");
                default -> response(200, task(true));
            };
        });

        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.SUCCESS);
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(4);
        assertThat(metrics.snapshot().successfulRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().failedRequests()).isZero();
        verify(budget, times(1)).tryConsumeCall(ApiProvider.BROWSERACT, 10);
    }

    @Test void guardsAndBudgetRefusalsAreNotOutboundAttemptsOrFailures() {
        props.getBrowserAct().setApiKey("");
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        props.getBrowserAct().setApiKey("test-only-key");
        assertThat(client.extract("https://amazon.in.evil.example/dp/B07CR28HLL", amazon).submitted()).isFalse();
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT, 10)).thenReturn(false);
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        assertThat(metrics.snapshot().retailerRequests()).isZero();
        assertThat(metrics.snapshot().browserActApiCalls()).isZero();
        assertThat(metrics.snapshot().successfulRequests()).isZero();
        assertThat(metrics.snapshot().failedRequests()).isZero();
        verifyNoInteractions(http);
    }

    @Test void cancellationDuringBudgetReservationDoesNotCountASubmission() {
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT, 10)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        });
        try {
            assertThat(client.extract(URL, amazon).submitted()).isFalse();
            assertThat(metrics.snapshot().retailerRequests()).isZero();
            assertThat(metrics.snapshot().browserActApiCalls()).isZero();
            assertThat(metrics.snapshot().failedRequests()).isZero();
            verifyNoInteractions(http);
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 429, 500})
    void submissionApiErrorsCountOneFailedAttemptWithoutRetry(int status) throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(status, "private error"));
        assertThat(client.extract(URL, amazon).submitted()).isTrue();
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(1);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().successfulRequests()).isZero();
        verify(http, times(1)).send(any(), any());
    }

    @Test void invalidIdentityIsAFailedWorkflowDespiteSuccessfulHttpResponses() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"), response(200, task(false)));
        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(2);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().successfulRequests()).isZero();
    }

    @Test void pollingApiErrorCountsOneWorkflowFailure() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"), response(500, "private error"));
        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(2);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(1);
        verify(http, times(2)).send(any(), any());
    }

    @Test void pollingDeadlineAndTransportTimeoutCountFailedAttempts() throws Exception {
        props.getBrowserAct().setTimeoutMs(1000);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"),
                response(200, "{\"id\":\"task-1\",\"status\":\"running\"}"));
        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(1);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(2);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("test timeout"));
        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(metrics.snapshot().retailerRequests()).isEqualTo(2);
        assertThat(metrics.snapshot().failedRequests()).isEqualTo(2);
        assertThat(metrics.snapshot().browserActApiCalls()).isEqualTo(3);
        assertThat(metrics.snapshot().successfulRequests()).isZero();
    }
}
