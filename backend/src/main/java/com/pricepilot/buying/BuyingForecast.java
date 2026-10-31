package com.pricepilot.buying;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.pricepilot.buying.BuyingModels.*;

/** Rolling-origin holdouts compare simple baselines; no seasonal sale calendar is assumed. */
@Component
public class BuyingForecast {
    private final PlanningProperties properties;
    public BuyingForecast(PlanningProperties properties) { this.properties = properties; }

    public Forecast estimate(Series series, Preferences preferences, Instant now) {
        LocalDate today = now.atZone(ZoneId.of(properties.getZoneId())).toLocalDate();
        LocalDate horizon = today.plusDays(properties.getMaximumForecastDays());
        if (preferences.deadlineDate() != null && preferences.deadlineDate().isBefore(horizon)) horizon = preferences.deadlineDate();
        if (horizon.isBefore(today)) horizon = today;
        List<String> limitations = new ArrayList<>(List.of(
                "This is an empirical scenario range, not a calibrated prediction interval or a promised future price.",
                "Baselines are tested on later stored daily observations using only earlier observations. Missing dates are not filled.",
                "No reliable seasonality or confirmed future sale is established by this history."));
        if (series == null || series.stats().stale() || !"INR".equals(series.currency())) {
            limitations.add("A recent verified INR observation is needed before forecasting.");
            return insufficient(horizon, List.of(), limitations);
        }
        Map<LocalDate, BigDecimal> daily = new TreeMap<>();
        series.observations().stream().filter(Observation::inStock).forEach(point -> daily.put(
                point.capturedAt().atZone(ZoneId.of(properties.getZoneId())).toLocalDate(), point.price()));
        List<Daily> data = daily.entrySet().stream().map(entry -> new Daily(entry.getKey(), entry.getValue())).toList();
        long span = data.isEmpty() ? 0 : ChronoUnit.DAYS.between(data.get(0).date(), data.get(data.size() - 1).date()) + 1;
        double coverage = span == 0 ? 0 : data.size() * 100.0 / span;
        if (series.stats().inStockCount() < properties.getMinimumForecastObservations()
                || data.size() < properties.getMinimumForecastDays() || coverage < properties.getMinimumTrainingCoveragePercent()) {
            limitations.add("Too few distinct observed days or too many gaps for a useful holdout comparison. Prefer a target price and reassessment.");
            return insufficient(horizon, List.of(), limitations);
        }
        int split = Math.max(3, Math.min(data.size() - 3, (int) Math.floor(data.size() * .7)));
        List<Tested> tested = new ArrayList<>();
        for (String method : List.of("LAST_OBSERVATION", "ROLLING_MEDIAN", "RECENT_TREND")) {
            List<BigDecimal> errors = new ArrayList<>();
            for (int index = split; index < data.size(); index++) {
                BigDecimal predicted = predict(method, data.subList(0, index), data.get(index).date());
                errors.add(predicted.subtract(data.get(index).price()).abs());
            }
            BigDecimal mae = errors.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(errors.size()), 2, RoundingMode.HALF_UP);
            BigDecimal scale = data.subList(split, data.size()).stream().map(Daily::price).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(data.size() - split), 2, RoundingMode.HALF_UP);
            tested.add(new Tested(new Backtest(method, errors.size(), mae, BuyingHistory.percent(mae, scale)), errors));
        }
        tested.sort(Comparator.comparing(item -> item.result().mae()));
        Tested best = tested.get(0);
        List<Backtest> backtests = tested.stream().map(Tested::result).toList();
        if (best.result().relativeMaePercent().doubleValue() > properties.getMaximumBacktestErrorPercent()) {
            limitations.add("Even the best simple baseline had too much holdout error for a useful forecast.");
            return insufficient(horizon, backtests, limitations);
        }
        BigDecimal estimate = predict(best.result().method(), data, horizon);
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(today, horizon));
        BigDecimal error = BuyingHistory.percentile(best.errors().stream().sorted().toList(), .9)
                .multiply(BigDecimal.valueOf(Math.sqrt(days)));
        List<BigDecimal> prices = data.stream().map(Daily::price).sorted().toList();
        BigDecimal lower = estimate.subtract(error).min(BuyingHistory.percentile(prices, .1)).max(new BigDecimal("0.01"));
        BigDecimal upper = estimate.add(error).max(BuyingHistory.percentile(prices, .9));
        limitations.add("The range combines historical percentiles with holdout error widened for the horizon; this widening is a conservative heuristic, not a probability guarantee.");
        if (!series.stats().completeYear()) limitations.add("The available history does not cover a complete year of observations.");
        return new Forecast("ESTIMATED", best.result().method(), horizon, money(lower), money(upper), money(estimate),
                "HIGH", backtests, List.copyOf(limitations));
    }

    private static Forecast insufficient(LocalDate horizon, List<Backtest> backtests, List<String> limitations) {
        return new Forecast("INSUFFICIENT_EVIDENCE", null, horizon, null, null, null, "INSUFFICIENT", backtests, List.copyOf(limitations));
    }

    private static BigDecimal predict(String method, List<Daily> training, LocalDate date) {
        List<Daily> recent = training.subList(Math.max(0, training.size() - 7), training.size());
        BigDecimal last = recent.get(recent.size() - 1).price();
        if (method.equals("LAST_OBSERVATION")) return last;
        if (method.equals("ROLLING_MEDIAN")) return BuyingHistory.percentile(recent.stream().map(Daily::price).sorted().toList(), .5);
        long origin = recent.get(0).date().toEpochDay();
        double meanX = recent.stream().mapToLong(point -> point.date().toEpochDay() - origin).average().orElse(0);
        double meanY = recent.stream().mapToDouble(point -> point.price().doubleValue()).average().orElse(last.doubleValue());
        double numerator = 0, denominator = 0;
        for (Daily point : recent) {
            double x = point.date().toEpochDay() - origin - meanX;
            numerator += x * (point.price().doubleValue() - meanY);
            denominator += x * x;
        }
        double predicted = meanY + (denominator == 0 ? 0 : numerator / denominator) * (date.toEpochDay() - origin - meanX);
        return money(BigDecimal.valueOf(Math.max(.01, Math.min(5_000_000, predicted))));
    }

    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private record Daily(LocalDate date, BigDecimal price) { }
    private record Tested(Backtest result, List<BigDecimal> errors) { }
}
