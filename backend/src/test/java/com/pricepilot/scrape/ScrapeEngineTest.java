package com.pricepilot.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.browseract.BrowserActClient;
import com.pricepilot.scrape.browseract.BrowserActResult;
import com.pricepilot.scrape.extract.*;
import com.pricepilot.scrape.rules.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScrapeEngineTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private final DomainRules rules = new DomainRules(new AppProperties(), new DefaultResourceLoader());
    private final HttpPageFetcher direct = mock(HttpPageFetcher.class);
    private final UnblockerPageFetcher unblocker = mock(UnblockerPageFetcher.class);
    private final BrowserActClient browser = mock(BrowserActClient.class);
    private final PolitenessGate gate = mock(PolitenessGate.class);
    private ScrapeEngine engine;
    private MockedStatic<UrlGuard> urlGuard;

    @BeforeEach void setup() throws Exception {
        urlGuard = mockStatic(UrlGuard.class); // Isolate URL normalization from live DNS checks.
        rules.reload();
        when(gate.execute(anyString(), anyLong(), any())).thenAnswer(i -> ((Callable<?>)i.getArgument(2)).call());
        ObjectMapper mapper = new ObjectMapper();
        engine = new ScrapeEngine(rules, gate, direct, unblocker, new UrlCanonicalizer(rules), browser,
                List.of(new CssRuleExtractor(), new JsonLdExtractor(mapper), new MetaTagExtractor(), new MicrodataExtractor()));
    }
    @AfterEach void cleanup() { urlGuard.close(); }

    private void page(String body) {
        when(direct.fetch(eq(URL), any())).thenReturn(new FetchedPage(URL, URL, 200,
                "<meta property='og:title' content='Honeywell USB C Hub'><h1 id='productTitle'>Honeywell USB C Hub</h1>" + body, body.length(), 1, FetchedPage.FetchSource.DIRECT));
    }
    private static String price(String amount, String classes) {
        return "<span class='a-price " + classes + "'><span class='a-offscreen'>" + amount + "</span></span>";
    }
    @Test void sellingPriceWinsEvenWhenMrpComesFirst() {
        page("<div id='corePriceDisplay_desktop_feature_div'>" + price("₹3,999", "a-text-price")
                + price("₹1,609.50", "priceToPay") + "</div>");
        ScrapeResult result = engine.scrapeRaw(URL);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.snapshot().price()).isEqualByComparingTo("1609.50");
        assertThat(result.snapshot().mrp()).isEqualByComparingTo("3999");
    }
    @Test void mrpAndRecommendationPricesCannotBecomeSellingPrices() {
        page("<div id='corePriceDisplay_desktop_feature_div'>" + price("₹3,999", "a-text-price") + "</div>"
                + "<div id='recommendations'>" + price("₹299", "priceToPay") + "</div>"
                + "<meta itemprop='price' content='3999'><script>{\"priceAmount\":3999}</script>"
                + "<script type='application/ld+json'>{\"@type\":\"Product\",\"name\":\"Other item\",\"offers\":{\"price\":3999}}</script>");
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }
    @Test void emiCannotBecomeSellingPrice() {
        page("<div id='corePriceDisplay_desktop_feature_div'><div id='emi'>" + price("₹149", "") + "</div></div>");
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }
    @Test void nestedPriceDigitsRemainTogether() {
        page("<div id='corePriceDisplay_desktop_feature_div'>" + price("₹<span>1,609</span><span>.50</span>", "priceToPay") + "</div>");
        assertThat(engine.scrapeRaw(URL).snapshot().price()).isEqualByComparingTo("1609.50");
    }
    @Test void contradictoryMrpRejectsSnapshot() {
        page("<div id='corePriceDisplay_desktop_feature_div'>" + price("₹999", "a-text-price") + price("₹1,609", "priceToPay") + "</div>");
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }
    @Test void failedBrowserActIsAttemptedOnlyOnceThenHtmlWorks() {
        when(browser.isConfigured()).thenReturn(true);
        when(browser.extract(URL)).thenReturn(Optional.empty());
        page("<span id='priceblock_dealprice'>₹1609</span>");
        assertThat(engine.scrapeRaw(URL).isSuccess()).isTrue();
        verify(browser, times(1)).extract(URL);
    }
    @Test void validBrowserActSkipsHtmlAndDoesNotInventRetailerHttpStatus() {
        when(browser.isConfigured()).thenReturn(true);
        when(browser.extract(URL)).thenReturn(Optional.of(new BrowserActResult("Honeywell USB C Hub", new BigDecimal("1609"),
                new BigDecimal("3999"), "INR", true, "B07CR28HLL", null, "success")));
        ScrapeResult result = engine.scrapeRaw(URL);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.httpStatus()).isNull();
        assertThat(result.paid()).isTrue();
        verifyNoInteractions(direct);
    }
    @Test void blockAndMissingPricesAreFailures() {
        page("Enter the characters you see below");
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.BLOCKED);
        page("No offer available");
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }
    @Test void otherRetailersKeepJsoupAndNeverInvokeBrowserAct() {
        when(browser.isConfigured()).thenReturn(true);
        String url = "https://www.croma.com/example/p/12345";
        String html = "<script type='application/ld+json'>{\"@type\":\"Product\",\"name\":\"Test Product\",\"offers\":{\"price\":1609,\"priceCurrency\":\"INR\"}}</script>";
        when(direct.fetch(eq(url), any())).thenReturn(new FetchedPage(url,url,200,html,html.length(),1,FetchedPage.FetchSource.DIRECT));
        assertThat(engine.scrapeRaw(url).snapshot().price()).isEqualByComparingTo("1609");
        verify(browser, never()).extract(anyString());
    }
    @Test void differentProductRedirectIsRejected() {
        String other = "https://www.amazon.in/dp/B000000001";
        when(direct.fetch(eq(URL), any())).thenReturn(new FetchedPage(URL,other,200,
                "<meta property='og:title' content='Other Product'><h1>Other Product</h1><span id='priceblock_dealprice'>₹999</span>",100,1,FetchedPage.FetchSource.DIRECT));
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }
    @Test void equivalentAmazonUrlsDeduplicateButDifferentAsinsDoNot() {
        UrlCanonicalizer c = new UrlCanonicalizer(rules);
        assertThat(c.canonicalize("http://amazon.in/Some-Title/gp/product/B07CR28HLL/ref=abc?tag=tracking").hash())
                .isEqualTo(c.canonicalize(URL).hash());
        assertThat(c.canonicalize("https://amazon.in/dp/B000000001").hash()).isNotEqualTo(c.canonicalize(URL).hash());
        assertThatThrownBy(() -> c.canonicalize("https://amazon.in/s?k=usb"))
                .isInstanceOf(UnsupportedUrlException.class);
    }
}
