package com.pricepilot.alerts;

import com.pricepilot.domain.AlertKind;

import java.math.BigDecimal;

/**
 * One alert that has been written to the database and is waiting to be emailed.
 *
 * <p>Exists so that the two halves of raising an alert can be separated. The database write happens
 * in a short transaction; the SMTP send — which can block for ten seconds against a slow mail
 * server — happens afterwards with no connection held. This record is what crosses between them, and
 * it carries values rather than entities because by then the entities are detached and touching a
 * lazy association would throw.
 *
 * @param alertLogId  the row to mark delivered, or to record a delivery error against
 * @param email       where to send it
 * @param displayName the recipient's name, for the greeting; may be {@code null}
 * @param kind        which kind of alert, which selects the subject line
 * @param productId   the product, used to build the deep link into the app
 * @param productTitle the product name as shown in the app
 * @param retailer    rule key of the retailer whose page produced this price
 * @param price       the price that triggered the alert
 * @param previousPrice what it was before, or {@code null} if unknown
 * @param targetPrice the user's target, when the alert is a target hit
 * @param headline    the one-line summary, already composed and shown in the in-app feed too
 */
public record PendingAlert(
        Long alertLogId,
        String email,
        String displayName,
        AlertKind kind,
        Long productId,
        String productTitle,
        String retailer,
        BigDecimal price,
        BigDecimal previousPrice,
        BigDecimal targetPrice,
        String headline) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /** First name if we have a display name, otherwise something neutral. */
    public String greetingName() {
        if (displayName == null || displayName.isBlank()) {
            return "there";
        }
        String first = displayName.trim().split("\\s+")[0];
        return first.isBlank() ? "there" : first;
    }
}
