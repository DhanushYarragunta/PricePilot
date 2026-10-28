package com.pricepilot.scrape.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The extraction recipe for one retailer, assembled from {@code domain-rules.yml}.
 *
 * <p>Instances are immutable once built, so a rules reload swaps whole objects rather than mutating
 * anything a running scrape might be reading.
 */
public final class HostRule {

    private final String host;
    private final String retailer;
    private final String currency;
    private final long minDelayMs;
    private final boolean unblockerFirst;

    private final List<String> strategies;
    private final List<SelectorSpec> price;
    private final List<SelectorSpec> mrp;
    private final List<SelectorSpec> title;
    private final List<SelectorSpec> image;
    private final List<SelectorSpec> outOfStockSelectors;
    private final List<SelectorSpec> availabilitySelectors;

    private final List<String> outOfStockText;
    private final List<String> blockMarkers;
    private final List<String> jsonKeys;
    private final List<Pattern> jsonPatterns;
    private final List<Pattern> mrpPatterns;
    private final List<String> stateVars;

    private final List<String> stripQueryParams;
    private final List<String> keepQueryParams;
    private final Pattern canonicalRegex;
    private final String canonicalFormat;

    private HostRule(Builder b) {
        this.host = b.host;
        this.retailer = b.retailer != null ? b.retailer : b.host;
        this.currency = b.currency != null ? b.currency : "INR";
        this.minDelayMs = b.minDelayMs;
        this.unblockerFirst = b.unblockerFirst;
        this.strategies = List.copyOf(b.strategies);
        this.price = List.copyOf(b.price);
        this.mrp = List.copyOf(b.mrp);
        this.title = List.copyOf(b.title);
        this.image = List.copyOf(b.image);
        this.outOfStockSelectors = List.copyOf(b.outOfStockSelectors);
        this.availabilitySelectors = List.copyOf(b.availabilitySelectors);
        this.outOfStockText = List.copyOf(b.outOfStockText);
        this.blockMarkers = List.copyOf(b.blockMarkers);
        this.jsonKeys = List.copyOf(b.jsonKeys);
        this.jsonPatterns = List.copyOf(b.jsonPatterns);
        this.mrpPatterns = List.copyOf(b.mrpPatterns);
        this.stateVars = List.copyOf(b.stateVars);
        this.stripQueryParams = List.copyOf(b.stripQueryParams);
        this.keepQueryParams = List.copyOf(b.keepQueryParams);
        this.canonicalRegex = b.canonicalRegex;
        this.canonicalFormat = b.canonicalFormat;
    }

    public static Builder builder(String host) {
        return new Builder(host);
    }

    public String host() { return host; }
    public String retailer() { return retailer; }
    public String currency() { return currency; }
    public long minDelayMs() { return minDelayMs; }

    /** When true, go straight to the unblocker provider instead of trying a direct fetch first. */
    public boolean unblockerFirst() { return unblockerFirst; }

    public List<String> strategies() { return strategies; }
    public List<SelectorSpec> price() { return price; }
    public List<SelectorSpec> mrp() { return mrp; }
    public List<SelectorSpec> title() { return title; }
    public List<SelectorSpec> image() { return image; }
    public List<SelectorSpec> outOfStockSelectors() { return outOfStockSelectors; }
    public List<SelectorSpec> availabilitySelectors() { return availabilitySelectors; }
    public List<String> outOfStockText() { return outOfStockText; }
    public List<String> blockMarkers() { return blockMarkers; }
    public List<String> jsonKeys() { return jsonKeys; }
    public List<Pattern> jsonPatterns() { return jsonPatterns; }
    public List<Pattern> mrpPatterns() { return mrpPatterns; }
    public List<String> stateVars() { return stateVars; }
    public List<String> stripQueryParams() { return stripQueryParams; }

    /**
     * When non-empty, only these query parameters survive canonicalisation and everything else is
     * dropped. Used for hosts like Flipkart where one parameter ({@code pid}) identifies the
     * product and the rest is session noise.
     */
    public List<String> keepQueryParams() { return keepQueryParams; }

    public Pattern canonicalRegex() { return canonicalRegex; }
    public String canonicalFormat() { return canonicalFormat; }

    public boolean supports(String strategy) {
        return strategies.contains(strategy);
    }

    @Override
    public String toString() {
        return "HostRule[" + host + " -> " + retailer + ", strategies=" + strategies + "]";
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private final String host;
        private String retailer;
        private String currency;
        private long minDelayMs = 4000L;
        private boolean unblockerFirst = false;

        private List<String> strategies = new ArrayList<>();
        private List<SelectorSpec> price = new ArrayList<>();
        private List<SelectorSpec> mrp = new ArrayList<>();
        private List<SelectorSpec> title = new ArrayList<>();
        private List<SelectorSpec> image = new ArrayList<>();
        private List<SelectorSpec> outOfStockSelectors = new ArrayList<>();
        private List<SelectorSpec> availabilitySelectors = new ArrayList<>();
        private List<String> outOfStockText = new ArrayList<>();
        private List<String> blockMarkers = new ArrayList<>();
        private List<String> jsonKeys = new ArrayList<>();
        private List<Pattern> jsonPatterns = new ArrayList<>();
        private List<Pattern> mrpPatterns = new ArrayList<>();
        private List<String> stateVars = new ArrayList<>();
        private List<String> stripQueryParams = new ArrayList<>();
        private List<String> keepQueryParams = new ArrayList<>();
        private Pattern canonicalRegex;
        private String canonicalFormat;

        private Builder(String host) {
            this.host = host;
        }

        public Builder retailer(String v) { this.retailer = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder minDelayMs(long v) { this.minDelayMs = v; return this; }
        public Builder unblockerFirst(boolean v) { this.unblockerFirst = v; return this; }
        public Builder strategies(List<String> v) { this.strategies = v; return this; }
        public Builder price(List<SelectorSpec> v) { this.price = v; return this; }
        public Builder mrp(List<SelectorSpec> v) { this.mrp = v; return this; }
        public Builder title(List<SelectorSpec> v) { this.title = v; return this; }
        public Builder image(List<SelectorSpec> v) { this.image = v; return this; }
        public Builder outOfStockSelectors(List<SelectorSpec> v) { this.outOfStockSelectors = v; return this; }
        public Builder availabilitySelectors(List<SelectorSpec> v) { this.availabilitySelectors = v; return this; }
        public Builder outOfStockText(List<String> v) { this.outOfStockText = v; return this; }
        public Builder blockMarkers(List<String> v) { this.blockMarkers = v; return this; }
        public Builder jsonKeys(List<String> v) { this.jsonKeys = v; return this; }
        public Builder jsonPatterns(List<Pattern> v) { this.jsonPatterns = v; return this; }
        public Builder mrpPatterns(List<Pattern> v) { this.mrpPatterns = v; return this; }
        public Builder stateVars(List<String> v) { this.stateVars = v; return this; }
        public Builder stripQueryParams(List<String> v) { this.stripQueryParams = v; return this; }
        public Builder keepQueryParams(List<String> v) { this.keepQueryParams = v; return this; }
        public Builder canonicalRegex(Pattern v) { this.canonicalRegex = v; return this; }
        public Builder canonicalFormat(String v) { this.canonicalFormat = v; return this; }

        public HostRule build() {
            if (strategies.isEmpty()) {
                strategies = new ArrayList<>(List.of("json-ld", "microdata", "meta", "css"));
            }
            return new HostRule(this);
        }
    }

    /** A rule for a host that has no entry of its own: structured data only. */
    public static HostRule generic(String host, HostRule defaults) {
        return builder(host)
                .retailer(prettyRetailer(host))
                .currency(defaults.currency())
                .minDelayMs(defaults.minDelayMs())
                .strategies(defaults.strategies())
                .price(defaults.price())
                .mrp(defaults.mrp())
                .title(defaults.title())
                .image(defaults.image())
                .outOfStockSelectors(Collections.emptyList())
                .availabilitySelectors(Collections.emptyList())
                .outOfStockText(defaults.outOfStockText())
                .blockMarkers(defaults.blockMarkers())
                .jsonKeys(defaults.jsonKeys())
                .jsonPatterns(defaults.jsonPatterns())
                .mrpPatterns(defaults.mrpPatterns())
                .stateVars(defaults.stateVars())
                .stripQueryParams(defaults.stripQueryParams())
                .keepQueryParams(Collections.emptyList())
                .build();
    }

    /** "www.example-store.co.in" -> "Example Store". Good enough for a label. */
    static String prettyRetailer(String host) {
        String h = host.startsWith("www.") ? host.substring(4) : host;
        int dot = h.indexOf('.');
        String base = dot > 0 ? h.substring(0, dot) : h;
        String[] words = base.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? host : sb.toString();
    }
}
