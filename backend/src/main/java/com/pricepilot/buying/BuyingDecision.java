package com.pricepilot.buying;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.pricepilot.buying.BuyingModels.*;

/** Hard constraints are enforced in code. AI prose never gets to override these decisions. */
@Component
public class BuyingDecision {
    private final PlanningProperties properties;
    public BuyingDecision(PlanningProperties properties) { this.properties = properties; }

    public Plan decide(Preferences preferences, Series series, Forecast forecast, Instant now) {
        return decide(preferences, series, forecast, now, null);
    }

    public Plan decide(Preferences preferences, Series series, Forecast forecast, Instant now, BigDecimal savingsTarget) {
        ZoneId zone = ZoneId.of(properties.getZoneId());
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate deadline = preferences.deadlineDate();
        boolean hardDeadline = deadline != null && preferences.deadlineKind() == DeadlineKind.HARD;
        long remaining = deadline == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(today, deadline);
        Instant reassess = now.plus(Duration.ofHours(properties.getReassessAfterHours()));
        if (hardDeadline && !deadline.isBefore(today)) {
            Instant end = deadline.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
            if (reassess.isAfter(end)) reassess = end;
        }
        Stats stats = series == null ? null : series.stats();
        BigDecimal current = stats == null ? null : stats.currentPrice();
        BigDecimal target = target(preferences, stats, savingsTarget);
        List<String> reasons = new ArrayList<>();
        List<String> triggers = List.of("A new verified price reaches the target or crosses the hard budget.",
                "Your budget, deadline, earliest purchase date, or willingness to accept alternatives changes.",
                "The next reassessment is due; a future price refresh is not guaranteed.");
        Outcome outcome;
        String summary;
        if (hardDeadline && deadline.isBefore(today)) {
            outcome = Outcome.INSUFFICIENT_EVIDENCE;
            summary = "Your hard purchase deadline has passed. Confirm a new date before following a buying schedule.";
            reasons.add("A recommendation cannot satisfy the original hard deadline by buying after it.");
        } else if (series == null || stats.currentPrice() == null || stats.stale()
                || !Boolean.TRUE.equals(stats.currentInStock()) || "DEAD".equals(series.listingStatus())) {
            outcome = Outcome.INSUFFICIENT_EVIDENCE;
            summary = "A recent, in-stock verified offer is needed before recommending a purchase.";
            reasons.add("The last saved price is retained, but a stale, missing, out-of-stock or retired offer is not treated as currently purchasable.");
        } else if (preferences.hardBudget() && preferences.budget() != null && current.compareTo(preferences.budget()) > 0) {
            outcome = hardDeadline && remaining <= properties.getDeadlineRiskDays()
                    ? Outcome.COMPARE_ALTERNATIVES : Outcome.BUY_AT_TARGET_PRICE;
            summary = outcome == Outcome.COMPARE_ALTERNATIVES
                    ? "The current offer exceeds your hard budget and the deadline is approaching. Review affordable alternatives or revise the plan."
                    : "Keep the hard budget intact and watch for a verified offer at or below the target.";
            reasons.add("The current verified price is above your hard maximum; buying it is excluded from this plan.");
            if (!preferences.allowAlternatives()) reasons.add("You have not agreed to alternatives; compare them only if you choose to change that preference.");
        } else if (preferences.earliestDate() != null && preferences.earliestDate().isAfter(today)) {
            outcome = Outcome.WAIT_UNTIL_DATE;
            summary = "Respect your earliest acceptable purchase date and reassess before committing.";
            Instant earliest = preferences.earliestDate().atStartOfDay(zone).toInstant();
            if (earliest.isBefore(reassess)) reassess = earliest;
            reasons.add("You said not to buy before " + preferences.earliestDate() + ".");
        } else if (preferences.budget() == null && preferences.hardBudget()) {
            outcome = Outcome.INSUFFICIENT_EVIDENCE;
            summary = "The history is available, but your affordable budget is still unknown.";
            reasons.add("A low historical price alone does not establish that this purchase fits your budget.");
        } else if ((preferences.targetPrice() != null || preferences.desiredSavingsPercent() != null)
                && target != null && current.compareTo(target) <= 0) {
            outcome = Outcome.BUY_NOW;
            summary = "The verified offer meets your target and current purchase constraints.";
            reasons.add("The target is met by an observed selling price, not by a forecast.");
        } else if ((hardDeadline && remaining <= properties.getDeadlineRiskDays()) || "HIGH".equals(preferences.urgency())) {
            outcome = Outcome.BUY_NOW;
            summary = "Buying the verified offer now reduces deadline risk while respecting your hard budget.";
            reasons.add("The configured deadline safety window or your stated urgency makes waiting less suitable.");
            reasons.add("Delivery timing and the exact variant still need confirmation with the retailer.");
        } else if (stats.inStockCount() >= properties.getMinimumForecastObservations()
                && stats.minimum() != null && current.compareTo(stats.minimum()) <= 0) {
            outcome = Outcome.BUY_NOW;
            summary = "The verified offer is at the lowest selling price in the available observed history.";
            reasons.add("This is a low within the recorded period, not proof of the lowest possible future price or a complete year of coverage.");
        } else if ("ESTIMATED".equals(forecast.status()) && forecast.medianEstimate() != null
                && BuyingHistory.percent(current.subtract(forecast.medianEstimate()), current).doubleValue()
                    >= properties.getMinimumExpectedSavingsPercent()
                && (!hardDeadline || remaining > properties.getDeadlineRiskDays())) {
            outcome = Outcome.WAIT_UNTIL_DATE;
            summary = "A short, bounded wait may be worthwhile; reassess on the scheduled date.";
            reasons.add("A baseline that passed holdout checks suggests potential savings above the configured waiting threshold.");
            reasons.add("The empirical forecast range is uncertain, so the target and deadline remain the decision limits.");
        } else {
            outcome = Outcome.BUY_AT_TARGET_PRICE;
            summary = "Use a target price and scheduled reassessment instead of waiting for an unconfirmed sale.";
            reasons.add("The available evidence does not establish a sufficiently reliable saving from waiting.");
        }
        if (stats != null && !stats.completeYear()) reasons.add("Only " + stats.observedDays() + " of the preceding 365 day buckets contain observations.");
        if (target != null) {
            String basis = preferences.targetPrice() != null ? "your stated target price"
                    : preferences.desiredSavingsPercent() != null ? "your desired savings from the verified price when that savings preference was set"
                    : stats != null && stats.p25() != null ? "the lower quartile of available in-stock observations"
                    : "your stated budget";
            reasons.add("The target is based on " + basis + (preferences.hardBudget() && preferences.budget() != null ? ", capped by your hard budget." : "."));
        }
        if (preferences.budget() != null && !preferences.hardBudget() && current != null && current.compareTo(preferences.budget()) > 0) {
            reasons.add("The current offer is above your flexible budget; confirm that this tradeoff is acceptable.");
        }
        if (deadline != null) reasons.add((hardDeadline ? "Hard purchase deadline: " : "Flexible target date: ") + deadline + ".");
        return new Plan(outcome, summary, List.copyOf(reasons), target, reassess, deadline, now, triggers);
    }

    private static BigDecimal target(Preferences preferences, Stats stats, BigDecimal savingsTarget) {
        BigDecimal target = preferences.targetPrice();
        if (target == null && preferences.desiredSavingsPercent() != null) target = savingsTarget;
        if (target == null && preferences.desiredSavingsPercent() != null && stats != null && stats.currentPrice() != null) {
            target = stats.currentPrice().multiply(BigDecimal.ONE.subtract(preferences.desiredSavingsPercent().movePointLeft(2)))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        if (target == null && stats != null) target = stats.p25();
        if (target == null) target = preferences.budget();
        if (preferences.hardBudget() && preferences.budget() != null) {
            target = target == null ? preferences.budget() : target.min(preferences.budget());
        }
        return target;
    }
}
