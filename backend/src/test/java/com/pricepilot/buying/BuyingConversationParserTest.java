package com.pricepilot.buying;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pricepilot.ai.AiClient;
import com.pricepilot.ai.AiException;
import com.pricepilot.ai.AiResponse;
import com.pricepilot.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.pricepilot.buying.BuyingFixtures.NOW;
import static com.pricepilot.buying.BuyingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuyingConversationParserTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AiClient ai = mock(AiClient.class);
    private final BuyingConversationParser parser = new BuyingConversationParser(ai, mapper, new PlanningProperties());
    private final BuyingPreferences preferences = new BuyingPreferences(mapper);
    @AfterEach void close() { parser.close(); }

    @Test void explicitDateAndAbsoluteBudgetWorkWithoutAi() {
        var parsed = parser.parse("I need to buy this by 2027-02-28. My absolute budget is ₹90,000.", Preferences.defaults(10L), List.of(), NOW);
        Preferences saved = preferences.merge(Preferences.defaults(10L), parsed.updates());
        assertThat(saved.budget()).isEqualByComparingTo("90000");
        assertThat(saved.hardBudget()).isTrue();
        assertThat(saved.deadlineDate()).isEqualTo(LocalDate.parse("2027-02-28"));
        assertThat(saved.deadlineKind()).isEqualTo(DeadlineKind.HARD);
        assertThat(parsed.question()).isNull();
        verify(ai, never()).complete(anyString(), anyString(), any());
    }

    @Test void changedAmbiguousDeadlineClearsTheOldHardDateAndAsksOnlyOneQuestion() {
        Preferences previous = new Preferences(10L, new BigDecimal("90000"), true, LocalDate.parse("2027-02-28"),
                DeadlineKind.HARD, null, null, null, false, "NORMAL", null);
        when(ai.isEnabled()).thenReturn(true);
        var parsed = parser.parse("Actually I can wait until May.", previous, List.of(), NOW);
        Preferences saved = preferences.merge(previous, parsed.updates());
        assertThat(saved.deadlineDate()).isNull();
        assertThat(saved.deadlineKind()).isEqualTo(DeadlineKind.FLEXIBLE);
        assertThat(saved.budget()).isEqualByComparingTo("90000");
        assertThat(parsed.question()).contains("exact purchase date");
        assertThat(parsed.question().chars().filter(c -> c == '?').count()).isEqualTo(1);
        verify(ai, never()).complete(anyString(), anyString(), any());
    }

    @Test void bareMonthAndRelativeMoreMonthsNeverInventAYearOrDay() {
        assertThat(parser.basic("I need it by February 28", NOW).question()).isNotNull();
        assertThat(parser.basic("I can wait two more months", NOW).question()).isNotNull();
        assertThat(parser.basic("I may buy it later", NOW).question()).isNull();
        assertThat(parser.basic("Buy it by 12/05", NOW).updates().path("deadlineDate").isNull()).isTrue();
        assertThat(parser.basic("I can wait longer", NOW).updates().path("deadlineDate").isNull()).isTrue();
        assertThat(parser.basic("I can wait longer", NOW).question()).contains("revised purchase date");
    }

    @Test void flexibleBudgetDoesNotMakeAnExplicitPurchaseDeadlineFlexible() {
        var parsed = parser.basic("I have a flexible budget of 90000, but I need to buy by 2027-02-28", NOW);
        assertThat(parsed.updates().path("hardBudget").asBoolean()).isFalse();
        assertThat(parsed.updates().path("deadlineKind").asText()).isEqualTo("HARD");
    }

    @Test void targetAmountsAndExplicitNullCorrectionsPersistThroughPreferenceMerge() throws Exception {
        var parsed = parser.basic("If the price falls below ₹80,000, I want to buy it.", NOW);
        Preferences initial = preferences.merge(Preferences.defaults(10L), parsed.updates());
        assertThat(initial.targetPrice()).isEqualByComparingTo("80000");
        Preferences cleared = preferences.merge(initial, mapper.readTree("{\"targetPrice\":null,\"deadlineDate\":null,\"deadlineKind\":\"FLEXIBLE\"}"));
        assertThat(cleared.targetPrice()).isNull();
        assertThat(cleared.deadlineDate()).isNull();
    }

    @Test void ai401DoesNotBlockExplicitPreferenceUpdates() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenThrow(new AiException("Authentication failed", false, 401));
        var parsed = parser.parse("My budget is 80k", Preferences.defaults(10L), List.of(), NOW);
        assertThat(parsed.updates().path("budget").decimalValue()).isEqualByComparingTo("80000");
        assertThat(parsed.warnings()).anyMatch(warning -> warning.contains("AI authentication"));
        verify(ai, times(1)).complete(anyString(), anyString(), any());
    }

    @Test void modelCannotInventAmountsOrDatesFromAnUnrelatedUserQuote() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenReturn(new AiResponse("""
                {"updates":{"budget":90000,"deadlineDate":"2027-02-28","reason":"Invented reason"},
                 "evidence":{"budget":"Should I wait?","deadlineDate":"Should I wait?","reason":"Should I wait?"},"question":null}
                """, "fixture", 1, 1, 0, 0, BigDecimal.ZERO, "stop"));
        var parsed = parser.parse("Should I wait?", Preferences.defaults(10L), List.of(), NOW);
        assertThat(parsed.updates().has("budget")).isFalse();
        assertThat(parsed.updates().has("deadlineDate")).isFalse();
        assertThat(parsed.updates().has("reason")).isFalse();
    }

    @Test void monetaryModelValueMustMatchTheCitedAmountAfterUnitNormalization() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenReturn(new AiResponse("""
                {"updates":{"budget":900000},"evidence":{"budget":"I can spend 3k"},"question":null}
                """, "fixture", 1, 1, 0, 0, BigDecimal.ZERO, "stop"));
        assertThat(parser.parse("I can spend 3k", Preferences.defaults(10L), List.of(), NOW).updates().has("budget")).isFalse();
    }

    @Test void modelCannotRelaxHardConstraintsJustBecauseTheirNamesAppearInEvidence() {
        String message = "Keep my budget and deadline unchanged";
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenReturn(response("""
                {"updates":{"hardBudget":false,"deadlineKind":"FLEXIBLE","budget":null,"deadlineDate":null},
                 "evidence":{"hardBudget":"Keep my budget and deadline unchanged","deadlineKind":"Keep my budget and deadline unchanged",
                             "budget":"Keep my budget and deadline unchanged","deadlineDate":"Keep my budget and deadline unchanged"},"question":null}
                """));

        Preferences previous = savedRequirements();
        Preferences saved = preferences.merge(previous, parser.parse(message, previous, List.of(), NOW).updates());

        assertThat(saved).isEqualTo(previous);
        verify(ai).complete(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Do not remove my budget", "Don't clear my target price", "Never forget my deadline",
            "Do not remove my desired savings percent", "I don't want no budget limit", "I do not want no deadline"})
    void negatedClearsPreserveSavedRequirements(String message) {
        Preferences previous = savedRequirements();

        assertThat(preferences.merge(previous, parser.basic(message, NOW).updates())).isEqualTo(previous);
    }

    @Test void modelCannotReintroduceAClearThatTheUserNegated() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenReturn(response("""
                {"updates":{"budget":null,"hardBudget":false},
                 "evidence":{"budget":"remove my budget","hardBudget":"Do not remove my budget"},"question":null}
                """));
        Preferences previous = savedRequirements();

        assertThat(preferences.merge(previous,
                parser.parse("Do not remove my budget", previous, List.of(), NOW).updates())).isEqualTo(previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"I cannot buy tomorrow", "Don't change my deadline to 2027-03-31",
            "Do not move my deadline to December", "Maybe I need it by 2027-03-31", "Should I buy tomorrow?"})
    void negatedOrUncertainDatePreservesTheKnownDeadlineAndAsksOnce(String message) {
        when(ai.isEnabled()).thenReturn(true);
        Preferences previous = savedRequirements();
        var parsed = parser.parse(message, previous, List.of(), NOW);

        assertThat(preferences.merge(previous, parsed.updates())).isEqualTo(previous);
        assertThat(parsed.question()).isNotNull();
        assertThat(parsed.question().chars().filter(character -> character == '?').count()).isEqualTo(1);
        verify(ai, never()).complete(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Not before 2027-02-01", "I cannot buy before 2027-02-01", "My earliest purchase date is 2027-02-01"})
    void explicitNotBeforeStillSetsAnEarliestDateWithoutMovingTheDeadline(String message) {
        Preferences previous = savedRequirements();
        Preferences saved = preferences.merge(previous, parser.basic(message, NOW).updates());

        assertThat(saved.earliestDate()).isEqualTo(LocalDate.parse("2027-02-01"));
        assertThat(saved.deadlineDate()).isEqualTo(previous.deadlineDate());
        assertThat(saved.deadlineKind()).isEqualTo(DeadlineKind.HARD);
    }

    @Test void explicitClearsAndConstraintPolarityChangesStillWorkWithoutAi() {
        Preferences previous = savedRequirements();
        Preferences cleared = preferences.merge(previous,
                parser.basic("Remove my budget. Clear my target price. Forget my deadline.", NOW).updates());
        assertThat(cleared.budget()).isNull();
        assertThat(cleared.targetPrice()).isNull();
        assertThat(cleared.deadlineDate()).isNull();
        assertThat(cleared.deadlineKind()).isEqualTo(DeadlineKind.FLEXIBLE);

        Preferences flexible = preferences.merge(previous,
                parser.basic("Make my budget flexible. My deadline is flexible.", NOW).updates());
        assertThat(flexible.hardBudget()).isFalse();
        assertThat(flexible.deadlineKind()).isEqualTo(DeadlineKind.FLEXIBLE);
        assertThat(flexible.budget()).isEqualTo(previous.budget());
        assertThat(flexible.deadlineDate()).isEqualTo(previous.deadlineDate());

        Preferences strict = preferences.merge(flexible,
                parser.basic("My budget is hard. My deadline is hard.", NOW).updates());
        assertThat(strict.hardBudget()).isTrue();
        assertThat(strict.deadlineKind()).isEqualTo(DeadlineKind.HARD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Maybe make my budget flexible", "My deadline might be flexible", "Should I remove my budget?", "Is my budget flexible?"})
    void uncertainRelaxationPreservesTheSavedConstraintsAndClarifies(String message) {
        Preferences previous = savedRequirements();
        var parsed = parser.basic(message, NOW);

        assertThat(preferences.merge(previous, parsed.updates())).isEqualTo(previous);
        assertThat(parsed.question()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Not necessarily the same company, my budget is 3000 and I need to buy by 2027-03-31",
            "Not necessarily the same company my budget is 3000 and I need to buy by 2027-03-31"})
    void unrelatedBrandNegationDoesNotBlockAnExplicitBudgetAndDate(String message) {
        var parsed = parser.basic(message, NOW);

        assertThat(parsed.updates().path("budget").decimalValue()).isEqualByComparingTo("3000");
        assertThat(parsed.updates().path("deadlineDate").asText()).isEqualTo("2027-03-31");
        assertThat(parsed.updates().path("deadlineKind").asText()).isEqualTo("HARD");
        assertThat(parsed.question()).isNull();
    }

    @Test void negatedCommandDoesNotBlockASeparatePositivePreferenceChange() {
        var parsed = parser.basic("Do not remove my deadline. My budget is 3000.", NOW);
        Preferences previous = savedRequirements();
        Preferences saved = preferences.merge(previous, parsed.updates());

        assertThat(saved.budget()).isEqualByComparingTo("3000");
        assertThat(saved.deadlineDate()).isEqualTo(previous.deadlineDate());
    }

    @Test void rejectedOldDateDoesNotHideAnExplicitReplacementInTheNextClause() {
        var parsed = parser.basic("Do not use 2027-02-28; instead I need to buy by 2027-03-31", NOW);

        assertThat(parsed.updates().path("deadlineDate").asText()).isEqualTo("2027-03-31");
        assertThat(parsed.question()).isNull();
    }

    @Test void multipleUnresolvedDatesDoNotEraseASavedHardDeadline() {
        var parsed = parser.basic("Buy on 2027-03-01 or 2027-03-31", NOW);
        Preferences previous = savedRequirements();

        assertThat(preferences.merge(previous, parsed.updates())).isEqualTo(previous);
        assertThat(parsed.question()).isNotNull();
    }

    @Test void modelCannotApplyAnAmountWhenItsQuoteOmitsTheUsersNegation() {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.complete(anyString(), anyString(), any())).thenReturn(response("""
                {"updates":{"budget":120000},"evidence":{"budget":"budget to 120000"},"question":null}
                """));
        Preferences previous = savedRequirements();

        assertThat(preferences.merge(previous,
                parser.parse("Do not change my budget to 120000", previous, List.of(), NOW).updates())).isEqualTo(previous);
    }

    private static Preferences savedRequirements() {
        return new Preferences(10L, new BigDecimal("90000"), true, LocalDate.parse("2027-02-28"),
                DeadlineKind.HARD, null, new BigDecimal("80000"), new BigDecimal("5"), false, "NORMAL", null);
    }

    private static AiResponse response(String json) {
        return new AiResponse(json, "fixture", 1, 1, 0, 0, BigDecimal.ZERO, "stop");
    }

    @Test void invalidHardDateWindowAndMoneyAreRejected() throws Exception {
        var incompatible = mapper.readTree("{\"deadlineDate\":\"2027-01-01\",\"earliestDate\":\"2027-02-01\"}");
        assertThatThrownBy(() -> preferences.merge(Preferences.defaults(10L), incompatible)).isInstanceOf(ApiException.class);
        var negative = mapper.readTree("{\"budget\":-100}");
        assertThatThrownBy(() -> preferences.merge(Preferences.defaults(10L), negative)).isInstanceOf(ApiException.class);
    }
}
