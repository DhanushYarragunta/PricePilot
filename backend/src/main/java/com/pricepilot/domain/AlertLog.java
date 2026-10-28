package com.pricepilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A notification that was raised for a tracker.
 *
 * <p>Rows are written whether or not email delivery is configured, so the in-app alert feed works
 * with SMTP switched off and delivery failures stay diagnosable.
 */
@Entity
@Table(name = "alert_log")
public class AlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracker_id", nullable = false)
    private Tracker tracker;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private AlertKind kind;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "message", length = 600)
    private String message;

    @Column(name = "delivered", nullable = false)
    private boolean delivered = false;

    @Column(name = "delivery_error", length = 400)
    private String deliveryError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tracker getTracker() { return tracker; }
    public void setTracker(Tracker tracker) { this.tracker = tracker; }
    public AlertKind getKind() { return kind; }
    public void setKind(AlertKind kind) { this.kind = kind; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
    public String getDeliveryError() { return deliveryError; }
    public void setDeliveryError(String deliveryError) { this.deliveryError = deliveryError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
