package com.pricepilot.domain;

/**
 * Claude's buy-timing call on a product at its current price.
 *
 * <p>Ordered from most to least favourable so the UI can sort and colour by ordinal.
 */
public enum DealVerdict {
    /** Near or at the lowest price observed; buying now is well supported. */
    STRONG_BUY,
    /** Meaningfully below the typical price. */
    GOOD_VALUE,
    /** Around the usual price. Neither a bargain nor a rip-off. */
    FAIR,
    /** Above typical, and history suggests a better price is likely. */
    WAIT,
    /** Substantially above the observed norm, or a fabricated discount. */
    OVERPRICED,
    /** Not enough price history yet to say anything honest. */
    INSUFFICIENT_DATA;

    /** Used for the "share of tracked products currently worth buying" metric. */
    public boolean isFavourable() {
        return this == STRONG_BUY || this == GOOD_VALUE;
    }
}
