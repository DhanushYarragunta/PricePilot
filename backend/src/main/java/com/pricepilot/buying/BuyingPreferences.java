package com.pricepilot.buying;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.web.ApiException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static com.pricepilot.buying.BuyingModels.*;

@Component
public class BuyingPreferences {
    static final Set<String> FIELDS = Set.of("listingId", "budget", "hardBudget", "deadlineDate", "deadlineKind",
            "earliestDate", "targetPrice", "desiredSavingsPercent", "allowAlternatives", "urgency", "reason");
    private final ObjectMapper mapper;
    public BuyingPreferences(ObjectMapper mapper) { this.mapper = mapper; }

    public Preferences merge(Preferences current, JsonNode patch) {
        if (patch == null || !patch.isObject()) throw ApiException.badRequest("Preferences must be a JSON object.");
        ObjectNode merged = mapper.valueToTree(current);
        patch.fields().forEachRemaining(entry -> {
            if (!FIELDS.contains(entry.getKey())) throw ApiException.badRequest("Unknown buying preference: " + entry.getKey());
            merged.set(entry.getKey(), entry.getValue());
        });
        Long listingId = null;
        JsonNode listing = merged.get("listingId");
        if (listing != null && !listing.isNull()) {
            if (!listing.isIntegralNumber() || !listing.canConvertToLong() || listing.asLong() < 1) throw ApiException.badRequest("Choose a valid listing.");
            listingId = listing.asLong();
        }
        BigDecimal budget = patch.has("budget") ? money(merged.get("budget"), "Budget", new BigDecimal("5000000")) : current.budget();
        BigDecimal target = patch.has("targetPrice") ? money(merged.get("targetPrice"), "Target price", new BigDecimal("5000000")) : current.targetPrice();
        BigDecimal savings = patch.has("desiredSavingsPercent") ? money(merged.get("desiredSavingsPercent"), "Desired savings percent", new BigDecimal("99.99")) : current.desiredSavingsPercent();
        LocalDate deadline = date(merged.get("deadlineDate"));
        LocalDate earliest = date(merged.get("earliestDate"));
        DeadlineKind kind;
        try { kind = DeadlineKind.valueOf(merged.path("deadlineKind").asText()); }
        catch (Exception ex) { throw ApiException.badRequest("Deadline kind must be HARD or FLEXIBLE."); }
        if (deadline != null && earliest != null && kind == DeadlineKind.HARD && earliest.isAfter(deadline)) {
            throw ApiException.badRequest("The earliest purchase date is after the hard deadline. Correct one of those dates.");
        }
        String urgency = merged.path("urgency").asText();
        if (!Set.of("LOW", "NORMAL", "HIGH").contains(urgency)) throw ApiException.badRequest("Urgency must be LOW, NORMAL or HIGH.");
        JsonNode reasonValue = merged.get("reason");
        String reason = reasonValue == null || reasonValue.isNull() ? null : reasonValue.asText();
        if (reasonValue != null && !reasonValue.isNull() && (!reasonValue.isTextual() || reason.length() > 500)) {
            throw ApiException.badRequest("Keep your buying reason under 500 characters.");
        }
        return new Preferences(listingId, budget, bool(merged, "hardBudget"), deadline, kind, earliest, target, savings,
                bool(merged, "allowAlternatives"), urgency, reason == null || reason.isBlank() ? null : reason.trim());
    }

    private static boolean bool(ObjectNode node, String field) {
        if (!node.path(field).isBoolean()) throw ApiException.badRequest(field + " must be true or false.");
        return node.path(field).asBoolean();
    }
    private static BigDecimal money(JsonNode node, String label, BigDecimal maximum) {
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw ApiException.badRequest(label + " must be a number or null.");
        BigDecimal value = node.decimalValue();
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(maximum) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest(label + " is outside the supported range or has more than two decimal places.");
        }
        return value;
    }
    private static LocalDate date(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || !node.asText().matches("20\\d{2}-\\d{2}-\\d{2}")) throw ApiException.badRequest("Use an exact date in YYYY-MM-DD format.");
        try { return LocalDate.parse(node.asText()); }
        catch (Exception ex) { throw ApiException.badRequest("That date is not valid. Use YYYY-MM-DD."); }
    }
}
