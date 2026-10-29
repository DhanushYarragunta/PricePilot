package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserActClientTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private final AppProperties props = new AppProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BudgetGuard budget = mock(BudgetGuard.class);
    private final HttpClient http = mock(HttpClient.class);
    private final AtomicLong now = new AtomicLong();
    private BrowserActClient client;
    @BeforeEach void setup() {
        props.getBrowserAct().setApiKey("test-only-key");
        props.getBrowserAct().setWorkflowId("test-workflow");
        props.getBrowserAct().setMonthlyCallBudget(10);
        props.getBrowserAct().setTimeoutMs(1000);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,10)).thenReturn(true);
        client = new BrowserActClient(props,mapper,budget,http,now::get,ms -> now.addAndGet(ms*1_000_000));
    }
    private ObjectNode product() {
        ObjectNode p=mapper.createObjectNode();
        p.put("title","Honeywell USB C Hub"); p.put("selling_price","₹1,609.50"); p.put("mrp",3999);
        p.put("currency","INR"); p.put("availability","in_stock"); p.put("asin","B07CR28HLL");
        p.put("url",URL); p.put("status","success"); return p;
    }
    private ObjectNode task(ObjectNode product) {
        ObjectNode t=mapper.createObjectNode();t.put("id","task-1");t.put("status","finished");
        t.putObject("output").put("string", product.toString());return t;
    }
    @SuppressWarnings("unchecked") private HttpResponse<String> response(int status,String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public String body() { return body; }
            public HttpRequest request() { return null; }
            public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a,b)->true); }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("https://api.browseract.com"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
    @Test void usesDocumentedTaskEndpointAndOutputString() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{\"id\":\"task-1\"}"),response(200,task(product()).toString()));
        var result=client.extract(URL).orElseThrow();
        assertThat(result.sellingPrice()).isEqualByComparingTo("1609.50");
        assertThat(result.mrp()).isEqualByComparingTo("3999");
        ArgumentCaptor<HttpRequest> requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(2)).send(requests.capture(),any());
        assertThat(requests.getAllValues().get(0).uri().getPath()).isEqualTo("/v2/workflow/run-task");
        assertThat(requests.getAllValues().get(0).method()).isEqualTo("POST");
        assertThat(requests.getAllValues().get(1).uri().toString()).isEqualTo("https://api.browseract.com/v2/workflow/get-task?task_id=task-1");
        assertThat(requests.getValue().headers().firstValue("Authorization")).contains("Bearer test-only-key");
        verify(budget,times(1)).tryConsumeCall(ApiProvider.BROWSERACT,10);
    }
    @Test void totalDeadlineIncludesPollWaiting() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{\"id\":\"task-1\"}"),response(200,"{\"id\":\"task-1\",\"status\":\"running\"}"));
        assertThat(client.extract(URL)).isEmpty();
        assertThat(now.get()).isEqualTo(Duration.ofMillis(1000).toNanos());
        verify(http,times(2)).send(any(),any());
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500})
    void httpFailuresNeverResubmit(int code) throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(code,"untrusted error body"));
        assertThat(client.extract(URL)).isEmpty();verify(http,times(1)).send(any(),any());
    }
    @Test void malformedApiResponseIsRejected() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"not json"));
        assertThat(client.extract(URL)).isEmpty();
    }
    @Test void requestTimeoutFallsBack() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("timeout"));
        assertThat(client.extract(URL)).isEmpty();
    }
    @Test void interruptionIsPreserved() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try { assertThat(client.extract(URL)).isEmpty();assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
    @Test void disabledOrExhaustedBudgetNeverSends() {
        props.getBrowserAct().setMonthlyCallBudget(0);
        assertThat(client.extract(URL)).isEmpty();
        props.getBrowserAct().setMonthlyCallBudget(10);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,10)).thenReturn(false);
        assertThat(client.extract(URL)).isEmpty();verifyNoInteractions(http);
    }
    @Test void unlimitedConfigurationStillMetersOneSubmission() throws Exception {
        props.getBrowserAct().setMonthlyCallBudget(-1);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,-1)).thenReturn(true);
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200,"{\"id\":\"task-1\"}"), response(200,task(product()).toString()));
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.extract(URL)).isPresent();
        verify(budget,times(1)).tryConsumeCall(ApiProvider.BROWSERACT,-1);
    }
    @Test void nonAmazonUrlsNeverSendCredentials() {
        assertThat(client.extract("https://amazon.in.evil.example/dp/B07CR28HLL")).isEmpty();
        assertThat(client.extract("https://user@amazon.in/dp/B07CR28HLL")).isEmpty();
        verifyNoInteractions(http,budget);
    }
    @ParameterizedTest @ValueSource(strings={"canceled","failed","paused","pausing","unknown"})
    void terminalAndInvalidStatusesStopPolling(String status) throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{\"id\":\"task-1\"}"),
                response(200,"{\"id\":\"task-1\",\"status\":\""+status+"\"}"));
        assertThat(client.extract(URL)).isEmpty();verify(http,times(2)).send(any(),any());
    }
    @Test void neverSwapsSellingPriceAndMrp() {
        ObjectNode p=product();p.put("selling_price",3999);p.put("mrp",1609);
        assertThat(client.parseOutput(task(p),URL)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"selling_price","title","currency","url","asin","availability","status"})
    void missingRequiredFieldsRejectResult(String field) {
        ObjectNode p=product();p.remove(field);assertThat(client.parseOutput(task(p),URL)).isEmpty();
    }
    @Test void rejectsWrongIdentityCurrencyAndFailedExtraction() {
        ObjectNode p=product();p.put("asin","B000000001");assertThat(client.parseOutput(task(p),URL)).isEmpty();
        p=product();p.put("url","https://www.amazon.in/dp/B000000001");assertThat(client.parseOutput(task(p),URL)).isEmpty();
        p=product();p.put("currency","USD");assertThat(client.parseOutput(task(p),URL)).isEmpty();
        p=product();p.put("status","blocked");assertThat(client.parseOutput(task(p),URL)).isEmpty();
    }
    @Test void rejectsMalformedOutputAndMultipleProducts() {
        ObjectNode t=task(product());((ObjectNode)t.get("output")).put("string","{broken");
        assertThat(client.parseOutput(t,URL)).isEmpty();
        ((ObjectNode)t.get("output")).put("string","["+product()+","+product()+"]");
        assertThat(client.parseOutput(t,URL)).isEmpty();
    }
    @Test void optionalMrpAndOutOfStockArePreservedWithoutInventingDiscount() {
        ObjectNode p=product();p.putNull("mrp");p.put("availability","out_of_stock");
        var result=client.parseOutput(task(p),URL).orElseThrow();
        assertThat(result.mrp()).isNull();assertThat(result.inStock()).isFalse();
    }
}
