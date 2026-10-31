package com.pricepilot.research;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix="pricepilot.research")
public class ResearchProperties {
    private int maxSearches=6;
    private int timeoutSeconds=65;
    private int cacheHours=24;
    private int maxSources=16;
    private Map<String,List<String>> officialDomains=new LinkedHashMap<>(Map.ofEntries(
            Map.entry("apple",List.of("apple.com","support.apple.com")), Map.entry("samsung",List.of("samsung.com")),
            Map.entry("sony",List.of("sony.com","sony.co.in")), Map.entry("dell",List.of("dell.com")),
            Map.entry("hp",List.of("hp.com")), Map.entry("lenovo",List.of("lenovo.com")),
            Map.entry("oneplus",List.of("oneplus.com","oneplus.in")), Map.entry("xiaomi",List.of("mi.com")),
            Map.entry("logitech",List.of("logitech.com")), Map.entry("boat",List.of("boat-lifestyle.com"))));
    public int getMaxSearches(){return Math.max(0,Math.min(8,maxSearches));}
    public void setMaxSearches(int v){maxSearches=v;}
    public int getTimeoutSeconds(){return Math.max(5,Math.min(90,timeoutSeconds));}
    public void setTimeoutSeconds(int v){timeoutSeconds=v;}
    public int getCacheHours(){return Math.max(1,Math.min(168,cacheHours));}
    public void setCacheHours(int v){cacheHours=v;}
    public int getMaxSources(){return Math.max(1,Math.min(24,maxSources));}
    public void setMaxSources(int v){maxSources=v;}
    public Map<String,List<String>> getOfficialDomains(){return officialDomains;}
    public void setOfficialDomains(Map<String,List<String>> v){officialDomains=v==null?new LinkedHashMap<>():new LinkedHashMap<>(v);}
}
