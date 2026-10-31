package com.pricepilot.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchEvidenceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ResearchProperties props=new ResearchProperties();
    private final ResearchReport.Identity identity=new ResearchReport.Identity(1L,2L,"Acme AX-100 128GB Black","Acme",
            "AX-100","128GB Black","https://retailer.example/product/1",Map.of(),false,List.of());
    private static final String QUOTE="The AX-100 128GB Black supports USB-C charging and a replaceable battery.";
    ResearchEvidenceTest(){props.setOfficialDomains(Map.of("acme",List.of("acme.example")));}
    private ResearchSearchProvider.Page page(String url,String text){return new ResearchSearchProvider.Page("Acme AX-100 128GB Black",url,text,"page_content","2026-01-03",Instant.EPOCH);}
    private ObjectNode claim(String section,String text,String id,String quote){
        var root=mapper.createObjectNode();var claim=root.putArray("claims").addObject();
        claim.put("section",section).put("category","Hardware").put("text",text);
        claim.putArray("citations").addObject().put("sourceId",id).put("quote",quote);return root;
    }
    @Test void acceptsOfficialSpecOnlyWithVerbatimEvidenceAndRealSourceUrl(){
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",QUOTE)),identity,props);
        var result=ResearchEvidence.claims(claim("specs",QUOTE,"source-0",QUOTE),sources,identity);
        assertThat(result.get("specs")).hasSize(1);
        assertThat(result.get("specs").get(0).evidence().get(0).url()).isEqualTo("https://acme.example/ax100");
        assertThat(result.get("specs").get(0).confidence()).isEqualTo("official_source");
    }
    @Test void inventedCitationOrUnquotedClaimIsRejected(){
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",QUOTE)),identity,props);
        assertThat(ResearchEvidence.claims(claim("specs",QUOTE,"fabricated-source",QUOTE),sources,identity).get("specs")).isEmpty();
        assertThat(ResearchEvidence.claims(claim("specs","The product has a ten year warranty.","source-0",QUOTE),sources,identity).get("specs")).isEmpty();
        assertThat(ResearchEvidence.claims(claim("specs",QUOTE,"source-0",QUOTE+" Invented ending."),sources,identity).get("specs")).isEmpty();
    }
    @Test void copiedPagesAndTrackingUrlsDoNotBecomeCorroboration(){
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100?utm_source=one",QUOTE),
                page("https://acme.example/ax100?utm_source=two",QUOTE),page("https://syndicated.example/review",QUOTE)),identity,props);
        assertThat(sources).hasSize(1);
    }
    @Test void preservesPercentEncodedUrlsWithoutDoubleEncoding(){
        String url="https://acme.example/a%20b?x=a%2Fb&utm_source=test#section";
        assertThat(ResearchEvidence.safeUrl(url)).isEqualTo("https://acme.example/a%20b?x=a%2Fb");
        assertThat(ResearchEvidence.safeUrl(ResearchEvidence.safeUrl(url))).isEqualTo("https://acme.example/a%20b?x=a%2Fb");
    }
    @ParameterizedTest @ValueSource(strings={"https://localhost/product","https://127.0.0.1/product","http://acme.example/product","https://user@acme.example/product","https://acme.example:8443/product"})
    void unsafeSourceLinksAreNotReturned(String url){assertThat(ResearchEvidence.safeUrl(url)).isNull();}
    @Test void rejectsWrongGenerationAndCapacity(){
        assertThat(ResearchEvidence.matches(identity,"Acme AX-101 128GB Black",QUOTE.replace("AX-100","AX-101"))).isFalse();
        assertThat(ResearchEvidence.matches(identity,"Acme AX-100 256GB Black",QUOTE)).isFalse();
    }
    @Test void baseModelDoesNotSilentlyAcceptProReview(){
        var base=new ResearchReport.Identity(1L,2L,"Acme Phone 15 128GB Black","Acme","Phone 15","128GB Black","https://retailer.example/phone",Map.of(),false,List.of());
        assertThat(ResearchEvidence.matches(base,"Acme Phone 15 Pro 128GB Black review","A comparison with Phone 15 128GB Black.")).isFalse();
    }
    @Test void fullTitleMatchingNeverCollapsesIntoBrandOnlyResearch(){
        var unknown=new ResearchReport.Identity(1L,2L,"Acme AX-100 128GB Black","Acme",null,null,"https://retailer.example/phone",Map.of(),true,List.of());
        assertThat(ResearchEvidence.matches(unknown,"Acme products","Acme makes many electronics products for Indian consumers.")).isFalse();
    }
    @Test void redditStatementCannotBecomeOfficialSpecification(){
        var sources=ResearchEvidence.sources(List.of(page("https://www.reddit.com/r/phones/comments/abc/test",QUOTE)),identity,props);
        assertThat(ResearchEvidence.claims(claim("specs",QUOTE,"source-0",QUOTE),sources,identity).get("specs")).isEmpty();
        var owners=ResearchEvidence.claims(claim("ownerExperiences",QUOTE,"source-0",QUOTE),sources,identity).get("ownerExperiences");
        assertThat(owners).hasSize(1);assertThat(owners.get(0).confidence()).isEqualTo("single_owner_anecdote");
    }
    @ParameterizedTest @ValueSource(strings={"The AX-100 128GB Black costs ₹4999 today.","The AX-100 128GB Black has an MRP of 5999.","The AX-100 128GB Black has EMI payments of 399 each month."})
    void moneyNeverBecomesResearchClaims(String quote){
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",quote)),identity,props);
        assertThat(ResearchEvidence.claims(claim("specs",quote,"source-0",quote),sources,identity).get("specs")).isEmpty();
    }
    @Test void snippetEvidenceIsMarkedAsSuch(){
        var page=new ResearchSearchProvider.Page("Acme AX-100 128GB Black","https://acme.example/ax100",QUOTE,"search_snippet",null,Instant.EPOCH);
        var sources=ResearchEvidence.sources(List.of(page),identity,props);
        assertThat(ResearchEvidence.claims(claim("specs",QUOTE,"source-0",QUOTE),sources,identity).get("specs").get(0).confidence()).isEqualTo("snippet_only");
    }
    @Test void missingAiStillProducesCitedExcerptsWithoutInference(){
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",QUOTE)),identity,props);
        var fallback=ResearchEvidence.excerptFallback(sources,identity,mapper);
        assertThat(ResearchEvidence.claims(fallback,sources,identity).get("specs")).hasSize(1);
    }
    @Test void separateCommentsInSameThreadAreNotIndependentOwners(){
        assertThat(ResearchEvidence.independentKey("https://www.reddit.com/r/phones/comments/abc/post/comment1"))
                .isEqualTo(ResearchEvidence.independentKey("https://reddit.com/r/phones/comments/abc/post/comment2"));
    }
    @Test void sourceLevelMatchCannotCiteCompetitorAsOriginalSpecification(){
        String competitor="The AX-101 128GB Black has a ceramic body and wireless charging.";
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",QUOTE+" "+competitor)),identity,props);
        assertThat(sources).hasSize(1);
        assertThat(ResearchEvidence.claims(claim("specs",competitor,"source-0",competitor),sources,identity).get("specs")).isEmpty();
        assertThat(ResearchEvidence.claims(claim("specs",competitor,"source-0",QUOTE+" "+competitor),sources,identity).get("specs")).isEmpty();
    }
    @Test void useCaseClaimsNeedTheSameVerbatimModelEvidence(){
        String use="The AX-100 128GB Black is designed for outdoor use with gloved hands.";
        var sources=ResearchEvidence.sources(List.of(page("https://acme.example/ax100",use)),identity,props);
        assertThat(ResearchEvidence.claims(claim("bestFor",use,"source-0",use),sources,identity).get("bestFor")).hasSize(1);
    }

    @Test void shortGenerationNumbersRemainPartOfUnknownFullTitleIdentity(){
        var unknown=new ResearchReport.Identity(1L,2L,"Acme Phone 15 128GB Black","Acme",null,null,"https://retailer.example/phone",Map.of(),true,List.of());
        assertThat(ResearchEvidence.matches(unknown,"Acme Phone 14 128GB Black","Acme Phone 14 128GB Black has a durable design.")).isFalse();
        String original="Acme Phone 15 128GB Black has a replaceable battery.";
        String competitor="Acme Phone 14 128GB Black has a ceramic body.";
        var page=new ResearchSearchProvider.Page("Acme Phone 15 128GB Black","https://acme.example/compare",original+" "+competitor,"page_content",null,Instant.EPOCH);
        var sources=ResearchEvidence.sources(List.of(page),unknown,props);
        assertThat(ResearchEvidence.claims(claim("specs",competitor,"source-0",original+" "+competitor),sources,unknown).get("specs")).isEmpty();
    }

    @Test void manufacturerCommunitySubdomainDoesNotCertifyOfficialSpecifications(){
        var sources=ResearchEvidence.sources(List.of(page("https://community.acme.example/post",QUOTE)),identity,props);
        assertThat(sources.get(0).sourceType()).isEqualTo("other");
        assertThat(ResearchEvidence.claims(claim("specs",QUOTE,"source-0",QUOTE),sources,identity).get("specs")).isEmpty();
    }

}
