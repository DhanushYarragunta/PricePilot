package com.pricepilot.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pricepilot.domain.DealVerdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * The JSON contract between the prompt and the database. Jackson's view of Claude's reply.
 *
 * <p>Nothing in here is trusted. A language model is asked for JSON in a particular shape and
 * usually produces exactly that, but "usually" is not a property worth building a live service on:
 * the verdict can come back lowercased or wrapped in quotes-within-quotes, the confidence can arrive
 * as the string {@code "0.85"} or as {@code 85}, and {@code suggested_price} can arrive as
 * {@code "₹74,999"} despite being asked for as a plain number. Every accessor below is therefore a
 * sanitiser rather than a getter, and the raw fields stay package-visible so the unmodified reply can
 * still be logged when something looks wrong.
 *
 * <p>Unknown properties are ignored instead of rejected. If a future prompt revision adds a field
 * and the model starts returning it before this class knows about it, that should be a no-op, not a
 * failed analysis.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DealVerdictJson(
        @JsonProperty("verdict") String verdict,
        @JsonProperty("confidence") JsonNumber confidence,
        @JsonProperty("headline") String headline,
        @JsonProperty("reasoning") String reasoning,
        @JsonProperty("fake_discount") Boolean fakeDiscount,
        @JsonProperty("suggested_price") JsonNumber suggestedPrice) {

    /** Matches the {@code headline varchar(300)} column; the prompt asks for 120. */
    private static final int HEADLINE_LIMIT = 300;

    /**
     * The reasoning column is {@code text}, so this is not a storage limit — it is a guard against a
     * runaway generation filling the dashboard with an essay.
     */
    private static final int REASONING_LIMIT = 4000;

    /**
     * Resolves the verdict, tolerating case and stray punctuation.
     *
     * @throws AiException if the string is not one of the six verdicts. This is the one field worth
     *                     refusing on: a verdict is the whole output, and silently defaulting it to
     *                     FAIR would quietly turn a broken prompt into confident wrong advice
     */
    public DealVerdict resolvedVerdict() {
        if (verdict == null || verdict.isBlank()) {
            throw new AiException("Claude returned no verdict field", false, null);
        }
        String cleaned = verdict.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("[^A-Za-z_]", "")
                .toUpperCase(Locale.ROOT);
        try {
            return DealVerdict.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            throw new AiException("Claude returned an unrecognised verdict '" + verdict
                                  + "'. Expected one of " + DealPrompt.allowedVerdicts(), false, null);
        }
    }

    /**
     * Confidence as a fraction between 0 and 1.
     *
     * <p>A value above 1 is read as a percentage and divided by 100 — the model occasionally answers
     * {@code 85} to a question whose scale is 0 to 1, and a stored confidence of 85.0 would render as
     * "8500% sure" on the dashboard. Missing or unparseable confidence becomes 0.5, which is the
     * honest reading of "the model did not say".
     */
    public BigDecimal resolvedConfidence() {
        BigDecimal value = confidence == null ? null : confidence.asDecimal();
        if (value == null) {
            return new BigDecimal("0.500");
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = value.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        }
        if (value.signum() < 0) {
            value = BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = BigDecimal.ONE;
        }
        // Scale 3 to match the numeric(4,3) column.
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    /** Headline, trimmed, stripped of markdown emphasis, and truncated to fit the column. */
    public String resolvedHeadline(DealVerdict fallbackFor) {
        String text = clean(headline);
        if (text.isEmpty()) {
            // Not worth failing the analysis over. A verdict with no headline still renders.
            text = defaultHeadline(fallbackFor);
        }
        return truncate(text, HEADLINE_LIMIT);
    }

    public String resolvedReasoning() {
        return truncate(clean(reasoning), REASONING_LIMIT);
    }

    public boolean resolvedFakeDiscount() {
        return Boolean.TRUE.equals(fakeDiscount);
    }

    /**
     * The suggested target price, or null.
     *
     * <p>Rejected rather than stored when it is non-positive or absurdly large, because this number
     * is shown to the user as a target to wait for and a nonsense figure there is worse than a blank.
     */
    public BigDecimal resolvedSuggestedPrice() {
        BigDecimal value = suggestedPrice == null ? null : suggestedPrice.asDecimal();
        if (value == null || value.signum() <= 0
            || value.compareTo(new BigDecimal("100000000")) > 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ helpers

    private static String defaultHeadline(DealVerdict verdict) {
        return switch (verdict) {
            case STRONG_BUY -> "Near the lowest price we have seen.";
            case GOOD_VALUE -> "Below the usual price for this product.";
            case FAIR -> "Around the typical price.";
            case WAIT -> "Has been cheaper recently.";
            case OVERPRICED -> "Near the highest price we have seen.";
            case INSUFFICIENT_DATA -> "Not enough price history yet.";
        };
    }

    /**
     * Strips the markdown the prompt asked it not to use, and collapses whitespace. The dashboard
     * renders these as plain text, so a stray {@code **} would show up literally.
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("**", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit - 1).trim() + "…";
    }
}
