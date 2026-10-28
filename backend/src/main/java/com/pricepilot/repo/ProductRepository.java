package com.pricepilot.repo;

import com.pricepilot.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Products that at least one active tracker points at. Used by the scheduler to avoid
     * spending requests on products nobody is watching any more.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p
            WHERE EXISTS (
                SELECT 1 FROM Tracker t
                WHERE t.product = p AND t.active = true
            )
            """)
    List<Product> findWatched();

    @Query("""
            SELECT p FROM Product p
            WHERE LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.updatedAt DESC
            """)
    List<Product> search(@Param("q") String q);
}
