package com.pricepilot.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Asks Tavily where else a product is sold.
 *
 * <p>This is the only part of PricePilot that talks to a search engine, and it has exactly one job:
 * produce URLs. It does not read prices, titles for storage, availability, or anything else out of
 * search results. Everything a search engine returns about a product page is a cached impression of
 * that page at an unknown moment in the past, and the one thing this project promises is that every
 * stored price was read off a live page. Mixing the two would be invisible in the UI and would
 * quietly make the price history wrong.
 *
 * <p>The search is domain-restricted to hosts that already have extraction rules. There is no point
 * discovering a page on a retailer the engine cannot parse, and restricting the query improves the
 * results as a side effect: {@code include_domains} turns a general web search into a product search
 * across a known set of storefronts.
 *
 * <p>Entirely optional. With no {@code TAVILY_API_KEY} the method below reports itself unavailable,
 * cross-retailer discovery quietly does not happen, and everything else — tracking, history, alerts,
 * analysis — works exactly as before.
 */
@Component
public class TavilyClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyClient.class);

    /** Hard cap regardless of configuration, so a bad value cannot request a hundred results. */
    private static final int MAX_RESULTS_CEILING = 20;

    private final AppProperties props;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public TavilyClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper) {
        this(props, budget, mapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build());
    }

    TavilyClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper, HttpClient http) {
        this.props = props;
        this.budget = budget;
        this.mapper = mapper;
        this.http = http;
        if (!props.getDiscovery().isConfigured()) {
            log.info("TAVILY_API_KEY is not set. Cross-retailer search is disabled.");
        }
    }

    public boolean isAvailable() {
        return props.getDiscovery().isConfigured();
    }

    /** Backward-compatible URL discovery for the existing same-product workflow. */
    public List<SearchHit> search(String query, Collection<String> includeDomains) {
        return searchOutcome(query, includeDomains).hits();
    }

    /** A failed search is distinct from a successful search that found no results. */
    public SearchOutcome searchOutcome(String query, Collection<String> includeDomains) {
        if (!isAvailable()) return SearchOutcome.failed(SearchStatus.NOT_CONFIGURED, null);
        if (query == null || query.isBlank() || includeDomains == null || includeDomains.isEmpty()) {
            return SearchOutcome.failed(SearchStatus.INVALID_REQUEST, null);
        }
        if (Thread.currentThread().isInterrupted()) return SearchOutcome.failed(SearchStatus.INTERRUPTED, null);
        AppProperties.Discovery cfg = props.getDiscovery();
        if (!budget.tryConsumeCall(ApiProvider.TAVILY, cfg.getMonthlyCallBudget())) {
            return SearchOutcome.failed(SearchStatus.BUDGET_EXHAUSTED, null);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + "/search"))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(query, includeDomains, cfg), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                // Provider bodies may contain echoed credentials or user text; log only the status.
                log.warn("Search provider returned HTTP {}", response.statusCode());
                return SearchOutcome.failed(SearchStatus.PROVIDER_ERROR, response.statusCode());
            }
            return parse(response.body());
        } catch (HttpTimeoutException ex) {
            log.warn("Search provider request timed out");
            return SearchOutcome.failed(SearchStatus.TIMEOUT, null);
        } catch (IOException ex) {
            log.warn("Search provider connection failed");
            return SearchOutcome.failed(SearchStatus.NETWORK_ERROR, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return SearchOutcome.failed(SearchStatus.INTERRUPTED, null);
        } catch (RuntimeException ex) {
            log.warn("Search request could not be completed");
            return SearchOutcome.failed(SearchStatus.INVALID_REQUEST, null);
        }
    }

    private String requestBody(String query, Collection<String> domains, AppProperties.Discovery cfg) {
        ObjectNode root = mapper.createObjectNode();
        root.put("api_key", cfg.getApiKey());
        root.put("query", query);
        // "basic" rather than "advanced": advanced costs more credits per call and spends them on
        // reading page content, which is precisely the part being thrown away here.
        root.put("search_depth", "basic");
        root.put("max_results", Math.min(cfg.getMaxResults(), MAX_RESULTS_CEILING));
        root.put("include_answer", false);
        root.put("include_raw_content", false);
        root.put("include_images", false);

        ArrayNode include = root.putArray("include_domains");
        for (String domain : domains) {
            include.add(domain);
        }
        return root.toString();
    }

    private SearchOutcome parse(String body) {
        if (body == null || body.length() > 1_048_576) return SearchOutcome.failed(SearchStatus.INVALID_RESPONSE, 200);
        JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (Exception ex) {
            return SearchOutcome.failed(SearchStatus.INVALID_RESPONSE, 200);
        }
        JsonNode results = root == null ? null : root.get("results");
        if (results == null || !results.isArray()) return SearchOutcome.failed(SearchStatus.INVALID_RESPONSE, 200);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode node : results) {
            if (!node.path("url").isTextual()) continue;
            String url = node.path("url").asText("");
            if (url.isBlank() || url.length() > 2048) continue;
            hits.add(new SearchHit(url, node.path("title").asText(""), node.path("score").asDouble(0)));
            if (hits.size() == MAX_RESULTS_CEILING) break;
        }
        return new SearchOutcome(List.copyOf(hits), SearchStatus.SUCCESS, 200);
    }

    public enum SearchStatus {
        SUCCESS, NOT_CONFIGURED, BUDGET_EXHAUSTED, INVALID_REQUEST,
        PROVIDER_ERROR, TIMEOUT, NETWORK_ERROR, INTERRUPTED, INVALID_RESPONSE
    }

    public record SearchOutcome(List<SearchHit> hits, SearchStatus status, Integer httpStatus) {
        public static SearchOutcome failed(SearchStatus status, Integer httpStatus) {
            return new SearchOutcome(List.of(), status, httpStatus);
        }
        public boolean succeeded() { return status == SearchStatus.SUCCESS; }
        public String warning() {
            return switch (status) {
                case SUCCESS -> null;
                case NOT_CONFIGURED -> "Cross-store search is not configured; no stores were searched.";
                case BUDGET_EXHAUSTED -> "The monthly store-search allowance is used up. New store offers could not be searched.";
                case PROVIDER_ERROR -> "The store-search provider returned HTTP " + httpStatus + ". This search could not be completed.";
                case TIMEOUT -> "The store-search provider timed out. This search could not be completed.";
                case NETWORK_ERROR -> "The store-search provider could not be reached. This search could not be completed.";
                case INTERRUPTED -> "The store search was interrupted before it completed.";
                case INVALID_RESPONSE -> "The store-search provider returned an unreadable response. Results could not be checked.";
                case INVALID_REQUEST -> "Store search could not start because its request or configuration was invalid.";
            };
        }
    }

    /**
     * One raw search result, cut down to the three fields discovery uses.
     *
     * <p>The content snippet is dropped here rather than further downstream, so that no later code
     * can reach for it. Snippets are where a stale price would come from, and the cheapest way to
     * guarantee one never ends up in the database is to not carry it past this class.
     */
    public record SearchHit(String url, String title, double score) {
    }
}
