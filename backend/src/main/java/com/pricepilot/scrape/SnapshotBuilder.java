package com.pricepilot.scrape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Collects fragments from several extraction strategies into one snapshot.
 *
 * <p>Strategies are not all-or-nothing, and treating them that way throws away good data. A
 * Flipkart page may publish a clean JSON-LD block with the product name and image but no price,
 * while the price sits in the React state. Croma may give a price through microdata and the image
 * only through Open Graph. So each strategy contributes whatever it found, the first contribution
 * for a field wins, and the chain stops as soon as everything needed is present.
 *
 * <p>Only the strategy that supplied the <em>price</em> is recorded, because that is the one whose
 * success rate matters when deciding which rule to fix.
 */
public final class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);

    private BigDecimal price;
    private String priceStrategy;
    private BigDecimal mrp;
    private String currency;
    private String title;
    private String imageUrl;
    private Boolean inStock;

    public SnapshotBuilder(String defaultCurrency) {
        this.currency = defaultCurrency;
    }

    /** First price wins. Later strategies cannot overwrite it. */
    public void offerPrice(BigDecimal value, String strategy) {
        if (!PriceParser.isPlausible(value) || price != null) {
            return;
        }
        price = value;
        priceStrategy = strategy;
        log.debug("price {} from {}", value, strategy);
    }

    /**
     * Preserve MRP evidence, including contradictions, for final snapshot validation.
     * Silently discarding a conflict could make an incorrect selling price look verified.
     */
    public void offerMrp(BigDecimal value, String strategy) {
        if (value == null || mrp != null) {
            return;
        }
        // Keep contradictory evidence so final validation rejects the snapshot.
        mrp = value;
    }

    public void offerTitle(String value) {
        if (title != null || value == null) {
            return;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 3) {
            return;
        }
        title = cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    public void offerImage(String value) {
        if (imageUrl != null || value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        if (!v.startsWith("http")) {
            return;
        }
        imageUrl = v.length() > 1000 ? null : v;
    }

    public void offerInStock(Boolean value) {
        if (inStock == null && value != null) {
            inStock = value;
        }
    }

    public void offerCurrency(String value) {
        if (value != null && !value.isBlank()) {
            currency = value.trim().toUpperCase();
        }
    }

    public boolean hasPrice() {
        return price != null;
    }

    /**
     * True when there is nothing further to gain from running more strategies. Availability is
     * excluded on purpose: it is resolved from page text afterwards, not by the extractors.
     */
    public boolean isComplete() {
        return price != null && title != null && imageUrl != null && mrp != null;
    }

    public BigDecimal price() {
        return price;
    }

    public String priceStrategy() {
        return priceStrategy;
    }

    /** Defaults availability to true: a page that gave us a price is almost always buyable. */
    public ProductSnapshot build() {
        return new ProductSnapshot(
                price,
                mrp,
                currency,
                title,
                imageUrl,
                inStock == null || inStock,
                priceStrategy == null ? "none" : priceStrategy);
    }
}
