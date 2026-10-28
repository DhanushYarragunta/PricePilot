package com.pricepilot.alerts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Formats rupee amounts for human-readable output.
 *
 * <p>Small, but worth having in one place. Indian digit grouping is lakh-based — ₹1,24,999, not
 * ₹124,999 — and getting it wrong in an email to an Indian user reads as sloppy in a way that is
 * hard to unsee. {@code en-IN} gives the correct grouping from the JDK's own locale data rather than
 * from hand-rolled string surgery.
 *
 * <p>The rupee sign is written as a unicode escape rather than as a literal character on purpose.
 * The escape is resolved by the compiler, so the source file stays plain ASCII and cannot be
 * silently mangled by an editor, a diff tool, or a build step that guesses the wrong encoding.
 */
public final class Money {

    /**
     * U+20B9 INDIAN RUPEE SIGN, built from its code point rather than typed as a literal.
     *
     * <p>Pure-ASCII source is immune to a build that guesses the wrong encoding, an editor that
     * re-saves the file, or a diff tool that normalises it. A corrupted currency symbol would appear
     * in every alert email and every price label, so this is cheap insurance on a visible surface.
     */
    private static final String RUPEE = Character.toString(0x20B9);

    private Money() {
    }

    /** {@code 74999.00} becomes {@code ₹74,999}. Paise are dropped — no retailer prices in them. */
    public static String inr(BigDecimal amount) {
        if (amount == null) {
            return "n/a";
        }
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"));
        format.setGroupingUsed(true);
        return RUPEE + format.format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    /** A signed difference, e.g. {@code ₹7,500 (9%)}. Returns {@code null} when there is no change. */
    public static String savings(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal delta = from.subtract(to);
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        int percent = delta.multiply(BigDecimal.valueOf(100))
                .divide(from, 0, RoundingMode.HALF_UP)
                .intValue();
        return inr(delta) + " (" + percent + "%)";
    }

    /** Turns a rule key such as {@code reliance_digital} into {@code Reliance Digital}. */
    public static String retailerName(String retailerKey) {
        if (retailerKey == null || retailerKey.isBlank()) {
            return "the retailer";
        }
        String[] parts = retailerKey.trim().toLowerCase(Locale.ROOT).split("[_\\-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            // "in" is the country suffix on keys like amazon_in, and "Amazon In" reads badly.
            if (part.equals("in")) {
                out.append("India");
            } else {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.isEmpty() ? retailerKey : out.toString();
    }
}
