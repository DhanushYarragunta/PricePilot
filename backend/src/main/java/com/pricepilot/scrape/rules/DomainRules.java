package com.pricepilot.scrape.rules;

import com.pricepilot.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads and serves the per-domain extraction rules.
 *
 * <p>Two deliberate design choices here, both about keeping a live site alive:
 *
 * <ol>
 *   <li><b>Startup is strict.</b> A malformed rules file fails the boot with a message naming the
 *       host and the bad value. Better to find out during deploy than to discover at 3am that every
 *       scrape has been silently returning nothing.
 *   <li><b>Reload is safe.</b> {@link #reload()} parses into a brand-new map and only swaps it in
 *       once parsing has fully succeeded. A typo made while hot-fixing a selector on the server
 *       leaves the previous, working rules in place and returns an error to the caller.
 * </ol>
 *
 * <p>Unknown hosts get generic URL normalization and throttling rules. Price extraction
 * still requires a configured BrowserAct workflow; there is no HTML extraction fallback.
 */
@Component
public class DomainRules {

    private static final Logger log = LoggerFactory.getLogger(DomainRules.class);

    private final AppProperties props;
    private final ResourceLoader resourceLoader;

    /** Replaced wholesale on reload; never mutated in place. */
    private volatile Snapshot snapshot = new Snapshot(Map.of(), HostRule.builder("defaults").build());

    public DomainRules(AppProperties props, ResourceLoader resourceLoader) {
        this.props = props;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void load() {
        Snapshot parsed = parse();
        this.snapshot = parsed;
        log.info("Loaded scraping rules for {} hosts from {}",
                parsed.hosts().size(), props.getScrape().getRulesPath());
    }

    /**
     * Re-reads the rules file. Returns a short human-readable summary for the ops endpoint.
     *
     * @throws IllegalStateException if the new file is invalid, in which case the currently loaded
     *                               rules are left untouched
     */
    public String reload() {
        Snapshot parsed = parse();
        this.snapshot = parsed;
        String summary = "Reloaded " + parsed.hosts().size() + " host rules: "
                + String.join(", ", parsed.hosts().keySet());
        log.info(summary);
        return summary;
    }

    /** All configured hosts, for the ops page and the "supported retailers" list in the UI. */
    public List<HostRule> all() {
        return new ArrayList<>(snapshot.hosts().values());
    }

    public HostRule defaults() {
        return snapshot.defaults();
    }

    /**
     * Longest-suffix host match. {@code www.amazon.in} and {@code amazon.in} both resolve to the
     * {@code amazon.in} rule; {@code some-shop.example} falls through to a generic rule.
     */
    public HostRule forHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return HostRule.generic("unknown", snapshot.defaults());
        }
        String host = rawHost.toLowerCase().trim();
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        HostRule best = null;
        int bestLength = -1;
        for (Map.Entry<String, HostRule> e : snapshot.hosts().entrySet()) {
            String candidate = e.getKey();
            boolean matches = host.equals(candidate) || host.endsWith("." + candidate);
            if (matches && candidate.length() > bestLength) {
                best = e.getValue();
                bestLength = candidate.length();
            }
        }
        return best != null ? best : HostRule.generic(host, snapshot.defaults());
    }

    /** True when this host has a hand-written rule rather than the generic fallback. */
    public boolean isKnownHost(String rawHost) {
        if (rawHost == null) {
            return false;
        }
        String host = rawHost.toLowerCase().trim();
        return snapshot.hosts().keySet().stream()
                .anyMatch(c -> host.equals(c) || host.endsWith("." + c));
    }

    // ------------------------------------------------------------------ parsing

    private record Snapshot(Map<String, HostRule> hosts, HostRule defaults) { }

    @SuppressWarnings("unchecked")
    private Snapshot parse() {
        String path = props.getScrape().getRulesPath();
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Scraping rules file not found at '" + path + "'. "
                    + "Set SCRAPE_RULES_PATH to a readable file, or leave it unset to use the "
                    + "bundled classpath:scraping/domain-rules.yml.");
        }

        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException(
                        "Scraping rules file '" + path + "' must be a YAML mapping at the top level.");
            }
            root = (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read scraping rules from '" + path + "'", e);
        }

        Map<String, Object> defaultsNode = mapAt(root, "defaults", "defaults");
        HostRule defaults = buildRule("defaults", defaultsNode, null);

        Map<String, Object> hostsNode = mapAt(root, "hosts", "hosts");
        Map<String, HostRule> hosts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : hostsNode.entrySet()) {
            String host = e.getKey().toLowerCase().trim();
            if (!(e.getValue() instanceof Map)) {
                throw new IllegalStateException(
                        "Rule for host '" + host + "' must be a mapping of settings.");
            }
            hosts.put(host, buildRule(host, (Map<String, Object>) e.getValue(), defaults));
        }

        if (hosts.isEmpty()) {
            log.warn("Scraping rules file '{}' declares no hosts. Every URL will use the generic "
                    + "structured-data-only rule.", path);
        }
        return new Snapshot(Collections.unmodifiableMap(hosts), defaults);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> root, String key, String label) {
        Object node = root.get(key);
        if (node == null) {
            return Map.of();
        }
        if (!(node instanceof Map)) {
            throw new IllegalStateException("'" + label + "' must be a YAML mapping.");
        }
        return (Map<String, Object>) node;
    }

    /**
     * Builds one rule. Where a host and the defaults both provide a list, the host's entries come
     * first and the defaults are appended — a host-specific selector is tried before the generic
     * one, but the generic one is still available as a safety net.
     */
    private static HostRule buildRule(String host, Map<String, Object> node, HostRule defaults) {
        HostRule.Builder b = HostRule.builder(host);

        // No retailer fallback here: HostRule defaults the label to the host name itself.
        b.retailer(str(node, "retailer", null));
        b.currency(firstNonNull(str(node, "currency", null),
                defaults != null ? defaults.currency() : "INR"));
        b.minDelayMs(longAt(node, "min-delay-ms",
                defaults != null ? defaults.minDelayMs() : 4000L, host));
        b.unblockerFirst(boolAt(node, "unblocker-first", false, host));

        List<String> strategies = strings(node, "strategies", host);
        if (strategies.isEmpty() && defaults != null) {
            strategies = defaults.strategies();
        }
        validateStrategies(host, strategies);
        b.strategies(strategies);

        boolean inheritPrices = boolAt(node, "inherit-price-selectors", true, host);
        b.price(selectors(node, "price", host, inheritPrices && defaults != null ? defaults.price() : List.of()));
        b.mrp(selectors(node, "mrp", host, inheritPrices && defaults != null ? defaults.mrp() : List.of()));
        b.title(selectors(node, "title", host, defaults != null ? defaults.title() : List.of()));
        b.image(selectors(node, "image", host, defaults != null ? defaults.image() : List.of()));
        b.outOfStockSelectors(selectors(node, "out-of-stock-selectors", host, List.of()));
        b.availabilitySelectors(selectors(node, "availability-selectors", host, List.of()));

        b.outOfStockText(lowercased(concat(
                strings(node, "out-of-stock-text", host),
                defaults != null ? defaults.outOfStockText() : List.of())));
        b.blockMarkers(lowercased(concat(
                strings(node, "block-markers", host),
                defaults != null ? defaults.blockMarkers() : List.of())));

        b.jsonKeys(concat(strings(node, "json-keys", host),
                defaults != null ? defaults.jsonKeys() : List.of()));
        b.jsonPatterns(concat(patterns(node, "json-patterns", host),
                defaults != null ? defaults.jsonPatterns() : List.of()));
        b.mrpPatterns(concat(patterns(node, "mrp-patterns", host),
                defaults != null ? defaults.mrpPatterns() : List.of()));
        b.stateVars(concat(strings(node, "state-vars", host),
                defaults != null ? defaults.stateVars() : List.of()));

        b.stripQueryParams(lowercased(concat(strings(node, "strip-query-params", host),
                defaults != null ? defaults.stripQueryParams() : List.of())));
        b.keepQueryParams(lowercased(strings(node, "keep-query-params", host)));

        String canonical = str(node, "canonical-regex", null);
        if (canonical != null) {
            try {
                b.canonicalRegex(Pattern.compile(canonical));
            } catch (PatternSyntaxException ex) {
                throw new IllegalStateException(
                        "Invalid canonical-regex for host '" + host + "': " + ex.getMessage(), ex);
            }
        }
        b.canonicalFormat(str(node, "canonical-format", null));

        return b.build();
    }

    private static final List<String> KNOWN_STRATEGIES =
            List.of("json-ld", "microdata", "meta", "embedded-json", "css", "browseract");

    private static void validateStrategies(String host, List<String> strategies) {
        for (String s : strategies) {
            if (!KNOWN_STRATEGIES.contains(s)) {
                throw new IllegalStateException(
                        "Unknown strategy '" + s + "' for host '" + host + "'. Known strategies: "
                        + KNOWN_STRATEGIES);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String str(Map<String, Object> node, String key, String fallback) {
        Object v = node.get(key);
        return v == null ? fallback : String.valueOf(v).trim();
    }

    private static long longAt(Map<String, Object> node, String key, long fallback, String host) {
        Object v = node.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "'" + key + "' for host '" + host + "' must be a number, got: " + v);
        }
    }

    private static boolean boolAt(Map<String, Object> node, String key, boolean fallback, String host) {
        Object v = node.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean bool) {
            return bool;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("true") || s.equals("false")) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalStateException(
                "'" + key + "' for host '" + host + "' must be true or false, got: " + v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> node, String key, String host) {
        Object v = node.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        if (!(v instanceof List)) {
            throw new IllegalStateException(
                    "'" + key + "' for host '" + host + "' must be a list of strings.");
        }
        List<String> out = new ArrayList<>();
        for (Object item : (List<Object>) v) {
            if (item == null) {
                throw new IllegalStateException(
                        "'" + key + "' for host '" + host + "' contains an empty list entry.");
            }
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static List<SelectorSpec> selectors(Map<String, Object> node, String key, String host,
                                                List<SelectorSpec> fallback) {
        List<SelectorSpec> own = new ArrayList<>();
        for (String raw : strings(node, key, host)) {
            try {
                own.add(SelectorSpec.parse(raw));
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "Bad selector '" + raw + "' in '" + key + "' for host '" + host + "': "
                        + ex.getMessage(), ex);
            }
        }
        return concat(own, fallback);
    }

    private static List<Pattern> patterns(Map<String, Object> node, String key, String host) {
        List<Pattern> out = new ArrayList<>();
        for (String raw : strings(node, key, host)) {
            try {
                out.add(Pattern.compile(raw));
            } catch (PatternSyntaxException ex) {
                throw new IllegalStateException(
                        "Invalid regex in '" + key + "' for host '" + host + "': " + raw
                        + " (" + ex.getDescription() + ")", ex);
            }
        }
        return out;
    }

    /** Host entries first, defaults appended, duplicates removed, order preserved. */
    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> out = new ArrayList<>(first.size() + second.size());
        for (T t : first) {
            if (!containsEquivalent(out, t)) {
                out.add(t);
            }
        }
        for (T t : second) {
            if (!containsEquivalent(out, t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * {@link Pattern} and {@link SelectorSpec} do not implement equals, so dedupe on the string
     * form instead. This matters because defaults are appended to every host list.
     */
    private static <T> boolean containsEquivalent(List<T> list, T candidate) {
        String c = String.valueOf(candidate);
        for (T t : list) {
            if (String.valueOf(t).equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> lowercased(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            out.add(s.toLowerCase());
        }
        return out;
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
