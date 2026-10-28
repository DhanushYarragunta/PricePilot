package com.pricepilot.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.usage.BudgetGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public TavilyClient(AppProperties props, BudgetGuard budget, ObjectMapper mapper) {
        this.props = props;
        this.budget = budget;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (!props.getDiscovery().isConfigured()) {
            log.info("TAVILY_API_KEY is not set. Cross-retailer discovery is off; products added by "
                     + "URL are tracked normally.");
        }
    }

    public boolean isAvailable() {
        return props.getDiscovery().isConfigured();
    }

    /**
     * One search. Returns an empty list rather than throwing on any failure, because discovery is a
     * convenience: a user who added an Amazon URL still gets Amazon tracked whether or not we manage
     * to find the same product on Croma.
     *
     * @param query          what to search for
     * @param includeDomains registrable domains to restrict the search to. Must not be empty — an
     *                       unrestricted search returns review blogs and marketplace aggregators
     */
    public List<SearchHit> search(String query, Collection<String> includeDomains) {
        if (!isAvailable()) {
            return List.of();
        }
        if (query == null || query.isBlank() || includeDomains.isEmpty()) {
            return List.of();
        }

        AppProperties.Discovery cfg = props.getDiscovery();

        // The call is reserved before it is made, so two concurrent searches cannot both slip past
        // the last remaining slot. The trade is that a search which dies in the network still spends
        // a slot it was never billed for. That errs towards under-using the free tier, which is the
        // right direction to be wrong in.
        if (!budget.tryConsumeCall(ApiProvider.TAVILY, cfg.getMonthlyCallBudget())) {
            log.info("Monthly Tavily budget of {} calls is used up; skipping discovery",
                    cfg.getMonthlyCallBudget());
            return List.of();
        }

        String body = requestBody(query, includeDomains, cfg);
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + "/search"))
                .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .header("content-type", "application/json")
                // Sent both ways deliberately. Tavily has accepted the key as a body field since its
                // first API and as a bearer token in later revisions; sending both means a change at
                // their end does not silently break discovery here. If search ever starts returning
                // 401 with a valid key, this is the line to check.
                .header("authorization", "Bearer " + cfg.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Tavily returned HTTP {} for '{}': {}", response.statusCode(), query,
                        abbreviate(response.body()));
                return List.of();
            }
            return parse(response.body());
        } catch (IOException e) {
            log.warn("Tavily search for '{}' failed: {}", query, e.toString());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RuntimeException e) {
            log.warn("Tavily search for '{}' returned something unexpected: {}", query, e.toString());
            return List.of();
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

    private List<SearchHit> parse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("Tavily returned a body that is not JSON: {}", abbreviate(body));
            return List.of();
        }

        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return List.of();
        }

        List<SearchHit> hits = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            String url = node.path("url").asText(null);
            if (url == null || url.isBlank()) {
                continue;
            }
            hits.add(new SearchHit(
                    url,
                    node.path("title").asText(""),
                    node.path("score").asDouble(0.0)));
        }
        return hits;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
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
