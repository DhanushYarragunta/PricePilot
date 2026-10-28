package com.pricepilot.scrape.extract;

import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.SnapshotBuilder;
import com.pricepilot.scrape.rules.HostRule;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads schema.org microdata: the {@code itemprop} attributes sprinkled through the markup.
 *
 * <p>Microdata is the older sibling of JSON-LD and plenty of Indian storefronts still emit it,
 * sometimes alongside an incomplete JSON-LD block. It is slightly less reliable because the values
 * live in the markup rather than in a dedicated data island, but it has the same decisive advantage
 * over CSS selectors: the attribute names are a published standard, so they are not renamed when the
 * design team ships a new theme.
 *
 * <p>The important detail is {@code content} versus visible text. Well-formed microdata puts a clean
 * machine value in the attribute:
 *
 * <pre>
 *   &lt;meta itemprop="price" content="49999.00"&gt;
 *   &lt;span itemprop="price" content="49999.00"&gt;Rs 49,999&lt;/span&gt;
 * </pre>
 *
 * <p>The attribute is tried first for exactly that reason, and the visible text is the fallback for
 * the sites that omit it. Both run through the same price parser, so {@code "Rs 49,999 (17% off)"}
 * still resolves to 49999 rather than 17.
 *
 * <p>When the page contains several {@code itemscope} Product blocks — the real product plus a row
 * of recommendations — only the first one that yields a usable price is read, and enclosing blocks
 * are preferred over nested ones because related-item carousels are always nested deeper.
 */
@Component
public class MicrodataExtractor implements PriceExtractor {

    /**
     * Scopes to search, in order. A declared Product scope is the trustworthy case; a bare
     * {@code [itemprop=price]} anywhere on the page is the desperate one, and it comes last.
     */
    private static final List<String> PRODUCT_SCOPES = List.of(
            "[itemtype~=(?i)schema\\.org/Product]",
            "[itemtype~=(?i)schema\\.org/(IndividualProduct|ProductModel)]",
            "[itemscope][itemtype*=Product]");

    @Override
    public String name() {
        return "microdata";
    }

    @Override
    public void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out) {
        for (String scopeSelector : PRODUCT_SCOPES) {
            for (Element scope : doc.select(scopeSelector)) {
                if (readScope(scope, out)) {
                    return;
                }
            }
        }
        // No declared Product scope, or none of them carried a price. Fall back to the whole
        // document: a surprising number of pages put itemprop attributes on without a scope.
        readScope(doc, out);
    }

    /**
     * Pulls everything findable out of one scope.
     *
     * @return true when this scope produced a price, meaning the search can stop
     */
    private boolean readScope(Element scope, SnapshotBuilder out) {
        Optional<BigDecimal> price = number(scope, "price");
        if (price.isEmpty()) {
            price = number(scope, "lowPrice");
        }

        // Title and image are offered regardless, because a scope without a price may still be the
        // only place the product name appears, and SnapshotBuilder ignores anything already set.
        text(scope, "name").ifPresent(out::offerTitle);
        url(scope, "image").ifPresent(out::offerImage);
        text(scope, "priceCurrency").ifPresent(out::offerCurrency);
        availability(scope).ifPresent(out::offerInStock);

        if (price.isPresent()) {
            out.offerPrice(price.get(), name());
            number(scope, "highPrice").ifPresent(v -> out.offerMrp(v, name()));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ value reads

    private static Optional<BigDecimal> number(Element scope, String prop) {
        for (Element el : scope.select("[itemprop=" + prop + "]")) {
            // The machine-readable attribute first. "content" is the standard one; "value" turns up
            // on <data> and <input> elements, where the visible text is empty or a placeholder.
            for (String attr : new String[]{"content", "value", "data-price"}) {
                String raw = el.attr(attr);
                if (!raw.isBlank()) {
                    Optional<BigDecimal> parsed = PriceParser.parse(raw);
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                }
            }
            Optional<BigDecimal> fromText = PriceParser.parse(el.text());
            if (fromText.isPresent()) {
                return fromText;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> text(Element scope, String prop) {
        for (Element el : scope.select("[itemprop=" + prop + "]")) {
            String content = el.attr("content");
            if (!content.isBlank()) {
                return Optional.of(content);
            }
            String value = el.text();
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Images need absolute URLs. {@code absUrl} resolves against the document base URI, which the
     * engine sets from the final URL of the response — so a redirect to a different host still
     * produces a working image link.
     */
    private static Optional<String> url(Element scope, String prop) {
        for (Element el : scope.select("[itemprop=" + prop + "]")) {
            for (String attr : new String[]{"content", "src", "href", "data-src"}) {
                String abs = el.absUrl(attr);
                if (!abs.isBlank()) {
                    return Optional.of(abs);
                }
                String raw = el.attr(attr);
                if (raw.startsWith("http")) {
                    return Optional.of(raw);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Boolean> availability(Element scope) {
        for (Element el : scope.select("[itemprop=availability]")) {
            String raw = el.attr("href");
            if (raw.isBlank()) {
                raw = el.attr("content");
            }
            if (raw.isBlank()) {
                raw = el.text();
            }
            if (raw.isBlank()) {
                continue;
            }
            String value = raw.toLowerCase(Locale.ROOT);
            int slash = value.lastIndexOf('/');
            String token = (slash >= 0 ? value.substring(slash + 1) : value).trim();
            if (token.contains("outofstock") || token.contains("soldout")
                    || token.contains("discontinued") || token.equals("out of stock")) {
                return Optional.of(false);
            }
            if (token.contains("instock") || token.contains("instoreonly")
                    || token.contains("limitedavailability") || token.equals("in stock")) {
                return Optional.of(true);
            }
        }
        return Optional.empty();
    }
}
