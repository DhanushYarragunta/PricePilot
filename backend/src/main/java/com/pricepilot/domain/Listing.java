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

import java.time.Instant;

/**
 * One retailer's page for a product — the thing we actually fetch.
 *
 * <p>Listings are deduplicated globally by {@code urlHash}, so if fifty users track the same
 * Flipkart URL there is still exactly one listing and one fetch. That sharing is a large part of
 * why the request budget stays small.
 *
 * <p>{@code checkIntervalMinutes} and {@code nextCheckAt} are owned by the adaptive scheduler:
 * a listing whose price never moves drifts towards the maximum interval, while one that moves
 * (or that someone has an active alert on) is pulled back towards the minimum.
 */
@Entity
@Table(name = "listing")
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * The retailer's display name as given in the domain rules file, e.g. {@code "Amazon.in"},
     * {@code "Reliance Digital"}.
     *
     * <p>Stored on the listing rather than resolved from the host on read, so a price history stays
     * readable after a rule is renamed or a host is dropped from the rules file — the chart still says
     * which shop the line belongs to. It is denormalised display text, not a key: nothing joins or
     * switches on it, and the host in {@link #url} is the real identifier.
     */
    @Column(name = "retailer", nullable = false, length = 64)
    private String retailer;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    /** SHA-256 hex of the canonicalised URL. Unique across the table. */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "title", length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ListingStatus status = ListingStatus.ACTIVE;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_strategy", length = 48)
    private String lastStrategy;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "check_interval_minutes", nullable = false)
    private int checkIntervalMinutes = 180;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    /** The listing whose price represents the product in list views. */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextCheckAt == null) {
            nextCheckAt = now;
        }
    }

    public boolean isActive() {
        return status == ListingStatus.ACTIVE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public String getRetailer() { return retailer; }
    public void setRetailer(String retailer) { this.retailer = retailer; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUrlHash() { return urlHash; }
    public void setUrlHash(String urlHash) { this.urlHash = urlHash; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public ListingStatus getStatus() { return status; }
    public void setStatus(ListingStatus status) { this.status = status; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public String getLastStrategy() { return lastStrategy; }
    public void setLastStrategy(String lastStrategy) { this.lastStrategy = lastStrategy; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public int getCheckIntervalMinutes() { return checkIntervalMinutes; }
    public void setCheckIntervalMinutes(int checkIntervalMinutes) { this.checkIntervalMinutes = checkIntervalMinutes; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(Instant nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
