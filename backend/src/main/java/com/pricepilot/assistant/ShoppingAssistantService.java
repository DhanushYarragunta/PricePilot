package com.pricepilot.assistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.ai.AiClient;
import com.pricepilot.ai.AiException;
import com.pricepilot.ai.AiResponse;
import com.pricepilot.discovery.TavilyClient;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UnsupportedUrlException;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.pricepilot.assistant.ShoppingAdvice.*;

/**
 * A bounded, read-only comparison pipeline. Search discovers URLs, retailer pages supply prices,
 * and the model ranks evidence IDs. Suggestions never mutate a product's shared price history.
 */
@Service
public class ShoppingAssistantService {
    // BrowserAct may legitimately need 90 seconds. Keep both stages and the whole request bounded.
    static final Timings DEFAULT_TIMINGS = new Timings(Duration.ofSeconds(180), Duration.ofSeconds(12),
            Duration.ofSeconds(12), Duration.ofSeconds(10), Duration.ofSeconds(150), Duration.ofSeconds(8));
    private static final int MAX_CANDIDATES = 4;
    private static final String SPECS_CAUTION = "Check the exact variant and required specifications on the retailer page.";

    private final ProductRepository products;
    private final PricePointRepository prices;
    private final TavilyClient search;
    private final DomainRules rules;
    private final UrlCanonicalizer canonicalizer;
    private final ScrapeEngine engine;
    private final AiClient ai;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor workers;
    private final Timings timings;

    @Autowired
    public ShoppingAssistantService(ProductRepository products, PricePointRepository prices,
                                    TavilyClient search, DomainRules rules,
                                    UrlCanonicalizer canonicalizer, ScrapeEngine engine,
                                    AiClient ai, ObjectMapper mapper) {
        this(products, prices, search, rules, canonicalizer, engine, ai, mapper, DEFAULT_TIMINGS);
    }

    ShoppingAssistantService(ProductRepository products, PricePointRepository prices,
                             TavilyClient search, DomainRules rules,
                             UrlCanonicalizer canonicalizer, ScrapeEngine engine,
                             AiClient ai, ObjectMapper mapper, Timings timings) {
        this.products = products;
        this.prices = prices;
        this.search = search;
        this.rules = rules;
        this.canonicalizer = canonicalizer;
        this.engine = engine;
        this.ai = ai;
        this.mapper = mapper;
        this.timings = timings;
        AtomicInteger threadNumber = new AtomicInteger();
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "shopping-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }

    record Timings(Duration request, Duration planning, Duration search, Duration canonical,
                   Duration scrape, Duration adviceReserve) { }

    public Response advise(Long productId, Request request) {
        long deadline = System.nanoTime() + timings.request().toNanos();
        Product product = products.findById(productId).orElseThrow(() -> ApiException.notFound("Product"));
        List<String> warnings = new ArrayList<>();
        List<Candidate> candidates = observed(productId, request);
        boolean searchAvailable = search.isAvailable();
        if (request.requirements() == null || request.requirements().isBlank()) {
            return new Response(fallback(candidates, request, searchAvailable), null, searchAvailable,
                    List.copyOf(candidates), List.of("Add your must-have requirements before starting a comparison. No search or AI request has been made."));
        }
        ObjectNode context = context(product, request);
        context.put("searchAvailable", searchAvailable);
        AtomicBoolean authenticationRejected = new AtomicBoolean();

        if (searchAvailable) {
            List<String> domains = rules.all().stream().map(HostRule::host).distinct().toList();
            List<String> queries = queries(product, request, context, deadline, warnings, authenticationRejected);
            List<Callable<TavilyClient.SearchOutcome>> searches = queries.stream()
                    .<Callable<TavilyClient.SearchOutcome>>map(query -> () -> search.searchOutcome(query, domains)).toList();
            List<TavilyClient.SearchOutcome> outcomes = await(searches, deadline, timings.search(), warnings,
                    "Some store searches could not finish. Results may be incomplete.");
            outcomes.stream().filter(outcome -> !outcome.succeeded()).map(TavilyClient.SearchOutcome::warning)
                    .forEach(warnings::add);
            searchAvailable = outcomes.stream().anyMatch(TavilyClient.SearchOutcome::succeeded);
            List<TavilyClient.SearchHit> hits = interleave(outcomes.stream().map(TavilyClient.SearchOutcome::hits).toList());
            List<Source> sources = candidateSources(hits, candidates, request, product.getBrand(), deadline, warnings);
            long pageDeadline = ai.isEnabled() && !authenticationRejected.get()
                    ? deadline - timings.adviceReserve().toNanos() : deadline;
            List<Candidate> found = checkSources(sources, candidates, request, pageDeadline, warnings);
            candidates.addAll(found);
            if (searchAvailable && found.isEmpty()) {
                warnings.add("No additional product links could be validated in this search. Try a more specific category or requirement; alternatives may still exist.");
            }
        } else {
            warnings.add("Cross-store search is not configured. Advice uses your tracked product only; no other stores were searched.");
        }

        String answer = fallback(candidates, request, searchAvailable);
        String model = null;
        if (ai.isEnabled() && !authenticationRejected.get()) {
            context.put("searchAvailable", searchAvailable);
            context.set("candidates", mapper.valueToTree(candidates));
            context.set("warnings", mapper.valueToTree(warnings));
            List<AiResponse> replies = await(List.of(() -> ai.complete(ShoppingPrompt.ADVICE, context.toString(), null)),
                    deadline, Duration.ofMillis(Math.max(1, ai.timeoutMs())), warnings,
                    "AI advice is temporarily unavailable. The observed product information is shown below.");
            if (!replies.isEmpty()) {
                AiResponse reply = replies.get(0);
                JsonNode json = parse(reply);
                if (json != null && json.path("answer").isTextual() && validText(json.path("answer").asText(), 1800)) {
                    answer = json.path("answer").asText().trim();
                    candidates = rank(candidates, json.path("rankings"));
                    model = reply.model();
                } else {
                    warnings.add("The AI reply could not be validated. Showing source information without an AI recommendation.");
                }
            }
        } else if (!ai.isEnabled()) {
            warnings.add("AI advice is not configured on this deployment.");
        }
        Comparator<Candidate> order = Comparator.comparingInt(candidate -> constraintPriority(candidate, request));
        if (model == null) {
            order = Comparator.comparingInt((Candidate candidate) -> knownViolation(candidate, request))
                    .thenComparing(Comparator.comparingInt((Candidate candidate) ->
                    ShoppingSearchText.relevance(candidate.title(), request, product.getBrand())).reversed())
                    .thenComparingInt(candidate -> constraintPriority(candidate, request))
                    .thenComparing(candidate -> !candidate.id().startsWith("candidate-"));
        }
        candidates.sort(order);
        return new Response(answer, model, searchAvailable, List.copyOf(candidates), warnings.stream().distinct().toList());
    }

    private ObjectNode context(Product product, Request request) {
        ObjectNode context = mapper.createObjectNode();
        context.put("referenceTitle", product.getTitle());
        context.put("referenceBrand", product.getBrand());
        context.put("referenceCategory", product.getCategory());
        context.put("message", request.message());
        context.put("requirements", request.requirements());
        context.put("budgetInr", request.budget());
        context.set("untrustedConversation", mapper.valueToTree(request.turns()));
        return context;
    }

    private List<String> queries(Product product, Request request, ObjectNode context,
                                 long deadline, List<String> warnings, AtomicBoolean authenticationRejected) {
        String original = ShoppingSearchText.originalQuery(product);
        String alternative = ShoppingSearchText.alternativeQuery(product, request, null);
        if (ai.isEnabled()) {
            List<AiResponse> replies = await(List.of(() -> {
                try {
                    return ai.complete(ShoppingPrompt.SEARCH, context.toString(), null);
                } catch (AiException ex) {
                    if (Integer.valueOf(401).equals(ex.getHttpStatus()) || Integer.valueOf(403).equals(ex.getHttpStatus())) {
                        authenticationRejected.set(true);
                    }
                    throw ex;
                }
            }), deadline, timings.planning(), warnings,
                    "AI search planning is unavailable. The search is using your requirements and product category.");
            if (!replies.isEmpty()) {
                JsonNode json = parse(replies.get(0));
                String query = json == null || !json.path("alternative_query").isTextual()
                        ? "" : json.path("alternative_query").asText("");
                if (validText(query, 240)) alternative = ShoppingSearchText.alternativeQuery(product, request, query);
            }
        }
        // Alternatives receive the first discovery slots; the exact reference is already tracked.
        return List.of(alternative, original).stream().distinct().toList();
    }

    private List<Candidate> observed(Long productId, Request request) {
        List<Candidate> out = new ArrayList<>();
        for (PricePoint point : prices.latestObservations(productId)) {
            Listing listing = point.getListing();
            if (listing == null || listing.getStatus() == ListingStatus.DEAD || !safeWebUrl(listing.getUrl())
                    || !PriceParser.isPlausible(point.getPrice()) || !"INR".equals(point.getCurrency())) continue;
            List<String> cautions = cautions(point.getPrice(), point.isInStock(), request);
            cautions.addAll(ShoppingSearchText.contradictions(listing.getTitle(), request.requirements()));
            cautions.add("Previously tracked observation; confirm the current offer and variant before buying.");
            if (point.getCapturedAt() == null || point.getCapturedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))) {
                cautions.add("This saved price is more than a day old or has no capture time.");
            }
            out.add(new Candidate("tracked-" + listing.getId(), listing.getTitle(), listing.getUrl(),
                    listing.getRetailer(), point.getPrice(), point.getCurrency(), point.isInStock(),
                    point.getCapturedAt(), "unconfirmed", "A listing you already track; exact model and variant still need checking.", List.copyOf(cautions)));
            if (out.size() == 6) break;
        }
        return out;
    }

    private List<Source> candidateSources(List<TavilyClient.SearchHit> hits, List<Candidate> existing,
                                          Request request, String referenceBrand, long deadline,
                                          List<String> warnings) {
        Set<String> seen = new HashSet<>();
        List<TavilyClient.SearchHit> eligible = new ArrayList<>();
        for (Candidate candidate : existing) seen.add(candidate.url());
        for (TavilyClient.SearchHit hit : hits) {
            if (safeRetailerUrl(hit.url()) && seen.add(hit.url())) eligible.add(hit);
        }
        eligible.sort(Comparator.comparingInt((TavilyClient.SearchHit hit) ->
                ShoppingSearchText.relevance(hit.title(), request, referenceBrand)).reversed());
        List<Callable<Source>> canonicalChecks = new ArrayList<>();
        for (TavilyClient.SearchHit hit : diverse(eligible, hit -> retailerHost(hit.url()), 12)) {
            // Canonicalisation and DNS checks run inside the deadline, not on the servlet thread.
            canonicalChecks.add(() -> {
                UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(hit.url());
                if (canonical == null || !safeRetailerUrl(canonical.url())) {
                    throw new UnsupportedUrlException("Search URL is not a supported retailer product page.");
                }
                return new Source(canonical, hit.title());
            });
        }
        Set<String> canonicalSeen = new HashSet<>();
        existing.forEach(candidate -> canonicalSeen.add(candidate.url()));
        List<Source> sources = await(canonicalChecks, deadline, timings.canonical(), warnings,
                "Some search links were skipped because they could not be validated as retailer product pages.",
                "Some search links were skipped because validation did not finish before the time limit.");
        List<Source> unique = new ArrayList<>();
        for (Source source : sources) {
            if (canonicalSeen.add(source.canonical().url())) unique.add(source);
        }
        return diverse(unique, source -> source.canonical().rule().host(), MAX_CANDIDATES);
    }

    private record Source(UrlCanonicalizer.Canonical canonical, String searchTitle) { }
    private record Inspection(String id, Candidate candidate, String warning) { }

    private List<Candidate> checkSources(List<Source> sources, List<Candidate> existing, Request request,
                                         long deadline, List<String> warnings) {
        Map<String, Candidate> found = new LinkedHashMap<>();
        Set<String> canonicalSeen = ConcurrentHashMap.newKeySet();
        existing.forEach(candidate -> canonicalSeen.add(candidate.url()));
        sources.forEach(source -> canonicalSeen.add(source.canonical().url()));
        List<Callable<Inspection>> checks = new ArrayList<>();
        for (Source source : sources) {
            String id = "candidate-" + checks.size();
            // A safely validated source survives an ordinary fetch failure, without invented offer data.
            found.put(id, unverified(source, id, request));
            checks.add(() -> inspect(source, id, request, canonicalSeen));
        }
        for (Inspection inspection : await(checks, deadline, timings.scrape(), warnings,
                "Some retailer pages could not be checked. Their search links are shown without live prices.",
                "Some retailer checks reached the time limit. Their search links are shown without live prices.")) {
            if (inspection.candidate() == null) found.remove(inspection.id());
            else found.put(inspection.id(), inspection.candidate());
            if (inspection.warning() != null) warnings.add(inspection.warning());
        }
        return new ArrayList<>(found.values());
    }

    private static Candidate unverified(Source source, String id, Request request) {
        String title = trim(source.searchTitle(), 500);
        List<String> cautions = new ArrayList<>(List.of("Search titles can be stale. Price, availability and specifications are unverified."));
        cautions.addAll(ShoppingSearchText.contradictions(title, request.requirements()));
        return new Candidate(id, title.isBlank() ? "Unverified product page" : title,
                source.canonical().url(), source.canonical().retailer(), null, "INR", null, null,
                "unconfirmed", "Found in search; the live offer and required features still need verification.",
                List.copyOf(cautions));
    }

    private Inspection inspect(Source source, String id, Request request, Set<String> canonicalSeen) {
        UrlCanonicalizer.Canonical canonical = source.canonical();
        if (Thread.currentThread().isInterrupted()) return null;
        ScrapeResult result;
        String url = canonical.url();
        try {
            result = engine.scrape(canonical.url(), canonical.rule());
            if (result != null && result.finalUrl() != null) {
                if (!safeRetailerUrl(result.finalUrl())) return rejected(id);
                if (!result.finalUrl().equals(canonical.url())) {
                    url = canonicalizer.canonicalize(result.finalUrl()).url();
                    if (!safeRetailerUrl(url)) return rejected(id);
                    if (!url.equals(canonical.url()) && !canonicalSeen.add(url)) return new Inspection(id, null, null);
                }
            }
        } catch (UnsupportedUrlException ex) {
            return rejected(id);
        }
        ProductSnapshot snapshot = result == null ? null : result.snapshot();
        if (result == null || !result.isSuccess() || snapshot == null || !snapshot.isVerified()) {
            return new Inspection(id, unverified(source, id, request),
                    "Some retailer pages did not provide a verified live offer. Their links are shown with unknown prices and availability.");
        }
        List<String> cautions = cautions(snapshot.price(), snapshot.inStock(), request);
        cautions.add(SPECS_CAUTION);
        cautions.addAll(ShoppingSearchText.contradictions(snapshot.title(), request.requirements()));
        Candidate candidate = new Candidate(id, snapshot.title(), url, rules.forHost(URI.create(url).getHost()).retailer(),
                snapshot.price(), snapshot.currency(), snapshot.inStock(), result.observedAt(), "unconfirmed",
                "Price read from the retailer page; product suitability still needs checking.", List.copyOf(cautions));
        return new Inspection(id, candidate, null);
    }

    private static Inspection rejected(String id) {
        return new Inspection(id, null, "Some retailer links were skipped because their page destination could not be validated.");
    }

    private String retailerHost(String url) {
        String host = URI.create(url).getHost();
        HostRule rule = rules.forHost(host);
        return rule == null ? host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") : rule.host();
    }

    /** Preserve relevance within each retailer while avoiding four serial checks of one host. */
    private static <T> List<T> diverse(List<T> ranked, Function<T, String> host, int limit) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T item : ranked) groups.computeIfAbsent(host.apply(item), ignored -> new ArrayList<>()).add(item);
        List<T> selected = new ArrayList<>();
        int round = 0;
        boolean added = true;
        while (added && selected.size() < limit) {
            added = false;
            for (List<T> group : groups.values()) {
                if (round >= group.size()) continue;
                selected.add(group.get(round));
                added = true;
                if (selected.size() == limit) break;
            }
            round++;
        }
        return selected;
    }

    private boolean safeRetailerUrl(String url) {
        if (!safeWebUrl(url)) return false;
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)
                || !rules.isKnownHost(uri.getHost())) return false;
        String path = uri.getPath() == null ? "/" : uri.getPath().toLowerCase(Locale.ROOT);
        return !path.equals("/") && !path.matches(".*(?:^|/)(?:search|s|categories|category|blog|blogs|article|articles|collections)(?:/.*)?$");
    }

    private static boolean safeWebUrl(String url) {
        if (url == null || url.length() > 2048) return false;
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ex) { return false; }
    }

    private static List<String> cautions(java.math.BigDecimal price, boolean inStock, Request request) {
        List<String> cautions = new ArrayList<>();
        if (!inStock) cautions.add("Reported out of stock when checked.");
        if (request.budget() != null && price.compareTo(request.budget()) > 0) cautions.add("Above your stated budget.");
        return cautions;
    }

    private static int constraintPriority(Candidate candidate, Request request) {
        if (ShoppingSearchText.contradicted(candidate)) return 3;
        if (candidate.price() == null || !Boolean.TRUE.equals(candidate.inStock())) return 2;
        return request.budget() != null && candidate.price().compareTo(request.budget()) > 0 ? 1 : 0;
    }

    private static int knownViolation(Candidate candidate, Request request) {
        if (ShoppingSearchText.contradicted(candidate)) return 3;
        if (Boolean.FALSE.equals(candidate.inStock())) return 2;
        return request.budget() != null && candidate.price() != null
                && candidate.price().compareTo(request.budget()) > 0 ? 1 : 0;
    }

    private JsonNode parse(AiResponse response) {
        if (response == null || response.truncated() || response.text() == null || response.text().length() > 32_000) return null;
        try {
            JsonNode result = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.text());
            return result != null && result.isObject() ? result : null;
        } catch (Exception ex) { return null; }
    }

    static List<Candidate> rank(List<Candidate> evidence, JsonNode rankings) {
        if (!rankings.isArray()) return evidence;
        Map<String, Candidate> remaining = new LinkedHashMap<>();
        for (Candidate candidate : evidence) remaining.put(candidate.id(), candidate);
        List<Candidate> ranked = new ArrayList<>();
        for (JsonNode ranking : rankings) {
            Candidate candidate = remaining.remove(ranking.path("id").asText());
            if (candidate == null) continue;
            String reason = ranking.path("reason").asText("");
            if (!validText(reason, 300)) reason = candidate.reason();
            List<String> cautions = new ArrayList<>(candidate.cautions());
            if (ranking.path("cautions").isArray()) {
                int count = 0;
                for (JsonNode caution : ranking.path("cautions")) {
                    if (++count > 3) break;
                    if (validText(caution.asText(), 200)) cautions.add(caution.asText().trim());
                }
            }
            // The model cannot certify identity or promote an unread page to a verified alternative.
            String kind = candidate.kind();
            if (candidate.id().startsWith("candidate-") && candidate.price() != null
                    && !ShoppingSearchText.contradicted(candidate)
                    && "alternative".equals(ranking.path("kind").asText())) kind = "alternative";
            ranked.add(new Candidate(candidate.id(), candidate.title(), candidate.url(), candidate.retailer(),
                    candidate.price(), candidate.currency(), candidate.inStock(), candidate.capturedAt(),
                    kind, reason, cautions.stream().distinct().toList()));
        }
        ranked.addAll(remaining.values());
        return ranked;
    }

    private static boolean validText(String text, int max) {
        if (text == null || text.isBlank() || text.length() > max) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        // Store links and monetary figures live only in the server-owned evidence cards.
        return !lower.matches("(?s).*(?:https?://|www\\.|₹|[$€£]|\\b(?:inr|rupees?|dollars?)\\b|\\brs\\.).*");
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String fallback(List<Candidate> candidates, Request request, boolean searchAvailable) {
        if (request.requirements() == null || request.requirements().isBlank()) {
            return "What will you use this product for, and which features are essential? Share your budget and must-have features so alternatives can be compared fairly.";
        }
        if (!searchAvailable) return "I can help assess the product you track, but cross-store search is unavailable. Compare its specifications with your must-have requirements; additional brands and offers have not been checked.";
        if (candidates.stream().noneMatch(c -> c.id().startsWith("candidate-"))) {
            return "I could not verify an additional offer in this search. Your tracked observations are shown below. Try a more specific product category or adjust your must-have requirements.";
        }
        if (candidates.stream().filter(c -> c.id().startsWith("candidate-")).allMatch(c -> c.price() == null)) {
            return "I found additional retailer links for your requirements, but their live offers could not be verified. The links are shown below with unknown prices and availability. Open each page to check the exact variant and required features.";
        }
        return "Here is the product information found for your comparison. Check each option's exact variant, compatibility and current offer against your requirements; unverified details are marked.";
    }

    /** Reserve early slots for both exact-model and alternative searches. */
    static List<TavilyClient.SearchHit> interleave(List<List<TavilyClient.SearchHit>> batches) {
        List<TavilyClient.SearchHit> hits = new ArrayList<>();
        int max = batches.stream().mapToInt(List::size).max().orElse(0);
        for (int index = 0; index < max; index++) {
            for (List<TavilyClient.SearchHit> batch : batches) {
                if (index < batch.size()) hits.add(batch.get(index));
            }
        }
        return hits;
    }

    private <T> List<T> await(List<Callable<T>> tasks, long deadline, Duration stageLimit,
                              List<String> warnings, String failure) {
        return await(tasks, deadline, stageLimit, warnings, failure, failure);
    }

    private <T> List<T> await(List<Callable<T>> tasks, long deadline, Duration stageLimit,
                              List<String> warnings, String failure, String timeout) {
        if (tasks.isEmpty()) return List.of();
        long stageDeadline = Math.min(deadline, System.nanoTime() + stageLimit.toNanos());
        if (Thread.currentThread().isInterrupted() || stageDeadline <= System.nanoTime()) {
            warnings.add(timeout);
            return List.of();
        }
        List<Future<T>> futures = new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                try { futures.add(workers.submit(task)); }
                catch (RejectedExecutionException ex) { warnings.add(failure); break; }
            }
            for (Future<T> future : futures) {
                try {
                    long remaining = stageDeadline - System.nanoTime();
                    T result = future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    if (result != null) results.add(result);
                } catch (TimeoutException ex) {
                    warnings.add(timeout);
                } catch (ExecutionException ex) {
                    // AiClient's deliberate errors are sanitized; arbitrary provider exceptions are not.
                    if (ex.getCause() instanceof AiException aiFailure && aiFailure.getMessage() != null) {
                        warnings.add(aiFailure.getMessage());
                    } else {
                        warnings.add(failure);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            warnings.add(failure);
        } finally {
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            workers.purge();
        }
        return results;
    }
}
