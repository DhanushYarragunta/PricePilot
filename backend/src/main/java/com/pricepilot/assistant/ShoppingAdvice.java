package com.pricepilot.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The shopping conversation is private request context, never a shared product or price record. */
public final class ShoppingAdvice {
    private ShoppingAdvice() { }

    public record Request(
            @NotBlank @Size(max = 2000) String message,
            @DecimalMin("1") @DecimalMax("5000000") @Digits(integer = 7, fraction = 2) BigDecimal budget,
            @Size(max = 1200) String requirements,
            @Size(max = 8) List<@NotNull @Valid Turn> history) {
        public List<Turn> turns() { return history == null ? List.of() : history; }
    }

    public record Turn(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String content) { }

    public record Response(String answer, String model, boolean searchAvailable,
                           List<Candidate> candidates, List<String> warnings) { }

    /** Price, URL and title are always populated by server-held observations or a retailer fetch. */
    public record Candidate(String id, String title, String url, String retailer,
                            BigDecimal price, String currency, Boolean inStock, Instant capturedAt,
                            String kind, String reason, List<String> cautions) { }
}
