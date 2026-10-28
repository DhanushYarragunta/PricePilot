package com.pricepilot.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything Claude is told about a product, assembled into one immutable object.
 *
 * <p>This type exists to draw a hard line between gathering evidence and reasoning about it.
 * {@link DealAnalyzer} fills it from the database, {@link DealPrompt} renders it as text, and the
 * model sees nothing that is not in here. That makes the analysis auditable: when a verdict looks
 * wrong, the question "what did it actually know?" has a precise answer.
 *
 * <p>Every number in here was read off a real product page by the scraping engine. There are no
 * estimates, no search snippets, and no figures supplied by the retailer's marketing except
 * {@link #mrp}, which is explicitly labelled as the retailer's own claim precisely because it is the
 * one number that is routinely inflated.
 *
 * <p>The derived helpers below are computed in Java rather than left to the model. Arithmetic is not
 * what a language model is for, and a verdict that rests on a miscalculated percentage is worse than
 * no verdict. The model's job is judgement: is this price good, given these facts.
 *
 * @param productId       internal id, carried so the analysis can be attributed
 * @param title           product title as scraped
 * @param brand           may be null; retailers are inconsistent about publishing it
 * @param category        may be null
 * @param currency        ISO code, {@code INR} for every supported retailer today
 * @param currentPrice    the cheapest in-stock price across all tracked retailers right now
 * @param currentRetailer which retailer that price is from
 * @param mrp             the retailer's claimed list price, or null if none was published. Treat as
 *                        a marketing claim, not a fact
 * @param allTimeLow      lowest in-stock price ever observed by this system
 * @param allTimeHigh     highest in-stock price ever observed
 * @param average30d      mean in-stock price over the last 30 days, or null when there is no data
 * @param observations    how many price points back the statistics above
 * @param historySpanDays days between the first and most recent observation
 * @param retailers       current price at each tracked retailer, cheapest first
 */
public record DealContext(
        Long productId,
        String title,
        String brand,
        String category,
        String currency,
        BigDecimal currentPrice,
        String currentRetailer,
        BigDecimal mrp,
        BigDecimal allTimeLow,
        BigDecimal allTimeHigh,
        BigDecimal average30d,
        long observations,
        long historySpanDays,
        List<RetailerPrice> retailers) {

    /**
     * One retailer's current offer.
     *
     * @param ageMinutes how long ago this was scraped. Included because a two-day-old price from a
     *                   retailer that keeps blocking us should not carry the same weight as one from
     *                   ten minutes ago, and the model can only account for that if it is told
     */
    public record RetailerPrice(
            String retailer,
            BigDecimal price,
            boolean inStock,
            Instant capturedAt,
            long ageMinutes) {
    }

    /**
     * Below this many observations the historical statistics are not worth reasoning from, and the
     * analyzer asks for an {@code INSUFFICIENT_DATA} verdict instead of inviting the model to invent
     * confidence it cannot have. Three is low on purpose: a brand-new product with one scrape still
     * gets cross-retailer comparison and fake-discount detection, which need no history at all.
     */
    public static final int MIN_OBSERVATIONS_FOR_HISTORY = 3;

    public boolean hasHistory() {
        return observations >= MIN_OBSERVATIONS_FOR_HISTORY && allTimeLow != null && allTimeHigh != null;
    }

    /** True when more than one retailer has a live price, so a comparison is meaningful. */
    public boolean hasComparison() {
        return retailers.size() > 1;
    }

    /** The retailer's claimed discount off MRP, or empty when no MRP was published. */
    public Optional<BigDecimal> claimedDiscountPercent() {
        if (mrp == null || currentPrice == null
            || mrp.compareTo(BigDecimal.ZERO) <= 0
            || mrp.compareTo(currentPrice) <= 0) {
            return Optional.empty();
        }
        return Optional.of(percent(mrp.subtract(currentPrice), mrp));
    }

    /**
     * How far above the all-time low the current price sits, as a percentage of the low. Zero means
     * this is the cheapest it has ever been seen.
     */
    public Optional<BigDecimal> premiumOverLowPercent() {
        if (!hasHistory() || allTimeLow.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(percent(currentPrice.subtract(allTimeLow), allTimeLow));
    }

    /**
     * Where the current price falls in the observed range, from 0 (at the all-time low) to 100 (at
     * the all-time high).
     *
     * <p>This is the single most useful number in the whole context, and the reason the project
     * stores history at all. "₹74,999" means nothing on its own; "₹74,999, which is 8% of the way up
     * from the lowest price ever seen" is a decision.
     */
    public Optional<BigDecimal> positionInRangePercent() {
        if (!hasHistory()) {
            return Optional.empty();
        }
        BigDecimal span = allTimeHigh.subtract(allTimeLow);
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            // The price has never moved. Reporting "0% of the way up" would read as a bargain.
            return Optional.empty();
        }
        BigDecimal raw = percent(currentPrice.subtract(allTimeLow), span);
        return Optional.of(raw.max(BigDecimal.ZERO).min(new BigDecimal("100")));
    }

    /** Percentage below the 30-day average; negative means the price is above it. */
    public Optional<BigDecimal> belowAveragePercent() {
        if (average30d == null || average30d.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(percent(average30d.subtract(currentPrice), average30d));
    }

    /** True when the current price equals the lowest ever observed. */
    public boolean atAllTimeLow() {
        return hasHistory() && currentPrice.compareTo(allTimeLow) <= 0;
    }

    /**
     * The gap between the cheapest and dearest in-stock retailer, as a percentage of the cheapest.
     * A large spread is the clearest possible signal that a price is worth acting on.
     */
    public Optional<BigDecimal> retailerSpreadPercent() {
        List<RetailerPrice> live = retailers.stream().filter(RetailerPrice::inStock).toList();
        if (live.size() < 2) {
            return Optional.empty();
        }
        BigDecimal cheapest = live.get(0).price();
        BigDecimal dearest = cheapest;
        for (RetailerPrice offer : live) {
            if (offer.price().compareTo(cheapest) < 0) {
                cheapest = offer.price();
            }
            if (offer.price().compareTo(dearest) > 0) {
                dearest = offer.price();
            }
        }
        if (cheapest.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(percent(dearest.subtract(cheapest), cheapest));
    }

    /**
     * A short fingerprint of the facts that should trigger a fresh analysis.
     *
     * <p>Used to avoid paying for a second opinion on an unchanged situation. Deliberately coarse —
     * the price rounded to whole units, the stock position, and the retailer count — because a
     * one-rupee move is not new information, and re-running the model on it would turn a $5 monthly
     * budget into a $50 one with nothing to show.
     */
    public String factFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(currentPrice == null ? "?" : currentPrice.setScale(0, RoundingMode.DOWN).toPlainString());
        sb.append('|').append(currentRetailer);
        sb.append('|').append(mrp == null ? "-" : mrp.setScale(0, RoundingMode.DOWN).toPlainString());
        sb.append('|').append(retailers.stream().filter(RetailerPrice::inStock).count());
        return sb.toString();
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(new BigDecimal("100"))
                .divide(whole, 1, RoundingMode.HALF_UP);
    }
}
