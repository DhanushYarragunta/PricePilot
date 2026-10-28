package com.pricepilot.repo;

import com.pricepilot.domain.PricePoint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PricePointRepository extends JpaRepository<PricePoint, Long> {

    Optional<PricePoint> findFirstByListingIdOrderByCapturedAtDesc(Long listingId);

    /** Chart series for one listing. */
    @Query("""
            SELECT pp FROM PricePoint pp
            WHERE pp.listing.id = :listingId
              AND pp.capturedAt >= :since
            ORDER BY pp.capturedAt ASC
            """)
    List<PricePoint> history(@Param("listingId") Long listingId, @Param("since") Instant since);

    /** Chart series across every listing of a product, for the comparison view. */
    @Query("""
            SELECT pp FROM PricePoint pp
            WHERE pp.listing.product.id = :productId
              AND pp.capturedAt >= :since
            ORDER BY pp.capturedAt ASC
            """)
    List<PricePoint> historyForProduct(@Param("productId") Long productId, @Param("since") Instant since);

    @Query("""
            SELECT MIN(pp.price) FROM PricePoint pp
            WHERE pp.listing.product.id = :productId AND pp.inStock = true
            """)
    Optional<BigDecimal> allTimeLow(@Param("productId") Long productId);

    @Query("""
            SELECT MAX(pp.price) FROM PricePoint pp
            WHERE pp.listing.product.id = :productId AND pp.inStock = true
            """)
    Optional<BigDecimal> allTimeHigh(@Param("productId") Long productId);

    /**
     * Mean in-stock price inside a window.
     *
     * <p>Returns {@code Double}, not {@code BigDecimal}, because that is what {@code AVG} actually
     * produces: JPA specifies the average of any numeric type as a {@code Double}, and Hibernate
     * obliges. Declaring {@code Optional<BigDecimal>} compiles perfectly — generics are erased — and
     * then throws {@code ClassCastException} at the call site the first time real data reaches it.
     * Callers convert explicitly, which is also where the rounding decision belongs.
     */
    @Query("""
            SELECT AVG(pp.price) FROM PricePoint pp
            WHERE pp.listing.product.id = :productId
              AND pp.inStock = true
              AND pp.capturedAt >= :since
            """)
    Optional<Double> averageSince(@Param("productId") Long productId, @Param("since") Instant since);

    /**
     * Cheapest current price per listing for a product. Returns rows of
     * {@code [listingId, retailer, price, inStock, capturedAt]}, newest observation per listing.
     *
     * <p>Uses a correlated subquery rather than a window function so it stays portable and
     * readable; the row counts here are tiny.
     */
    @Query("""
            SELECT pp.listing.id, pp.listing.retailer, pp.price, pp.inStock, pp.capturedAt
            FROM PricePoint pp
            WHERE pp.listing.product.id = :productId
              AND pp.capturedAt = (
                  SELECT MAX(p2.capturedAt) FROM PricePoint p2
                  WHERE p2.listing = pp.listing
              )
            ORDER BY pp.price ASC
            """)
    List<Object[]> latestPerListing(@Param("productId") Long productId);

    /** Total observations recorded — a headline number on the metrics page. */
    @Query("SELECT COUNT(pp) FROM PricePoint pp")
    long countAll();

    /**
     * The newest observation for each listing of a product, as entities with the listing already
     * fetched, cheapest first.
     *
     * <p>Distinct from {@link #latestPerListing} on purpose. That one projects the five columns the
     * comparison table needs and is the right choice for rendering. This one returns whole rows,
     * because the deal analysis also needs {@code listPrice} — the retailer's claimed MRP, which is
     * the input to fake-discount detection.
     *
     * <p>{@code JOIN FETCH} rather than a lazy walk: the result is read outside a transaction, so an
     * uninitialised {@code listing} proxy would throw on the first call to {@code getRetailer()}.
     */
    @Query("""
            SELECT pp FROM PricePoint pp
            JOIN FETCH pp.listing l
            WHERE l.product.id = :productId
              AND pp.capturedAt = (
                  SELECT MAX(p2.capturedAt) FROM PricePoint p2
                  WHERE p2.listing = l
              )
            ORDER BY pp.price ASC
            """)
    List<PricePoint> latestObservations(@Param("productId") Long productId);

    /** How many observations back the statistics for one product. */
    @Query("SELECT COUNT(pp) FROM PricePoint pp WHERE pp.listing.product.id = :productId")
    long countForProduct(@Param("productId") Long productId);

    /** When tracking of this product effectively began, for reporting the span of the history. */
    @Query("SELECT MIN(pp.capturedAt) FROM PricePoint pp WHERE pp.listing.product.id = :productId")
    Optional<Instant> firstObservedAt(@Param("productId") Long productId);

    @Query("SELECT COUNT(pp) FROM PricePoint pp WHERE pp.changed = true")
    long countChanged();

    /** Recent price points for a listing, newest first, for the "did it move?" check. */
    @Query("""
            SELECT pp FROM PricePoint pp
            WHERE pp.listing.id = :listingId
            ORDER BY pp.capturedAt DESC
            """)
    List<PricePoint> recent(@Param("listingId") Long listingId, Pageable pageable);

    // ------------------------------------------------------------------ batched, for the dashboard

    /**
     * Current price per listing across several products at once. Rows of
     * {@code [productId, listingId, retailer, status, price, inStock, capturedAt]}, cheapest first.
     *
     * <p>The batched sibling of {@link #latestPerListing}, and the reason the dashboard renders in a
     * fixed number of queries rather than a number that grows with how much the user tracks. Calling
     * the single-product version once per card is the N+1 that every list view in every ORM-backed
     * application eventually grows, so it is worth closing before it exists.
     *
     * <p>The listing status is projected rather than filtered on, because what counts as a usable
     * price is a product decision and belongs with the code that makes it: a {@code DEAD} listing's
     * last price is fiction and must not set a headline "from" figure, while a {@code BLOCKED} one is
     * merely stale and is better shown with its age than hidden.
     */
    @Query("""
            SELECT pp.listing.product.id, pp.listing.id, pp.listing.retailer, pp.listing.status,
                   pp.price, pp.inStock, pp.capturedAt
            FROM PricePoint pp
            WHERE pp.listing.product.id IN :productIds
              AND pp.capturedAt = (
                  SELECT MAX(p2.capturedAt) FROM PricePoint p2
                  WHERE p2.listing = pp.listing
              )
            ORDER BY pp.price ASC
            """)
    List<Object[]> latestForProducts(@Param("productIds") Collection<Long> productIds);

    /**
     * Lowest and highest in-stock price ever recorded, per product. Rows of
     * {@code [productId, min, max]}.
     *
     * <p>In-stock only, matching {@link #allTimeLow}. An out-of-stock listing often shows a stale or
     * placeholder price, and letting one set the all-time low would produce a floor the product never
     * actually sold at — which is exactly the sort of number a price tracker must not get wrong.
     */
    @Query("""
            SELECT pp.listing.product.id, MIN(pp.price), MAX(pp.price)
            FROM PricePoint pp
            WHERE pp.listing.product.id IN :productIds
              AND pp.inStock = true
            GROUP BY pp.listing.product.id
            """)
    List<Object[]> lowHighForProducts(@Param("productIds") Collection<Long> productIds);

    /**
     * The oldest price still inside the window, per product. Rows of {@code [productId, price]}.
     *
     * <p>Backs the "down 12% this month" line on a dashboard card. Comparing against the oldest
     * observation in the window rather than against a 30-day average is the right choice for that
     * sentence: an average answers "is this cheap for this product", which is the verdict card's job,
     * while a user reading a trend wants to know where the price was when the window opened.
     */
    @Query("""
            SELECT pp.listing.product.id, pp.price
            FROM PricePoint pp
            WHERE pp.listing.product.id IN :productIds
              AND pp.capturedAt >= :since
              AND pp.capturedAt = (
                  SELECT MIN(p2.capturedAt) FROM PricePoint p2
                  WHERE p2.listing.product.id = pp.listing.product.id
                    AND p2.capturedAt >= :since
              )
            """)
    List<Object[]> earliestInWindowForProducts(@Param("productIds") Collection<Long> productIds,
                                               @Param("since") Instant since);
}
