package com.pricepilot.buying;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.pricepilot.buying.BuyingFixtures.*;
import static com.pricepilot.buying.BuyingModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class BuyingForecastTest {
    private final PlanningProperties properties = new PlanningProperties();
    private final BuyingForecast forecast = new BuyingForecast(properties);

    @Test void insufficientHistoryDoesNotProduceAPricePrediction() {
        Forecast result = forecast.estimate(series(properties, 3, index -> "1000"), Preferences.defaults(10L), NOW);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.lower()).isNull();
        assertThat(result.upper()).isNull();
        assertThat(result.medianEstimate()).isNull();
    }

    @Test void baselinesAreBacktestedAndHorizonRespectsDeadline() {
        Preferences prefs = new Preferences(10L, null, true, LocalDate.parse("2026-11-05"), DeadlineKind.HARD,
                null, null, null, false, "NORMAL", null);
        Forecast result = forecast.estimate(series(properties, 30, index -> index < 24 ? "1000" : "1100"), prefs, NOW);
        assertThat(result.status()).isEqualTo("ESTIMATED");
        assertThat(result.horizonDate()).isEqualTo(prefs.deadlineDate());
        assertThat(result.backtests()).hasSize(3).allSatisfy(test -> {
            assertThat(test.holdoutPoints()).isGreaterThanOrEqualTo(3);
            assertThat(test.mae()).isGreaterThan(java.math.BigDecimal.ZERO);
        });
        assertThat(result.lower()).isLessThanOrEqualTo(result.medianEstimate());
        assertThat(result.upper()).isGreaterThanOrEqualTo(result.medianEstimate());
        assertThat(result.uncertainty()).isEqualTo("HIGH");
        assertThat(result.limitations()).anyMatch(text -> text.contains("not a calibrated prediction interval"));
    }

    @Test void unreliableBaselinesFallBackToTargetPlanning() {
        Forecast result = forecast.estimate(series(properties, 30, index -> index % 2 == 0 ? "1000" : "30000"), Preferences.defaults(10L), NOW);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.backtests()).hasSize(3);
        assertThat(result.lower()).isNull();
        assertThat(result.limitations()).anyMatch(text -> text.contains("too much holdout error"));
    }
}
