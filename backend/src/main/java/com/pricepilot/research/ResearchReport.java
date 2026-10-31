package com.pricepilot.research;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Research evidence is separate from observed prices and never contains a price field. */
public record ResearchReport(
        Identity identity, List<Claim> specs, List<Claim> strengths, List<Claim> weaknesses,
        List<Claim> ownerExperiences, List<Claim> reviewFindings, List<Claim> alternatives,
        List<Claim> bestFor, List<Claim> avoidIf,
        String verdict, List<String> limitations, List<Source> sources,
        Instant researchAt, Instant expiresAt, boolean cached, int searchesPerformed) {
    public record Request(Long listingId, @Size(max=160) String model,
                          @Size(max=160) String variant, boolean refresh) { }
    public record Identity(Long productId, Long listingId, String title, String brand, String model,
                           String variant, String referenceUrl, Map<String,String> identifiers,
                           boolean ambiguous, List<String> uncertainties) { }
    public record Citation(String sourceId, String url, String quote) { }
    public record Claim(String text, String category, String evidenceType, String confidence,
                        List<Citation> evidence) { }
    public record Source(String id, String title, String url, String sourceType, Instant retrievedAt,
                         String publishedAt, String excerpt, String contentKind) { }
    public ResearchReport asCached() {
        return new ResearchReport(identity,specs,strengths,weaknesses,ownerExperiences,reviewFindings,
                alternatives,bestFor,avoidIf,verdict,limitations,sources,researchAt,expiresAt,true,searchesPerformed);
    }
}
