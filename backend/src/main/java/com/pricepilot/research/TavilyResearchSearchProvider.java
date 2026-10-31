package com.pricepilot.research;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Tavily search supports include_raw_content; snippets remain explicitly labelled if absent. */
@Component
public class TavilyResearchSearchProvider implements ResearchSearchProvider {
    private static final Logger log=LoggerFactory.getLogger(TavilyResearchSearchProvider.class);
    private final AppProperties props;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final HttpClient http;
    @Autowired public TavilyResearchSearchProvider(AppProperties props,BudgetGuard budget,ObjectMapper mapper){
        this(props,budget,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    TavilyResearchSearchProvider(AppProperties p,BudgetGuard b,ObjectMapper m,HttpClient h){props=p;budget=b;mapper=m;http=h;}
    public boolean available(){return props.getDiscovery().isConfigured();}
    public String name(){return "tavily";}
    public SearchResult search(String query,List<String> domains,Duration timeout){
        if(!available()) return SearchResult.failed("Research search is not configured; no web search was performed.");
        if(query==null||query.isBlank()||query.length()>500||timeout.isNegative()||timeout.isZero())
            return SearchResult.failed("Research search was skipped because its request or deadline was invalid.");
        if(Thread.currentThread().isInterrupted()) return SearchResult.failed("Research search was interrupted.");
        long started=System.nanoTime();
        try{
            var cfg=props.getDiscovery();
            URI base=URI.create(cfg.getBaseUrl());
            if(!"https".equalsIgnoreCase(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null
                    ||base.getQuery()!=null||base.getFragment()!=null) return SearchResult.failed("Research search endpoint configuration is invalid.");
            var body=mapper.createObjectNode(); body.put("query",query);body.put("search_depth","basic");
            body.put("max_results",5);body.put("include_answer",false);body.put("include_raw_content",true);
            body.put("include_images",false);
            if(domains!=null&&!domains.isEmpty()) body.set("include_domains",mapper.valueToTree(domains));
            URI endpoint=URI.create(cfg.getBaseUrl().replaceAll("/+$","")+"/search");
            long remaining=Math.min(timeout.toMillis(),Math.max(1,cfg.getTimeoutMs()));
            HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(Math.max(1,remaining)))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            if(!budget.tryConsumeCall(ApiProvider.TAVILY,cfg.getMonthlyCallBudget()))
                return SearchResult.failed("The shared monthly Tavily allowance is exhausted.");
            if(Thread.currentThread().isInterrupted()||System.nanoTime()-started>=timeout.toNanos())
                return SearchResult.failed("Research search reached its time limit before submission.");
            remaining=Math.min(Math.max(1,cfg.getTimeoutMs()),timeout.toMillis()-Duration.ofNanos(System.nanoTime()-started).toMillis());
            if(remaining<=0)return SearchResult.failed("Research search reached its time limit before submission.");
            request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(remaining))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            log.info("research_search provider=tavily status={} elapsed_ms={}",response.statusCode(),Duration.ofNanos(System.nanoTime()-started).toMillis());
            if(response.statusCode()!=200) return SearchResult.failed("Research search provider returned HTTP "+response.statusCode()+".");
            return parse(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();return SearchResult.failed("Research search was interrupted.");}
        catch(Exception e){log.warn("research_search provider=tavily failure={}",e.getClass().getSimpleName());
            return SearchResult.failed("Research search could not finish; its results are unavailable.");}
    }
    SearchResult parse(String body){
        if(body==null||body.length()>2_000_000) return SearchResult.failed("Research search returned an invalid response.");
        try{
            JsonNode root=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if(root==null||!root.path("results").isArray()) return SearchResult.failed("Research search returned an invalid response.");
            List<Page> pages=new ArrayList<>();
            for(JsonNode node:root.path("results")){
                String url=ResearchEvidence.safeUrl(node.path("url").asText(""));
                String title=node.path("title").asText("");
                String raw=node.path("raw_content").isTextual()?node.path("raw_content").asText():"";
                boolean full=!raw.isBlank();String text=full?raw:node.path("content").asText("");
                if(url==null||title.isBlank()||text.isBlank())continue;
                String date=node.path("published_date").asText(null);
                if(date!=null&&!date.matches("\\d{4}-\\d{2}-\\d{2}(?:[T ].{1,30})?"))date=null;
                pages.add(new Page(ResearchEvidence.trim(title,500),url,ResearchEvidence.trim(text,14000),
                        full?"page_content":"search_snippet",date,Instant.now()));
                if(pages.size()==5)break;
            }
            return new SearchResult(List.copyOf(pages),true,null);
        }catch(Exception e){return SearchResult.failed("Research search returned an unreadable response.");}
    }
}
