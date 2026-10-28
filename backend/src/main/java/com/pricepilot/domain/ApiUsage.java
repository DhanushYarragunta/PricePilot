package com.pricepilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Monthly spend counter for one paid provider.
 *
 * <p>Checked before every outbound paid call. This is the thing that stops a bug in the
 * scheduler from quietly draining a month of Claude credits overnight.
 */
@Entity
@Table(
        name = "api_usage",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_api_usage_provider_period",
                columnNames = {"provider", "period"}
        )
)
public class ApiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ApiProvider provider;

    /** Period key in {@code YYYY-MM} form, in UTC. */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Column(name = "calls", nullable = false)
    private int calls = 0;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens = 0L;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ApiProvider getProvider() { return provider; }
    public void setProvider(ApiProvider provider) { this.provider = provider; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public int getCalls() { return calls; }
    public void setCalls(int calls) { this.calls = calls; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
