package com.pricepilot.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.ai.AiClient;
import com.pricepilot.ai.AiException;
import com.pricepilot.ai.AiResponse;
import com.pricepilot.discovery.TavilyClient;
import com.pricepilot.domain.Listing;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.repo.PricePointRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UnsupportedUrlException;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static com.pricepilot.assistant.ShoppingAdvice.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShoppingAssistantServiceTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final PricePointRepository prices = mock(PricePointRepository.class);
    private final TavilyClient search = mock(TavilyClient.class);
    private final DomainRules rules = mock(DomainRules.class);
    private final UrlCanonicalizer canonicalizer = mock(UrlCanonicalizer.class);
    private final ScrapeEngine engine = mock(ScrapeEngine.class);
    private final AiClient ai = mock(AiClient.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HostRule rule = HostRule.builder("amazon.in").retailer("Amazon.in").build();
    private ShoppingAssistantService service;
    private static final String ORIGINAL = "https://www.amazon.in/dp/B000000001";
    private static final String OTHER = "https://www.amazon.in/dp/B000000002";
    private final Request request = new Request("Compare alternatives", new BigDecimal("3000"),
            "USB-C hub for a laptop with Ethernet", List.of());

    @BeforeEach void setup() {
        when(ai.timeoutMs()).thenReturn(45_000L);
        service = new ShoppingAssistantService(products, prices, search, rules, canonicalizer, engine, ai, mapper);
        Product product = new Product(); product.setId(1L); product.setTitle("Example USB-C hub with Ethernet");
        when(products.findById(1L)).thenReturn(Optional.of(product));
        Listing listing = new Listing(); listing.setId(2L); listing.setTitle(product.getTitle());
        listing.setUrl(ORIGINAL); listing.setRetailer("Amazon.in");
        PricePoint point = new PricePoint(); point.setListing(listing); point.setPrice(new BigDecimal("2500"));
        point.setCapturedAt(Instant.now());
        when(prices.latestObservations(1L)).thenReturn(List.of(point));
        when(rules.all()).thenReturn(List.of(rule));
        when(rules.isKnownHost("www.amazon.in")).thenReturn(true);
        when(rules.forHost("www.amazon.in")).thenReturn(rule);
    }

    @AfterEach void close() { service.close(); }

    @Test void unavailableSearchNeverPretendsToHaveComparedOtherStores() {
        Response response = service.advise(1L, request);
        assertThat(response.searchAvailable()).isFalse();
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.answer()).contains("additional brands and offers have not been checked");
        assertThat(response.warnings()).anyMatch(w -> w.contains("no other stores were searched"));
        verifyNoInteractions(canonicalizer, engine);
        verify(search, never()).searchOutcome(anyString(), anyCollection());
    }

    @Test void blankRequirementsDoNotSpendSearchOrAiBudget() {
        when(search.isAvailable()).thenReturn(true);
        when(ai.isEnabled()).thenReturn(true);
        Response response = service.advise(1L, new Request("Find alternatives", null, " ", List.of()));
        assertThat(response.answer()).contains("features are essential");
        verify(search, never()).searchOutcome(anyString(), anyCollection());
        verify(ai, never()).complete(anyString(), anyString(), any());
        verifyNoInteractions(canonicalizer, engine);
    }

    @Test void bothQueriesGetCandidateSlotsAndCanonicalDuplicatesDoNotGetFetched() {
        when(search.isAvailable()).thenReturn(true);
        String duplicate = OTHER + "?tag=test";
        String alternative = "https://www.amazon.in/dp/B000000003";
        when(search.searchOutcome(contains("buy India"), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(ORIGINAL + "?tag=test", "Already tracked", 1),
                new TavilyClient.SearchHit(OTHER, "Another offer", .9),
                new TavilyClient.SearchHit(duplicate, "Duplicate offer", .8))));
        when(search.searchOutcome(contains("alternatives India"), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(alternative, "Different brand hub", .9))));
        when(canonicalizer.canonicalize(ORIGINAL + "?tag=test")).thenReturn(new UrlCanonicalizer.Canonical(ORIGINAL, "www.amazon.in", "original", rule));
        when(canonicalizer.canonicalize(OTHER)).thenReturn(new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "other", rule));
        when(canonicalizer.canonicalize(duplicate)).thenReturn(new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "other", rule));
        when(canonicalizer.canonicalize(alternative)).thenReturn(new UrlCanonicalizer.Canonical(alternative, "www.amazon.in", "alternative", rule));
        Response response = service.advise(1L, request);
        assertThat(response.candidates()).hasSize(3);
        verify(engine, never()).scrape(eq(ORIGINAL), any(HostRule.class));
        verify(engine, times(1)).scrape(OTHER, rule);
        verify(engine, times(1)).scrape(alternative, rule);
    }

    @Test void interleavesExactAndAlternativeSearchResults() {
        var a = new TavilyClient.SearchHit("a", "a", 1);
        var b = new TavilyClient.SearchHit("b", "b", 1);
        var c = new TavilyClient.SearchHit("c", "c", 1);
        var d = new TavilyClient.SearchHit("d", "d", 1);
        assertThat(ShoppingAssistantService.interleave(List.of(List.of(a, b, c), List.of(d))))
                .containsExactly(a, d, b, c);
    }

    @Test void retailerPriceIsUsedAndUntrustedSearchHostsAreNeverFetched() {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit("https://evil.example/collect", "Fake cheapest hub", 1),
                new TavilyClient.SearchHit(OTHER, "Stale search title", .9))));
        when(canonicalizer.canonicalize(OTHER)).thenReturn(new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "hash", rule));
        ProductSnapshot snapshot = new ProductSnapshot(new BigDecimal("1999"), null, "INR",
                "Different manufacturer USB-C Ethernet hub", null, true, "css");
        when(engine.scrape(OTHER, rule)).thenReturn(new ScrapeResult(ScrapeOutcome.SUCCESS, snapshot,
                "www.amazon.in", OTHER, 200, 12, 100, null, "css", null));
        Response response = service.advise(1L, request);
        assertThat(response.candidates()).hasSize(2);
        Candidate candidate = response.candidates().stream().filter(c -> c.id().startsWith("candidate")).findFirst().orElseThrow();
        assertThat(candidate.title()).isEqualTo(snapshot.title());
        assertThat(candidate.price()).isEqualByComparingTo("1999");
        assertThat(candidate.kind()).isEqualTo("unconfirmed");
        assertThat(candidate.capturedAt()).isNotNull();
        verify(canonicalizer, never()).canonicalize("https://evil.example/collect");
        verify(engine, times(1)).scrape(OTHER, rule);
        verify(search, atMost(2)).searchOutcome(anyString(), anyCollection());
        verify(products, never()).save(any());
        verify(prices, never()).save(any());
    }

    @Test void failedScrapeKeepsPriceAndAvailabilityUnknown() {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(new TavilyClient.SearchHit(OTHER, "Another USB-C hub", .9))));
        when(canonicalizer.canonicalize(OTHER)).thenReturn(new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "hash", rule));
        when(engine.scrape(OTHER, rule)).thenReturn(ScrapeResult.aborted(ScrapeOutcome.BLOCKED, "www.amazon.in", OTHER, 403, 1, "Blocked"));
        Response response = service.advise(1L, request);
        Candidate candidate = response.candidates().stream().filter(c -> c.id().startsWith("candidate")).findFirst().orElseThrow();
        assertThat(candidate.price()).isNull();
        assertThat(candidate.inStock()).isNull();
        assertThat(candidate.capturedAt()).isNull();
        assertThat(candidate.cautions()).anyMatch(c -> c.contains("unverified"));
    }

    @Test void relevantUnpricedAlternativeIsNotHiddenBehindUnrelatedTrackedTitleWhenAiFails() {
        when(search.isAvailable()).thenReturn(true);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(eq(ShoppingPrompt.SEARCH), anyString(), isNull()))
                .thenThrow(new AiException("Rejected", false, 401));
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "Different USB-C hub RJ45 1Gbps Ethernet", .9))));
        when(canonicalizer.canonicalize(OTHER)).thenReturn(new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "hash", rule));
        Response response = service.advise(1L, new Request("Different company products", new BigDecimal("3000"),
                "USB-C hub RJ45 1Gbps Ethernet", List.of()));
        assertThat(response.candidates().get(0).url()).isEqualTo(OTHER);
        assertThat(response.candidates().get(0).price()).isNull();
        assertThat(response.candidates().get(0).kind()).isEqualTo("unconfirmed");
        verify(ai, never()).complete(eq(ShoppingPrompt.ADVICE), anyString(), any());
    }

    @Test void modelCannotInventCandidateUrlsPricesOrCertifyExactMatches() throws Exception {
        Candidate evidence = new Candidate("candidate-0", "Observed hub", OTHER, "Amazon.in", new BigDecimal("1999"),
                "INR", true, Instant.now(), "unconfirmed", "Observed", List.of("Check variant"));
        var rankings = mapper.readTree("""
                [{"id":"invented","url":"https://evil.example","price":1},
                 {"id":"candidate-0","url":"https://evil.example","price":1,"kind":"same_product","reason":"Title mentions a hub","cautions":[]},
                 {"id":"candidate-0","reason":"Duplicate"}]
                """);
        List<Candidate> result = ShoppingAssistantService.rank(List.of(evidence), rankings);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).price()).isEqualByComparingTo("1999");
        assertThat(result.get(0).url()).isEqualTo(OTHER);
        assertThat(result.get(0).kind()).isEqualTo("unconfirmed");
        assertThat(result.get(0).cautions()).contains("Check variant");
    }

    @Test void unverifiedCandidateCannotBecomeAnAlternativeWithAClaimedPrice() throws Exception {
        Candidate evidence = new Candidate("candidate-0", "Search title", OTHER, "Amazon.in", null,
                "INR", null, null, "unconfirmed", "Unread page", List.of());
        var ranked = ShoppingAssistantService.rank(List.of(evidence), mapper.readTree(
                "[{\"id\":\"candidate-0\",\"kind\":\"alternative\",\"reason\":\"Only ₹999, buy at https://evil.example\"}]"));
        assertThat(ranked.get(0).kind()).isEqualTo("unconfirmed");
        assertThat(ranked.get(0).reason()).isEqualTo("Unread page");
        assertThat(ranked.get(0).price()).isNull();
    }

    @Test void malformedAiOutputFallsBackWithoutReturningTheUntrustedText() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull())).thenReturn(reply("{not json"));
        Response response = service.advise(1L, request);
        assertThat(response.model()).isNull();
        assertThat(response.answer()).doesNotContain("{not json");
        assertThat(response.warnings()).anyMatch(w -> w.contains("could not be validated"));
    }

    @Test void searchBudgetFailureIsVisibleAndDoesNotClaimACompletedSearch() {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(
                TavilyClient.SearchOutcome.failed(TavilyClient.SearchStatus.BUDGET_EXHAUSTED, null));
        Response response = service.advise(1L, request);
        assertThat(response.searchAvailable()).isFalse();
        assertThat(response.warnings()).anyMatch(w -> w.contains("allowance is used up"));
        assertThat(response.warnings()).noneMatch(w -> w.contains("No additional product pages were verified"));
        verifyNoInteractions(canonicalizer, engine);
    }

    @Test void deliberateAiAllowanceErrorReachesUserButArbitraryErrorsDoNot() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull()))
                .thenThrow(new AiException("Monthly AI allowance exhausted", false, null));
        Response response = service.advise(1L, request);
        assertThat(response.warnings()).contains("Monthly AI allowance exhausted");
        doThrow(new IllegalStateException("private-provider-body"))
                .when(ai).complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull());
        Response second = service.advise(1L, request);
        assertThat(second.warnings()).noneMatch(w -> w.contains("private-provider-body"));
    }

    @Test void aiReceivesRequirementsAndConversationAsData() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull()))
                .thenReturn(reply("{\"answer\":\"Which laptop ports do you need to connect?\",\"rankings\":[]}"));
        Request followup = new Request("What about another brand?", new BigDecimal("3000"), "Ethernet required",
                List.of(new Turn("user", "I use a MacBook")));
        Response response = service.advise(1L, followup);
        assertThat(response.model()).isEqualTo("fixture-model");
        verify(ai).complete(eq(ShoppingPrompt.ADVICE), argThat(text -> text.contains("Ethernet required")
                && text.contains("I use a MacBook") && text.contains("untrustedConversation")), isNull());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void planningAuthFailureSkipsSecondAiCallButKeepsRequirementSearchAndNextRequestCanRecover(int status) {
        Product product = products.findById(1L).orElseThrow();
        product.setBrand("Anker");
        product.setTitle("Anker USB-C Hub " + "charging HDMI docking laptop adapter ".repeat(20));
        Request requirements = new Request("Different brands please", new BigDecimal("3000"),
                "USB-C hub RJ45 1Gbps Ethernet", List.of());
        when(search.isAvailable()).thenReturn(true);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(eq(ShoppingPrompt.SEARCH), anyString(), isNull()))
                .thenThrow(new AiException("AI authentication failed", false, status));
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "Different brand USB-C hub RJ45 1Gbps Ethernet", .9))));
        canonical(OTHER);

        Response response = service.advise(1L, requirements);

        verify(ai, times(1)).complete(anyString(), anyString(), isNull());
        assertThat(response.warnings()).contains("AI authentication failed");
        assertThat(response.candidates()).extracting(Candidate::url).contains(OTHER);
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(search, times(2)).searchOutcome(queries.capture(), anyCollection());
        String alternative = queries.getAllValues().stream().filter(query -> query.endsWith("alternatives India"))
                .findFirst().orElseThrow();
        assertThat(alternative).startsWith("USB-C hub RJ45 1Gbps Ethernet").doesNotContain("Anker");
        assertThat(alternative.length()).isLessThanOrEqualTo(240);

        doReturn(reply("{\"alternative_query\":\"USB-C hub different manufacturers\"}"))
                .when(ai).complete(eq(ShoppingPrompt.SEARCH), anyString(), isNull());
        when(ai.complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull()))
                .thenReturn(reply("{\"answer\":\"Check each option's required ports.\",\"rankings\":[]}"));

        Response recovered = service.advise(1L, requirements);

        assertThat(recovered.model()).isEqualTo("fixture-model");
        verify(ai, times(2)).complete(eq(ShoppingPrompt.SEARCH), anyString(), isNull());
        verify(ai, times(1)).complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull());
    }

    @Test void pageTimeoutKeepsValidatedSourceAndCompletedOtherRetailerEvidence() {
        useTimings(Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofMillis(60), Duration.ZERO);
        String fast = "https://www.amazon.in/dp/B000000003";
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "USB-C hub Ethernet slow", .9),
                new TavilyClient.SearchHit(fast, "USB-C hub Ethernet fast", .8))));
        canonical(OTHER);
        canonical(fast);
        CountDownLatch release = new CountDownLatch(1);
        when(engine.scrape(OTHER, rule)).thenAnswer(invocation -> {
            release.await();
            return scrapeSuccess(OTHER, "Slow USB-C hub Ethernet", "1499");
        });
        when(engine.scrape(fast, rule)).thenReturn(scrapeSuccess(fast, "USB-C hub Ethernet fast", "1999"));

        Response response;
        try {
            response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.advise(1L, request));
        } finally {
            release.countDown();
        }

        Candidate slow = response.candidates().stream().filter(candidate -> candidate.url().equals(OTHER)).findFirst().orElseThrow();
        assertThat(slow.price()).isNull();
        assertThat(slow.inStock()).isNull();
        assertThat(slow.capturedAt()).isNull();
        assertThat(slow.kind()).isEqualTo("unconfirmed");
        assertThat(response.candidates().stream().filter(candidate -> candidate.url().equals(fast)).findFirst().orElseThrow().price())
                .isEqualByComparingTo("1999");
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("retailer checks reached the time limit"));
        assertThat(response.warnings()).noneMatch(warning -> warning.startsWith("No additional"));
    }

    @Test void ordinaryPageErrorKeepsUnpricedSourceWithoutLeakingProviderText() {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "USB-C Ethernet hub", .9))));
        canonical(OTHER);
        when(engine.scrape(OTHER, rule)).thenThrow(new IllegalStateException("private-provider-error-body"));

        Response response = service.advise(1L, request);

        Candidate candidate = response.candidates().stream().filter(item -> item.url().equals(OTHER)).findFirst().orElseThrow();
        assertThat(candidate.price()).isNull();
        assertThat(candidate.inStock()).isNull();
        assertThat(candidate.capturedAt()).isNull();
        assertThat(response.answer()).contains("found additional retailer links");
        assertThat(response.warnings()).noneMatch(warning -> warning.contains("private-provider") || warning.startsWith("No additional"));
    }

    @Test void validationTimeoutNeverCreatesASourceCardOrFetchesAnUnapprovedUrl() {
        useTimings(Duration.ofSeconds(2), Duration.ofMillis(60), Duration.ofMillis(100), Duration.ZERO);
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "USB-C Ethernet hub", .9))));
        CountDownLatch release = new CountDownLatch(1);
        when(canonicalizer.canonicalize(OTHER)).thenAnswer(invocation -> {
            release.await();
            return new UrlCanonicalizer.Canonical(OTHER, "www.amazon.in", "hash", rule);
        });

        Response response;
        try {
            response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.advise(1L, request));
        } finally {
            release.countDown();
        }

        assertThat(response.candidates()).extracting(Candidate::url).doesNotContain(OTHER);
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("validation did not finish"));
        verifyNoInteractions(engine);
    }

    @ParameterizedTest
    @EnumSource(value = ScrapeOutcome.class, names = {"SUCCESS", "NO_PRICE"})
    void unsafePageDestinationRemovesSourceEvenWhenTheScrapeHasNoPrice(ScrapeOutcome outcome) {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "USB-C Ethernet hub", .9))));
        canonical(OTHER);
        ProductSnapshot snapshot = outcome == ScrapeOutcome.SUCCESS
                ? new ProductSnapshot(new BigDecimal("1999"), null, "INR", "USB-C Ethernet hub", null, true, "browseract") : null;
        when(engine.scrape(OTHER, rule)).thenReturn(new ScrapeResult(outcome, snapshot, "evil.example",
                "https://evil.example/private", 200, 10, 100, null, "browseract", null));

        Response response = service.advise(1L, request);

        assertThat(response.candidates()).extracting(Candidate::url).containsExactly(ORIGINAL);
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("destination could not be validated"));
        verify(canonicalizer, never()).canonicalize("https://evil.example/private");
    }

    @Test void guardRejectionDuringFetchCannotReviveThePreviouslyApprovedSource() {
        when(search.isAvailable()).thenReturn(true);
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(List.of(
                new TavilyClient.SearchHit(OTHER, "USB-C Ethernet hub", .9))));
        canonical(OTHER);
        when(engine.scrape(OTHER, rule)).thenThrow(new UnsupportedUrlException("private guard detail"));

        Response response = service.advise(1L, request);

        assertThat(response.candidates()).extracting(Candidate::url).containsExactly(ORIGINAL);
        assertThat(response.warnings()).noneMatch(warning -> warning.contains("private guard detail"));
    }

    @Test void validationAndFourPageSelectionBothGiveOtherRetailersASlot() {
        HostRule flipkart = HostRule.builder("flipkart.com").retailer("Flipkart").build();
        String flipkartUrl = "https://www.flipkart.com/ethernet-hub/p/item123";
        when(search.isAvailable()).thenReturn(true);
        when(rules.all()).thenReturn(List.of(rule, flipkart));
        when(rules.isKnownHost("www.flipkart.com")).thenReturn(true);
        when(rules.forHost("www.flipkart.com")).thenReturn(flipkart);
        List<TavilyClient.SearchHit> hits = new ArrayList<>();
        for (int index = 2; index <= 15; index++) {
            hits.add(new TavilyClient.SearchHit("https://www.amazon.in/dp/B" + String.format("%09d", index),
                    "USB-C hub with Ethernet " + index, .9));
        }
        hits.add(new TavilyClient.SearchHit(flipkartUrl, "USB-C hub with Ethernet different brand", .8));
        when(search.searchOutcome(anyString(), anyCollection())).thenReturn(success(hits));
        when(canonicalizer.canonicalize(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String host = URI.create(url).getHost();
            return new UrlCanonicalizer.Canonical(url, host, url, host.endsWith("flipkart.com") ? flipkart : rule);
        });

        Response response = service.advise(1L, request);

        assertThat(response.candidates()).hasSize(5);
        assertThat(response.candidates()).extracting(Candidate::url).contains(flipkartUrl);
        verify(canonicalizer, atMost(12)).canonicalize(anyString());
        verify(engine, times(4)).scrape(anyString(), any(HostRule.class));
        verify(engine).scrape(flipkartUrl, flipkart);
    }

    @Test void finalAdviceCannotExtendTheOverallRequestDeadline() {
        useTimings(Duration.ofMillis(250), Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(30));
        when(ai.isEnabled()).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.complete(eq(ShoppingPrompt.ADVICE), anyString(), isNull())).thenAnswer(invocation -> {
            release.await();
            return reply("{\"answer\":\"Late answer\",\"rankings\":[]}");
        });

        Response response;
        try {
            response = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.advise(1L, request));
        } finally {
            release.countDown();
        }

        assertThat(response.model()).isNull();
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("AI advice is temporarily unavailable"));
    }

    @Test void modelCannotPromoteAnExplicitRequirementContradiction() throws Exception {
        Candidate contradicted = new Candidate("candidate-0", "Hub without Ethernet", OTHER, "Amazon.in",
                new BigDecimal("999"), "INR", true, Instant.now(), "unconfirmed", "Check features",
                ShoppingSearchText.contradictions("Hub without Ethernet", "Ethernet required"));
        var ranked = ShoppingAssistantService.rank(List.of(contradicted), mapper.readTree(
                "[{\"id\":\"candidate-0\",\"kind\":\"alternative\",\"reason\":\"Best option\",\"cautions\":[]}]"));

        assertThat(ranked.get(0).kind()).isEqualTo("unconfirmed");
        assertThat(ranked.get(0).cautions()).containsAll(contradicted.cautions());
    }

    private void canonical(String url) {
        when(canonicalizer.canonicalize(url)).thenReturn(new UrlCanonicalizer.Canonical(url, "www.amazon.in", url, rule));
    }

    private ScrapeResult scrapeSuccess(String url, String title, String price) {
        ProductSnapshot snapshot = new ProductSnapshot(new BigDecimal(price), null, "INR", title, null, true, "browseract");
        return new ScrapeResult(ScrapeOutcome.SUCCESS, snapshot, "www.amazon.in", url, 200, 10, 100, null, "browseract", null);
    }

    private void useTimings(Duration requestLimit, Duration canonicalLimit, Duration scrapeLimit, Duration reserve) {
        service.close();
        service = new ShoppingAssistantService(products, prices, search, rules, canonicalizer, engine, ai, mapper,
                new ShoppingAssistantService.Timings(requestLimit, Duration.ofMillis(100), Duration.ofMillis(100),
                        canonicalLimit, scrapeLimit, reserve));
    }

    private static TavilyClient.SearchOutcome success(List<TavilyClient.SearchHit> hits) {
        return new TavilyClient.SearchOutcome(hits, TavilyClient.SearchStatus.SUCCESS, 200);
    }

    private static AiResponse reply(String text) {
        return new AiResponse(text, "fixture-model", 10, 20, 0, 0, BigDecimal.ZERO, "stop");
    }
}
