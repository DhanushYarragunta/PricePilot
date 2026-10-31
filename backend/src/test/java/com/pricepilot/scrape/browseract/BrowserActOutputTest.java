package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.UrlGuard;
import com.pricepilot.scrape.rules.DomainRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class BrowserActOutputTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private static final String SLUG_URL =
            "https://www.amazon.in/Honeywell-Adaptors-HC000006-ADP-BLK/dp/B07CR28HLL";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties props = new AppProperties();
    private final DomainRules rules = new DomainRules(props, new DefaultResourceLoader());
    // Output validation alone does not use the budget repository or send HTTP requests.
    private final BrowserActClient client = new BrowserActClient(props, mapper, null, new UrlCanonicalizer(rules));
    private MockedStatic<UrlGuard> urlGuard;

    @BeforeEach void setup() {
        rules.reload();
        urlGuard = mockStatic(UrlGuard.class); // Keep actual normalization, isolate live DNS checks.
    }

    @AfterEach void cleanup() { urlGuard.close(); }

    private ObjectNode product(String url) {
        ObjectNode product = mapper.createObjectNode();
        product.put("status", "success");
        product.put("url", url);
        product.put("asin", "B07CR28HLL");
        product.put("title", "Honeywell USB C Hub");
        product.put("selling_price", "1609.00");
        product.put("mrp", "3999.00");
        product.put("currency", "INR");
        product.put("availability", "in_stock");
        return product;
    }

    private ObjectNode task(ObjectNode product) {
        ObjectNode task = mapper.createObjectNode();
        task.put("id", "task-fixture");
        task.put("status", "finished");
        task.putObject("output").put("string", product.toString());
        return task;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            URL,
            "https://amazon.in/gp/product/B07CR28HLL",
            SLUG_URL,
            SLUG_URL + "/ref=sr_1_4?psc=1&tag=example-21",
            "https://www.amazon.in/Honeywell-Adaptors/gp/product/B07CR28HLL/"
    })
    void acceptsEquivalentRequestedAndReturnedProductUrls(String url) {
        var returnedSlug = client.parseOutput(task(product(url)), URL).orElseThrow();
        assertThat(returnedSlug.asin()).isEqualTo("B07CR28HLL");
        assertThat(returnedSlug.sellingPrice()).isEqualByComparingTo("1609.00");
        assertThat(returnedSlug.mrp()).isEqualByComparingTo("3999.00");

        var requestedSlug = client.parseOutput(task(product(URL)), url).orElseThrow();
        assertThat(requestedSlug.asin()).isEqualTo("B07CR28HLL");
        assertThat(requestedSlug.sellingPrice()).isEqualByComparingTo("1609.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.amazon.in/Other-Product/dp/B000000001",
            "https://amazon.in.evil.example/Honeywell/dp/B07CR28HLL",
            "https://user@www.amazon.in/Honeywell/dp/B07CR28HLL",
            "http://www.amazon.in/Honeywell/dp/B07CR28HLL",
            "https://www.amazon.in:444/Honeywell/dp/B07CR28HLL",
            "https://www.amazon.in/Honeywell/dp/B07CR28HLLX",
            "https://www.amazon.in/Honeywell/dp/B07CR28HL",
            "https://www.amazon.in/s?k=Honeywell&url=/dp/B07CR28HLL",
            "https://www.amazon.in/Honeywell#dp/B07CR28HLL"
    })
    void rejectsWrongProductAndInvalidReturnedUrls(String url) {
        assertThat(client.parseOutput(task(product(url)), URL)).isEmpty();
    }

    @Test
    void matchingSlugUrlDoesNotOverrideConflictingAsinField() {
        ObjectNode product = product(SLUG_URL);
        product.put("asin", "B000000001");
        assertThat(client.parseOutput(task(product), URL)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.flipkart.com/phone/p/itm123?pid=MOB123",
            "https://www.croma.com/phone/p/123456",
            "https://www.reliancedigital.in/phone/p/123456",
            "https://www.vijaysales.com/phone/123456",
            "https://www.tatacliq.com/phone/p-mp00000123456",
            "https://www.jiomart.com/p/electronics/phone/123456",
            "https://www.snapdeal.com/product/phone/123456",
            "https://www.myntra.com/shoes/brand/model/123456/buy",
            "https://www.ajio.com/shoes/p/123456",
            "https://www.nykaa.com/cream/p/123456",
            "https://www.sangeethamobiles.com/phone/123456",
            "https://www.poorvika.com/phone/123456"
    })
    void acceptsEveryOtherSupportedRetailerWithoutAsin(String url) {
        ObjectNode p = product(url);
        p.remove("asin");
        var result = client.parseOutput(task(p), url).orElseThrow();
        assertThat(result.asin()).isNull();
        assertThat(result.sellingPrice()).isEqualByComparingTo("1609");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://www.flipkart.com/phone/p/itm123?pid=MOB123 | https://flipkart.com/phone/p/itm123?utm_source=test&pid=MOB123&lid=LST123",
            "https://www.croma.com/phone/p/123456 | https://croma.com/phone/p/123456?utm_source=test",
            "https://www.croma.com/phone/p/123456 | https://croma.com:443/phone/p/123456",
            "http://www.croma.com/phone/p/123456 | http://croma.com:80/phone/p/123456",
            "http://www.croma.com/phone/p/123456 | https://croma.com/phone/p/123456",
            "https://www.reliancedigital.in/phone/p/123456 | https://reliancedigital.in/phone/p/123456?ref=home",
            "https://www.myntra.com/shoes/brand/model/123456/buy | https://myntra.com/shoes/brand/model/123456/buy/?utm_campaign=test"
    })
    void acceptsEquivalentRetailerUrls(String requested, String returned) {
        ObjectNode p = product(returned);
        p.remove("asin");
        assertThat(client.parseOutput(task(p), requested)).isPresent();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://www.flipkart.com/phone/p/itm123?pid=MOB123 | https://www.flipkart.com/phone/p/itm123?pid=MOB999",
            "https://www.croma.com/phone/p/123456 | https://www.croma.com/phone/p/654321",
            "https://www.reliancedigital.in/phone/p/123456 | https://www.reliancedigital.in/phone/p/654321",
            "https://www.myntra.com/shoes/brand/model/123456/buy | https://www.myntra.com/shoes/brand/model/654321/buy",
            "https://www.croma.com/phone/p/123456 | https://www.reliancedigital.in/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | https://croma.com.evil.example/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | https://user@www.croma.com/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | https://www.croma.com:8443/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | https://www.croma.com:80/phone/p/123456",
            "http://www.croma.com/phone/p/123456 | http://www.croma.com:8080/phone/p/123456",
            "http://www.croma.com/phone/p/123456 | http://www.croma.com:443/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | http://www.croma.com/phone/p/123456",
            "https://www.croma.com/phone/p/123456 | file:///phone/p/123456",
            "https://www.croma.com/phone/p/123456 | http://localhost/phone/p/123456"
    })
    void rejectsDifferentRetailerProductsAndUnsafeUrls(String requested, String returned) {
        assertThat(client.parseOutput(task(product(returned)), requested)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.99", "5000001", "99.999", "USD 99", "17% off",
            "₹2,000 per month", "EMI ₹149", "MRP ₹3999", "₹999 with exchange", "₹1609–₹3999"})
    void rejectsInvalidSellingPricesForOtherRetailers(String price) {
        String url = "https://www.croma.com/phone/p/123456";
        ObjectNode p = product(url);
        p.put("selling_price", price);
        assertThat(client.parseOutput(task(p), url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "100", "not a price", "999999999"})
    void rejectsInvalidOrContradictoryMrp(String mrp) {
        String url = "https://www.flipkart.com/phone/p/itm123?pid=MOB123";
        ObjectNode p = product(url);
        p.put("mrp", mrp);
        assertThat(client.parseOutput(task(p), url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "url", "title", "selling_price", "currency", "availability"})
    void otherRetailersStillRequireGeneralProductContract(String field) {
        String url = "https://www.nykaa.com/cream/p/123456";
        ObjectNode p = product(url);
        p.remove(field);
        assertThat(client.parseOutput(task(p), url)).isEmpty();
    }

    @Test
    void neverSubstitutesMrpInstallmentsExchangeOrRecommendationPricesForSellingPrice() {
        String url = "https://www.flipkart.com/phone/p/itm123?pid=MOB123";
        ObjectNode p = product(url);
        p.remove("selling_price");
        p.put("price", 1609);
        p.put("emi", 149);
        p.put("exchange_price", 999);
        p.putArray("recommendations").addObject().put("selling_price", 299);
        assertThat(client.parseOutput(task(p), url)).isEmpty();
    }

    @Test
    void rejectsMatchingHashWhenRetailerRuleIsDifferent() {
        var canonicalizer = org.mockito.Mockito.mock(UrlCanonicalizer.class);
        var croma = rules.forHost("croma.com");
        String requested = "https://www.croma.com/phone/p/123456";
        String returned = "https://www.reliancedigital.in/phone/p/123456";
        org.mockito.Mockito.when(canonicalizer.canonicalize(requested)).thenReturn(
                new UrlCanonicalizer.Canonical(requested, "www.croma.com", "same-hash", croma));
        org.mockito.Mockito.when(canonicalizer.canonicalize(returned)).thenReturn(
                new UrlCanonicalizer.Canonical(returned, "www.reliancedigital.in", "same-hash", rules.forHost("reliancedigital.in")));
        var checkedClient = new BrowserActClient(props, mapper, null, canonicalizer);
        assertThat(checkedClient.parseOutput(task(product(returned)), requested, croma)).isEmpty();
    }

    @Test
    void snapshotMappingNeverInfersMissingCurrency() {
        var result = new BrowserActResult("Product title", new java.math.BigDecimal("1999"),
                null, null, true, null, null, "success");
        var snapshot = BrowserActPriceExtractor.snapshot(result);
        assertThat(snapshot.currency()).isNull();
        assertThat(snapshot.isVerified()).isFalse();
        assertThat(snapshot.title()).isEqualTo(result.title());
        assertThat(snapshot.price()).isEqualTo(result.sellingPrice());
    }
}
