package com.pricepilot.scrape.browseract;

import java.math.BigDecimal;

/**
 * Validated product data from a configured BrowserAct retailer workflow.
 *
 * <p>This is an intermediate representation between the raw API response and the
 * {@link com.pricepilot.scrape.ProductSnapshot} that the rest of the pipeline works with.
 * Keeping it separate means the validation and mapping logic in
 * {@link BrowserActPriceExtractor} can clearly distinguish "what BrowserAct said" from
 * "what PricePilot accepted".
 *
 * @param title        the product name as shown on the page
 * @param sellingPrice the actual price a buyer would pay (the discounted price, not MRP)
 * @param mrp          the struck-through "was" price, or null if no discount is shown
 * @param currency     explicitly verified INR currency code
 * @param inStock      whether the product can be purchased
 * @param asin         verified Amazon Standard Identification Number; null for other retailers
 * @param imageUrl     URL of the main product image, if extracted
 * @param status       extraction status reported by BrowserAct (e.g. "success")
 */
public record BrowserActResult(
        String title,
        BigDecimal sellingPrice,
        BigDecimal mrp,
        String currency,
        boolean inStock,
        String asin,
        String imageUrl,
        String status
) {

    /** True when the page shows a discount: an MRP strictly above the selling price. */
    public boolean hasDiscount() {
        return mrp != null && sellingPrice != null && mrp.compareTo(sellingPrice) > 0;
    }
}
