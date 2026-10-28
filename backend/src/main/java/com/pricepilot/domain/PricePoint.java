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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One observed price for one listing at one moment. Append-only: nothing in the
 * application ever updates or deletes a price point, which is what makes the
 * history trustworthy enough to reason about.
 */
@Entity
@Table(name = "price_point")
public class PricePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Retailer's claimed pre-discount price, when shown. Often fiction — that is the point. */
    @Column(name = "list_price", precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "INR";

    @Column(name = "in_stock", nullable = false)
    private boolean inStock = true;

    @Column(name = "seller", length = 200)
    private String seller;

    @Column(name = "rating", precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** Which fetch strategy produced this observation. */
    @Column(name = "source_strategy", nullable = false, length = 48)
    private String sourceStrategy;

    /** False when identical to the previous observation for this listing. */
    @Column(name = "changed", nullable = false)
    private boolean changed = true;

    @PrePersist
    void onCreate() {
        if (capturedAt == null) {
            capturedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Listing getListing() { return listing; }
    public void setListing(Listing listing) { this.listing = listing; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getListPrice() { return listPrice; }
    public void setListPrice(BigDecimal listPrice) { this.listPrice = listPrice; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isInStock() { return inStock; }
    public void setInStock(boolean inStock) { this.inStock = inStock; }
    public String getSeller() { return seller; }
    public void setSeller(String seller) { this.seller = seller; }
    public BigDecimal getRating() { return rating; }
    public void setRating(BigDecimal rating) { this.rating = rating; }
    public Integer getReviewCount() { return reviewCount; }
    public void setReviewCount(Integer reviewCount) { this.reviewCount = reviewCount; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
    public String getSourceStrategy() { return sourceStrategy; }
    public void setSourceStrategy(String sourceStrategy) { this.sourceStrategy = sourceStrategy; }
    public boolean isChanged() { return changed; }
    public void setChanged(boolean changed) { this.changed = changed; }
}
