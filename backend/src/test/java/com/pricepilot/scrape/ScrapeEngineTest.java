package com.pricepilot.scrape;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.browseract.BrowserActClient;
import com.pricepilot.scrape.browseract.BrowserActExtraction;
import com.pricepilot.scrape.browseract.BrowserActResult;
import com.pricepilot.scrape.rules.DomainRules;
import com.pricepilot.scrape.rules.HostRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScrapeEngineTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private final DomainRules rules = new DomainRules(new AppProperties(), new DefaultResourceLoader());
    private final BrowserActClient browser = mock(BrowserActClient.class);
    private final PolitenessGate gate = mock(PolitenessGate.class);
    private ScrapeEngine engine;
    private MockedStatic<UrlGuard> urlGuard;

    @BeforeEach void setup() throws Exception {
        urlGuard = mockStatic(UrlGuard.class); // DNS is outside these routing tests.
        rules.reload();
        when(gate.execute(anyString(), anyLong(), any())).thenAnswer(i -> ((Callable<?>) i.getArgument(2)).call());
        engine = new ScrapeEngine(rules, gate, new UrlCanonicalizer(rules), browser);
    }
    @AfterEach void cleanup() { urlGuard.close(); }

    private static BrowserActResult product(String sellingPrice, String mrp, String currency) {
        return new BrowserActResult("USB C Hub", new BigDecimal(sellingPrice),
                mrp == null ? null : new BigDecimal(mrp), currency, false, null, null, "success");
    }
    private void succeeds(BrowserActResult product) {
        when(browser.extract(anyString(), any())).thenReturn(
                new BrowserActExtraction(product, ScrapeOutcome.SUCCESS, null, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            URL,
            "https://www.flipkart.com/usb-hub/p/itm123?pid=HUB123&otracker=search",
            "https://www.croma.com/usb-hub/p/12345",
            "https://www.reliancedigital.in/usb-hub/p/12345",
            "https://www.vijaysales.com/usb-hub/12345",
            "https://www.tatacliq.com/usb-hub/p-mp00000012345",
            "https://www.jiomart.com/p/electronics/usb-hub/12345",
            "https://www.snapdeal.com/product/usb-hub/12345",
            "https://www.myntra.com/shirts/example/12345/buy",
            "https://www.ajio.com/example-shirt/p/12345",
            "https://www.nykaa.com/example-product/p/12345",
            "https://www.sangeethamobiles.com/product/example/12345",
            "https://www.poorvika.com/example-product"
    })
    void everySupportedRetailerUsesOneBrowserActSubmission(String url) {
        succeeds(product("1609.50", "3999", "INR"));
        var canonical = new UrlCanonicalizer(rules).canonicalize(url);
        assertThat(rules.isKnownHost(canonical.host())).isTrue();
        ScrapeResult result = engine.scrapeRaw(url);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.snapshot().price()).isEqualByComparingTo("1609.50");
        assertThat(result.snapshot().mrp()).isEqualByComparingTo("3999");
        assertThat(result.snapshot().currency()).isEqualTo("INR");
        assertThat(result.snapshot().title()).isEqualTo("USB C Hub");
        assertThat(result.snapshot().inStock()).isFalse();
        assertThat(result.strategy()).isEqualTo("browseract");
        assertThat(result.source()).isEqualTo(FetchedPage.FetchSource.BROWSERACT);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.paid()).isTrue();
        verify(browser).extract(canonical.url(), canonical.rule());
        verifyNoMoreInteractions(browser);
        verify(gate).execute(eq("browseract:" + canonical.rule().host()), eq(canonical.rule().minDelayMs()), any());
    }

    @ParameterizedTest
    @EnumSource(value = ScrapeOutcome.class, names = {"ERROR", "NO_PRICE", "TIMEOUT", "BLOCKED"})
    void failedBrowserActNeverRetriesOrSwitchesToAnotherPriceSource(ScrapeOutcome outcome) {
        // Even an older mounted rules file requesting HTML/unblocker cannot activate it.
        HostRule legacy = HostRule.builder("croma.com").strategies(List.of("json-ld", "css"))
                .unblockerFirst(true).build();
        String url = "https://www.croma.com/usb-hub/p/12345";
        when(browser.extract(url, legacy)).thenReturn(new BrowserActExtraction(null, outcome,
                "BrowserAct failed; previous price preserved.", true));
        var result = engine.scrape(url, legacy);
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.snapshot()).isNull();
        assertThat(result.source()).isEqualTo(FetchedPage.FetchSource.BROWSERACT);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.isGone()).isFalse();
        assertThat(result.strategy()).isEqualTo("browseract");
        verify(browser).extract(url, legacy);
        verifyNoMoreInteractions(browser);
    }

    @Test void noConfiguredWorkflowIsARecordedFailureWithoutAPaidSubmission() {
        when(browser.extract(anyString(), any())).thenReturn(new BrowserActExtraction(null,
                ScrapeOutcome.ERROR, "No BrowserAct workflow is configured for croma.com.", false));
        var result = engine.scrapeRaw("https://www.croma.com/usb-hub/p/12345");
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.snapshot()).isNull();
        assertThat(result.source()).isNull();
        assertThat(result.paid()).isFalse();
        assertThat(result.message()).contains("workflow");
        verify(browser).extract(anyString(), any());
        verifyNoMoreInteractions(browser);
    }

    @Test void sharedValidationStillRejectsContradictoryMrp() {
        succeeds(product("1609", "999", "INR"));
        var result = engine.scrapeRaw(URL);
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
        assertThat(result.snapshot()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "100000001"})
    void sharedValidationStillRejectsInvalidPrice(String amount) {
        succeeds(product(amount, null, "INR"));
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }

    @Test void sharedValidationStillRejectsForeignCurrency() {
        succeeds(product("100", null, "USD"));
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }

    @Test void sharedValidationDoesNotInferMissingCurrency() {
        succeeds(product("100", null, null));
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.NO_PRICE);
    }

    @Test void clientExceptionReturnsSafeFailureWithoutFallback() {
        when(browser.extract(anyString(), any())).thenThrow(new IllegalStateException("secret provider text"));
        var result = engine.scrapeRaw(URL);
        assertThat(result.outcome()).isEqualTo(ScrapeOutcome.ERROR);
        assertThat(result.snapshot()).isNull();
        assertThat(result.message()).doesNotContain("secret provider text");
        verify(browser).extract(anyString(), any());
        verifyNoMoreInteractions(browser);
    }

    @Test void interruptedThrottleDoesNotCallProvider() {
        doThrow(new ScrapeException(ScrapeOutcome.TIMEOUT, "interrupted"))
                .when(gate).execute(anyString(), anyLong(), any());
        assertThat(engine.scrapeRaw(URL).outcome()).isEqualTo(ScrapeOutcome.TIMEOUT);
        verifyNoInteractions(browser);
    }

    @Test void unsafeRawInputNeverReachesProvider() {
        urlGuard.when(() -> UrlGuard.assertFetchable(any())).thenThrow(new UnsupportedUrlException("Unsafe URL"));
        assertThatThrownBy(() -> engine.scrapeRaw("http://localhost/product"))
                .isInstanceOf(UnsupportedUrlException.class);
        verifyNoInteractions(browser, gate);
    }

    @Test void equivalentAmazonUrlsDeduplicateButDifferentAsinsDoNot() {
        UrlCanonicalizer canonicalizer = new UrlCanonicalizer(rules);
        assertThat(canonicalizer.canonicalize("http://amazon.in/Some-Title/gp/product/B07CR28HLL/ref=abc?tag=tracking").hash())
                .isEqualTo(canonicalizer.canonicalize(URL).hash());
        assertThat(canonicalizer.canonicalize("https://amazon.in/dp/B000000001").hash())
                .isNotEqualTo(canonicalizer.canonicalize(URL).hash());
        assertThatThrownBy(() -> canonicalizer.canonicalize("https://amazon.in/s?k=usb"))
                .isInstanceOf(UnsupportedUrlException.class);
    }

    @Test void everyRuleAdvertisesOnlyBrowserAct() {
        assertThat(rules.all()).hasSize(13).allSatisfy(rule ->
                assertThat(rule.strategies()).containsExactly("browseract"));
        assertThat(rules.forHost("other-shop.example").strategies()).containsExactly("browseract");
    }
}
