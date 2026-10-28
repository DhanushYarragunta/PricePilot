package com.pricepilot.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A number from a language model, which is to say possibly not a number.
 *
 * <p>The prompt asks for {@code "confidence": 0.85} and {@code "suggested_price": 74999}. What
 * actually arrives is usually that, and occasionally {@code "0.85"}, {@code 85}, a price with a
 * rupee symbol and grouping commas, {@code null}, or {@code {"value": 74999}}. Declaring those
 * fields as {@code BigDecimal} makes Jackson throw on the variants, which turns a cosmetic
 * formatting quirk into a failed analysis and a wasted API call.
 *
 * <p>So the fields are declared as this type instead, which accepts whatever JSON value turns up and
 * does the coercion itself in {@link #asDecimal()}. The original is kept, so the raw reply can still
 * be logged verbatim when a parse fails.
 *
 * <p>Deliberately lenient about <em>format</em> and not at all lenient about <em>magnitude</em>.
 * Range checks belong with the field that knows what range is sensible, which is
 * {@link DealVerdictJson}: a currency symbol is a formatting accident, whereas a confidence of 40 is
 * a different kind of problem and should be handled where that distinction is known.
 */
public record JsonNumber(@JsonValue Object raw) {

    /**
     * Delegating creator, so Jackson hands over the parsed JSON value whatever its type instead of
     * trying to bind it to a shape. Written out in full rather than as a compact constructor to make
     * the single-argument signature Jackson needs unmistakable.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public JsonNumber(Object raw) {
        this.raw = raw;
    }

    /** Keys checked when the value arrives wrapped in an object instead of as a scalar. */
    private static final List<String> WRAPPER_KEYS = List.of("value", "amount", "price", "number");

    /**
     * Characters removed before a number is looked for: every flavour of space a model might emit
     * (including the non-breaking and thin spaces that retailers use in prices) plus grouping commas.
     *
     * <p>Written as regex escapes rather than literal characters on purpose. Invisible whitespace
     * variants in source code are impossible to review and easy to mangle in an editor; this way the
     * file stays plain ASCII and the intent is readable.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\u00A0\\u202F\\u2009\\u2007,]");

    /**
     * The first plain decimal in the remaining text. Anchoring on the first run means
     * {@code "74999.00 (42% off)"} yields 74999.00 rather than a mashed-together 7499900.0042.
     */
    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * The value as a decimal, or null when it cannot be read as one.
     *
     * <p>Null rather than an exception: a missing confidence has a sensible default and a missing
     * suggested price is a legitimate answer, so neither is worth aborting an otherwise good verdict
     * over.
     */
    public BigDecimal asDecimal() {
        return toDecimal(raw);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            // Via toString rather than doubleValue, so 0.85 does not arrive as 0.8500000000000001.
            return parse(number.toString());
        }
        if (value instanceof String text) {
            return parse(text);
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : WRAPPER_KEYS) {
                Object inner = map.get(key);
                if (inner != null) {
                    return toDecimal(inner);
                }
            }
        }
        return null;
    }

    /**
     * Pulls a decimal out of free text, discarding currency symbols, grouping separators and any
     * trailing words.
     *
     * <p>Separators are deleted rather than interpreted. In this market a figure could plausibly be
     * written with Indian grouping (two digits at a time) or Western grouping (three), and both mean
     * the same thing once the commas are gone. Decimal points are kept. A comma used as a decimal
     * separator would be misread, but no Indian retailer and no English-language model output writes
     * one.
     */
    private static BigDecimal parse(String text) {
        String stripped = SEPARATORS.matcher(text).replaceAll("");
        Matcher matcher = FIRST_NUMBER.matcher(stripped);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException e) {
            // The pattern only matches valid decimals, so this is unreachable in practice. Returning
            // null rather than letting it propagate keeps the no-exception contract absolute.
            return null;
        }
    }
}
