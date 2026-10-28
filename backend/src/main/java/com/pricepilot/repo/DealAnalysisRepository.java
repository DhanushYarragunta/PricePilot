package com.pricepilot.repo;

import com.pricepilot.domain.DealAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DealAnalysisRepository extends JpaRepository<DealAnalysis, Long> {

    Optional<DealAnalysis> findFirstByProductIdOrderByCreatedAtDesc(Long productId);

    @Query("""
            SELECT d FROM DealAnalysis d
            WHERE d.product.id = :productId
            ORDER BY d.createdAt DESC
            """)
    List<DealAnalysis> history(@Param("productId") Long productId);

    /**
     * The newest analysis for each of several products, in one query.
     *
     * <p>Exists so the dashboard does not issue one query per card. Thirty tracked products rendering
     * thirty verdict lookups is the classic N+1: fast enough to miss in development with three rows
     * and the first thing to break the page under real use.
     *
     * <p>Two analyses of the same product sharing a {@code createdAt} to the microsecond would both
     * match. That is possible rather than likely, and the caller keeps the first row per product, so
     * the duplicate is harmless — which is a better trade than the window function this would
     * otherwise need.
     *
     * <p>{@code JOIN FETCH} on the product because the caller keys the results by product id while
     * outside a transaction. Reading an id off a lazy proxy happens to work, but relying on that is
     * the kind of subtlety that turns into a {@code LazyInitializationException} the first time
     * someone refactors the call site.
     */
    @Query("""
            SELECT d FROM DealAnalysis d
            JOIN FETCH d.product p
            WHERE p.id IN :productIds
              AND d.createdAt = (
                  SELECT MAX(d2.createdAt) FROM DealAnalysis d2
                  WHERE d2.product = p
              )
            """)
    List<DealAnalysis> latestForProducts(@Param("productIds") Collection<Long> productIds);

    /**
     * Verdict distribution over the newest analysis of each product. Returns rows of
     * {@code [verdict, count]}.
     *
     * <p>This is the honest version of a "share of deals that are good value" statistic: it is
     * computed from current verdicts on real tracked products, not asserted.
     */
    @Query("""
            SELECT d.verdict, COUNT(d) FROM DealAnalysis d
            WHERE d.createdAt = (
                SELECT MAX(d2.createdAt) FROM DealAnalysis d2
                WHERE d2.product = d.product
            )
            GROUP BY d.verdict
            """)
    List<Object[]> currentVerdictDistribution();

    @Query("SELECT COALESCE(SUM(d.costUsd), 0) FROM DealAnalysis d")
    java.math.BigDecimal totalCostUsd();

    @Query("SELECT COUNT(d) FROM DealAnalysis d WHERE d.fakeDiscount = true")
    long countFakeDiscounts();
}
