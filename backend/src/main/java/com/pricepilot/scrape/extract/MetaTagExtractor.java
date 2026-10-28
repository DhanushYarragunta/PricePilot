package com.pricepilot.scrape.extract;

import com.pricepilot.scrape.PriceParser;
import com.pricepilot.scrape.SnapshotBuilder;
import com.pricepilot.scrape.rules.HostRule;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Reads Open Graph and Twitter Card meta tags from the document head.
 *
 * <p>These tags exist so that a link pasted into WhatsApp or Twitter renders a preview card, which
 * means retailers maintain them carefully — a broken preview is a visible marketing problem, so
 * these tags get fixed faster than most things on the page. {@code og:title} and {@code og:image}
 * are almost universally present and correct.
 *
 * <p>Price is a different matter. {@code product:price:amount} is part of the Open Graph product
 * namespace and some Indian storefronts publish it, but plenty do not, and a few leave a stale
 * value behind after a price change. So this strategy is positioned as a reliable source of
 * <em>title and image</em> that sometimes also supplies a price — which is exactly the situation the
 * merge-based snapshot builder was written for. It fills in the display fields, and a later strategy
 * in the chain supplies the price it could not find.
 *
 * <p>One more reason it matters: the title and image are what make the dashboard look like a real
 * product tracker rather than a list of URLs. A card with a photo and a proper product name is worth
 * more to someone looking at the live site than any amount of backend sophistication.
 */
@Component
public class MetaTagExtractor implements PriceExtractor {

    /** In confidence order. The product namespace is the only one actually meant to carry a price. */
    private static final List<String> PRICE_PROPERTIES = List.of(
            "product:price:amount",
            "og:price:amount",
            "product:offer:price",
            "twitter:data1");

    private static final List<String> MRP_PROPERTIES = List.of(
            "product:original_price:amount",
            "og:price:standard_amount");

    private static final List<String> CURRENCY_PROPERTIES = List.of(
            "product:price:currency",
            "og:price:currency");

    private static final List<String> TITLE_PROPERTIES = List.of(
            "og:title",
            "twitter:title");

    private static final List<String> IMAGE_PROPERTIES = List.of(
            "og:image:secure_url",
            "og:image",
            "twitter:image",
            "twitter:image:src");

    private static final List<String> AVAILABILITY_PROPERTIES = List.of(
            "product:availability",
            "og:availability");

    @Override
    public String name() {
        return "meta";
    }

    @Override
    public void contribute(Document doc, String html, HostRule rule, SnapshotBuilder out) {
        firstText(doc, TITLE_PROPERTIES).ifPresent(out::offerTitle);
        firstUrl(doc, IMAGE_PROPERTIES).ifPresent(out::offerImage);
        firstText(doc, CURRENCY_PROPERTIES).ifPresent(out::offerCurrency);
        firstNumber(doc, PRICE_PROPERTIES).ifPresent(v -> out.offerPrice(v, name()));
        firstNumber(doc, MRP_PROPERTIES).ifPresent(v -> out.offerMrp(v, name()));
        firstText(doc, AVAILABILITY_PROPERTIES).ifPresent(raw -> {
            String v = raw.toLowerCase().replace(" ", "");
            if (v.contains("outofstock") || v.contains("soldout")) {
                out.offerInStock(false);
            } else if (v.contains("instock") || v.equals("available")) {
                out.offerInStock(true);
            }
        });

        // Last resort for the title only: the <title> element. Never for the price — a page title
        // like "Buy Galaxy S24 at Rs 74,999 - Croma" can be months out of date.
        if (!doc.title().isBlank()) {
            out.offerTitle(cleanPageTitle(doc.title(), rule));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Matches on either {@code property} (the Open Graph spec) or {@code name} (what many sites
     * actually write, and what the Twitter Card spec uses), because being strict here costs data
     * for no benefit.
     */
    private static Optional<Element> find(Document doc, String key) {
        Element el = doc.selectFirst("meta[property=" + key + "]");
        if (el == null) {
            el = doc.selectFirst("meta[name=" + key + "]");
        }
        return Optional.ofNullable(el);
    }

    private static Optional<String> firstText(Document doc, List<String> keys) {
        for (String key : keys) {
            Optional<String> value = find(doc, key)
                    .map(el -> el.attr("content"))
                    .filter(v -> !v.isBlank());
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> firstNumber(Document doc, List<String> keys) {
        for (String key : keys) {
            Optional<Element> el = find(doc, key);
            if (el.isEmpty()) {
                continue;
            }
            Optional<BigDecimal> parsed = PriceParser.parse(el.get().attr("content"));
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrl(Document doc, List<String> keys) {
        for (String key : keys) {
            Optional<Element> el = find(doc, key);
            if (el.isEmpty()) {
                continue;
            }
            String abs = el.get().absUrl("content");
            if (!abs.isBlank()) {
                return Optional.of(abs);
            }
            String raw = el.get().attr("content");
            if (raw.startsWith("http")) {
                return Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    /**
     * Trims the marketing scaffolding off a page title so the dashboard shows a product name rather
     * than an SEO string. "Buy Samsung Galaxy S24 Online at Best Price - Croma" becomes
     * "Samsung Galaxy S24".
     */
    private static String cleanPageTitle(String raw, HostRule rule) {
        String t = raw.trim();
        for (String separator : new String[]{" | ", " - ", " – ", " :: "}) {
            int idx = t.lastIndexOf(separator);
            if (idx > 20) {
                String tail = t.substring(idx + separator.length()).toLowerCase();
                String retailer = rule.retailer() == null ? "" : rule.retailer().toLowerCase();
                // Only cut when the tail is clearly the retailer's own branding, not product detail.
                if (tail.length() < 40
                        && (retailer.contains(tail.split("[ .]")[0]) || tail.contains("buy")
                            || tail.contains(".com") || tail.contains(".in"))) {
                    t = t.substring(0, idx).trim();
                }
            }
        }
        t = t.replaceAll("(?i)^(buy|shop)\\s+", "");
        t = t.replaceAll("(?i)\\s+(online\\s+)?at\\s+(the\\s+)?best\\s+price.*$", "");
        t = t.replaceAll("(?i)\\s+online$", "");
        return t.trim();
    }
}
