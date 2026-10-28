package com.pricepilot.scrape.extract;

import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.SnapshotBuilder;
import com.pricepilot.scrape.rules.HostRule;
import com.pricepilot.scrape.rules.SelectorSpec;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Applies the per-host CSS selectors from {@code domain-rules.yml}.
 *
 * <p>Last in the chain for most retailers, and first for Amazon. That inversion is the whole point
 * of making the order configurable: Amazon rarely publishes JSON-LD on a product page, so waiting
 * for structured data there would mean waiting for something that is not coming, while Croma
 * publishes clean JSON-LD and reaching for its CSS classes first would mean depending on class names
 * that change whenever the design team ships.
 *
 * <p>So this strategy is the fragile one, and it is treated accordingly. Selectors are a list, not a
 * single value, and they are tried in order until one yields a plausible number — so when Amazon
 * renames {@code .a-price-whole}, the next entry in the list usually still works and the site keeps
 * running until the rule is edited. The ops page reports success rate per strategy precisely so that
 * "the CSS rules for amazon.in have started failing" is a visible, specific signal rather than a
 * vague sense that something is wrong.
 *
 * <p>A selector may ask for an attribute instead of text, written {@code selector@attr}:
 *
 * <pre>
 *   "span.a-offscreen"                     -&gt; the element's text
 *   "meta[itemprop=price]@content"         -&gt; the content attribute
 *   "img#landingImage@data-old-hires"      -&gt; Amazon's full-resolution image
 * </pre>
 *
 * <p>Image attributes go through Jsoup's {@code absUrl}, which resolves against the document base
 * URI — set by the engine from the response's final URL, so relative paths and protocol-relative
 * {@code //cdn...} URLs both come out as working links.
 */
@Component
public class CssRuleExtractor implements PriceExtractor {

    private static final Logger log = LoggerFactory.getLogger(CssRuleExtractor.class);

    @Override
    public String name() {
        return "css";
    }

    @Override
    public void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out) {
        firstNumber(doc, rule.price(), rule).ifPresent(v -> out.offerPrice(v, name()));
        firstNumber(doc, rule.mrp(), rule).ifPresent(v -> out.offerMrp(v, name()));
        firstText(doc, rule.title(), rule).ifPresent(out::offerTitle);
        firstUrl(doc, rule.image(), rule).ifPresent(out::offerImage);
    }

    // ------------------------------------------------------------------ selector application

    private static Optional<BigDecimal> firstNumber(Document doc, List<SelectorSpec> specs, HostRule rule) {
        for (SelectorSpec spec : specs) {
            for (Element el : select(doc, spec, rule)) {
                Optional<BigDecimal> parsed = PriceParser.parse(valueOf(el, spec));
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstText(Document doc, List<SelectorSpec> specs, HostRule rule) {
        for (SelectorSpec spec : specs) {
            for (Element el : select(doc, spec, rule)) {
                String value = valueOf(el, spec);
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrl(Document doc, List<SelectorSpec> specs, HostRule rule) {
        for (SelectorSpec spec : specs) {
            for (Element el : select(doc, spec, rule)) {
                if (spec.usesAttribute()) {
                    String abs = el.absUrl(spec.attribute());
                    if (!abs.isBlank()) {
                        return Optional.of(abs);
                    }
                    // Some sites pack a JSON map of image URLs into a data attribute. Take the first
                    // http(s) URL out of it rather than discarding the whole thing.
                    String raw = el.attr(spec.attribute());
                    Optional<String> embedded = firstUrlIn(raw);
                    if (embedded.isPresent()) {
                        return embedded;
                    }
                } else {
                    for (String attr : new String[]{"src", "data-src", "data-lazy-src", "href", "content"}) {
                        String abs = el.absUrl(attr);
                        if (!abs.isBlank()) {
                            return Optional.of(abs);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Runs one selector, turning a malformed selector into a logged warning rather than a failed
     * scrape. The rules file is hand-edited on a live server, so a typo in it must degrade one
     * retailer's extraction and nothing else.
     */
    private static List<Element> select(Document doc, SelectorSpec spec, HostRule rule) {
        try {
            return doc.select(spec.cssSelector());
        } catch (Selector.SelectorParseException e) {
            log.warn("Invalid selector '{}' for host {} — fix it in domain-rules.yml and POST "
                    + "/api/ops/rules/reload. Skipping it for now.", spec.cssSelector(), rule.host());
            return List.of();
        }
    }

    /**
     * Attribute when asked for, otherwise text.
     *
     * <p>{@code wholeOwnText} rather than {@code text()} is deliberate for prices. Retailers nest
     * the rupee symbol, the whole number and the decimal fraction in separate child spans, and
     * Jsoup's {@code text()} inserts a space between them — turning 49,999 into "49, 999" and
     * leaving the parser to find a 49. Taking the element's own text first, and falling back to the
     * full subtree only when that is empty, keeps the digits together in the common case.
     */
    private static String valueOf(Element el, SelectorSpec spec) {
        if (spec.usesAttribute()) {
            return el.attr(spec.attribute());
        }
        String own = el.wholeOwnText().trim();
        if (!own.isBlank()) {
            return own;
        }
        // Children only: join without a separator so split-up digit groups re-form.
        StringBuilder sb = new StringBuilder();
        for (Element child : el.children()) {
            sb.append(child.wholeOwnText().trim());
        }
        String joined = sb.toString().trim();
        return joined.isBlank() ? el.text() : joined;
    }

    private static Optional<String> firstUrlIn(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int idx = raw.indexOf("http");
        if (idx < 0) {
            return Optional.empty();
        }
        int end = idx;
        while (end < raw.length() && "\"' ,}]\\".indexOf(raw.charAt(end)) < 0) {
            end++;
        }
        String candidate = raw.substring(idx, end);
        return candidate.length() > 12 ? Optional.of(candidate) : Optional.empty();
    }
}
