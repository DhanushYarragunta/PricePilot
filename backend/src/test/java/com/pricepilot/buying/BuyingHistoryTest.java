package com.pricepilot.buying;

import com.pricepilot.domain.PricePoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.pricepilot.buying.BuyingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class BuyingHistoryTest {
    private final PlanningProperties properties = new PlanningProperties();
    private final BuyingHistory history = new BuyingHistory(properties);

    @Test void dailyYearHasFullCoverageWithoutInventedPoints() {
        var listing = listing(10, "Amazon.in");
        var points = daily(listing, 365, index -> "1000");
        var result = history.analyze(List.of(listing), points, List.of(points.get(364)), 10L, NOW);
        var series = result.series().get(0);
        assertThat(series.observations()).hasSize(365);
        assertThat(series.stats().completeYear()).isTrue();
        assertThat(series.stats().observedDays()).isEqualTo(365);
        assertThat(series.stats().coveragePercent()).isEqualByComparingTo("100");
        assertThat(series.stats().minimum()).isEqualByComparingTo("1000");
        assertThat(series.stats().average()).isEqualByComparingTo("1000");
        assertThat(series.stats().p10()).isEqualByComparingTo("1000");
        assertThat(series.gaps()).isEmpty();
    }

    @Test void gapsAndIncompleteCoverageStayVisibleWithOnlyActualObservations() {
        var listing = listing(10, "Amazon.in");
        var points = List.of(point(listing, "1000", NOW.minus(Duration.ofDays(90))), point(listing, "800", NOW));
        var series = history.analyze(List.of(listing), points, List.of(points.get(1)), 10L, NOW).series().get(0);
        assertThat(series.observations()).hasSize(2);
        assertThat(series.stats().completeYear()).isFalse();
        assertThat(series.stats().observedDays()).isEqualTo(2);
        assertThat(series.stats().median()).isEqualByComparingTo("900");
        assertThat(series.stats().average()).isEqualByComparingTo("900");
        assertThat(series.stats().p10()).isNull();
        assertThat(series.stats().priceDrops()).isEqualTo(1);
        assertThat(series.stats().observedLowPriceSpanDays()).isZero();
        assertThat(series.gaps()).anyMatch(gap -> gap.kind().equals("BETWEEN_OBSERVATIONS") && gap.hours() == 90 * 24);
    }

    @Test void retailerVariantAndCurrencyRemainSeparateAndMrpNeverEntersPriceStatistics() {
        var amazon = listing(10, "Amazon.in");
        var flipkart = listing(11, "Flipkart");
        PricePoint indian = point(amazon, "1000", NOW);
        PricePoint foreign = point(amazon, "10", NOW.minusSeconds(10)); foreign.setCurrency("USD");
        PricePoint other = point(flipkart, "700", NOW);
        var result = history.analyze(List.of(amazon, flipkart), List.of(indian, foreign, other), List.of(indian, other), 10L, NOW);
        assertThat(result.series()).hasSize(3);
        assertThat(history.selected(result).stats().minimum()).isEqualByComparingTo("1000");
        assertThat(history.selected(result).stats().maximum()).isEqualByComparingTo("1000");
        assertThat(history.selected(result).observations().get(0).mrp()).isEqualByComparingTo("2000");
        assertThat(result.series().stream().filter(series -> series.currency().equals("USD")).findFirst().orElseThrow().stats().minimum())
                .isEqualByComparingTo("10");
    }

    @Test void oldCurrentObservationIsPreservedAndFlaggedStaleWithoutExtendingTheChart() {
        var listing = listing(10, "Amazon.in");
        var old = point(listing, "1609", NOW.minus(Duration.ofDays(400)));
        var series = history.analyze(List.of(listing), List.of(old), List.of(old), 10L, NOW).series().get(0);
        assertThat(series.observations()).isEmpty();
        assertThat(series.stats().currentPrice()).isEqualByComparingTo("1609");
        assertThat(series.stats().lastObservedAt()).isEqualTo(old.getCapturedAt());
        assertThat(series.stats().stale()).isTrue();
        assertThat(series.stats().minimum()).isNull();
    }

    @Test void invalidFutureAndUnavailablePricesCannotSetHistoricalLow() {
        var listing = listing(10, "Amazon.in");
        var valid = point(listing, "1000", NOW);
        var unavailable = point(listing, "1", NOW.minusSeconds(1)); unavailable.setInStock(false);
        var invalid = point(listing, "-1", NOW.minusSeconds(2));
        var future = point(listing, "2", NOW.plusSeconds(30));
        var series = history.analyze(List.of(listing), List.of(valid, unavailable, invalid, future), List.of(valid), 10L, NOW).series().get(0);
        assertThat(series.observations()).hasSize(2);
        assertThat(series.stats().minimum()).isEqualByComparingTo("1000");
        assertThat(series.stats().inStockCount()).isEqualTo(1);
    }
}
