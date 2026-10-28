package com.pricepilot.repo;

import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ApiUsage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiUsageRepository extends JpaRepository<ApiUsage, Long> {

    Optional<ApiUsage> findByProviderAndPeriod(ApiProvider provider, String period);

    /**
     * Creates this month's counter row if it is not there yet, and does nothing if it is.
     *
     * <p>Needed because {@link #findForUpdate} can only lock a row that exists, and on the first
     * paid call of a new month it does not. Two workers hitting that moment together would both
     * try to insert, and one would die on the unique constraint — which, inside a transaction,
     * poisons the whole transaction rather than just failing the insert. {@code ON CONFLICT DO
     * NOTHING} makes the race a no-op instead of an error, and it is a single statement so there
     * is no window between the check and the insert.
     *
     * <p>The provider is bound as a string because this is native SQL and bypasses the enum
     * converter; the column stores the enum name, so the caller passes {@code provider.name()}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO api_usage (provider, period, calls, input_tokens, output_tokens, cost_usd, updated_at)
            VALUES (:provider, :period, 0, 0, 0, 0, now())
            ON CONFLICT (provider, period) DO NOTHING
            """, nativeQuery = true)
    void ensureRow(@Param("provider") String provider, @Param("period") String period);

    /**
     * Same lookup as {@link #findByProviderAndPeriod}, but takes a row lock so two concurrent
     * scrape workers cannot both read the same counter, both decide there is budget left, and both
     * spend it. The rows are tiny and contention is measured in single-digit threads, so a
     * pessimistic lock is the simple, correct choice here.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT u FROM ApiUsage u
            WHERE u.provider = :provider AND u.period = :period
            """)
    Optional<ApiUsage> findForUpdate(@Param("provider") ApiProvider provider,
                                     @Param("period") String period);

    List<ApiUsage> findByPeriod(String period);

    @Query("SELECT u FROM ApiUsage u ORDER BY u.period DESC, u.provider ASC")
    List<ApiUsage> allNewestFirst();
}
