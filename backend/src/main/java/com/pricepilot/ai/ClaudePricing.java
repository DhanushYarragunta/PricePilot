package com.pricepilot.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Converts token counts into dollars.
 *
 * <p>This exists so the cost of every analysis is a recorded fact rather than an estimate. The
 * metrics page can then answer "what has this feature actually cost?" with a number from the
 * database, and the budget guard can stop before the credits run out instead of after.
 *
 * <p><b>These rates are hard-coded and will go out of date.</b> They are the published per-million-
 * token prices as of mid-2026, and they are kept here rather than in configuration because a wrong
 * price is better than a missing one — an analysis with no cost recorded is invisible to the budget.
 * If the real bill ever disagrees with the metrics page, this table is the first place to look:
 * check the current rates at <a href="https://claude.com/pricing">claude.com/pricing</a> and update
 * it. The token counts themselves always come from the API response, so they stay correct
 * regardless.
 *
 * <p>Cached input is billed differently: writing to the cache costs more than a normal input token
 * and reading from it costs far less. Both are tracked, because the whole point of enabling prompt
 * caching is to make that difference show up somewhere visible.
 */
public final class ClaudePricing {

    private ClaudePricing() {
    }

    /** USD per million tokens. */
    private record Rate(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {
        static Rate of(String in, String out) {
            BigDecimal input = new BigDecimal(in);
            return new Rate(
                    input,
                    new BigDecimal(out),
                    // Standard five-minute cache: writes are 1.25x input, reads are 0.1x input.
                    input.multiply(new BigDecimal("1.25")),
                    input.multiply(new BigDecimal("0.10")));
        }
    }

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /**
     * Keyed by a model-family prefix rather than an exact ID, so a point release
     * ({@code claude-haiku-4-5-20260xyz}) does not silently fall through to the default.
     */
    private static final Map<String, Rate> RATES = Map.of(
            "claude-haiku-4-5", Rate.of("1.00", "5.00"),
            "claude-haiku-3-5", Rate.of("0.80", "4.00"),
            "claude-sonnet-4-5", Rate.of("3.00", "15.00"),
            "claude-sonnet-4", Rate.of("3.00", "15.00"),
            "claude-opus-4", Rate.of("15.00", "75.00"));

    /** Haiku rates, used when the model is unrecognised. */
    private static final Rate DEFAULT_RATE = Rate.of("1.00", "5.00");

    /**
     * Cost of one call, rounded to six decimal places to match the {@code numeric(12,6)} columns.
     *
     * <p>Six places is not decoration. A single Haiku analysis costs well under a cent, so rounding
     * to the nearest cent would record every call as zero and the running total would never move.
     */
    public static BigDecimal cost(String model,
                                  long inputTokens,
                                  long outputTokens,
                                  long cacheWriteTokens,
                                  long cacheReadTokens) {
        Rate rate = rateFor(model);
        BigDecimal total = perMillion(rate.input(), inputTokens)
                .add(perMillion(rate.output(), outputTokens))
                .add(perMillion(rate.cacheWrite(), cacheWriteTokens))
                .add(perMillion(rate.cacheRead(), cacheReadTokens));
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    /** True when the model name was recognised, so an unknown model can be logged once at startup. */
    public static boolean isKnownModel(String model) {
        return lookup(model) != null;
    }

    private static Rate rateFor(String model) {
        Rate found = lookup(model);
        return found != null ? found : DEFAULT_RATE;
    }

    private static Rate lookup(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String id = model.toLowerCase(Locale.ROOT);
        // Longest prefix wins, so "claude-sonnet-4-5" is not matched by "claude-sonnet-4".
        Rate best = null;
        int bestLength = -1;
        for (Map.Entry<String, Rate> entry : RATES.entrySet()) {
            if (id.startsWith(entry.getKey()) && entry.getKey().length() > bestLength) {
                best = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        return best;
    }

    private static BigDecimal perMillion(BigDecimal ratePerMillion, long tokens) {
        if (tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return ratePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, 10, RoundingMode.HALF_UP);
    }
}
