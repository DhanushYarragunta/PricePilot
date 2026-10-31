package com.pricepilot.research;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Search/page evidence adapter. It cannot produce or store observed retailer prices. */
public interface ResearchSearchProvider {
    boolean available();
    String name();
    SearchResult search(String query, List<String> domains, Duration timeout);
    record Page(String title,String url,String text,String contentKind,String publishedAt,Instant retrievedAt) { }
    record SearchResult(List<Page> pages, boolean succeeded, String limitation) {
        public static SearchResult failed(String message){return new SearchResult(List.of(),false,message);}
    }
}
