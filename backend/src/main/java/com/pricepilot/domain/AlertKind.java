package com.pricepilot.domain;

/**
 * Why a user is being notified.
 */
public enum AlertKind {
    /** Price fell to or below the user's stated target. */
    TARGET_HIT,
    /** Claude upgraded the product to a favourable verdict. */
    DEAL_DETECTED,
    /** Lowest price we have ever recorded for this product. */
    ALL_TIME_LOW,
    /** Previously out of stock, now purchasable. */
    BACK_IN_STOCK
}
