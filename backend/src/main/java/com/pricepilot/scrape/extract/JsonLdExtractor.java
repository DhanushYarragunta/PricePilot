package com.pricepilot.scrape.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.SnapshotBuilder;
import com.pricepilot.scrape.rules.HostRule;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads {@code <script type="application/ld+json">} blocks for a schema.org Product.
 *
 * <p>This is the best strategy available and the reason the whole system works without a browser.
 * Retailers publish this block deliberately, for Google Shopping, so it is machine-readable by
 * design, the field names are a public standard, and — crucially — it survives the front-end
 * redesigns that break every CSS selector on the page. Croma, Reliance Digital, Tata CLiQ, Vijay
 * Sales and Flipkart all publish it. Amazon mostly does not, which is one more reason Amazon is
 * the hard case.
 *
 * <p>Real-world JSON-LD is messier than the spec suggests, and all of the following show up:
 *
 * <pre>
 *   a single object            {"@type":"Product", ...}
 *   an array of objects        [{"@type":"BreadcrumbList"}, {"@type":"Product"}]
 *   an @graph wrapper          {"@graph":[{"@type":"Product"}]}
 *   @type as an array          {"@type":["Product","IndividualProduct"]}
 *   offers as an object        "offers":{"@type":"Offer","price":"1299.00"}
 *   offers as an array         "offers":[{...},{...}]
 *   an AggregateOffer          "offers":{"@type":"AggregateOffer","lowPrice":1299}
 *   price with a currency      "price":"Rs. 1,29,999"
 *   several Product blocks      one for the page, one for a "related items" widget
 * </pre>
 *
 * <p>So the search walks the whole JSON tree looking for Product nodes rather than assuming a
 * shape, and when several are found it prefers the one whose name is the longest — on a product
 * page that is reliably the page's own product rather than a recommendation tile.
 */
@Component
public class JsonLdExtractor implements PriceExtractor {

    private static final Logger log = LoggerFactory.getLogger(JsonLdExtractor.class);

    /** Guard against a pathological document sending the walker into a very large tree. */
    private static final int MAX_NODES = 20_000;

    private final ObjectMapper mapper;

    public JsonLdExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "json-ld";
    }

    @Override
    public void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out) {
        List<JsonNode> products = new ArrayList<>();

        for (Element script : doc.select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json == null || json.isBlank()) {
                json = script.html();
            }
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                collectProducts(mapper.readTree(json), products);
            } catch (Exception e) {
                // A single malformed block is normal — retailers hand-roll these. Keep going.
                log.debug("Skipping unparseable JSON-LD block on {}: {}", rule.host(), e.getMessage());
            }
        }

        if (products.isEmpty()) {
            return;
        }

        JsonNode best = products.get(0);
        if (products.size() > 1) {
            int bestNameLength = nameOf(best).map(String::length).orElse(0);
            for (JsonNode candidate : products) {
                int length = nameOf(candidate).map(String::length).orElse(0);
                boolean candidateHasPrice = findPrice(candidate).isPresent();
                boolean bestHasPrice = findPrice(best).isPresent();
                // A priced block always beats an unpriced one; otherwise the longer name wins.
                if ((candidateHasPrice && !bestHasPrice)
                        || (candidateHasPrice == bestHasPrice && length > bestNameLength)) {
                    best = candidate;
                    bestNameLength = length;
                }
            }
        }

        nameOf(best).ifPresent(out::offerTitle);
        imageOf(best).ifPresent(out::offerImage);
        findPrice(best).ifPresent(p -> out.offerPrice(p, name()));
        findMrp(best).ifPresent(p -> out.offerMrp(p, name()));
        findCurrency(best).ifPresent(out::offerCurrency);
        findAvailability(best).ifPresent(out::offerInStock);
    }

    // ------------------------------------------------------------------ tree walking

    /** Breadth-first so shallow, page-level Product blocks are found before nested ones. */
    private static void collectProducts(JsonNode root, List<JsonNode> found) {
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_NODES) {
            JsonNode node = queue.poll();
            visited++;
            if (node == null) {
                continue;
            }
            if (node.isObject()) {
                if (isProduct(node)) {
                    found.add(node);
                }
                node.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && e.getValue().isContainerNode()) {
                        queue.add(e.getValue());
                    }
                });
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    if (child != null && child.isContainerNode()) {
                        queue.add(child);
                    }
                }
            }
        }
    }

    private static boolean isProduct(JsonNode node) {
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return isProductType(type.asText());
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isTextual() && isProductType(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isProductType(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        // Strip a schema.org URL prefix if present: some sites write the full IRI.
        int slash = t.lastIndexOf('/');
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        return t.equals("product")
                || t.equals("individualproduct")
                || t.equals("productmodel")
                || t.equals("vehicle")
                || t.equals("book");
    }

    // ------------------------------------------------------------------ field reads

    private static Optional<String> nameOf(JsonNode product) {
        JsonNode name = product.get("name");
        if (name != null && name.isTextual() && !name.asText().isBlank()) {
            return Optional.of(name.asText());
        }
        return Optional.empty();
    }

    /** {@code image} may be a string, an array of strings, or an ImageObject with a url. */
    private static Optional<String> imageOf(JsonNode product) {
        JsonNode image = product.get("image");
        if (image == null) {
            return Optional.empty();
        }
        if (image.isTextual()) {
            return Optional.of(image.asText());
        }
        if (image.isArray() && !image.isEmpty()) {
            JsonNode first = image.get(0);
            if (first.isTextual()) {
                return Optional.of(first.asText());
            }
            if (first.isObject() && first.hasNonNull("url")) {
                return Optional.of(first.get("url").asText());
            }
        }
        if (image.isObject() && image.hasNonNull("url")) {
            return Optional.of(image.get("url").asText());
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> findPrice(JsonNode product) {
        for (JsonNode offer : offersOf(product)) {
            Optional<BigDecimal> price = firstNumeric(offer, "price", "lowPrice");
            if (price.isPresent()) {
                return price;
            }
            // Schema allows the price to sit inside a nested PriceSpecification.
            JsonNode spec = offer.get("priceSpecification");
            if (spec != null) {
                Optional<BigDecimal> nested = firstNumeric(spec, "price", "lowPrice");
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return firstNumeric(product, "price");
    }

    private static Optional<BigDecimal> findMrp(JsonNode product) {
        for (JsonNode offer : offersOf(product)) {
            Optional<BigDecimal> mrp = firstNumeric(offer, "highPrice", "listPrice");
            if (mrp.isPresent()) {
                return mrp;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findCurrency(JsonNode product) {
        for (JsonNode offer : offersOf(product)) {
            JsonNode currency = offer.get("priceCurrency");
            if (currency != null && currency.isTextual() && !currency.asText().isBlank()) {
                return Optional.of(currency.asText());
            }
        }
        return Optional.empty();
    }

    /**
     * schema.org availability is an IRI such as {@code https://schema.org/InStock}. Comparing on
     * the last path segment avoids tripping over http-versus-https and bare-name variants, all of
     * which appear in the wild.
     */
    private static Optional<Boolean> findAvailability(JsonNode product) {
        for (JsonNode offer : offersOf(product)) {
            JsonNode availability = offer.get("availability");
            if (availability == null || !availability.isTextual()) {
                continue;
            }
            String value = availability.asText().toLowerCase(Locale.ROOT);
            int slash = value.lastIndexOf('/');
            String token = slash >= 0 ? value.substring(slash + 1) : value;
            switch (token) {
                case "instock", "instoreonly", "limitedavailability", "onlineonly", "presale" -> {
                    return Optional.of(true);
                }
                case "outofstock", "soldout", "discontinued", "backorder" -> {
                    return Optional.of(false);
                }
                default -> {
                    // Unknown token: better to say nothing than to guess wrong and fire a
                    // "back in stock" alert for something that never went out of stock.
                }
            }
        }
        return Optional.empty();
    }

    private static List<JsonNode> offersOf(JsonNode product) {
        List<JsonNode> offers = new ArrayList<>();
        JsonNode node = product.get("offers");
        if (node == null) {
            return offers;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (child != null && child.isObject()) {
                    offers.add(child);
                }
            }
        } else if (node.isObject()) {
            offers.add(node);
            // An AggregateOffer can itself wrap individual offers.
            JsonNode nested = node.get("offers");
            if (nested != null && nested.isArray()) {
                for (JsonNode child : nested) {
                    if (child != null && child.isObject()) {
                        offers.add(child);
                    }
                }
            }
        }
        return offers;
    }

    private static Optional<BigDecimal> firstNumeric(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            Optional<BigDecimal> parsed = value.isNumber()
                    ? PriceParser.parseJsonValue(value.numberValue())
                    : PriceParser.parse(value.asText());
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }
}
