package com.pricepilot.repo;

import com.pricepilot.domain.AlertKind;
import com.pricepilot.domain.AlertLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

    /** The user's alert feed. Joins the product in so the list renders without N+1 queries. */
    @Query("""
            SELECT a FROM AlertLog a
            JOIN FETCH a.tracker t
            JOIN FETCH t.product
            WHERE t.user.id = :userId
            ORDER BY a.createdAt DESC
            """)
    List<AlertLog> feedForUser(@Param("userId") Long userId, Pageable pageable);

    /**
     * Cooldown guard. Returns true when this tracker already got this kind of alert inside the
     * cooldown window, which is how a product oscillating around a target price is stopped from
     * sending a dozen emails in an afternoon.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM AlertLog a
            WHERE a.tracker.id = :trackerId
              AND a.kind = :kind
              AND a.createdAt >= :since
            """)
    boolean existsRecent(@Param("trackerId") Long trackerId,
                         @Param("kind") AlertKind kind,
                         @Param("since") Instant since);

    long countByTrackerIdAndKind(Long trackerId, AlertKind kind);

    @Query("SELECT COUNT(a) FROM AlertLog a")
    long countAll();

    @Query("SELECT COUNT(a) FROM AlertLog a WHERE a.delivered = true")
    long countDelivered();

    /** Undelivered alerts, newest first — the retry/diagnostic list when SMTP was down. */
    @Query("""
            SELECT a FROM AlertLog a
            WHERE a.delivered = false AND a.deliveryError IS NOT NULL
            ORDER BY a.createdAt DESC
            """)
    List<AlertLog> recentFailures(Pageable pageable);
}
