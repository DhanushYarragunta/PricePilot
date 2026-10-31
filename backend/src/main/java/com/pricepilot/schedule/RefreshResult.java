package com.pricepilot.schedule;

import com.pricepilot.domain.ScrapeOutcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * What one refresh of one listing achieved.
 *
 * <p>Produced by {@link RefreshWriter} inside the transaction that wrote the observation, and
 * consumed outside it — by the alert fan-out, by the deal analyser, and by the manual-refresh
 * endpoint. That is the reason it is a plain immutable record of values rather than a reference to
 * the entities involved: once the transaction has committed, the entities are detached, and passing
 * them around invites a lazy-loading failure in whichever thread picks them up next.
 *
 * <p>It carries the comparison against the previous observation as well as the new one, because
 * "₹74,999" is not information — "₹74,999, down from ₹82,499, and the lowest we have ever seen" is.
 * Working that out needs the previous row, which is only conveniently in hand at the moment of the
 * write.
 *
 * @param listingId          the listing that was refreshed
 * @param productId          its product, so alerts can fan out without another query
 * @param outcome            what happened, including {@link ScrapeOutcome#SKIPPED_FRESH}
 * @param retailer           the retailer's display name, e.g. {@code Amazon.in} — shown to the user
 *                           as-is, so it must never be treated as an identifier
 * @param price              the newly observed price, or {@code null} unless the outcome is success
 * @param previousPrice      the price before this check, or {@code null} if this was the first ever
 * @param mrp                the retailer's claimed pre-discount price, when the page showed one
 * @param inStock            whether it can be bought now
 * @param cameBackInStock    true when this check found stock where the previous one found none
 * @param priceChanged       true when the price differs from the previous observation
 * @param allTimeLow         true when this is the lowest in-stock price ever recorded for the product
 * @param nextCheckAt        when the scheduler will look again
 * @param intervalMinutes    the interval the adaptive policy settled on
 * @param strategy           which extraction strategy supplied the price
 * @param latencyMs          how long the fetch took
 * @param paid               whether the bytes came through the paid unblocker
 * @param message            human-readable detail; the failure diagnosis when there was one
 */
public record RefreshResult(
        Long listingId,
        Long productId,
        ScrapeOutcome outcome,
        String retailer,
        BigDecimal price,
        BigDecimal previousPrice,
        BigDecimal mrp,
        boolean inStock,
        boolean cameBackInStock,
        boolean priceChanged,
        boolean allTimeLow,
        Instant nextCheckAt,
        int intervalMinutes,
        String strategy,
        int latencyMs,
        boolean paid,
        String message,
        Instant completedAt) {

    /** Existing callers do not imply a known transaction completion time. */
    public RefreshResult(Long listingId, Long productId, ScrapeOutcome outcome, String retailer,
                         BigDecimal price, BigDecimal previousPrice, BigDecimal mrp, boolean inStock,
                         boolean cameBackInStock, boolean priceChanged, boolean allTimeLow,
                         Instant nextCheckAt, int intervalMinutes, String strategy, int latencyMs,
                         boolean paid, String message) {
        this(listingId, productId, outcome, retailer, price, previousPrice, mrp, inStock,
                cameBackInStock, priceChanged, allTimeLow, nextCheckAt, intervalMinutes,
                strategy, latencyMs, paid, message, null);
    }

    /** Added only after the persistence transaction returns, before optional mail/AI work. */
    public RefreshResult withCompletedAt(Instant at) {
        return new RefreshResult(listingId, productId, outcome, retailer, price, previousPrice, mrp,
                inStock, cameBackInStock, priceChanged, allTimeLow, nextCheckAt, intervalMinutes,
                strategy, latencyMs, paid, message, at);
    }

    public boolean isSuccess() {
        return outcome == ScrapeOutcome.SUCCESS;
    }

    public boolean isSkipped() {
        return outcome == ScrapeOutcome.SKIPPED_FRESH;
    }

    /** BrowserAct submissions are explicit; cached successes and pre-submission failures cost none. */
    public boolean consumedRequest() {
        return paid;
    }

    /** How far the price fell, or {@code null} when it did not fall. */
    public BigDecimal drop() {
        if (price == null || previousPrice == null) {
            return null;
        }
        BigDecimal delta = previousPrice.subtract(price);
        return delta.compareTo(BigDecimal.ZERO) > 0 ? delta : null;
    }

    /** The fall as a percentage of the old price, or {@code null} when the price did not fall. */
    public Integer dropPercent() {
        BigDecimal delta = drop();
        if (delta == null || previousPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return delta.multiply(BigDecimal.valueOf(100))
                .divide(previousPrice, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Whether this refresh is worth telling someone about. Used to decide whether to bother the
     * alert fan-out at all, which saves a tracker query on the overwhelming majority of checks that
     * find nothing new.
     */
    public boolean worthAlerting() {
        return isSuccess() && inStock && (drop() != null || allTimeLow || cameBackInStock);
    }

    /** Recorded when the listing was deleted between being scheduled and being written. */
    public static RefreshResult vanished(Long listingId) {
        return new RefreshResult(listingId, null, ScrapeOutcome.ERROR, null, null, null, null,
                false, false, false, false, null, 0, "none", 0, false,
                "The listing no longer exists");
    }
}
