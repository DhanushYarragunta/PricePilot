package com.pricepilot.web;

import com.pricepilot.ai.AiException;
import com.pricepilot.scrape.UnsupportedUrlException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every exception into a JSON body of the same shape.
 *
 * <p>One shape, always: {@code {code, message, timestamp}}, plus {@code fields} on a validation
 * failure and {@code reference} on an unexpected one. The frontend therefore needs exactly one
 * error renderer, and adding an endpoint cannot introduce a new error format for it to handle.
 *
 * <h2>The rule about messages</h2>
 * Anticipated failures return their real message, because it is useful and was written to be read.
 * Unanticipated ones return a fixed sentence and a reference id, with the stack trace and the id
 * logged server-side. Exception text leaks internals — table names, file paths, library versions,
 * occasionally a connection string — and a public demo link means the person reading the error may
 * be anyone. The reference id is what keeps that from costing diagnosability: the user quotes eight
 * characters and the full trace is one grep away.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Deliberate, expected failures. Logged at debug: these are not problems with the system. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> onApiException(ApiException e, HttpServletRequest request) {
        if (e.getStatus().is5xxServerError()) {
            log.warn("{} {} -> {} ({})", request.getMethod(), request.getRequestURI(),
                    e.getStatus().value(), e.getMessage());
        } else {
            log.debug("{} {} -> {} ({})", request.getMethod(), request.getRequestURI(),
                    e.getStatus().value(), e.getMessage());
        }
        return body(e.getStatus(), e.getCode(), e.getMessage(), null, null);
    }

    /**
     * A URL for a retailer with no extraction rule.
     *
     * <p>Handled separately so the message can say what to do about it. "Unsupported URL" on its own
     * invites the user to try the same URL again; naming the sites that do work turns a dead end
     * into a next step.
     */
    @ExceptionHandler(UnsupportedUrlException.class)
    public ResponseEntity<Map<String, Object>> onUnsupportedUrl(UnsupportedUrlException e) {
        return body(HttpStatus.BAD_REQUEST, "unsupported_url", e.getMessage(), null, null);
    }

    /**
     * Claude was unreachable, out of budget, or returned something unusable.
     *
     * <p>503 rather than 500: the request was fine and retrying later may well work. AI analysis is
     * also optional throughout, so this status tells the frontend to hide the verdict card and show
     * the price history, which is the part that always works.
     */
    @ExceptionHandler(AiException.class)
    public ResponseEntity<Map<String, Object>> onAiException(AiException e) {
        log.warn("AI request failed: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "ai_unavailable",
                "The AI analysis is temporarily unavailable. Price tracking is unaffected.",
                null, null);
    }

    /** Bean-validation failures on a request body — the one case where per-field detail is useful. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                // Keeps the first message per field. Two complaints about one input is noise in a
                // form that can only show one message under it.
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "validation_failed",
                "Some of the values sent were not valid", fields, null);
    }

    /** Malformed JSON, a wrong type in the path, or a missing query parameter. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Map<String, Object>> onMalformedRequest(Exception e) {
        log.debug("Malformed request: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "malformed_request",
                "The request could not be read. Check the request body and parameters.", null, null);
    }

    /**
     * Everything unplanned.
     *
     * <p>This is the only handler that logs a stack trace, and it logs at error with the same
     * reference the caller is given. If this fires in production it is a bug, and it should be the
     * loudest thing in the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest request) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error [{}] on {} {}: {}",
                reference, request.getMethod(), request.getRequestURI(), e.toString(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Something went wrong on our side. Quote reference " + reference
                + " if you report this.", null, reference);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status,
                                                     String code,
                                                     String message,
                                                     Map<String, String> fields,
                                                     String reference) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        if (fields != null && !fields.isEmpty()) {
            payload.put("fields", fields);
        }
        if (reference != null) {
            payload.put("reference", reference);
        }
        return ResponseEntity.status(status).body(payload);
    }
}
