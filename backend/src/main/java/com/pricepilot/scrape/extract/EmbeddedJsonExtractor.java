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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Digs the price out of JSON embedded in the page's own scripts.
 *
 * <p>This is the strategy that makes single-page storefronts work without a browser, and it is the
 * reason Flipkart is tractable. A Flipkart product page ships almost no price in its HTML — the
 * visible markup is assembled by React at runtime — but the data React renders from is right there
 * in the response, serialised into a script tag. We are not executing the app; we are reading the
 * props it was about to render. Same data, no browser, no 400MB of Chromium on a t3.small.
 *
 * <p>Three mechanisms, tried in order, because the pages differ in how much structure they preserve:
 *
 * <ol>
 *   <li><b>Named state variables</b> ({@code stateVars}: {@code __INITIAL_STATE__},
 *       {@code __NEXT_DATA__}, {@code __PRELOADED_STATE__}). The assignment is located, the object
 *       is extracted with a brace-balanced scan, parsed properly by Jackson, and then walked for
 *       the keys in {@code jsonKeys}. This is the accurate path: a real parse, so a number is a
 *       number and nesting is understood.
 *   <li><b>Host-specific regexes</b> ({@code jsonPatterns}). For when the payload is not valid
 *       standalone JSON — minified JavaScript with function calls in it, or JSON split across
 *       several assignments. Capture group 1 is the price. Flipkart's {@code "finalPrice":{"value":N}}
 *       shape is matched this way.
 *   <li><b>A plain key scan</b> ({@code jsonKeys}). Searches every inline script for
 *       {@code "key": number}. Crude, but it is what rescues Amazon's {@code priceAmount} field, and
 *       the price parser's plausibility bounds keep it from returning a quantity or a rating.
 * </ol>
 *
 * <p>A brace-balanced scan rather than a regex for step one, because JSON nests and regexes do not
 * count. The scanner tracks string state and escapes, so a brace inside a product description —
 * {@code "name": "Set of 3 {assorted}"} — does not end the object early.
 */
@Component
public class EmbeddedJsonExtractor implements PriceExtractor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedJsonExtractor.class);

    /** Stop a runaway scan on a pathological page. 4MB of JSON is already far past normal. */
    private static final int MAX_JSON_CHARS = 4_000_000;
    private static final int MAX_NODES = 60_000;

    /**
     * Price-ish keys recognised on every host, on top of whatever the rule adds. Ordered by
     * confidence: a field named {@code sellingPrice} is a stronger claim than one named {@code value}.
     */
    private static final List<String> COMMON_PRICE_KEYS = List.of(
            "sellingPrice", "finalPrice", "offerPrice", "discountedPrice", "specialPrice",
            "currentPrice", "salePrice", "priceAmount", "displayPrice", "price");

    private static final List<String> COMMON_MRP_KEYS = List.of(
            "mrp", "listPrice", "strikeOffPrice", "originalPrice", "maximumRetailPrice",
            "wasPrice", "basePrice");

    private static final List<String> COMMON_TITLE_KEYS = List.of(
            "productTitle", "productName", "displayName", "titleText", "name", "title");

    private static final List<String> COMMON_IMAGE_KEYS = List.of(
            "imageUrl", "largeImage", "mainImage", "primaryImage", "hiResImage", "image");

    private final ObjectMapper mapper;

    public EmbeddedJsonExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "embedded-json";
    }

    @Override
    public void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out) {
        if (html == null || html.isEmpty()) {
            return;
        }

        // 1. Named state variables, parsed properly.
        for (String var : rule.stateVars()) {
            Optional<JsonNode> tree = extractStateVar(html, var);
            if (tree.isEmpty()) {
                continue;
            }
            if (harvest(tree.get(), rule, out)) {
                return;
            }
        }

        // Script tags that are pure JSON payloads: <script type="application/json" id="__NEXT_DATA__">
        for (Element script : doc.select("script[type=application/json]")) {
            String json = script.data().isBlank() ? script.html() : script.data();
            if (json.isBlank() || json.length() > MAX_JSON_CHARS) {
                continue;
            }
            try {
                if (harvest(mapper.readTree(json), rule, out)) {
                    return;
                }
            } catch (Exception e) {
                log.debug("Unparseable application/json block on {}: {}", rule.host(), e.getMessage());
            }
        }

        // 2. Host-specific regexes over the raw body.
        for (Pattern pattern : rule.jsonPatterns()) {
            Optional<BigDecimal> value = firstGroupAsPrice(pattern, html);
            if (value.isPresent()) {
                out.offerPrice(value.get(), name());
                break;
            }
        }
        for (Pattern pattern : rule.mrpPatterns()) {
            Optional<BigDecimal> value = firstGroupAsPrice(pattern, html);
            if (value.isPresent()) {
                out.offerMrp(value.get(), name());
                break;
            }
        }

        // 3. The plain key scan, over inline scripts only — searching the whole document would pick
        //    up visible text and duplicate what the CSS strategy already does, less reliably.
        if (!out.hasPrice()) {
            String scripts = inlineScripts(doc);
            if (!scripts.isEmpty()) {
                List<String> keys = new ArrayList<>(rule.jsonKeys());
                keys.addAll(COMMON_PRICE_KEYS);
                for (String key : keys) {
                    Optional<BigDecimal> value = scanKey(scripts, key);
                    if (value.isPresent()) {
                        out.offerPrice(value.get(), name());
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ parsed-tree harvesting

    /** @return true when a price was found, meaning later mechanisms can be skipped */
    private boolean harvest(JsonNode root, HostRule rule, SnapshotBuilder out) {
        List<String> priceKeys = new ArrayList<>(rule.jsonKeys());
        priceKeys.addAll(COMMON_PRICE_KEYS);

        Optional<BigDecimal> price = findByKeys(root, priceKeys);
        Optional<BigDecimal> mrp = findByKeys(root, COMMON_MRP_KEYS);

        findTextByKeys(root, COMMON_TITLE_KEYS).ifPresent(out::offerTitle);
        findTextByKeys(root, COMMON_IMAGE_KEYS)
                .filter(v -> v.startsWith("http"))
                .ifPresent(out::offerImage);
        price.ifPresent(v -> out.offerPrice(v, name()));
        mrp.ifPresent(v -> out.offerMrp(v, name()));
        return price.isPresent();
    }

    /**
     * Breadth-first search for the first of {@code keys} that holds a plausible number.
     *
     * <p>Breadth-first on purpose. These state objects are deep, and the same key name appears at
     * many levels: once on the page's own product and again inside every recommendation tile and
     * every "frequently bought together" entry. The page's own product is reliably the shallower
     * one, so the first match at the smallest depth is the right answer. A depth-first search would
     * cheerfully return the price of an accessory.
     *
     * <p>The keys are tried in confidence order at each level, so {@code sellingPrice} two levels
     * deep beats a generic {@code price} one level deep only when they sit at the same depth —
     * depth wins first, then confidence.
     */
    private static Optional<BigDecimal> findByKeys(JsonNode root, List<String> keys) {
        List<JsonNode> level = new ArrayList<>();
        level.add(root);
        int visited = 0;

        while (!level.isEmpty() && visited < MAX_NODES) {
            // Check every key against every node at this depth before descending.
            for (String key : keys) {
                for (JsonNode node : level) {
                    if (!node.isObject()) {
                        continue;
                    }
                    JsonNode value = node.get(key);
                    if (value == null || value.isNull()) {
                        continue;
                    }
                    Optional<BigDecimal> parsed = asPrice(value);
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                }
            }
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : level) {
                visited++;
                if (node.isObject()) {
                    node.fields().forEachRemaining(e -> {
                        if (e.getValue() != null && e.getValue().isContainerNode()) {
                            next.add(e.getValue());
                        }
                    });
                } else if (node.isArray()) {
                    for (JsonNode child : node) {
                        if (child != null && child.isContainerNode()) {
                            next.add(child);
                        }
                    }
                }
            }
            level = next;
        }
        return Optional.empty();
    }

    /**
     * A price may be a number, a numeric string, or a nested object with the amount one level in —
     * {@code "finalPrice": {"value": 49999, "currency": "INR"}} is the common shape.
     */
    private static Optional<BigDecimal> asPrice(JsonNode value) {
        if (value.isNumber()) {
            return PriceParser.parseJsonValue(value.numberValue());
        }
        if (value.isTextual()) {
            return PriceParser.parse(value.asText());
        }
        if (value.isObject()) {
            for (String inner : new String[]{"value", "amount", "price", "displayValue", "raw"}) {
                JsonNode nested = value.get(inner);
                if (nested != null && (nested.isNumber() || nested.isTextual())) {
                    Optional<BigDecimal> parsed = nested.isNumber()
                            ? PriceParser.parseJsonValue(nested.numberValue())
                            : PriceParser.parse(nested.asText());
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> findTextByKeys(JsonNode root, List<String> keys) {
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
                for (String key : keys) {
                    JsonNode value = node.get(key);
                    if (value != null && value.isTextual()) {
                        String text = value.asText().trim();
                        if (text.length() >= 3 && text.length() <= 500) {
                            return Optional.of(text);
                        }
                    }
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
        return Optional.empty();
    }

    // ------------------------------------------------------------------ raw-text mechanisms

    /**
     * Finds {@code window.VAR = {...}} or {@code "VAR": {...}} and returns the parsed object.
     *
     * <p>Every assignment of the variable is tried, not just the first, because some builds emit a
     * small placeholder before the real payload.
     */
    private Optional<JsonNode> extractStateVar(String html, String var) {
        int from = 0;
        while (true) {
            int idx = html.indexOf(var, from);
            if (idx < 0) {
                return Optional.empty();
            }
            from = idx + var.length();

            // Walk forward past the assignment punctuation to the opening brace or bracket.
            int cursor = from;
            boolean sawAssignment = false;
            while (cursor < html.length() && cursor - from < 40) {
                char c = html.charAt(cursor);
                if (c == '=' || c == ':') {
                    sawAssignment = true;
                } else if (c == '{' || c == '[') {
                    break;
                } else if (!Character.isWhitespace(c) && c != '"' && c != '\'' && c != ')') {
                    // Something unexpected: this occurrence is a mention, not an assignment.
                    cursor = -1;
                    break;
                }
                cursor++;
            }
            if (cursor < 0 || cursor >= html.length() || !sawAssignment) {
                continue;
            }
            char open = html.charAt(cursor);
            if (open != '{' && open != '[') {
                continue;
            }

            String json = balanced(html, cursor);
            if (json == null || json.length() > MAX_JSON_CHARS) {
                continue;
            }
            try {
                return Optional.of(mapper.readTree(json));
            } catch (Exception e) {
                log.debug("State var {} did not parse as JSON ({} chars): {}",
                        var, json.length(), e.getMessage());
            }
        }
    }

    /**
     * Returns the balanced {@code {...}} or {@code [...]} beginning at {@code start}, or null if it
     * never closes. String- and escape-aware, so punctuation inside product names is safe.
     */
    static String balanced(String s, int start) {
        char open = s.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < s.length() && i - start < MAX_JSON_CHARS; i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static Optional<BigDecimal> firstGroupAsPrice(Pattern pattern, String html) {
        try {
            Matcher m = pattern.matcher(html);
            while (m.find()) {
                String captured = m.groupCount() >= 1 ? m.group(1) : m.group();
                if (captured == null) {
                    continue;
                }
                Optional<BigDecimal> parsed = PriceParser.parse(captured);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        } catch (RuntimeException e) {
            // A hand-written regex in the rules file can backtrack badly on a large page. Losing
            // one pattern is acceptable; taking down the scrape worker is not.
            log.warn("Rule regex failed while scanning ({}): {}", pattern.pattern(), e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Looks for {@code "key": 49999} or {@code "key":"49999.00"} in script text.
     *
     * <p>The pattern deliberately refuses a value containing a comma. Inside JSON a thousands
     * separator is not legal, so {@code "price": 1,49,900} would be two fields rather than one
     * number, and accepting commas here only ever matches something that is not a price.
     */
    private static Optional<BigDecimal> scanKey(String scripts, String key) {
        Pattern p = Pattern.compile(
                "[\"']" + Pattern.quote(key) + "[\"']\\s*:\\s*[\"']?(\\d+(?:\\.\\d{1,2})?)[\"']?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(scripts);
        while (m.find()) {
            Optional<BigDecimal> parsed = PriceParser.parse(m.group(1));
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    /** Concatenates inline script bodies, skipping the ones already handled by other strategies. */
    private static String inlineScripts(Document doc) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        for (Element script : doc.select("script")) {
            if (!script.attr("src").isBlank()) {
                continue;   // external file; we do not fetch sub-resources
            }
            String type = script.attr("type").toLowerCase(Locale.ROOT);
            if (type.contains("ld+json")) {
                continue;   // JsonLdExtractor's territory
            }
            String data = script.data();
            if (data.isBlank()) {
                continue;
            }
            sb.append(data).append('\n');
            if (sb.length() > MAX_JSON_CHARS) {
                break;
            }
        }
        return sb.toString();
    }
}
