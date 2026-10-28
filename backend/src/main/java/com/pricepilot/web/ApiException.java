package com.pricepilot.web;

import org.springframework.http.HttpStatus;

/**
 * An error the API is reporting on purpose.
 *
 * <p>The distinction this type draws is the one that matters when something goes wrong at 2am: an
 * {@code ApiException} is a case the code anticipated and has a sensible answer for — a URL from an
 * unsupported retailer, a tracker that belongs to someone else, a plan limit reached. Anything else
 * reaching {@link ApiExceptionHandler} is a bug, and the two are logged at completely different
 * levels so the log is a list of real problems rather than a list of users mistyping URLs.
 *
 * <p>Carries a stable machine-readable {@code code} alongside the human message. The frontend
 * switches on the code; the message is for the person reading the screen. Without the code, the
 * frontend ends up matching on message text, and then nobody can reword an error without breaking
 * the UI.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    // ------------------------------------------------------------------ factories

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " was not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    /**
     * Used when a resource exists but belongs to another account.
     *
     * <p>Reports 404, not 403. Answering "forbidden" would confirm that the id exists, which turns a
     * sequential id into an enumeration oracle: a caller could count other people's trackers by
     * watching which ids give 403 and which give 404. A caller with no right to a resource should
     * not be able to tell it apart from one that was never there.
     */
    public static ApiException notYours(String what) {
        return notFound(what);
    }

    /**
     * Used when the caller is known but genuinely not allowed — no id is being revealed.
     *
     * <p>Distinct from {@link #notYours(String)}: this is for actions that are refused on their own
     * terms, such as registering without the required invite code, where there is no resource whose
     * existence could leak.
     */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException limitReached(String message) {
        return new ApiException(HttpStatus.CONFLICT, "limit_reached", message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", message);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
    }
}
