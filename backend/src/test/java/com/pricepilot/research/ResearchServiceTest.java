package com.pricepilot.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.ai.AiClient;
import com.pricepilot.ai.AiException;
import com.pricepilot.ai.AiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchServiceTest {
    private final ResearchProperties props=new ResearchProperties();
    private final ResearchIdentity identities=mock(ResearchIdentity.class);
    private final ResearchCache cache=mock(ResearchCache.class);
    private final ResearchSearchProvider search=mock(ResearchSearchProvider.class);
    private final AiClient ai=mock(AiClient.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final Instant now=Instant.parse("2026-10-31T00:00:00Z");
    private final ResearchReport.Identity identity=new ResearchReport.Identity(1L,2L,"Acme AX-100 128GB Black","Acme","AX-100","128GB Black","https://retailer.example/item",Map.of(),false,List.of());
    private ResearchService service;
    private static final ResearchReport.Request REQUEST=new ResearchReport.Request(2L,"AX-100","128GB Black",false);
    private static final String QUOTE="The AX-100 128GB Black supports USB-C charging and a replaceable battery.";
    @BeforeEach void setup(){
        props.setOfficialDomains(Map.of("acme",List.of("acme.example")));
        when(identities.resolve(eq(1L),any())).thenReturn(identity);when(cache.find(anyString())).thenReturn(Optional.empty());
        when(search.available()).thenReturn(true);when(search.name()).thenReturn("tavily");
        when(search.search(anyString(),anyList(),any())).thenReturn(new ResearchSearchProvider.SearchResult(List.of(
                new ResearchSearchProvider.Page("Acme AX-100 128GB Black","https://acme.example/ax100",QUOTE,"page_content",null,now)),true,null));
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(),anyString(),isNull())).thenReturn(reply("{\"claims\":[],\"searchQueries\":[]}"));
        service=new ResearchService(props,identities,cache,search,ai,mapper,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void close(){service.close();}
    private AiResponse reply(String text){return new AiResponse(text,"configured-model",0,0,0,0,null,"stop");}
    private ResearchReport report(Instant expiry){return new ResearchReport(identity,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"Previous research",List.of(),
            List.of(new ResearchReport.Source("source-0","Acme AX-100","https://acme.example/ax100","official",now,null,QUOTE,"page_content")),now.minusSeconds(100),expiry,false,4);}
    @Test void freshCacheMakesNoProviderRequests(){
        when(cache.find(anyString())).thenReturn(Optional.of(report(now.plusSeconds(10))));
        assertThat(service.research(1L,REQUEST).cached()).isTrue();verifyNoInteractions(search,ai);
    }
    @Test void getDoesNotRefreshExpiredCacheOrChangeItsTimestamp(){
        var old=report(now.minusSeconds(1));when(cache.find(anyString())).thenReturn(Optional.of(old));
        var result=service.get(1L,REQUEST);
        assertThat(result.cached()).isTrue();assertThat(result.researchAt()).isEqualTo(old.researchAt());
        assertThat(result.limitations()).anyMatch(s->s.contains("expired"));verifyNoInteractions(search,ai);
    }
    @Test void expiredCacheIsRefreshedOnPost(){
        when(cache.find(anyString())).thenReturn(Optional.of(report(now.minusSeconds(1))));
        var result=service.research(1L,REQUEST);
        assertThat(result.cached()).isFalse();assertThat(result.researchAt()).isEqualTo(now);verify(cache).save(anyString(),any());
    }
    @Test void explicitRefreshIgnoresFreshCache(){
        when(cache.find(anyString())).thenReturn(Optional.of(report(now.plusSeconds(500))));
        assertThat(service.research(1L,new ResearchReport.Request(2L,"AX-100","128GB Black",true)).cached()).isFalse();
        verify(search,atLeastOnce()).search(anyString(),anyList(),any());
    }
    @Test void searchBudgetLimitsBothRounds(){
        props.setMaxSearches(5);
        when(ai.complete(anyString(),anyString(),isNull())).thenReturn(reply("""
                {"claims":[],"searchQueries":["AX-100 128GB Black battery test results","AX-100 128GB Black durability problems"]}
                """));
        var result=service.research(1L,REQUEST);
        assertThat(result.searchesPerformed()).isEqualTo(5);verify(search,times(5)).search(anyString(),anyList(),any());
        verify(ai,times(2)).complete(anyString(),anyString(),isNull());
    }
    @Test void zeroBudgetAndMissingProviderNeverInvokeAiOrSearch(){
        props.setMaxSearches(0);
        assertThat(service.research(1L,REQUEST).sources()).isEmpty();verify(search,never()).search(anyString(),anyList(),any());
        verifyNoInteractions(ai);
    }
    @Test void authFailureKeepsDeterministicCitedEvidenceAndAvoidsSecondAiCall(){
        when(ai.complete(anyString(),anyString(),isNull())).thenThrow(new AiException("secret provider body",false,401));
        var result=service.research(1L,REQUEST);
        assertThat(result.sources()).hasSize(1);assertThat(result.specs()).hasSize(1);
        assertThat(result.limitations()).anyMatch(s->s.contains("HTTP 401"));assertThat(result.limitations().toString()).doesNotContain("secret provider body");
        verify(ai,times(1)).complete(anyString(),anyString(),isNull());
    }
    @Test void failedRefreshPreservesPreviousReportAndDoesNotWriteCache(){
        var old=report(now.minusSeconds(1));when(cache.find(anyString())).thenReturn(Optional.of(old));
        when(search.search(anyString(),anyList(),any())).thenReturn(ResearchSearchProvider.SearchResult.failed("Provider unavailable"));
        var result=service.research(1L,REQUEST);
        assertThat(result.cached()).isTrue();assertThat(result.researchAt()).isEqualTo(old.researchAt());
        verify(cache,never()).save(anyString(),any());verify(ai,never()).complete(anyString(),anyString(),isNull());
    }
    @Test void inventedModelQuotesCannotBecomeClaims(){
        when(ai.complete(anyString(),anyString(),isNull())).thenReturn(reply("""
                {"claims":[{"section":"specs","text":"This model has an invented feature.","citations":[{"sourceId":"source-0","quote":"This model has an invented feature."}]}]}
                """));
        assertThat(service.research(1L,REQUEST).specs()).isEmpty();
    }
    @Test void aiContextIsBoundedWithoutTruncatingPersistedSources(){
        var sources=java.util.stream.IntStream.range(0,24).mapToObj(index->new ResearchReport.Source("source-"+index,
                "AX-100 reference","https://source"+index+".example/report","other",now,null,"evidence ".repeat(1500),"page_content")).toList();
        var context=ResearchService.contextSources(sources,identity);
        assertThat(context.stream().mapToInt(source->source.excerpt().length()).sum()).isLessThanOrEqualTo(48000);
        assertThat(sources.get(0).excerpt()).hasSize(13500);
        assertThat(context).hasSize(24);
    }

}
