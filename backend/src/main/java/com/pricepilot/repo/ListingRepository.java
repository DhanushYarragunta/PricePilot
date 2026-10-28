package com.pricepilot.repo;

import com.pricepilot.domain.Listing;
import com.pricepilot.domain.ListingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ListingRepository extends JpaRepository<Listing, Long> {

    /** Global dedup lookup: one listing per canonical URL, shared across all users. */
    Optional<Listing> findByUrlHash(String urlHash);

    List<Listing> findByProductId(Long productId);

    List<Listing> findByProductIdAndStatus(Long productId, ListingStatus status);

    /**
     * One listing with its product already loaded.
     *
     * <p>The scheduler needs this because it works on a detached entity: the transaction that read
     * the listing has closed by the time the page is fetched, so walking the lazy {@code product}
     * association would throw {@code LazyInitializationException}. Fetching it up front is cheaper
     * and far more predictable than keeping a transaction — and a database connection — open across
     * a twenty-second HTTP request to a retailer.
     */
    @Query("SELECT l FROM Listing l JOIN FETCH l.product WHERE l.id = :id")
    Optional<Listing> findWithProductById(@Param("id") Long id);

    /**
     * The scheduler's core query: active listings that are due, oldest-due first, and only for
     * products somebody is actually still watching.
     */
    @Query("""
            SELECT l FROM Listing l
            WHERE l.status = com.pricepilot.domain.ListingStatus.ACTIVE
              AND l.nextCheckAt <= :now
              AND EXISTS (
                  SELECT 1 FROM Tracker t
                  WHERE t.product = l.product AND t.active = true
              )
            ORDER BY l.nextCheckAt ASC
            """)
    List<Listing> findDue(@Param("now") Instant now, Pageable pageable);

    /**
     * True when any active tracker on this listing's product wants tight polling,
     * which pins the listing to the minimum check interval.
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM Tracker t
            WHERE t.product.id = :productId
              AND t.active = true
              AND (t.targetPrice IS NOT NULL OR t.notifyOnDeal = true)
            """)
    boolean hasUrgentTracker(@Param("productId") Long productId);

    long countByStatus(ListingStatus status);

    @Query("SELECT COALESCE(AVG(l.checkIntervalMinutes), 0) FROM Listing l WHERE l.status = com.pricepilot.domain.ListingStatus.ACTIVE")
    double averageActiveIntervalMinutes();

    /**
     * Per-product listing summary for the dashboard. Rows of
     * {@code [productId, liveListingCount, lastCheckedAt]}.
     *
     * <p>{@code lastCheckedAt} comes from the listing rather than from the newest price point on
     * purpose. They are different facts: a check that was skipped as fresh, blocked, or that simply
     * found the same price again writes no price point, so the newest observation can be hours older
     * than the last time the retailer was actually asked. A card that says "checked 20 minutes ago"
     * should mean exactly that.
     *
     * <p>{@code DEAD} listings are excluded from both figures. A listing is only marked dead once its
     * page has gone, at which point counting it as a retailer where the product is available — or
     * letting its final check set the freshness shown to the user — would both be untrue.
     */
    @Query("""
            SELECT l.product.id, COUNT(l), MAX(l.lastCheckedAt)
            FROM Listing l
            WHERE l.product.id IN :productIds
              AND l.status <> com.pricepilot.domain.ListingStatus.DEAD
            GROUP BY l.product.id
            """)
    List<Object[]> summaryForProducts(@Param("productIds") java.util.Collection<Long> productIds);
}
