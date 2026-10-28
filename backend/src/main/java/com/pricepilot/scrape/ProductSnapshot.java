package com.pricepilot.scrape;

import java.math.BigDecimal;

/**
 * What one page told us about one listing.
 *
 * @param price     the price a buyer would pay right now
 * @param mrp       the struck-through "was" price, when the page shows one. Stored rather than
 *                  trusted: comparing the retailer's claimed MRP against observed history is how
 *                  the fake-discount check works
 * @param currency  ISO code, effectively always INR here
 * @param title     product name as the retailer states it
 * @param imageUrl  absolute URL of the main image
 * @param inStock   whether it can actually be bought
 * @param strategy  which extraction strategy supplied the price. This is the field that makes the
 *                  per-strategy success table on the ops page possible, and the reason that table
 *                  is a measurement rather than a guess
 */
public record ProductSnapshot(
        BigDecimal price,
        BigDecimal mrp,
        String currency,
        String title,
        String imageUrl,
        boolean inStock,
        String strategy) {

    /** True when the page claims a discount: an MRP strictly above the selling price. */
    public boolean claimsDiscount() {
        return mrp != null && price != null && mrp.compareTo(price) > 0;
    }

    /** The discount the retailer is advertising, as a percentage, or {@code null} if none. */
    public Integer claimedDiscountPercent() {
        if (!claimsDiscount()) {
            return null;
        }
        BigDecimal saved = mrp.subtract(price);
        return saved.multiply(BigDecimal.valueOf(100))
                .divide(mrp, 0, java.math.RoundingMode.HALF_UP)
                .intValue();
    }
}
