package com.pricepilot.buying;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BuyingModels {
    private BuyingModels() { }

    public enum Outcome { BUY_NOW, WAIT_UNTIL_DATE, BUY_AT_TARGET_PRICE, COMPARE_ALTERNATIVES, INSUFFICIENT_EVIDENCE }
    public enum DeadlineKind { HARD, FLEXIBLE }

    public record Preferences(Long listingId, BigDecimal budget, boolean hardBudget, LocalDate deadlineDate,
                              DeadlineKind deadlineKind, LocalDate earliestDate, BigDecimal targetPrice,
                              BigDecimal desiredSavingsPercent, boolean allowAlternatives, String urgency, String reason) {
        public static Preferences defaults(Long listingId) {
            return new Preferences(listingId, null, true, null, DeadlineKind.HARD, null, null,
                    null, false, "NORMAL", null);
        }
    }

    public record PatchRequest(@NotNull @Min(0) Long version, @NotNull JsonNode preferences) { }
    public record MessageRequest(@NotNull @Min(0) Long version, @NotBlank @Size(max = 2000) String message) { }
    public record Observation(BigDecimal price, BigDecimal mrp, boolean inStock, Instant capturedAt, String sourceStrategy) { }
    public record Gap(Instant from, Instant to, long hours, String kind) { }
    public record Stats(int observationCount, int inStockCount, int observedDays, BigDecimal coveragePercent,
                        boolean completeYear, Instant firstObservedAt, Instant lastObservedAt,
                        BigDecimal currentPrice, Boolean currentInStock, boolean stale,
                        BigDecimal minimum, BigDecimal maximum, BigDecimal average, BigDecimal median,
                        BigDecimal p10, BigDecimal p25, BigDecimal p75, BigDecimal p90,
                        BigDecimal currentVsMinPercent, BigDecimal currentVsMedianPercent, BigDecimal currentVsAveragePercent,
                        int priceChanges, int priceDrops, int belowMedianObservations, BigDecimal belowMedianPercent,
                        BigDecimal observedLowPriceSpanDays, long largestGapHours) { }
    public record Series(Long listingId, String retailer, String title, String url, String currency,
                         String listingStatus, Stats stats, List<Observation> observations, List<Gap> gaps) { }
    public record History(Instant windowStart, Instant windowEnd, int requestedDays, String source,
                          Long selectedListingId, List<Series> series, List<String> limitations) { }
    public record Backtest(String method, int holdoutPoints, BigDecimal mae, BigDecimal relativeMaePercent) { }
    public record Forecast(String status, String method, LocalDate horizonDate, BigDecimal lower, BigDecimal upper,
                           BigDecimal medianEstimate, String uncertainty, List<Backtest> backtests, List<String> limitations) { }
    public record Plan(Outcome outcome, String summary, List<String> reasons, BigDecimal targetPrice,
                       Instant reassessAt, LocalDate purchaseBy, Instant generatedAt, List<String> changeTriggers) { }
    public record Snapshot(Plan plan, Forecast forecast, Stats evidence, String currency, Instant observedAt,
                           String followUpQuestion, List<String> warnings, String listingStatus, BigDecimal savingsTarget) {
        public Snapshot(Plan plan, Forecast forecast, Stats evidence, String currency, Instant observedAt,
                        String followUpQuestion, List<String> warnings) {
            this(plan, forecast, evidence, currency, observedAt, followUpQuestion, warnings, null, null);
        }
        public Snapshot(Plan plan, Forecast forecast, Stats evidence, String currency, Instant observedAt,
                        String followUpQuestion, List<String> warnings, String listingStatus) {
            this(plan, forecast, evidence, currency, observedAt, followUpQuestion, warnings, listingStatus, null);
        }
    }
    public record Conversation(Long id, String role, String content, Instant createdAt) { }
    public record Notification(Long id, String kind, String message, Instant createdAt, Instant deliveredAt) { }
    public record View(Long id, Long productId, long version, Preferences preferences, Plan plan, History history,
                       Forecast forecast, List<Conversation> conversation, String followUpQuestion,
                       List<String> warnings, List<Notification> notifications, Instant updatedAt) { }
}
