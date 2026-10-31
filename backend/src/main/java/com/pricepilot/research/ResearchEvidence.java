package com.pricepilot.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic boundary between untrusted web/model text and a cited research report. */
final class ResearchEvidence {
    private static final Pattern MONEY=Pattern.compile("(?i)(?:[₹$€£]|\\b(?:inr|usd|eur|rupees?|dollars?|mrp|price|priced|pricing|emi|installments?)\\b|\\brs\\.)");
    private static final Set<String> REVIEW_DOMAINS=Set.of("rtings.com","notebookcheck.net","gsmarena.com","anandtech.com",
            "tomshardware.com","techradar.com","pcmag.com","theverge.com","arstechnica.com","trustedreviews.com");
    private ResearchEvidence(){}
    static String trim(String value,int max){if(value==null)return "";String text=value.replaceAll("\\s+"," ").trim();return text.length()>max?text.substring(0,max):text;}
    static String normalized(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");}
    static boolean containsPhrase(String text,String phrase){return (" "+normalized(text)+" ").contains(" "+normalized(phrase)+" ");}
    static boolean money(String text){return MONEY.matcher(text==null?"":text).find();}
    static String safeUrl(String raw){
        try{
            URI uri=URI.create(raw);String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||raw.length()>2048)return null;
            host=host.toLowerCase(Locale.ROOT);
            if(host.equals("localhost")||!host.contains(".")||host.endsWith(".local")||host.endsWith(".internal")
                    ||host.matches("[0-9.]+")||host.contains(":"))return null;
            String query=uri.getRawQuery();
            if(query!=null)query=Arrays.stream(query.split("&")).filter(p->!p.matches("(?i)(utm_[^=]*|gclid|fbclid)=.*")).sorted().reduce((a,b)->a+"&"+b).orElse("");
            return "https://"+host+(uri.getRawPath()==null||uri.getRawPath().isBlank()?"/":uri.getRawPath())
                    +(query==null||query.isBlank()?"":"?"+query);
        }catch(Exception e){return null;}
    }
    static boolean matches(ResearchReport.Identity identity,String title,String text){
        String evidence=title+" "+text;
        if(conflictingModelTitle(identity,title))return false;
        if(identity.model()!=null){
            if(!containsPhrase(evidence,identity.model()))return false;
        }else{
            Set<String> words=new HashSet<>(Arrays.asList(normalized(identity.title()).split(" ")));
            words.removeIf(w->(w.length()<3&&!w.matches(".*\\d.*"))||Set.of("with","for","and","the","new","india","buy").contains(w));
            if(words.size()<2)return false;
            long matched=words.stream().filter(w->containsPhrase(evidence,w)).count();
            if(matched<Math.max(2,Math.ceil(words.size()*0.65)))return false;
            // Numeric model tokens cannot be replaced by another generation.
            if(words.stream().filter(w->w.matches(".*\\d.*")).anyMatch(w->!containsPhrase(evidence,w)))return false;
        }
        return (identity.variant()==null||containsPhrase(evidence,identity.variant()))&&!conflictingCapacity(identity,title);
    }
    private static boolean conflictingModelTitle(ResearchReport.Identity identity,String sourceTitle){
        if(identity.model()==null||!containsPhrase(sourceTitle,identity.model()))return false;
        String known=normalized(identity.title()+" "+identity.variant());
        String source=normalized(sourceTitle);
        for(String modifier:List.of("pro","max","ultra","plus","mini","lite","fe","se")){
            if(containsPhrase(source,identity.model()+" "+modifier)&&!containsPhrase(known,modifier))return true;
        }
        return false;
    }
    static JsonNode excerptFallback(List<ResearchReport.Source> sources,ResearchReport.Identity identity,com.fasterxml.jackson.databind.ObjectMapper mapper){
        var result=mapper.createObjectNode();var claims=result.putArray("claims");
        for(var source:sources){
            for(String sentence:source.excerpt().split("(?<=[.!?])\\s+")){
                if(sentence.length()<20||sentence.length()>600||money(sentence)||!matches(identity,"",sentence))continue;
                String section=switch(source.sourceType()){case "official"->"specs";case "owner_discussion"->"ownerExperiences";
                    case "independent_review"->"reviewFindings";default->"reviewFindings";};
                if(source.sourceType().equals("other"))break;
                var claim=claims.addObject();claim.put("section",section);claim.put("category","Source excerpt");claim.put("text",sentence);
                claim.putArray("citations").addObject().put("sourceId",source.id()).put("quote",sentence);
                break;
            }
        }
        return result;
    }
    static String sourceType(String url,ResearchReport.Identity identity,ResearchProperties properties){
        String host=URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if(domain(host,"reddit.com"))return "owner_discussion";
        String brand=normalized(identity.brand());
        for(var entry:properties.getOfficialDomains().entrySet()){
            if(!brand.equals(normalized(entry.getKey()))&&!normalized(identity.title()).startsWith(normalized(entry.getKey())+" "))continue;
            if(entry.getValue().stream().anyMatch(d->exactHost(host,d)))return "official";
        }
        return REVIEW_DOMAINS.stream().anyMatch(d->exactHost(host,d))?"independent_review":"other";
    }
    private static boolean exactHost(String host,String configured){
        String normalized=host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");
        return normalized.equals(configured.toLowerCase(Locale.ROOT).replaceFirst("^www\\.",""));
    }
    static boolean domain(String host,String domain){return host.equalsIgnoreCase(domain)||host.toLowerCase(Locale.ROOT).endsWith("."+domain.toLowerCase(Locale.ROOT));}
    static List<ResearchReport.Source> sources(List<ResearchSearchProvider.Page> pages,ResearchReport.Identity identity,ResearchProperties properties){
        List<ResearchReport.Source> out=new ArrayList<>();Set<String> urls=new HashSet<>();List<Set<String>> fingerprints=new ArrayList<>();
        for(var page:pages){
            String url=safeUrl(page.url());String text=trim(page.text(),14000);
            if(url==null||text.length()<30||!matches(identity,page.title(),text)||!urls.add(url))continue;
            Set<String> fp=shingles(text);
            if(fingerprints.stream().anyMatch(old->similar(old,fp)))continue;
            fingerprints.add(fp);
            out.add(new ResearchReport.Source("source-"+out.size(),trim(page.title(),500),url,sourceType(url,identity,properties),
                    page.retrievedAt(),page.publishedAt(),text,page.contentKind()));
            if(out.size()>=properties.getMaxSources())break;
        }
        return List.copyOf(out);
    }
    private static Set<String> shingles(String content){
        String[] words=normalized(content).split(" ");Set<String> out=new HashSet<>();
        for(int i=0;i+4<=words.length;i++)out.add(String.join(" ",Arrays.copyOfRange(words,i,i+4)));
        return out;
    }
    private static boolean similar(Set<String> a,Set<String> b){
        if(a.isEmpty()||b.isEmpty())return a.equals(b);
        Set<String> shared=new HashSet<>(a);shared.retainAll(b);
        return shared.size()/(double)Math.max(a.size(),b.size())>=0.85;
    }
    static Map<String,List<ResearchReport.Claim>> claims(JsonNode root,List<ResearchReport.Source> sources,ResearchReport.Identity identity){
        Map<String,List<ResearchReport.Claim>> out=new LinkedHashMap<>();
        for(String key:List.of("specs","strengths","weaknesses","ownerExperiences","reviewFindings","alternatives","bestFor","avoidIf"))out.put(key,new ArrayList<>());
        Map<String,ResearchReport.Source> byId=new LinkedHashMap<>();sources.forEach(s->byId.put(s.id(),s));
        if(root==null||!root.path("claims").isArray())return out;
        Set<String> seen=new HashSet<>();int accepted=0;
        for(JsonNode claim:root.path("claims")){
            String section=claim.path("section").asText("");String text=trim(claim.path("text").asText(""),600);
            if(!out.containsKey(section)||text.length()<20||money(text)||!seen.add(section+":"+normalized(text)))continue;
            if(identity.model()==null&&!section.equals("alternatives")&&!matches(identity,"",text))continue;
            List<ResearchReport.Citation> refs=new ArrayList<>();Set<String> distinct=new HashSet<>();
            for(JsonNode citation:claim.path("citations")){
                var source=byId.get(citation.path("sourceId").asText(""));String quote=trim(citation.path("quote").asText(""),800);
                if(source==null||quote.length()<20||money(quote)||!distinct.add(source.id()))continue;
                if(!source.excerpt().contains(quote)||!quote.contains(text))continue;
                if(!matches(identity,"",quote))continue;
                if(!section.equals("alternatives")&&conflictingModelQuote(identity,quote))continue;
                if(!section.equals("alternatives")&&conflictingCapacity(identity,text))continue;
                refs.add(new ResearchReport.Citation(source.id(),source.url(),quote));
                if(refs.size()==4)break;
            }
            if(refs.isEmpty())continue;
            String type=byId.get(refs.get(0).sourceId()).sourceType();
            if(section.equals("specs")&&!type.equals("official"))continue;
            if(section.equals("ownerExperiences")&&!type.equals("owner_discussion"))continue;
            if(section.equals("reviewFindings")&&!type.equals("independent_review"))continue;
            String evidenceType=switch(type){case "official"->"official_specification";case "owner_discussion"->"owner_anecdote";
                case "independent_review"->"independent_review_finding";default->"source_statement";};
            String confidence=type.equals("owner_discussion")?"single_owner_anecdote":type.equals("official")?"official_source":"source_excerpt";
            // Multiple URLs are not sufficient: syndicated domains and duplicate threads cannot create consensus.
            if(type.equals("owner_discussion")&&refs.stream().allMatch(r->byId.get(r.sourceId()).sourceType().equals("owner_discussion"))
                    &&refs.stream().map(r->independentKey(r.url())).distinct().count()>=2)confidence="repeated_in_independent_threads";
            if(refs.stream().anyMatch(r->byId.get(r.sourceId()).contentKind().equals("search_snippet")))confidence="snippet_only";
            String category=trim(claim.path("category").asText("General"),50);if(money(category))category="General";
            out.get(section).add(new ResearchReport.Claim(text,category,evidenceType,confidence,List.copyOf(refs)));
            if(++accepted>=30)break;
        }
        return out;
    }
    private static boolean conflictingModelQuote(ResearchReport.Identity identity,String quote){
        if(identity.model()==null)return false;
        if(conflictingModelTitle(identity,quote))return true;
        var family=Pattern.compile("(?i)([a-z]+)[ -]?([0-9][a-z0-9-]*)").matcher(identity.model());
        if(!family.find())return false;
        String model=family.group().replaceAll("[ -]","").toLowerCase(Locale.ROOT);
        var mentioned=Pattern.compile("(?i)\\b"+Pattern.quote(family.group(1))+"[ -]?([0-9][a-z0-9-]*)\\b").matcher(quote);
        while(mentioned.find())if(!mentioned.group().replaceAll("[ -]","").toLowerCase(Locale.ROOT).equals(model))return true;
        return false;
    }
    private static boolean conflictingCapacity(ResearchReport.Identity identity,String text){
        Set<String> selected=new HashSet<>();
        String known=identity.variant()==null?identity.identifiers().getOrDefault("capacitiesInTitle",""):identity.variant();
        var capacities=Pattern.compile("(?i)\\b(\\d+)\\s*(GB|TB)\\b").matcher(known);
        while(capacities.find())selected.add(capacities.group(1)+capacities.group(2).toLowerCase(Locale.ROOT));
        if(selected.isEmpty())return false;
        var quoted=Pattern.compile("(?i)\\b(\\d+)\\s*(GB|TB)\\b").matcher(text);
        while(quoted.find())if(!selected.contains(quoted.group(1)+quoted.group(2).toLowerCase(Locale.ROOT)))return true;
        return false;
    }
    static String independentKey(String url){
        URI uri=URI.create(url);String host=uri.getHost().replaceFirst("^www\\.","");
        var thread=Pattern.compile("/comments/([^/]+)").matcher(uri.getPath());
        return domain(host,"reddit.com")&&thread.find()?"reddit:"+thread.group(1):host;
    }
}
