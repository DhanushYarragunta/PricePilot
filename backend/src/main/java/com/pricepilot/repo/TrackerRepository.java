package com.pricepilot.repo;

import com.pricepilot.domain.Tracker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrackerRepository extends JpaRepository<Tracker, Long> {

    @Query("""
            SELECT t FROM Tracker t
            JOIN FETCH t.product
            WHERE t.user.id = :userId
            ORDER BY t.createdAt DESC
            """)
    List<Tracker> findByUser(@Param("userId") Long userId);

    Optional<Tracker> findByUserIdAndProductId(Long userId, Long productId);

    long countByUserIdAndActiveTrue(Long userId);

    /** Everyone watching a product — the fan-out list when a price change fires alerts. */
    @Query("""
            SELECT t FROM Tracker t
            JOIN FETCH t.user
            WHERE t.product.id = :productId AND t.active = true
            """)
    List<Tracker> findActiveWatchers(@Param("productId") Long productId);

    @Query("SELECT COUNT(t) FROM Tracker t WHERE t.active = true")
    long countActive();

    /**
     * Distinct products under active tracking, versus total trackers. The gap between these
     * two numbers is the deduplication win — the fetches we never had to make.
     */
    @Query("SELECT COUNT(DISTINCT t.product.id) FROM Tracker t WHERE t.active = true")
    long countDistinctWatchedProducts();
}
