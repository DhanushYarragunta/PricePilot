package com.pricepilot.repo;

import com.pricepilot.domain.ScrapeAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScrapeAttemptRepository extends JpaRepository<ScrapeAttempt, Long> {

    /**
     * Success rate per host. Rows of {@code [host, attempts, successes]}.
     * {@code SKIPPED_FRESH} is excluded because it involved no network request,
     * so counting it would flatter the success rate.
     */
    @Query("""
            SELECT a.host,
                   COUNT(a),
                   SUM(CASE WHEN a.outcome = com.pricepilot.domain.ScrapeOutcome.SUCCESS THEN 1 ELSE 0 END)
            FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
              AND a.outcome <> com.pricepilot.domain.ScrapeOutcome.SKIPPED_FRESH
            GROUP BY a.host
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> successRateByHost(@Param("since") Instant since);

    /** Success rate per strategy. Rows of {@code [strategy, attempts, successes, avgLatencyMs]}. */
    @Query("""
            SELECT a.strategy,
                   COUNT(a),
                   SUM(CASE WHEN a.outcome = com.pricepilot.domain.ScrapeOutcome.SUCCESS THEN 1 ELSE 0 END),
                   COALESCE(AVG(a.latencyMs), 0)
            FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
              AND a.outcome <> com.pricepilot.domain.ScrapeOutcome.SKIPPED_FRESH
            GROUP BY a.strategy
            ORDER BY COUNT(a) DESC
            """)
    List<Object[]> successRateByStrategy(@Param("since") Instant since);

    /** Outcome breakdown. Rows of {@code [outcome, count]}. */
    @Query("""
            SELECT a.outcome, COUNT(a) FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
            GROUP BY a.outcome
            """)
    List<Object[]> outcomeBreakdown(@Param("since") Instant since);

    @Query("""
            SELECT COUNT(a) FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
              AND a.outcome = com.pricepilot.domain.ScrapeOutcome.SKIPPED_FRESH
            """)
    long countSkippedFresh(@Param("since") Instant since);

    @Query("""
            SELECT COUNT(a) FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
              AND a.outcome <> com.pricepilot.domain.ScrapeOutcome.SKIPPED_FRESH
            """)
    long countNetworkRequests(@Param("since") Instant since);

    @Query("""
            SELECT COALESCE(AVG(a.latencyMs), 0) FROM ScrapeAttempt a
            WHERE a.createdAt >= :since
              AND a.outcome = com.pricepilot.domain.ScrapeOutcome.SUCCESS
            """)
    double averageSuccessLatencyMs(@Param("since") Instant since);

    /** Recent attempts for one listing — powers the "why is this listing stuck?" panel. */
    @Query("""
            SELECT a FROM ScrapeAttempt a
            WHERE a.listing.id = :listingId
            ORDER BY a.createdAt DESC
            """)
    List<ScrapeAttempt> recentForListing(@Param("listingId") Long listingId, Pageable pageable);

    /**
     * Recent attempts across every listing of a product, newest first — the check log shown on the
     * product page.
     *
     * <p>Product-wide rather than per-listing because the question a user asks is "is this product's
     * price current?", and that is answered by the interleaved history of all its retailers. Issuing
     * {@link #recentForListing} once per listing would also mean four queries and four separate
     * twenty-row windows, which cannot be merged into one honest "last twenty checks".
     *
     * <p>{@code JOIN FETCH} on the listing because the view needs the retailer name for every row;
     * without it this is a twenty-row result followed by twenty more queries.
     */
    @Query("""
            SELECT a FROM ScrapeAttempt a
            JOIN FETCH a.listing l
            WHERE l.product.id = :productId
            ORDER BY a.createdAt DESC
            """)
    List<ScrapeAttempt> recentForProduct(@Param("productId") Long productId, Pageable pageable);

    /** Newest failures across all hosts — the first place to look when the site misbehaves. */
    @Query("""
            SELECT a FROM ScrapeAttempt a
            WHERE a.outcome NOT IN (
                com.pricepilot.domain.ScrapeOutcome.SUCCESS,
                com.pricepilot.domain.ScrapeOutcome.SKIPPED_FRESH
            )
            ORDER BY a.createdAt DESC
            """)
    List<ScrapeAttempt> recentFailures(Pageable pageable);

    /**
     * Housekeeping. Telemetry older than the retention window is deleted nightly so the
     * table cannot grow without bound on a small EC2 disk.
     */
    @Modifying
    @Query("DELETE FROM ScrapeAttempt a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
