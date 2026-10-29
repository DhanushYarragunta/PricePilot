package com.pricepilot.scrape.browseract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserActOutputTest {
    private static final String URL = "https://www.amazon.in/dp/B07CR28HLL";
    private static final String SLUG_URL =
            "https://www.amazon.in/Honeywell-Adaptors-HC000006-ADP-BLK/dp/B07CR28HLL";
    private final ObjectMapper mapper = new ObjectMapper();
    // Output validation alone does not use the budget repository or send HTTP requests.
    private final BrowserActClient client = new BrowserActClient(new AppProperties(), mapper, null);

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
}
