package com.pricepilot.scrape.browseract;

import com.pricepilot.scrape.ProductSnapshot;

/** Maps validated retailer workflow output without issuing any additional request. */
public final class BrowserActPriceExtractor {
    private BrowserActPriceExtractor() { }

    public static ProductSnapshot snapshot(BrowserActResult result) {
        return new ProductSnapshot(result.sellingPrice(), result.mrp(), result.currency(),
                result.title(), result.imageUrl(), result.inStock(), "browseract");
    }
}
