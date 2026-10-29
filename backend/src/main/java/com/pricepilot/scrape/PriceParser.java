package com.pricepilot.scrape;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever text a retailer put on the page into a rupee amount.
 *
 * <p>Indian price strings are messier than they look:
 *
 * <pre>
 *   "Rs 1,29,999.00"        lakh-crore grouping, not thousands grouping
 *   "Rs. 1,299"             different currency prefix
 *   "Rs 1,29,999. 00"       rupees and paise in separate HTML spans
 *   "Rs 49,999 (17% off)"   two numbers, only one of them is the price
 *   "M.R.P.: Rs 1,49,900"   a number that is deliberately not the price
 *   "129999"                JSON-LD, no formatting at all
 *   "4.3 out of 5 stars"    what a stale selector returns after a redesign
 * </pre>
 *
 * <p>Rather than trusting the first number it sees, the parser finds every numeric candidate and
 * scores them on context. A number preceded by a currency marker is almost certainly a price; a
 * number followed by {@code %} is almost certainly a discount; a number followed by "GB" is a
 * spec. That removes the whole class of "we recorded 17 rupees as the price of a laptop" bugs,
 * which is the kind of bad data that would then be fed to Claude and produce a confidently wrong
 * verdict.
 *
 * <p>Amounts are returned at scale 2, matching the {@code numeric(12,2)} columns.
 */
public final class PriceParser {

    private PriceParser() {
    }

    /** Below this, it is not a real product price — it is a rating, a count or a parse error. */
    private static final BigDecimal MIN_PLAUSIBLE = new BigDecimal("1.00");

    /** Ten crore. Nothing PricePilot tracks costs more than this. */
    private static final BigDecimal MAX_PLAUSIBLE = new BigDecimal("100000000.00");

    private static final BigDecimal SMALL_INTEGER_LIMIT = new BigDecimal("100");

    /**
     * Grouped form first, so "1,29,999" is not matched as just "1". Supports both Indian
     * (1,29,999) and Western (129,999) grouping because Indian sites use both.
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?"
            + "|\\d+(?:\\.\\d{1,2})?");

    /** Currency markers, matched against the few characters immediately before a number. */
    private static final Pattern CURRENCY_BEFORE = Pattern.compile(
            "(?:\\u20b9|\\u20a8|rs\\.?|inr|mrp\\.?:?|price:?|\\brupees?\\b)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Units that prove the number is a specification rather than a price. */
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^\\s*(?:gb|tb|mb|kb|mah|wh|inch|inches|cm|mm|kg|ml|ltr|litre|hz|mp|px|w|rpm"
            + "|months?|mos|years?|yrs?|days?|star|stars|%)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A candidate scoring below this is rejected outright rather than returned. */
    private static final int SCORE_FLOOR = -40;

    /** For an explicitly identified amount, never search prose for a plausible number. */
    public static Optional<BigDecimal> parseAmount(String text) {
        if (text == null) return Optional.empty();
        String value = normalise(text).replaceFirst("(?i)^(?:₹|INR|Rs\\.?)\\s*", "")
                .replaceFirst("(?i)\\s*INR$", "").trim();
        if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]{1,2})?")) {
            return Optional.empty();
        }
        BigDecimal amount = toDecimal(value);
        return isPlausible(amount) ? Optional.of(amount) : Optional.empty();
    }

    public static boolean isPlausible(BigDecimal value) {
        return value != null && value.compareTo(MIN_PLAUSIBLE) >= 0
                && value.compareTo(MAX_PLAUSIBLE) <= 0 && value.stripTrailingZeros().scale() <= 2;
    }

    /**
     * @param text raw text or attribute value from the page
     * @return the amount, or empty when nothing in the string looks like a price
     */
    public static Optional<BigDecimal> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String s = normalise(text);
        Matcher m = CANDIDATE.matcher(s);

        BigDecimal best = null;
        int bestScore = Integer.MIN_VALUE;

        while (m.find()) {
            String raw = m.group();
            BigDecimal value = toDecimal(raw);
            if (value == null) {
                continue;
            }
            if (value.compareTo(MIN_PLAUSIBLE) < 0 || value.compareTo(MAX_PLAUSIBLE) > 0) {
                continue;
            }

            // Strictly greater, so an earlier candidate wins a tie.
            int score = score(s, m.start(), m.end(), raw, value);
            if (score > bestScore) {
                bestScore = score;
                best = value;
            }
        }

        if (best == null || bestScore < SCORE_FLOOR) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /** Convenience for JSON values, which may arrive as a bare number or as a string. */
    public static Optional<BigDecimal> parseJsonValue(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number n) {
            BigDecimal d;
            try {
                d = new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (d.compareTo(MIN_PLAUSIBLE) < 0 || d.compareTo(MAX_PLAUSIBLE) > 0) {
                return Optional.empty();
            }
            return Optional.of(d);
        }
        return parse(String.valueOf(value));
    }

    // ------------------------------------------------------------------ internals

    // Written as numeric constants rather than literal characters so that the meaning survives
    // being copied, re-encoded or pasted through an editor that normalises whitespace.
    private static final char NBSP = 0x00A0;            // non-breaking space
    private static final char NARROW_NBSP = 0x202F;     // narrow non-breaking space
    private static final char THIN_SPACE = 0x2009;
    private static final char HAIR_SPACE = 0x200A;
    private static final char FIGURE_SPACE = 0x2007;
    private static final char WORD_JOINER = 0x2060;
    private static final char ZERO_WIDTH_SPACE = 0x200B;
    private static final char ZWNJ = 0x200C;
    private static final char ZWJ = 0x200D;
    private static final char BOM = 0xFEFF;

    /**
     * Collapses the Unicode spaces retailers use inside prices and drops zero-width characters,
     * so that a price separated from its symbol by a non-breaking space parses like any other.
     */
    private static String normalise(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == NBSP || c == NARROW_NBSP || c == THIN_SPACE || c == HAIR_SPACE
                    || c == FIGURE_SPACE || c == WORD_JOINER) {
                sb.append(' ');
            } else if (c == ZERO_WIDTH_SPACE || c == ZWNJ || c == ZWJ || c == BOM) {
                // drop
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static BigDecimal toDecimal(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int score(String s, int start, int end, String raw, BigDecimal value) {
        int score = 0;

        // A currency marker immediately before the number is the strongest signal available.
        String before = s.substring(Math.max(0, start - 12), start);
        if (CURRENCY_BEFORE.matcher(before).find()) {
            score += 30;
        }

        String tail = s.substring(end, Math.min(s.length(), end + 12));

        // A percent sign after the number means it is a discount, not a price.
        int after = 0;
        while (after < tail.length() && tail.charAt(after) == ' ') {
            after++;
        }
        if (after < tail.length() && tail.charAt(after) == '%') {
            score -= 100;
        }

        // Units after the number mean it is a spec: "8 GB", "6.7 inch", "5000 mAh".
        if (UNIT_AFTER.matcher(tail).find()) {
            score -= 60;
        }

        // Thousands separators are a price-shaped feature.
        if (raw.indexOf(',') >= 0) {
            score += 12;
        }

        // Bare small integers are ratings, review counts, EMI months, warranty years.
        if (raw.indexOf(',') < 0 && raw.indexOf('.') < 0
                && value.compareTo(SMALL_INTEGER_LIMIT) < 0) {
            score -= 45;
        }

        return score;
    }
}
