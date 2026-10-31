package com.pricepilot.research;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.ai.AiClient;
import com.pricepilot.ai.AiException;
import com.pricepilot.ai.AiResponse;
import com.pricepilot.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ResearchService {
    private static final Logger log=LoggerFactory.getLogger(ResearchService.class);
    private final ResearchProperties properties;
    private final ResearchIdentity identities;
    private final ResearchCache cache;
    private final ResearchSearchProvider search;
    private final AiClient ai;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(24),r->{Thread t=new Thread(r,"product-research");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String,CompletableFuture<ResearchReport>> inFlight=new ConcurrentHashMap<>();
    @Autowired public ResearchService(ResearchProperties p,ResearchIdentity i,ResearchCache c,ResearchSearchProvider s,AiClient a,ObjectMapper m){
        this(p,i,c,s,a,m,Clock.systemUTC());
    }
    ResearchService(ResearchProperties p,ResearchIdentity i,ResearchCache c,ResearchSearchProvider s,AiClient a,ObjectMapper m,Clock clock){
        properties=p;identities=i;cache=c;search=s;ai=a;mapper=m;this.clock=clock;workers.allowCoreThreadTimeOut(true);
    }
    @PreDestroy public void close(){workers.shutdownNow();}

    /** GET never invokes an external provider, including when the cached report is expired. */
    public ResearchReport get(Long productId,ResearchReport.Request request){
        var identity=identities.resolve(productId,request);var stored=cache.find(ResearchIdentity.key(identity));
        if(stored.isPresent()){
            var report=stored.get().asCached();
            if(report.expiresAt().isAfter(clock.instant()))return report;
            return withLimit(report,"This cached research has expired. Refresh it before treating it as current.");
        }
        return empty(identity,List.of("Research has not been run for this model and variant. Start research to consult sources."),null,null,false,0);
    }
    public ResearchReport research(Long productId,ResearchReport.Request request){
        var identity=identities.resolve(productId,request);String key=ResearchIdentity.key(identity);
        var old=cache.find(key);
        if(!request.refresh()&&old.isPresent()&&old.get().expiresAt().isAfter(clock.instant()))return old.get().asCached();
        CompletableFuture<ResearchReport> pending=new CompletableFuture<>();var existing=inFlight.putIfAbsent(key,pending);
        if(existing!=null){
            try{return existing.get(properties.getTimeoutSeconds()+2L,TimeUnit.SECONDS).asCached();}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw ApiException.unavailable("Research was interrupted.");}
            catch(Exception e){throw ApiException.unavailable("Research for this product is already in progress. Try again shortly.");}
        }
        try{
            ResearchReport report=run(identity);
            // A failed refresh does not destroy a previously useful evidence report.
            if(report.sources().isEmpty()&&old.isPresent()){
                report=withLimit(old.get().asCached(),"The latest research attempt found no usable evidence. Previous research is preserved with its original timestamp.");
            }else cache.save(key,report);
            pending.complete(report);return report;
        }catch(RuntimeException e){pending.completeExceptionally(e);throw e;}
        finally{inFlight.remove(key,pending);}
    }
    private ResearchReport run(ResearchReport.Identity identity){
        long deadline=System.nanoTime()+Duration.ofSeconds(properties.getTimeoutSeconds()).toNanos();
        List<String> limitations=new ArrayList<>(identity.uncertainties());
        limitations.add("Research text is not a live price source. Current prices come only from separately validated BrowserAct observations.");
        if(!search.available()||properties.getMaxSearches()==0){
            limitations.add(!search.available()?"Tavily research search is not configured. No web sources were searched.":"The per-report search allowance is zero. No web sources were searched.");
            return empty(identity,limitations,clock.instant(),clock.instant().plus(Duration.ofMinutes(5)),false,0);
        }
        List<Query> queries=initialQueries(identity);List<ResearchSearchProvider.Page> pages=new ArrayList<>();
        List<ResearchReport.Source> sources=List.of();JsonNode extracted=null;int performed=0;
        AtomicBoolean fatalAi=new AtomicBoolean(false);SetQueries seen=new SetQueries();
        for(int round=0;round<2&&performed<properties.getMaxSearches()&&remaining(deadline)>0;round++){
            List<Callable<ResearchSearchProvider.SearchResult>> jobs=new ArrayList<>();
            for(Query query:queries){
                if(performed+jobs.size()>=properties.getMaxSearches()||!seen.add(query.text()))continue;
                jobs.add(()->search.search(query.text(),query.domains(),Duration.ofMillis(Math.max(1,Math.min(14000,remaining(deadline))))));
            }
            if(jobs.isEmpty())break;
            performed+=jobs.size();
            var results=await(jobs,deadline,15000,limitations,"Some research searches did not finish before their time limit.");
            for(var result:results){if(result.succeeded())pages.addAll(result.pages());else if(result.limitation()!=null)limitations.add(result.limitation());}
            sources=ResearchEvidence.sources(pages,identity,properties);
            if(sources.isEmpty()||!ai.isEnabled()||fatalAi.get())break;
            JsonNode next=extract(identity,sources,deadline,limitations,fatalAi);
            if(next!=null)extracted=next;
            if(round==1||next==null)break;
            queries=followUps(next,identity);
            if(queries.isEmpty())break;
        }
        if(!ai.isEnabled())limitations.add("AI extraction is unavailable. Retrieved source excerpts are shown without generated claims.");
        if(sources.isEmpty())limitations.add("No retrieved evidence could be matched conservatively to this product and variant.");
        if(sources.stream().noneMatch(s->s.sourceType().equals("official")))limitations.add("No matching official specification source was retrieved; official specifications are not established.");
        if(sources.stream().noneMatch(s->s.sourceType().equals("owner_discussion")))limitations.add("No matching Reddit owner evidence was retrieved; no claim about owner consensus is made.");
        if(sources.stream().anyMatch(s->s.contentKind().equals("search_snippet")))limitations.add("Some evidence is a search excerpt because full page content was unavailable.");
        limitations.add("Sources are deduplicated by URL and similar text; distinct threads do not prove distinct owners. Contradictory excerpts remain visible and need review.");
        if(extracted==null&&!sources.isEmpty()){
            extracted=ResearchEvidence.excerptFallback(sources,identity,mapper);
            limitations.add("These are matched source excerpts, not AI interpretations. Missing facts are left unknown.");
        }
        var claims=ResearchEvidence.claims(extracted,sources,identity);
        int count=claims.values().stream().mapToInt(List::size).sum();
        if(count==0)limitations.add("No factual claims passed excerpt/citation validation. Read the source excerpts; no buying recommendation is established.");
        String verdict=identity.ambiguous()?"Confirm the exact model and variant before deciding. The matched evidence below is a research starting point, not a verified buy recommendation.":
                count==0?"Evidence is insufficient for a supported buying recommendation.":
                "Review the cited strengths, weaknesses and owner reports against your needs. This report does not infer compatibility or a buy decision from missing evidence.";
        Instant now=clock.instant();
        log.info("product_research product_id={} sources={} claims={} searches={} provider={}",identity.productId(),sources.size(),count,performed,search.name());
        return new ResearchReport(identity,copy(claims,"specs"),copy(claims,"strengths"),copy(claims,"weaknesses"),
                copy(claims,"ownerExperiences"),copy(claims,"reviewFindings"),copy(claims,"alternatives"),
                copy(claims,"bestFor"),copy(claims,"avoidIf"),verdict,
                limitations.stream().distinct().toList(),sources,now,now.plus(Duration.ofHours(properties.getCacheHours())),false,performed);
    }
    private record Query(String text,List<String> domains){}
    private List<Query> initialQueries(ResearchReport.Identity identity){
        String anchor=ResearchEvidence.trim(ResearchIdentity.anchor(identity),300).replace("\"","");
        if(identity.model()!=null)anchor="\""+anchor+"\"";
        List<String> domains=properties.getOfficialDomains().entrySet().stream()
                .filter(e->ResearchEvidence.normalized(identity.brand()).equals(ResearchEvidence.normalized(e.getKey()))
                        ||ResearchEvidence.normalized(identity.title()).startsWith(ResearchEvidence.normalized(e.getKey())+" "))
                .flatMap(e->e.getValue().stream()).distinct().toList();
        return List.of(new Query(anchor+" official specifications documentation",domains),
                new Query(anchor+" long term owner experience common problems",List.of("reddit.com")),
                new Query(anchor+" independent review benchmark battery performance durability",List.of()),
                new Query(anchor+" common problems reliability versus alternatives",List.of()));
    }
    private List<Query> followUps(JsonNode result,ResearchReport.Identity identity){
        List<Query> queries=new ArrayList<>();
        for(JsonNode query:result.path("searchQueries")){
            String text=ResearchEvidence.trim(query.asText(""),480);
            if(text.length()<15||ResearchEvidence.money(text)||text.matches("(?is).*https?://.*")
                    ||!ResearchEvidence.containsPhrase(text,ResearchIdentity.anchor(identity)))continue;
            queries.add(new Query(text,List.of()));if(queries.size()==2)break;
        }
        return queries;
    }
    private JsonNode extract(ResearchReport.Identity identity,List<ResearchReport.Source> sources,long deadline,List<String> limitations,AtomicBoolean fatal){
        var context=mapper.createObjectNode();context.set("identity",mapper.valueToTree(identity));
        var excerpts=contextSources(sources,identity);
        if(java.util.stream.IntStream.range(0,sources.size()).anyMatch(i->excerpts.get(i).excerpt().length()<sources.get(i).excerpt().length()))
            limitations.add("AI reads bounded excerpts (at most 48,000 source characters per round); details outside those excerpts may be missing.");
        context.set("sources",mapper.valueToTree(excerpts));
        List<AiResponse> replies=await(List.of(()->{
            try{return ai.complete(ResearchPrompt.SYSTEM,context.toString(),null);}
            catch(AiException e){if(Integer.valueOf(401).equals(e.getHttpStatus())||Integer.valueOf(403).equals(e.getHttpStatus()))fatal.set(true);throw e;}
        }),deadline,18000,limitations,"AI research extraction was unavailable; source evidence is preserved.");
        if(replies.isEmpty())return null;
        AiResponse response=replies.get(0);
        if(response.truncated()||response.text()==null||response.text().length()>60000){limitations.add("AI research output was incomplete; unsupported claims were omitted.");return null;}
        try{return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.text());}
        catch(Exception e){limitations.add("AI research output could not be validated; source evidence is preserved.");return null;}
    }
    static List<ResearchReport.Source> contextSources(List<ResearchReport.Source> sources,ResearchReport.Identity identity){
        int cap=Math.min(6000,48000/Math.max(1,sources.size()));
        List<ResearchReport.Source> out=new ArrayList<>();
        for(var source:sources){
            String text=source.excerpt();
            if(text.length()>cap){
                String anchor=identity.model()==null?identity.title():identity.model();
                int match=text.toLowerCase(java.util.Locale.ROOT).indexOf(anchor.toLowerCase(java.util.Locale.ROOT));
                int start=match<0?0:Math.max(0,Math.min(match-300,text.length()-cap));
                text=text.substring(start,start+cap);
            }
            out.add(new ResearchReport.Source(source.id(),source.title(),source.url(),source.sourceType(),source.retrievedAt(),
                    source.publishedAt(),text,source.contentKind()));
        }
        return List.copyOf(out);
    }
    private <T> List<T> await(List<Callable<T>> tasks,long deadline,long stageMs,List<String> limitations,String failure){
        long stage=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(stageMs));
        List<Future<T>> pending=new ArrayList<>();List<T> result=new ArrayList<>();
        try{
            for(var task:tasks){if(remaining(stage)<=0)break;pending.add(workers.submit(task));}
            for(var future:pending){
                try{T value=future.get(Math.max(0,remaining(stage)),TimeUnit.MILLISECONDS);if(value!=null)result.add(value);}
                catch(ExecutionException e){limitations.add(e.getCause() instanceof AiException error?aiFailure(error):failure);}
                catch(java.util.concurrent.TimeoutException e){limitations.add(failure);}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();limitations.add(failure);}
        catch(java.util.concurrent.RejectedExecutionException e){limitations.add("Research workers are busy. Some work was skipped.");}
        finally{pending.forEach(f->{if(!f.isDone())f.cancel(true);});workers.purge();}
        return result;
    }
    private static String aiFailure(AiException error){
        if(Integer.valueOf(401).equals(error.getHttpStatus()))return "AI authentication was rejected (HTTP 401). Source excerpts are retained without AI interpretation.";
        if(Integer.valueOf(403).equals(error.getHttpStatus()))return "AI permission was rejected (HTTP 403). Source excerpts are retained without AI interpretation.";
        return "AI research could not complete; no unsupported claims were accepted.";
    }
    private static long remaining(long deadline){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    private static List<ResearchReport.Claim> copy(Map<String,List<ResearchReport.Claim>> claims,String key){return List.copyOf(claims.getOrDefault(key,List.of()));}
    private static final class SetQueries{private final LinkedHashSet<String> seen=new LinkedHashSet<>();boolean add(String text){return seen.add(ResearchEvidence.normalized(text));}}
    private static ResearchReport empty(ResearchReport.Identity identity,List<String> limits,Instant researched,Instant expiry,boolean cached,int searches){
        return new ResearchReport(identity,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
                "Evidence is insufficient for a supported buying recommendation.",List.copyOf(limits),List.of(),researched,expiry,cached,searches);
    }
    private static ResearchReport withLimit(ResearchReport report,String limitation){
        List<String> limits=new ArrayList<>(report.limitations());limits.add(limitation);
        return new ResearchReport(report.identity(),report.specs(),report.strengths(),report.weaknesses(),report.ownerExperiences(),
                report.reviewFindings(),report.alternatives(),report.bestFor(),report.avoidIf(),report.verdict(),limits.stream().distinct().toList(),report.sources(),
                report.researchAt(),report.expiresAt(),report.cached(),report.searchesPerformed());
    }
}
