package com.pricepilot.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TavilyResearchSearchProviderTest {
    private final AppProperties props=new AppProperties();
    private final BudgetGuard budget=mock(BudgetGuard.class);
    private final HttpClient http=mock(HttpClient.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private TavilyResearchSearchProvider provider;
    @BeforeEach void setup(){
        props.getDiscovery().setApiKey("test-only-key");props.getDiscovery().setBaseUrl("https://api.tavily.com");
        when(budget.tryConsumeCall(eq(ApiProvider.TAVILY),anyInt())).thenReturn(true);
        provider=new TavilyResearchSearchProvider(props,budget,mapper,http);
    }
    @SuppressWarnings("unchecked") private HttpResponse<String> response(int status,String body){
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);return response;
    }
    @Test void requestsRawPageContentWithoutNativeModelToolOrPriceAnswer() throws Exception{
        var response=response(200,"{\"results\":[]}");
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(provider.search("AX-100 official specifications",List.of("acme.example"),Duration.ofSeconds(10)).succeeded()).isTrue();
        ArgumentCaptor<HttpRequest> sent=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(sent.capture(),any());
        assertThat(sent.getValue().uri()).isEqualTo(URI.create("https://api.tavily.com/search"));
        var body=mapper.readTree(body(sent.getValue()));
        assertThat(body.path("include_raw_content").asBoolean()).isTrue();assertThat(body.path("include_answer").asBoolean()).isFalse();
        assertThat(body.path("include_domains").get(0).asText()).isEqualTo("acme.example");
        verify(budget).tryConsumeCall(eq(ApiProvider.TAVILY),anyInt());
    }
    @Test void pageContentAndSnippetFallbackAreDistinguished(){
        var result=provider.parse("""
            {"results":[
              {"url":"https://acme.example/a","title":"Model one","raw_content":"This is full source text for product model one."},
              {"url":"https://review.example/b","title":"Model two","content":"This is only a search snippet for product model two."}]}
            """);
        assertThat(result.pages()).hasSize(2);assertThat(result.pages().get(0).contentKind()).isEqualTo("page_content");
        assertThat(result.pages().get(1).contentKind()).isEqualTo("search_snippet");assertThat(result.pages().get(0).retrievedAt()).isNotNull();
    }
    @Test void exhaustedBudgetNeverSends(){
        when(budget.tryConsumeCall(eq(ApiProvider.TAVILY),anyInt())).thenReturn(false);
        assertThat(provider.search("AX-100 specs",List.of(),Duration.ofSeconds(2)).succeeded()).isFalse();verifyNoInteractions(http);
    }
    @Test void providerErrorDoesNotExposeBodyOrKey() throws Exception{
        var response=response(401,"secret echoed test-only-key");
        when(http.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var result=provider.search("AX-100 specs",List.of(),Duration.ofSeconds(2));
        assertThat(result.limitation()).contains("401").doesNotContain("secret","test-only-key");
        verify(http,times(1)).send(any(),any());
    }
    @Test void invalidEndpointDoesNotSendCredentials(){
        props.getDiscovery().setBaseUrl("http://api.tavily.com");
        assertThat(provider.search("AX-100 specs",List.of(),Duration.ofSeconds(2)).succeeded()).isFalse();verifyNoInteractions(http,budget);
    }
    @Test void malformedResponseAndUnsafeUrlsAreRejected(){
        assertThat(provider.parse("not-json").succeeded()).isFalse();
        assertThat(provider.parse("{\"results\":[{\"url\":\"javascript:alert(1)\",\"title\":\"Bad\",\"content\":\"bad source\"}]}").pages()).isEmpty();
    }
    private static String body(HttpRequest request)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompletableFuture<String> complete=new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.writeBytes(b);}
            public void onError(Throwable error){complete.completeExceptionally(error);}
            public void onComplete(){complete.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return complete.get(1,TimeUnit.SECONDS);
    }
}
