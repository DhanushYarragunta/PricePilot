package com.pricepilot.usage;

import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ApiUsage;
import com.pricepilot.repo.ApiUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The spending limit. Every outbound paid call goes through here first.
 *
 * <p>This class exists because of a specific failure mode that has nothing to do with code quality:
 * a tracker that polls too eagerly, or a scheduler bug that retries in a tight loop, can drain a
 * month of API credits in an afternoon without anything looking broken. Logs fill with successful
 * calls. The site stays up. The bill arrives later. Since this is a personal project running on
 * personal credits, the counter is not an afterthought — it is the thing that lets the site be left
 * running unattended.
 *
 * <p>Two shapes of budget, because the two providers bill differently:
 *
 * <ul>
 *   <li><b>Call budget</b> ({@link #tryConsumeCall}) for the unblocker, which charges per request.
 *       The counter is incremented <em>before</em> the call under a row lock, so the limit is
 *       enforced exactly rather than approximately, and a crash mid-call cannot lose the charge.
 *   <li><b>Dollar budget</b> ({@link #hasUsdBudget} then {@link #recordSpend}) for Claude, where the
 *       cost is only known from the token counts in the response. The check happens before and the
 *       spend is recorded after, which means the cap can be exceeded by the cost of a single call.
 *       That is an accepted, bounded overshoot — a fraction of a cent on Haiku — and the honest
 *       alternative, pre-reserving a guessed amount, would under-use the budget instead.
 * </ul>
 *
 * <p>Each method runs in its own transaction ({@code REQUIRES_NEW}). The reason is deliberate: the
 * counter must commit even when the surrounding operation is rolled back. If a scrape fails after
 * the unblocker has already been called, the provider still charged for it, so forgetting the charge
 * would make the budget a lie.
 */
@Service
public class BudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ApiUsageRepository repo;

    public BudgetGuard(ApiUsageRepository repo) {
        this.repo = repo;
    }

    /** Billing periods are UTC calendar months, so the month does not roll over mid-evening IST. */
    public static String currentPeriod() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(PERIOD);
    }

    /**
     * Claims one call against a per-month call budget.
     *
     * @param budget maximum calls this month; zero or less disables the provider entirely
     * @return true if the call may proceed. The counter has already been incremented, so a caller
     *         that gets {@code true} and then decides not to make the call has spent budget for
     *         nothing — check everything else first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryConsumeCall(ApiProvider provider, int budget) {
        if (budget <= 0) {
            return false;
        }
        ApiUsage usage = lockRow(provider);
        if (usage.getCalls() >= budget) {
            log.warn("{} monthly call budget exhausted: {}/{} used in {}. Further calls are "
                     + "refused until next month, or until the budget is raised.",
                    provider, usage.getCalls(), budget, usage.getPeriod());
            return false;
        }
        usage.setCalls(usage.getCalls() + 1);
        usage.setUpdatedAt(java.time.Instant.now());
        repo.save(usage);

        int remaining = budget - usage.getCalls();
        if (remaining <= 5 || remaining % 100 == 0) {
            log.info("{} budget: {} of {} calls remaining this month", provider, remaining, budget);
        }
        return true;
    }

    /**
     * Checks whether there is room left under a dollar budget, without reserving anything.
     *
     * @param budget monthly ceiling in USD; zero or less disables the provider
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean hasUsdBudget(ApiProvider provider, BigDecimal budget) {
        if (budget == null || budget.signum() <= 0) {
            return false;
        }
        ApiUsage usage = lockRow(provider);
        if (usage.getCostUsd().compareTo(budget) >= 0) {
            log.warn("{} monthly spend budget exhausted: ${} of ${} used in {}. Analysis is "
                     + "paused until next month, or until AI_MONTHLY_BUDGET_USD is raised.",
                    provider, usage.getCostUsd().toPlainString(), budget.toPlainString(),
                    usage.getPeriod());
            return false;
        }
        return true;
    }

    /**
     * Records what a call actually cost. Safe to call with zeroes for providers that do not report
     * tokens; the call count still increments, which is what the ops page shows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSpend(ApiProvider provider, long inputTokens, long outputTokens, BigDecimal costUsd) {
        ApiUsage usage = lockRow(provider);
        usage.setInputTokens(usage.getInputTokens() + Math.max(0, inputTokens));
        usage.setOutputTokens(usage.getOutputTokens() + Math.max(0, outputTokens));
        if (costUsd != null && costUsd.signum() > 0) {
            usage.setCostUsd(usage.getCostUsd().add(costUsd));
        }
        usage.setUpdatedAt(java.time.Instant.now());
        repo.save(usage);
    }

    /**
     * Increments the call counter without checking a budget. For providers whose budget was already
     * claimed by a different mechanism — Claude's dollar check — so the call count still reflects
     * reality on the ops page.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCall(ApiProvider provider) {
        ApiUsage usage = lockRow(provider);
        usage.setCalls(usage.getCalls() + 1);
        usage.setUpdatedAt(java.time.Instant.now());
        repo.save(usage);
    }

    @Transactional(readOnly = true)
    public List<ApiUsage> thisMonth() {
        return repo.findByPeriod(currentPeriod());
    }

    @Transactional(readOnly = true)
    public List<ApiUsage> history() {
        return repo.allNewestFirst();
    }

    @Transactional(readOnly = true)
    public int callsThisMonth(ApiProvider provider) {
        return repo.findByProviderAndPeriod(provider, currentPeriod())
                .map(ApiUsage::getCalls)
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public BigDecimal spendThisMonth(ApiProvider provider) {
        return repo.findByProviderAndPeriod(provider, currentPeriod())
                .map(ApiUsage::getCostUsd)
                .orElse(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Returns this month's row for the provider, locked for update, creating it first if needed.
     * The insert-then-lock pair is why {@code ensureRow} uses {@code ON CONFLICT DO NOTHING}.
     */
    private ApiUsage lockRow(ApiProvider provider) {
        String period = currentPeriod();
        repo.ensureRow(provider.name(), period);
        return repo.findForUpdate(provider, period).orElseThrow(() ->
                new IllegalStateException("api_usage row for " + provider + " " + period
                        + " vanished immediately after being created. This should be impossible "
                        + "unless something is deleting rows concurrently."));
    }
}
