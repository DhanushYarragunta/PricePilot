package com.pricepilot.buying;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricepilot.service.ProductTrackingService;
import com.pricepilot.web.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.pricepilot.buying.BuyingModels.*;

/** Conversation orchestration uses short store transactions and never submits a BrowserAct task. */
@Service
public class BuyingPlanService {
    private final ProductTrackingService tracking;
    private final BuyingPlanStore store;
    private final BuyingEvidenceReader evidence;
    private final BuyingHistory history;
    private final BuyingForecast forecasts;
    private final BuyingDecision decisions;
    private final BuyingPreferences preferences;
    private final BuyingConversationParser parser;
    private final ObjectMapper mapper;
    private final PlanningProperties properties;

    public BuyingPlanService(ProductTrackingService tracking, BuyingPlanStore store, BuyingEvidenceReader evidence,
                             BuyingHistory history, BuyingForecast forecasts, BuyingDecision decisions,
                             BuyingPreferences preferences, BuyingConversationParser parser,
                             ObjectMapper mapper, PlanningProperties properties) {
        this.tracking = tracking; this.store = store; this.evidence = evidence; this.history = history;
        this.forecasts = forecasts; this.decisions = decisions; this.preferences = preferences;
        this.parser = parser; this.mapper = mapper; this.properties = properties;
    }

    public View get(Long userId, Long productId) {
        tracking.requireTracking(userId, productId);
        Instant now = Instant.now();
        BuyingPlanStore.Stored stored = load(userId, productId, now);
        Preferences prefs = prefs(stored);
        History data = evidence.read(productId, prefs.listingId(), now);
        Series selected = history.selected(data);
        Instant observed = selected == null ? null : selected.stats().lastObservedAt();
        if (!stored.snapshot().hasNonNull("plan") || !Objects.equals(observed, stored.lastObservedAt())
                || eligibilityChanged(stored, selected)
                || stored.nextReassessmentAt() == null || !stored.nextReassessmentAt().isAfter(now)) {
            Snapshot next = evaluate(stored, prefs, data, now, null, List.of());
            try {
                stored = store.save(stored, prefs, next, null, null, notice(stored, prefs, next, now), now);
            } catch (ApiException ex) {
                if (!"buying_plan_changed".equals(ex.getCode())) throw ex;
                // Parallel reads may initialize the same snapshot; use the request that committed first.
                stored = store.find(userId, productId).orElseThrow(() -> ex);
                data = evidence.read(productId, prefs(stored).listingId(), Instant.now());
            }
        }
        return view(stored, data);
    }

    public View patch(Long userId, Long productId, PatchRequest request) {
        tracking.requireTracking(userId, productId);
        Instant now = Instant.now();
        BuyingPlanStore.Stored stored = load(userId, productId, now);
        requireVersion(stored, request.version());
        Preferences prefs = preferences.merge(prefs(stored), request.preferences());
        History data = evidence.read(productId, prefs.listingId(), now);
        Snapshot next = evaluate(stored, prefs, data, now, null, List.of());
        BuyingPlanStore.Stored saved = store.save(stored, prefs, next, null,
                "Your purchase requirements were updated. " + answer(next), notice(stored, prefs, next, now), now);
        return view(saved, data);
    }

    public View converse(Long userId, Long productId, MessageRequest request) {
        tracking.requireTracking(userId, productId);
        Instant now = Instant.now();
        BuyingPlanStore.Stored stored = load(userId, productId, now);
        requireVersion(stored, request.version());
        Preferences current = prefs(stored);
        BuyingConversationParser.Parsed parsed = parser.parse(request.message(), current, store.conversation(stored.id()), now);
        Preferences updated;
        List<String> warnings = new ArrayList<>(parsed.warnings());
        String question = parsed.question();
        try {
            updated = preferences.merge(current, parsed.updates());
        } catch (ApiException ex) {
            BuyingConversationParser.Parsed basic = parser.basic(request.message(), now);
            try { updated = preferences.merge(current, basic.updates()); question = basic.question(); }
            catch (ApiException invalid) {
                updated = current;
                question = "Which valid budget and purchase date should I use to replace the conflicting details?";
            }
            warnings.add("Some proposed preference edits could not be validated. Existing valid details were retained where needed.");
        }
        History data = evidence.read(productId, updated.listingId(), now);
        Snapshot next = evaluate(stored, updated, data, now, question, warnings);
        BuyingPlanStore.Stored saved = store.save(stored, updated, next, request.message().trim(), answer(next),
                notice(stored, updated, next, now), now);
        return view(saved, data);
    }

    /** Called only for bounded due batches; no AI call and no new retailer fetch. */
    public void reassess(BuyingPlanStore.Stored stored, Instant now) {
        Preferences prefs = prefs(stored);
        History data = evidence.read(stored.productId(), prefs.listingId(), now);
        Snapshot next = evaluate(stored, prefs, data, now, null, List.of());
        store.save(stored, prefs, next, null, null, notice(stored, prefs, next, now), now);
    }

    private BuyingPlanStore.Stored load(Long userId, Long productId, Instant now) {
        return store.find(userId, productId).orElseGet(() -> {
            History data = evidence.read(productId, null, now);
            return store.initialize(userId, productId, Preferences.defaults(data.selectedListingId()));
        });
    }

    private Preferences prefs(BuyingPlanStore.Stored stored) {
        return preferences.merge(Preferences.defaults(stored.listingId()), stored.preferences());
    }

    private Snapshot evaluate(BuyingPlanStore.Stored previous, Preferences prefs, History data, Instant now,
                              String parsedQuestion, List<String> inputWarnings) {
        Series selected = history.selected(data);
        Forecast forecast = forecasts.estimate(selected, prefs, now);
        BigDecimal savingsTarget = savingsTarget(previous, prefs, selected);
        Plan plan = decisions.decide(prefs, selected, forecast, now, savingsTarget);
        String question = followUp(prefs, plan, parsedQuestion, now);
        List<String> warnings = new ArrayList<>(inputWarnings);
        warnings.add("Dates use " + properties.getZoneId() + ". Reassessment uses saved observations; it does not guarantee a future offer or additional retailer fetch.");
        if (selected == null || !selected.stats().completeYear()) warnings.add("A complete 365-day history is not available for this retailer and currency.");
        if (selected != null && selected.stats().stale()) warnings.add("The last verified price is stale. Its original timestamp is preserved.");
        if (selected != null && !selected.stats().stale() && selected.stats().lastObservedAt() != null) {
            Instant expires = selected.stats().lastObservedAt().plus(java.time.Duration.ofHours(properties.getStaleAfterHours()));
            if (expires.isAfter(now) && expires.isBefore(plan.reassessAt())) {
                plan = new Plan(plan.outcome(), plan.summary(), plan.reasons(), plan.targetPrice(), expires,
                        plan.purchaseBy(), plan.generatedAt(), plan.changeTriggers());
            }
        }
        return new Snapshot(plan, forecast, selected == null ? null : selected.stats(), selected == null ? "INR" : selected.currency(),
                selected == null ? null : selected.stats().lastObservedAt(), question, warnings.stream().distinct().toList(),
                selected == null ? null : selected.listingStatus(), savingsTarget);
    }

    private BigDecimal savingsTarget(BuyingPlanStore.Stored previous, Preferences prefs, Series selected) {
        if (prefs.desiredSavingsPercent() == null) return null;
        Preferences old = prefs(previous);
        boolean unchanged = old.desiredSavingsPercent() != null
                && old.desiredSavingsPercent().compareTo(prefs.desiredSavingsPercent()) == 0
                && Objects.equals(old.listingId(), prefs.listingId());
        if (unchanged && previous.snapshot().path("savingsTarget").isNumber()) {
            return previous.snapshot().path("savingsTarget").decimalValue();
        }
        if (selected == null || selected.stats().stale() || selected.stats().currentPrice() == null) return null;
        return selected.stats().currentPrice().multiply(BigDecimal.ONE.subtract(prefs.desiredSavingsPercent().movePointLeft(2)))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static boolean eligibilityChanged(BuyingPlanStore.Stored stored, Series selected) {
        if (selected == null) return stored.snapshot().hasNonNull("evidence");
        return !Objects.equals(stored.snapshot().path("listingStatus").asText(null), selected.listingStatus())
                || stored.snapshot().path("evidence").path("stale").asBoolean() != selected.stats().stale()
                || !Objects.equals(stored.snapshot().path("evidence").path("currentInStock").isNull() ? null
                        : stored.snapshot().path("evidence").path("currentInStock").asBoolean(), selected.stats().currentInStock());
    }

    private String followUp(Preferences prefs, Plan plan, String parsedQuestion, Instant now) {
        LocalDate today = now.atZone(ZoneId.of(properties.getZoneId())).toLocalDate();
        if (parsedQuestion != null) {
            String lower = parsedQuestion.toLowerCase(java.util.Locale.ROOT);
            boolean repeatsKnown = (lower.contains("budget") && prefs.budget() != null && !lower.contains("conflicting"))
                    || (lower.contains("deadline") && prefs.deadlineDate() != null);
            if (!repeatsKnown) return parsedQuestion;
        }
        if (prefs.deadlineDate() != null && prefs.deadlineKind() == DeadlineKind.HARD && prefs.deadlineDate().isBefore(today)) {
            return "What new purchase deadline should I use (YYYY-MM-DD)?";
        }
        if (prefs.budget() == null && prefs.hardBudget()) return "What is your maximum budget in INR?";
        if (prefs.deadlineDate() == null && prefs.deadlineKind() == DeadlineKind.HARD) {
            return "By which exact date do you need to buy this, or is your timing flexible?";
        }
        if (plan.outcome() == Outcome.COMPARE_ALTERNATIVES && !prefs.allowAlternatives()) {
            return "Would you consider an alternative within your hard budget?";
        }
        return null;
    }

    private BuyingPlanStore.Notice notice(BuyingPlanStore.Stored previous, Preferences prefs, Snapshot next, Instant now) {
        Stats stats = next.evidence();
        if (stats == null || stats.currentPrice() == null || stats.stale() || !Boolean.TRUE.equals(stats.currentInStock())
                || "DEAD".equals(next.listingStatus())) return null;
        BigDecimal price = stats.currentPrice();
        boolean explicitTarget = prefs.targetPrice() != null || prefs.desiredSavingsPercent() != null;
        if (explicitTarget && next.plan().targetPrice() != null && price.compareTo(next.plan().targetPrice()) <= 0
                && (!prefs.hardBudget() || (prefs.budget() != null && price.compareTo(prefs.budget()) <= 0))) {
            String key = "target:" + next.plan().targetPrice().stripTrailingZeros().toPlainString();
            return new BuyingPlanStore.Notice("TARGET_REACHED", key, "PricePilot buying target reached",
                    "A verified saved in-stock offer meets your explicit buying-plan target within your purchase budget. "
                            + "Open your latest plan to check the current price, variant, purchase window and checkout offer before buying.");
        }
        LocalDate today = now.atZone(ZoneId.of(properties.getZoneId())).toLocalDate();
        if (prefs.deadlineDate() != null && prefs.deadlineKind() == DeadlineKind.HARD && prefs.hardBudget()
                && prefs.budget() != null && price.compareTo(prefs.budget()) > 0
                && ChronoUnit.DAYS.between(today, prefs.deadlineDate()) <= properties.getDeadlineRiskDays()
                && !prefs.deadlineDate().isBefore(today)) {
            return new BuyingPlanStore.Notice("DEADLINE_OVER_BUDGET", "deadline:" + prefs.deadlineDate() + ":" + prefs.budget().stripTrailingZeros().toPlainString(),
                    "PricePilot purchase deadline is approaching", "Your purchase deadline is " + prefs.deadlineDate()
                    + ", but the last verified offer remains above your hard budget. " + next.plan().summary());
        }
        String oldOutcome = previous.snapshot().path("plan").path("outcome").asText("");
        if (!oldOutcome.isBlank() && !oldOutcome.equals(next.plan().outcome().name())) {
            String key = "plan:" + next.plan().outcome() + ":" + prefs.deadlineDate() + ":" + next.plan().targetPrice();
            return new BuyingPlanStore.Notice("PLAN_CHANGED", key, "Your PricePilot buying plan changed",
                    "New saved evidence or updated purchase requirements changed the plan from " + oldOutcome + " to "
                            + next.plan().outcome() + ". " + next.plan().summary());
        }
        return null;
    }

    private View view(BuyingPlanStore.Stored stored, History data) {
        Snapshot snapshot = mapper.convertValue(stored.snapshot(), Snapshot.class);
        return new View(stored.id(), stored.productId(), stored.version(), prefs(stored), snapshot.plan(), data,
                snapshot.forecast(), store.conversation(stored.id()), snapshot.followUpQuestion(), snapshot.warnings(),
                store.notifications(stored.id()), stored.updatedAt());
    }

    private static void requireVersion(BuyingPlanStore.Stored stored, Long version) {
        if (version == null || version.longValue() != stored.version()) {
            throw ApiException.conflict("buying_plan_changed", "Your buying plan changed. Reload it before applying this edit.");
        }
    }

    private static String answer(Snapshot snapshot) {
        String reasons = String.join(" ", snapshot.plan().reasons().stream().limit(2).toList());
        return snapshot.plan().summary() + " " + reasons + (snapshot.followUpQuestion() == null ? "" : " " + snapshot.followUpQuestion());
    }
}
