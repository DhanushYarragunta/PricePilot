package com.pricepilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A user's interest in a product, with their alert preferences.
 * One row per (user, product) pair.
 */
@Entity
@Table(
        name = "tracker",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_tracker_user_product",
                columnNames = {"user_id", "product_id"}
        )
)
public class Tracker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Alert me when the price reaches this. Null means "no explicit target". */
    @Column(name = "target_price", precision = 12, scale = 2)
    private BigDecimal targetPrice;

    /** Alert me when Claude upgrades the verdict, even without a target. */
    @Column(name = "notify_on_deal", nullable = false)
    private boolean notifyOnDeal = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Enforces the anti-spam cooldown. */
    @Column(name = "last_alert_at")
    private Instant lastAlertAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** A tracker with a live target keeps its listings on the tight check interval. */
    public boolean wantsTightPolling() {
        return active && (targetPrice != null || notifyOnDeal);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public void setTargetPrice(BigDecimal targetPrice) { this.targetPrice = targetPrice; }
    public boolean isNotifyOnDeal() { return notifyOnDeal; }
    public void setNotifyOnDeal(boolean notifyOnDeal) { this.notifyOnDeal = notifyOnDeal; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastAlertAt() { return lastAlertAt; }
    public void setLastAlertAt(Instant lastAlertAt) { this.lastAlertAt = lastAlertAt; }
}
