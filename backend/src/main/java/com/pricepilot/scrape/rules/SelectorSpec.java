package com.pricepilot.scrape.rules;

/**
 * One selector from {@code domain-rules.yml}.
 *
 * <p>Two forms are supported:
 *
 * <pre>
 *   "#productTitle"                        -> take the element's text
 *   "meta[property=og:image]@content"      -> take the content attribute
 * </pre>
 *
 * <p>The split is on the last {@code @} that is not inside a CSS attribute bracket. CSS selectors
 * effectively never contain a bare {@code @}, so this is unambiguous in practice, and getting it
 * wrong fails loudly at load time rather than silently returning no price at 3am.
 */
public final class SelectorSpec {

    private final String cssSelector;
    private final String attribute;

    private SelectorSpec(String cssSelector, String attribute) {
        this.cssSelector = cssSelector;
        this.attribute = attribute;
    }

    public static SelectorSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty selector in domain-rules.yml");
        }
        String trimmed = raw.trim();
        int at = trimmed.lastIndexOf('@');
        // Only treat it as an attribute suffix when nothing bracket-ish follows, so
        // `a[href@=x]` (nonsense, but possible) is not silently mangled.
        if (at > 0 && trimmed.indexOf(']', at) < 0 && at < trimmed.length() - 1) {
            String sel = trimmed.substring(0, at).trim();
            String attr = trimmed.substring(at + 1).trim();
            if (!sel.isEmpty() && !attr.isEmpty()) {
                return new SelectorSpec(sel, attr);
            }
        }
        return new SelectorSpec(trimmed, null);
    }

    public String cssSelector() {
        return cssSelector;
    }

    /** Attribute to read, or {@code null} to use the element's text. */
    public String attribute() {
        return attribute;
    }

    public boolean usesAttribute() {
        return attribute != null;
    }

    @Override
    public String toString() {
        return attribute == null ? cssSelector : cssSelector + "@" + attribute;
    }
}
