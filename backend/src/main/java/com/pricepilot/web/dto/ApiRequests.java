package com.pricepilot.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Every request body the API accepts, in one place.
 *
 * <p>Grouped rather than scattered across a dozen five-line files because the useful thing about
 * these types is how they compare: it should be obvious at a glance that nothing here accepts a
 * price, a product title, or an image URL. The client submits a URL and its own preferences; every
 * fact about the product comes from fetching the retailer's page. Keeping the request surface in one
 * readable file is what makes that property checkable instead of merely intended.
 *
 * <p>The constraints are enforced by {@code @Valid} on the controller parameter and turned into a
 * per-field JSON response by {@code ApiExceptionHandler}. The messages are written to be shown to a
 * user, not to a developer — "Please paste a product link" rather than "must not be blank" — because
 * they are read far more often by the former.
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** POST /api/auth/register */
    public record Register(
            @NotBlank(message = "Please enter your email address")
            @Email(message = "That does not look like an email address")
            @Size(max = 255, message = "That email address is too long")
            String email,

            // Minimum length is checked in AccountService rather than here, so the rule and the
            // reasoning behind it live together. This bound only stops an absurd payload.
            @NotBlank(message = "Please choose a password")
            @Size(max = 200, message = "That password is too long")
            String password,

            @Size(max = 80, message = "Please use a shorter name")
            String displayName,

            String inviteCode) {
    }

    /** POST /api/auth/login */
    public record Login(
            @NotBlank(message = "Please enter your email address")
            String email,

            @NotBlank(message = "Please enter your password")
            String password) {
    }

    /**
     * POST /api/products — start tracking the product at a URL.
     *
     * @param url           a product page at a supported retailer. Validated properly by
     *                      {@code UrlCanonicalizer}, which knows which hosts have extraction rules;
     *                      the annotation here only rejects an empty box
     * @param targetPrice   alert me at or below this. Optional
     * @param notifyOnDeal  alert me when the AI verdict improves. Defaults to true when omitted,
     *                      which is why it is a boxed {@code Boolean} — a primitive would silently
     *                      default to false and quietly turn the feature off for every client that
     *                      forgot the field
     * @param note          why the user is watching it, shown back to them on the card
     */
    public record Track(
            @NotBlank(message = "Please paste a product link")
            @Size(max = 2048, message = "That link is too long")
            String url,

            @DecimalMin(value = "0.01", message = "A target price has to be more than zero")
            @Digits(integer = 10, fraction = 2, message = "That is not a valid price")
            BigDecimal targetPrice,

            Boolean notifyOnDeal,

            @Size(max = 500, message = "Please use a shorter note")
            String note) {

        public boolean notifyOnDealOrDefault() {
            return notifyOnDeal == null || notifyOnDeal;
        }
    }

    /** POST /api/products/{id}/listings — add another retailer's page for the same product. */
    public record AddRetailer(
            @NotBlank(message = "Please paste a product link")
            @Size(max = 2048, message = "That link is too long")
            String url) {
    }

    /**
     * PATCH /api/trackers/{id} — change alert preferences.
     *
     * <p>Every field is nullable and means "leave this alone", which is what makes it a PATCH rather
     * than a PUT. {@code clearTarget} exists because {@code targetPrice: null} is indistinguishable
     * from an absent field once JSON is deserialised into a record — without a separate flag there
     * is no way to remove a target price once set, and a target that cannot be removed produces a
     * steady trickle of alerts the user has stopped wanting.
     */
    public record UpdateTracker(
            @DecimalMin(value = "0.01", message = "A target price has to be more than zero")
            @Digits(integer = 10, fraction = 2, message = "That is not a valid price")
            BigDecimal targetPrice,

            Boolean clearTarget,

            Boolean notifyOnDeal,

            @Size(max = 500, message = "Please use a shorter note")
            String note) {

        public boolean shouldClearTarget() {
            return Boolean.TRUE.equals(clearTarget);
        }
    }
}
