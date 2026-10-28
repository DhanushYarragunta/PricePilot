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
 * Telemetry for a single fetch attempt.
 *
 * <p>This table exists so that claims about the system's reliability are measurements rather
 * than assertions. "Which strategy works on Flipkart?" and "what fraction of scheduled checks
 * succeed?" are both plain SQL over this table.
 */
@Entity
@Table(name = "scrape_attempt")
public class ScrapeAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable: a discovery probe may be attempted before any listing row exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    private Listing listing;

    @Column(name = "host", nullable = false, length = 160)
    private String host;

    @Column(name = "strategy", nullable = false, length = 48)
    private String strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ScrapeOutcome outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "bytes")
    private Integer bytes;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 32)
    private TriggerSource triggerSource = TriggerSource.SCHEDULER;

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
    public Listing getListing() { return listing; }
    public void setListing(Listing listing) { this.listing = listing; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public ScrapeOutcome getOutcome() { return outcome; }
    public void setOutcome(ScrapeOutcome outcome) { this.outcome = outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
    public Integer getBytes() { return bytes; }
    public void setBytes(Integer bytes) { this.bytes = bytes; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public TriggerSource getTriggerSource() { return triggerSource; }
    public void setTriggerSource(TriggerSource triggerSource) { this.triggerSource = triggerSource; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
