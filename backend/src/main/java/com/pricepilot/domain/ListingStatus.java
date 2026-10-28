package com.pricepilot.domain;

/**
 * Lifecycle of a single retailer listing.
 */
public enum ListingStatus {
    /** Being checked on the adaptive schedule. */
    ACTIVE,
    /** Temporarily excluded by the user or an admin. */
    PAUSED,
    /** The retailer is refusing our requests. Retried rarely rather than dropped. */
    BLOCKED,
    /** Repeated hard failures (404 / product removed). No longer scheduled. */
    DEAD
}
