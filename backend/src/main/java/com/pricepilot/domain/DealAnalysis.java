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
 * A stored Claude verdict on a product.
 *
 * <p>Analyses are append-only, so the UI can show "this was a WAIT last week and is a GOOD_VALUE
 * today". Token counts and cost are stored per analysis, which is what makes the cost-per-analysis
 * figure on the metrics page a real measurement.
 */
@Entity
@Table(name = "deal_analysis")
public class DealAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 32)
    private DealVerdict verdict;

    /** Claude's own stated confidence, 0.000 to 1.000. */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    /** One-line summary shown on the card. */
    @Column(name = "headline", length = 300)
    private String headline;

    // NOTE: deliberately NOT @Lob. On PostgreSQL, @Lob on a String maps to the
    // `oid` large-object type, which does not match a `text` column and makes
    // Hibernate schema validation fail at startup. columnDefinition keeps it `text`.
    @Column(name = "reasoning", columnDefinition = "text")
    private String reasoning;

    /** True when the advertised "discount" is not supported by observed history. */
    @Column(name = "fake_discount", nullable = false)
    private boolean fakeDiscount = false;

    /** What Claude thinks is a fair price to wait for. */
    @Column(name = "suggested_price", precision = 12, scale = 2)
    private BigDecimal suggestedPrice;

    /** The price this verdict was about — lets us detect a stale verdict cheaply. */
    @Column(name = "analysed_price", precision = 12, scale = 2)
    private BigDecimal analysedPrice;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cost_usd", precision = 10, scale = 6)
    private BigDecimal costUsd;

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
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public DealVerdict getVerdict() { return verdict; }
    public void setVerdict(DealVerdict verdict) { this.verdict = verdict; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public boolean isFakeDiscount() { return fakeDiscount; }
    public void setFakeDiscount(boolean fakeDiscount) { this.fakeDiscount = fakeDiscount; }
    public BigDecimal getSuggestedPrice() { return suggestedPrice; }
    public void setSuggestedPrice(BigDecimal suggestedPrice) { this.suggestedPrice = suggestedPrice; }
    public BigDecimal getAnalysedPrice() { return analysedPrice; }
    public void setAnalysedPrice(BigDecimal analysedPrice) { this.analysedPrice = analysedPrice; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
