package com.pricepilot.buying;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.PricePoint;
import com.pricepilot.domain.Product;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

final class BuyingFixtures {
    static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    static Listing listing(long id, String retailer) {
        Product product = new Product(); product.setId(7L); product.setTitle("Example laptop");
        Listing listing = new Listing(); listing.setId(id); listing.setProduct(product); listing.setTitle("Example laptop 16GB");
        listing.setRetailer(retailer); listing.setUrl("https://www.amazon.in/dp/B000000001"); listing.setPrimary(id == 10);
        return listing;
    }
    static PricePoint point(Listing listing, String price, Instant captured) {
        PricePoint point = new PricePoint(); point.setListing(listing); point.setPrice(new BigDecimal(price));
        point.setListPrice(new BigDecimal(price).multiply(BigDecimal.valueOf(2))); point.setCurrency("INR");
        point.setInStock(true); point.setCapturedAt(captured); point.setSourceStrategy("browseract");
        return point;
    }
    static List<PricePoint> daily(Listing listing, int days, IntFunction<String> prices) {
        List<PricePoint> points = new ArrayList<>();
        for (int index = 0; index < days; index++) points.add(point(listing, prices.apply(index), NOW.minus(Duration.ofDays(days - 1L - index))));
        return points;
    }
    static BuyingModels.Series series(PlanningProperties properties, int days, IntFunction<String> prices) {
        Listing listing = listing(10, "Amazon.in");
        List<PricePoint> points = daily(listing, days, prices);
        return new BuyingHistory(properties).analyze(List.of(listing), points,
                List.of(points.get(points.size() - 1)), 10L, NOW).series().get(0);
    }
}
