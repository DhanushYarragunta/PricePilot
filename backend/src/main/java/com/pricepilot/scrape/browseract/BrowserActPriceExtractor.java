package com.pricepilot.scrape.browseract;

import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.SnapshotBuilder;

/** Maps validated workflow output without issuing a second paid request from the HTML chain. */
public final class BrowserActPriceExtractor {
    private BrowserActPriceExtractor() { }

    public static ProductSnapshot snapshot(BrowserActResult result) {
        SnapshotBuilder out = new SnapshotBuilder("INR");
        out.offerPrice(result.sellingPrice(), "browseract");
        out.offerMrp(result.mrp(), "browseract");
        out.offerCurrency(result.currency());
        out.offerTitle(result.title());
        out.offerImage(result.imageUrl());
        out.offerInStock(result.inStock());
        return out.build();
    }
}
