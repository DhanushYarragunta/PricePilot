package com.pricepilot.domain;

/**
 * What caused a fetch. Separating user-triggered from scheduled traffic matters
 * because interactive latency and background throughput have different targets.
 */
public enum TriggerSource {
    /** The adaptive background scheduler. */
    SCHEDULER,
    /** A user pressed refresh and is waiting for the answer. */
    MANUAL,
    /** First fetch when a product is added. */
    ONBOARD,
    /** Fetching a listing that cross-retailer discovery just found. */
    DISCOVERY
}
