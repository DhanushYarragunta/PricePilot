package com.pricepilot.buying;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.pricepilot.buying.BuyingFixtures.*;
import static com.pricepilot.buying.BuyingModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class BuyingDecisionTest {
    private final PlanningProperties properties = new PlanningProperties();
    private final BuyingDecision decision = new BuyingDecision(properties);
    private final Series current = series(properties, 3, index -> "1000");
    private final Forecast unknown = new Forecast("INSUFFICIENT_EVIDENCE", null, LocalDate.parse("2026-11-30"),
            null, null, null, "INSUFFICIENT", List.of(), List.of());

    @Test void hardBudgetPreventsBuyingEvenNearDeadline() {
        Plan plan = decision.decide(prefs("900", true, "2026-11-01", DeadlineKind.HARD, null, "1200"), current, unknown, NOW);
        assertThat(plan.outcome()).isEqualTo(Outcome.COMPARE_ALTERNATIVES);
        assertThat(plan.targetPrice()).isEqualByComparingTo("900");
        assertThat(plan.purchaseBy()).isEqualTo(LocalDate.parse("2026-11-01"));
    }

    @Test void deadlineRiskCanFavorBuyingWithinBudgetButFlexibleTargetDoesNotForceIt() {
        assertThat(decision.decide(prefs("1100", true, "2026-11-01", DeadlineKind.HARD, null, null), current, unknown, NOW).outcome())
                .isEqualTo(Outcome.BUY_NOW);
        assertThat(decision.decide(prefs("1100", true, "2026-11-01", DeadlineKind.FLEXIBLE, null, null), current, unknown, NOW).outcome())
                .isEqualTo(Outcome.BUY_AT_TARGET_PRICE);
    }

    @Test void passedHardDeadlineNeverCreatesABuyAfterDeadlineRecommendation() {
        Plan plan = decision.decide(prefs("1100", true, "2026-10-30", DeadlineKind.HARD, null, null), current, unknown, NOW);
        assertThat(plan.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(plan.summary()).contains("deadline has passed");
    }

    @Test void earliestDateAndReassessmentRespectHardPurchaseWindow() {
        Plan plan = decision.decide(prefs("1100", true, "2026-11-05", DeadlineKind.HARD, "2026-11-02", "1100"), current, unknown, NOW);
        assertThat(plan.outcome()).isEqualTo(Outcome.WAIT_UNTIL_DATE);
        assertThat(plan.reassessAt()).isBefore(LocalDate.parse("2026-11-05").atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
    }

    @Test void observedTargetTriggersBuyButStaleEvidenceCannot() {
        Preferences prefs = prefs("1100", true, null, DeadlineKind.FLEXIBLE, null, "1000");
        assertThat(decision.decide(prefs, current, unknown, NOW).outcome()).isEqualTo(Outcome.BUY_NOW);
        var listing = listing(10, "Amazon.in");
        var old = point(listing, "900", NOW.minus(Duration.ofDays(3)));
        Series stale = new BuyingHistory(properties).analyze(List.of(listing), List.of(old), List.of(old), 10L, NOW).series().get(0);
        assertThat(decision.decide(prefs, stale, unknown, NOW).outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
    }

    private static Preferences prefs(String budget, boolean hard, String deadline, DeadlineKind kind, String earliest, String target) {
        return new Preferences(10L, new BigDecimal(budget), hard, deadline == null ? null : LocalDate.parse(deadline), kind,
                earliest == null ? null : LocalDate.parse(earliest), target == null ? null : new BigDecimal(target), null, false, "NORMAL", null);
    }
}
