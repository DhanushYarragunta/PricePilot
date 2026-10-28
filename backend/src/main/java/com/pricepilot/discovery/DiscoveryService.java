package com.pricepilot.discovery;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.Product;
import com.pricepilot.repo.ListingRepository;
import com.pricepilot.scrape.UnsupportedUrlException;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the same product on other retailers.
 *
 * <p>Cross-retailer comparison is the feature that makes a price tracker worth opening: one price
 * with a history tells you when to buy, but four prices side by side tell you where. Getting there
 * means solving product matching, which is the genuinely hard part — "Samsung Galaxy S24 5G (Onyx
 * Black, 256 GB)" on one storefront is "SAMSUNG Galaxy S24 5G Smartphone 256GB Onyx" on the next,
 * and the two names share barely half their words.
 *
 * <p>The approach here is deliberately conservative, in three stages:
 *
 * <ol>
 *   <li><b>Search, restricted.</b> Tavily is asked only about hosts that already have extraction
 *       rules and are not already tracked for this product.
 *   <li><b>Score, then filter hard.</b> Candidates are scored on token overlap with the known title,
 *       weighting model numbers and capacities far above ordinary words, and anything below
 *       {@link #MIN_TITLE_SIMILARITY} is discarded outright.
 *   <li><b>Confirm by fetching.</b> What comes back is a list of leads. A lead becomes a tracked
 *       listing only once the scraping engine has fetched the page and read a real price from it.
 * </ol>
 *
 * <p>Nothing here auto-adds a listing. The candidates are returned for a person to confirm, because
 * the failure mode of silent product matching is the worst one this app has: a wrong match pollutes
 * the price history with a different product's prices, every statistic computed from that history
 * becomes nonsense, and nothing in the UI would show that anything was wrong.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /**
     * Below this score a candidate is thrown away. Tuned towards false negatives: missing a retailer
     * costs the user one price column, while matching the wrong product corrupts the history.
     */
    private static final double MIN_TITLE_SIMILARITY = 0.34;

    /** How many confirmed candidates to return at most. */
    private static final int MAX_CANDIDATES = 6;

    /** Query length cap. Long queries make search engines match on the wrong words. */
    private static final int MAX_QUERY_WORDS = 12;

    /**
     * Words that appear in storefront titles without distinguishing one product from another.
     * Removing them stops two unrelated phones scoring well on "smartphone", "with" and "online".
     */
    private static final Set<String> NOISE_WORDS = Set.of(
            "buy", "shop", "online", "best", "price", "prices", "offer", "offers", "sale", "deal",
            "deals", "latest", "new", "original", "genuine", "official", "india", "free", "delivery",
            "with", "and", "the", "for", "from", "size", "color", "colour", "model", "pack", "set",
            "smartphone", "mobile", "phone", "laptop", "device", "product", "features", "specs",
            "specification", "specifications", "reviews", "review", "rating", "at", "in", "of", "on");

    private final TavilyClient tavily;
    private final DomainRules rules;
    private final UrlCanonicalizer canonicalizer;
    private final ListingRepository listings;

    public DiscoveryService(TavilyClient tavily,
                            DomainRules rules,
                            UrlCanonicalizer canonicalizer,
                            ListingRepository listings) {
        this.tavily = tavily;
        this.rules = rules;
        this.canonicalizer = canonicalizer;
        this.listings = listings;
    }

    public boolean isAvailable() {
        return tavily.isAvailable();
    }

    /**
     * Looks for this product on retailers that are not already tracked for it.
     *
     * <p>Never throws. Discovery is additive — a product with one retailer is still a working tracked
     * product — so every failure path here ends in an empty list and a log line.
     */
    public List<DiscoveredListing> findCandidates(Product product) {
        if (!tavily.isAvailable()) {
            return List.of();
        }
        if (product == null || product.getTitle() == null || product.getTitle().isBlank()) {
            return List.of();
        }

        List<Listing> existing = listings.findByProductId(product.getId());
        Set<String> trackedHosts = new HashSet<>();
        Set<String> trackedHashes = new HashSet<>();
        for (Listing listing : existing) {
            String host = registrable(listing.getUrl());
            if (host != null) {
                trackedHosts.add(host);
            }
            if (listing.getUrlHash() != null) {
                trackedHashes.add(listing.getUrlHash());
            }
        }

        List<String> targetDomains = searchableDomains(trackedHosts);
        if (targetDomains.isEmpty()) {
            log.debug("Product {} is already tracked on every supported retailer", product.getId());
            return List.of();
        }

        String query = buildQuery(product);
        List<TavilyClient.SearchHit> hits = tavily.search(query, targetDomains);
        if (hits.isEmpty()) {
            log.debug("No discovery results for '{}'", query);
            return List.of();
        }

        Set<String> productTokens = significantTokens(product.getTitle());
        List<DiscoveredListing> candidates = new ArrayList<>();
        Set<String> seenHashes = new LinkedHashSet<>(trackedHashes);
        Set<String> seenHosts = new HashSet<>(trackedHosts);

        for (TavilyClient.SearchHit hit : hits) {
            UrlCanonicalizer.Canonical canonical;
            try {
                canonical = canonicalizer.canonicalize(hit.url());
            } catch (UnsupportedUrlException e) {
                // Search results include category pages, search pages and review articles. They are
                // not product pages, the canonicaliser knows it, and that is the right place for the
                // decision to live.
                log.trace("Discarding {}: {}", hit.url(), e.getMessage());
                continue;
            } catch (RuntimeException e) {
                log.debug("Discarding unparseable discovery result {}: {}", hit.url(), e.toString());
                continue;
            }

            if (!rules.isKnownHost(canonical.host())) {
                continue;
            }
            if (!seenHashes.add(canonical.hash())) {
                continue;
            }
            // One candidate per retailer. Several URLs for the same product on one storefront are
            // usually colour or storage variants, and picking between them is not a decision this
            // code can make correctly.
            if (!seenHosts.add(registrable(canonical.url()))) {
                continue;
            }

            double similarity = titleSimilarity(productTokens, hit.title());
            if (similarity < MIN_TITLE_SIMILARITY) {
                log.debug("Discarding {} — title similarity {} is below the {} threshold ('{}')",
                        canonical.host(), round(similarity), MIN_TITLE_SIMILARITY, hit.title());
                continue;
            }

            HostRule rule = canonical.rule();
            candidates.add(new DiscoveredListing(
                    canonical.url(),
                    canonical.host(),
                    rule.retailer(),
                    canonical.hash(),
                    hit.title(),
                    // Blended: mostly how well the titles agree, with the search engine's own
                    // ranking as a tie-breaker. Title agreement is the signal that matters, because
                    // search rank measures relevance to a query, not that two pages are the same
                    // product.
                    round(0.75 * similarity + 0.25 * clamp(hit.score()))));
        }

        candidates.sort(Comparator.comparingDouble(DiscoveredListing::relevance).reversed());
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }

        log.info("Discovery for product {} ('{}'): {} of {} results survived filtering",
                product.getId(), query, candidates.size(), hits.size());
        return List.copyOf(candidates);
    }

    // ------------------------------------------------------------------ query building

    /**
     * Turns a scraped product title into a search query.
     *
     * <p>Scraped titles are long and full of storefront padding. Trimming to the distinguishing
     * words — brand, model, capacity — searches better than passing the whole string, and keeps the
     * query short enough that the search engine weights every word.
     */
    String buildQuery(Product product) {
        List<String> words = new ArrayList<>();
        if (product.getBrand() != null && !product.getBrand().isBlank()) {
            words.add(product.getBrand().trim());
        }
        for (String word : product.getTitle().split("[\\s,()\\[\\]|/]+")) {
            String cleaned = word.replaceAll("[^A-Za-z0-9.+-]", "").trim();
            if (cleaned.length() < 2) {
                continue;
            }
            if (NOISE_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (words.stream().anyMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                continue;
            }
            words.add(cleaned);
            if (words.size() >= MAX_QUERY_WORDS) {
                break;
            }
        }
        return String.join(" ", words);
    }

    /** Supported registrable domains, minus the ones already tracked for this product. */
    private List<String> searchableDomains(Set<String> exclude) {
        Set<String> domains = new LinkedHashSet<>();
        for (HostRule rule : rules.all()) {
            String host = rule.host();
            if (host == null || host.isBlank()) {
                continue;
            }
            String registrable = registrable(host);
            if (registrable != null && !exclude.contains(registrable)) {
                domains.add(registrable);
            }
        }
        return List.copyOf(domains);
    }

    // ------------------------------------------------------------------ matching

    /**
     * How much two titles agree, from 0 to 1.
     *
     * <p>Weighted token overlap rather than plain Jaccard. Tokens containing a digit —
     * {@code 256gb}, {@code s24}, {@code 5g}, {@code a2589} — are worth four ordinary words, because
     * they are what actually distinguishes two otherwise identically-named products. Two listings
     * agreeing on "samsung galaxy black" while disagreeing on "128" versus "256" are a different
     * product at a different price, and weighting the numbers is what catches that.
     *
     * <p>Measured against the known product's tokens rather than symmetrically, since storefront
     * titles pad with words the original may not have and should not be penalised for them.
     */
    double titleSimilarity(Set<String> productTokens, String candidateTitle) {
        if (productTokens.isEmpty() || candidateTitle == null || candidateTitle.isBlank()) {
            return 0;
        }
        Set<String> candidateTokens = significantTokens(candidateTitle);
        if (candidateTokens.isEmpty()) {
            return 0;
        }

        double matched = 0;
        double total = 0;
        for (String token : productTokens) {
            double weight = weightOf(token);
            total += weight;
            if (candidateTokens.contains(token)) {
                matched += weight;
            }
        }
        return total == 0 ? 0 : matched / total;
    }

    private static double weightOf(String token) {
        return token.chars().anyMatch(Character::isDigit) ? 4.0 : 1.0;
    }

    /** Lowercased, punctuation-stripped tokens with the storefront padding removed. */
    static Set<String> significantTokens(String title) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : title.toLowerCase(Locale.ROOT).split("[^a-z0-9.+]+")) {
            String token = raw.replaceAll("^[.+]+|[.+]+$", "");
            if (token.length() < 2 || NOISE_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    // ------------------------------------------------------------------ small helpers

    /**
     * The registrable part of a host: {@code www.amazon.in} and {@code amazon.in} both become
     * {@code amazon.in}, so "already tracked on Amazon" is decided on the retailer rather than on
     * which hostname variant happened to be stored.
     *
     * <p>Only the {@code www.} prefix is stripped. Full public-suffix handling would need a suffix
     * list, and the supported retailers are all plain second- or third-level domains where this is
     * exactly right.
     */
    private static String registrable(String hostOrUrl) {
        if (hostOrUrl == null || hostOrUrl.isBlank()) {
            return null;
        }
        String host = hostOrUrl;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
        }
        host = host.toLowerCase(Locale.ROOT).trim();
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Exposed for the ops endpoint, which lists what discovery would search. */
    public List<String> supportedDomains() {
        return searchableDomains(Set.of());
    }
}
