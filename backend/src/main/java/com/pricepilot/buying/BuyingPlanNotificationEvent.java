package com.pricepilot.buying;

import java.time.Instant;

/** Durable, user-specific plan notification. A mail adapter acknowledges successful delivery by ID. */
public record BuyingPlanNotificationEvent(Long notificationId, Long planId, Long userId, Long productId,
                                          Long listingId, String kind, String subject, String message, Instant createdAt) { }
