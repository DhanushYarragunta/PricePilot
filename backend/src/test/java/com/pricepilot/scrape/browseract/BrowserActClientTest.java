package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.UrlGuard;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.usage.BudgetGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.core.io.DefaultResourceLoader;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
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
    private final DomainRules rules = new DomainRules(props, new DefaultResourceLoader());
    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer(rules);
    private HostRule amazon;
    private MockedStatic<UrlGuard> urlGuard;
    private BrowserActClient client;
    @BeforeEach void setup() {
        rules.reload();
        urlGuard = mockStatic(UrlGuard.class); // All URL normalization tests remain offline.
        amazon = rules.forHost("amazon.in");
        props.getBrowserAct().setApiKey("test-only-key");
        props.getBrowserAct().setWorkflowId("test-workflow");
        props.getBrowserAct().setMonthlyCallBudget(10);
        props.getBrowserAct().setTimeoutMs(1000);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,10)).thenReturn(true);
        client = new BrowserActClient(props,mapper,budget,canonicalizer,http,now::get,ms -> now.addAndGet(ms*1_000_000));
    }
    @AfterEach void cleanup() { urlGuard.close(); }
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
        var extraction=client.extract(URL, amazon);
        assertThat(extraction.outcome()).isEqualTo(ScrapeOutcome.SUCCESS);
        assertThat(extraction.submitted()).isTrue();
        var result=extraction.result();
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
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(result.submitted()).isTrue();
        assertThat(now.get()).isEqualTo(Duration.ofMillis(1000).toNanos());
        verify(http,times(2)).send(any(),any());
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500})
    void httpFailuresNeverResubmit(int code) throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(code,"untrusted error body"));
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.message()).isEqualTo("BrowserAct API returned HTTP " + code + ".");
        assertThat(result.submitted()).isTrue();
        verify(http,times(1)).send(any(),any());
        verify(budget,times(1)).tryConsumeCall(ApiProvider.BROWSERACT,10);
    }
    @Test void malformedApiResponseIsRejected() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"not json"));
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.submitted()).isTrue();
    }
    @Test void requestTimeoutDoesNotResubmit() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("timeout"));
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(result.submitted()).isTrue();
        verify(http,times(1)).send(any(),any());
    }
    @Test void interruptionIsPreserved() throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            var result = client.extract(URL, amazon);
            assertThat(result.result()).isNull();
            assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
            assertThat(result.submitted()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally { Thread.interrupted(); }
    }
    @Test void disabledOrExhaustedBudgetNeverSends() {
        props.getBrowserAct().setMonthlyCallBudget(0);
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        props.getBrowserAct().setMonthlyCallBudget(10);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,10)).thenReturn(false);
        var result = client.extract(URL, amazon);
        assertThat(result.submitted()).isFalse();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        verifyNoInteractions(http);
    }
    @Test void unlimitedConfigurationStillMetersOneSubmission() throws Exception {
        props.getBrowserAct().setMonthlyCallBudget(-1);
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT,-1)).thenReturn(true);
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200,"{\"id\":\"task-1\"}"), response(200,task(product()).toString()));
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.extract(URL, amazon).result()).isNotNull();
        verify(budget,times(1)).tryConsumeCall(ApiProvider.BROWSERACT,-1);
    }
    @Test void unsafeOrMismatchedAmazonUrlsNeverSpendBudget() {
        assertThat(client.extract("https://amazon.in.evil.example/dp/B07CR28HLL", amazon).submitted()).isFalse();
        assertThat(client.extract("https://user@amazon.in/dp/B07CR28HLL", amazon).submitted()).isFalse();
        verifyNoInteractions(http,budget);
    }
    @ParameterizedTest @ValueSource(strings={"canceled","failed","paused","pausing","unknown"})
    void terminalAndInvalidStatusesStopPolling(String status) throws Exception {
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,"{\"id\":\"task-1\"}"),
                response(200,"{\"id\":\"task-1\",\"status\":\""+status+"\"}"));
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.submitted()).isTrue();
        verify(http,times(2)).send(any(),any());
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            amazon.in | https://amazon.in/USB-Hub/gp/product/B07CR28HLL?tag=test | https://www.amazon.in/dp/B07CR28HLL
            flipkart.com | https://www.flipkart.com/phone/p/itm123?utm_source=test&pid=MOB123&lid=LST123 | https://www.flipkart.com/phone/p/itm123?pid=MOB123
            croma.com | https://www.croma.com/phone/p/123456?utm_source=test | https://www.croma.com/phone/p/123456
            reliancedigital.in | https://www.reliancedigital.in/phone/p/123456?ref=home | https://www.reliancedigital.in/phone/p/123456
            vijaysales.com | https://www.vijaysales.com/phone/123456/?utm_source=test | https://www.vijaysales.com/phone/123456
            tatacliq.com | https://www.tatacliq.com/phone/p-mp00000123456?utm_source=test | https://www.tatacliq.com/phone/p-mp00000123456
            jiomart.com | https://www.jiomart.com/p/electronics/phone/123456?utm_source=test | https://www.jiomart.com/p/electronics/phone/123456
            snapdeal.com | https://www.snapdeal.com/product/phone/123456?utm_source=test | https://www.snapdeal.com/product/phone/123456
            myntra.com | https://www.myntra.com/shoes/brand/model/123456/buy/?utm_source=test | https://www.myntra.com/shoes/brand/model/123456/buy
            ajio.com | https://www.ajio.com/shoes/p/123456?utm_source=test | https://www.ajio.com/shoes/p/123456
            nykaa.com | https://www.nykaa.com/cream/p/123456?utm_source=test | https://www.nykaa.com/cream/p/123456
            sangeethamobiles.com | https://www.sangeethamobiles.com/phone/123456?utm_source=test | https://www.sangeethamobiles.com/phone/123456
            poorvika.com | https://www.poorvika.com/phone/123456?utm_source=test | https://www.poorvika.com/phone/123456
            """)
    void everyRetailerSubmitsTheSameWorkflowAndCanonicalUrlOnce(String domain, String requestedUrl,
                                                              String canonicalUrl) throws Exception {
        props.getBrowserAct().setWorkflowId(" shared-workflow ");
        ObjectNode p = product();
        p.put("url", canonicalUrl);
        if (!"amazon.in".equals(domain)) p.remove("asin");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"), response(200, task(p).toString()));

        var result = client.extract(requestedUrl, rules.forHost(domain));

        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.SUCCESS);
        assertThat(result.submitted()).isTrue();
        assertThat(result.result().sellingPrice()).isEqualByComparingTo("1609.50");
        assertThat(result.result().asin()).isEqualTo("amazon.in".equals(domain) ? "B07CR28HLL" : null);
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(requests.capture(), any());
        assertThat(requests.getAllValues()).extracting(HttpRequest::method).containsExactly("POST", "GET");
        HttpRequest submission = requests.getAllValues().get(0);
        assertThat(submission.uri().toString()).isEqualTo("https://api.browseract.com/v2/workflow/run-task");
        assertThat(submission.headers().firstValue("Authorization")).contains("Bearer test-only-key");
        assertThat(submission.headers().firstValue("Content-Type")).contains("application/json");
        ObjectNode expected = mapper.createObjectNode();
        expected.put("workflow_id", "shared-workflow");
        expected.putArray("input_parameters").addObject().put("name", "url").put("value", canonicalUrl);
        assertThat(mapper.readTree(requestBody(submission))).isEqualTo(expected);
        verify(budget, times(1)).tryConsumeCall(ApiProvider.BROWSERACT, 10);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingWorkflowNeverSubmitsAnyRetailer(String workflowId) {
        props.getBrowserAct().setWorkflowId(workflowId);
        assertThat(client.isConfigured()).isFalse();
        var amazonResult = client.extract(URL, amazon);
        var cromaResult = client.extract("https://www.croma.com/phone/p/123456", rules.forHost("croma.com"));
        assertThat(amazonResult.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(amazonResult.submitted()).isFalse();
        assertThat(cromaResult.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(cromaResult.submitted()).isFalse();
        verifyNoInteractions(http, budget);
    }

    @Test void validatesKeyAndTimeoutBeforeConsumingBudget() {
        props.getBrowserAct().setApiKey("bad\nheader");
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        props.getBrowserAct().setApiKey("");
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        props.getBrowserAct().setApiKey("test-only-key");
        props.getBrowserAct().setTimeoutMs(0);
        assertThat(client.extract(URL, amazon).submitted()).isFalse();
        verifyNoInteractions(http, budget);
    }

    @Test void canonicalizerRejectionNeverConsumesBudget() {
        urlGuard.when(() -> UrlGuard.assertFetchable(any())).thenThrow(
                new com.pricepilot.scrape.UnsupportedUrlException("untrusted URL details"));
        var result = client.extract(URL, amazon);
        assertThat(result.submitted()).isFalse();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.message()).doesNotContain("untrusted URL details");
        verifyNoInteractions(http, budget);
    }

    @Test void mismatchedNonAmazonRuleNeverConsumesBudget() {
        var result = client.extract("https://www.croma.com/phone/p/123456", rules.forHost("flipkart.com"));
        assertThat(result.submitted()).isFalse();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        verifyNoInteractions(http, budget);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.croma.com:8443/phone/p/123456",
            "https://www.croma.com:80/phone/p/123456",
            "http://www.croma.com:8080/phone/p/123456",
            "http://www.croma.com:443/phone/p/123456"
    })
    void nonDefaultRequestPortsNeverConsumeBudget(String url) {
        var result = client.extract(url, rules.forHost("croma.com"));
        assertThat(result.submitted()).isFalse();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        verifyNoInteractions(http, budget);
    }

    @Test void preexistingInterruptionNeverConsumesBudget() {
        Thread.currentThread().interrupt();
        try {
            var result = client.extract(URL, amazon);
            assertThat(result.submitted()).isFalse();
            assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(http, budget);
        } finally { Thread.interrupted(); }
    }

    @Test void expiredDeadlineDuringBudgetReservationNeverSubmits() {
        when(budget.tryConsumeCall(ApiProvider.BROWSERACT, 10)).thenAnswer(invocation -> {
            now.addAndGet(Duration.ofMillis(1000).toNanos());
            return true;
        });
        var result = client.extract(URL, amazon);
        assertThat(result.submitted()).isFalse();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        verifyNoInteractions(http);
    }

    @Test void returnedPriceAfterDeadlineIsRejected() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(200, "{\"id\":\"task-1\"}"))
                .thenAnswer(invocation -> {
                    now.addAndGet(Duration.ofMillis(1000).toNanos());
                    return response(200, task(product()).toString());
                });
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        assertThat(result.submitted()).isTrue();
    }

    @Test void invalidFinishedProductIsNoPriceWithoutResubmission() throws Exception {
        ObjectNode p = product();
        p.put("status", "blocked");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"), response(200, task(p).toString()));
        var result = client.extract(URL, amazon);
        assertThat(result.result()).isNull();
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(result.submitted()).isTrue();
        verify(http, times(2)).send(any(), any());
        verify(budget, times(1)).tryConsumeCall(ApiProvider.BROWSERACT, 10);
    }

    @Test void mismatchedTaskIdStopsPolling() throws Exception {
        ObjectNode t = task(product());
        t.put("id", "different-task");
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(
                response(200, "{\"id\":\"task-1\"}"), response(200, t.toString()));
        assertThat(client.extract(URL, amazon).outcome()).isEqualTo(ScrapeOutcome.ERROR);
        verify(http, times(2)).send(any(), any());
    }

    @Test void provider404DoesNotMeanRetailerPageIsGone() throws Exception {
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(404, "not a retailer response"));
        var result = client.extract(URL, amazon);
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.message()).isEqualTo("BrowserAct API returned HTTP 404.");
    }

    private static String requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> body = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer value) {
                byte[] chunk = new byte[value.remaining()];
                value.get(chunk);
                bytes.writeBytes(chunk);
            }
            public void onError(Throwable error) { body.completeExceptionally(error); }
            public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return body.get(1, TimeUnit.SECONDS);
    }
}
