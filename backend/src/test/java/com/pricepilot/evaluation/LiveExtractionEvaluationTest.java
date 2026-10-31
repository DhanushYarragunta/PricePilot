package com.pricepilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.repo.ApiUsageRepository;
import com.pricepilot.scrape.FetchedPage;
import com.pricepilot.scrape.ProductSnapshot;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.DomainRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveExtractionEvaluationTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private ScrapeEngine engine;
    private UrlCanonicalizer canonicalizer;
    private DomainRules rules;
    private AtomicLong paid;

    @BeforeEach void prepareMocks() {
        engine = mock(ScrapeEngine.class);
        canonicalizer = mock(UrlCanonicalizer.class);
        rules = mock(DomainRules.class);
        paid = new AtomicLong();
        when(rules.isKnownHost(anyString())).thenReturn(true);
        when(canonicalizer.canonicalize(anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            return new UrlCanonicalizer.Canonical(url, "www.amazon.in", UrlCanonicalizer.sha256Hex(url), null);
        });
        when(engine.scrapeRaw(anyString(), eq(Duration.ZERO))).thenAnswer(call -> {
            paid.incrementAndGet();
            return success(call.getArgument(0), "Exact black 256 GB model");
        });
    }

    private LiveExtractionEvaluation runner(int used, int limit) {
        return new LiveExtractionEvaluation(engine, canonicalizer, rules, () -> used, limit,
                paid::get, List.of("fake-test-only-browser-key", "fake-workflow-id"));
    }

    private LiveExtractionEvaluation.Case item(int index) {
        return new LiveExtractionEvaluation.Case("case_" + index, "https://www.amazon.in/dp/B00000000" + index,
                Instant.now().minusSeconds(60).toString(), 600, "a".repeat(64), "b".repeat(64), true);
    }

    private Path output() { return directory.resolve("results.jsonl"); }
    private Path journal() { return directory.resolve("attempts.jsonl"); }
    private JsonNode first(Path path) throws Exception { return json.readTree(Files.readAllLines(path).get(0)); }

    private ScrapeResult success(String url, String title) {
        return new ScrapeResult(ScrapeOutcome.SUCCESS,
                new ProductSnapshot(new BigDecimal("1299.00"), null, "INR", title, null, true, "browseract"),
                "www.amazon.in", url, null, 90, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", null);
    }

    @Test void callsOriginalEngineWithZeroMaxAgeAndKeepsUntouchedSnapshotAndHashes() throws Exception {
        var item = item(1);
        var summary = runner(0, 10).run(List.of(item), output(), journal(), 3);
        assertThat(summary.attempted()).isEqualTo(1);
        verify(engine).scrapeRaw(item.product_url(), Duration.ZERO);
        JsonNode result = first(output());
        assertThat(result.path("returned_price").asText()).isEqualTo("1299.00");
        assertThat(result.path("returned_title").asText()).isEqualTo("Exact black 256 GB model");
        assertThat(result.path("returned_stock").asBoolean()).isTrue();
        assertThat(result.path("workflow_submissions").asInt()).isEqualTo(1);
        assertThat(result.path("fallback_used").asBoolean()).isFalse();
        assertThat(result.path("reuse").asText()).isEqualTo("NONE");
        assertThat(result.path("source").asText()).isEqualTo("BROWSERACT");
        assertThat(result.path("expected_record_sha256").asText()).isEqualTo(item.expected_record_sha256());
        assertThat(first(journal()).path("policy_sha256").asText()).isEqualTo(item.policy_sha256());
    }

    @Test void defaultSizedBatchStopsWithoutRunningRemainingProducts() throws Exception {
        var summary = runner(0, 100).run(List.of(item(1), item(2), item(3), item(4)), output(), journal(), 3);
        assertThat(summary.attempted()).isEqualTo(3);
        verify(engine, times(3)).scrapeRaw(anyString(), eq(Duration.ZERO));
        assertThat(Files.readAllLines(output())).hasSize(3);
    }

    @Test void exhaustedBudgetCreatesNoSubmissionOrJournalEntry() throws Exception {
        var summary = runner(10, 10).run(List.of(item(1)), output(), journal(), 3);
        assertThat(summary.stop_reason()).isEqualTo("budget_exhausted");
        assertThat(Files.size(journal())).isZero();
        verifyNoInteractions(engine);
    }

    @Test void rechecksSharedLedgerBeforeEachCase() throws Exception {
        var runner = new LiveExtractionEvaluation(engine, canonicalizer, rules,
                () -> (int) paid.get(), 1, paid::get, List.of());
        var summary = runner.run(List.of(item(1), item(2)), output(), journal(), 3);
        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(summary.stop_reason()).isEqualTo("budget_exhausted");
    }

    @Test void resumesWithoutRepeatingCompletedCases() throws Exception {
        var cases = List.of(item(1), item(2));
        var runner = runner(0, 10);
        runner.run(cases, output(), journal(), 1);
        var summary = runner.run(cases, output(), journal(), 1);
        assertThat(summary.already_started()).isEqualTo(1);
        assertThat(Files.readAllLines(output())).hasSize(2);
        verify(engine, times(1)).scrapeRaw(cases.get(0).product_url(), Duration.ZERO);
        verify(engine, times(1)).scrapeRaw(cases.get(1).product_url(), Duration.ZERO);
    }

    @Test void interruptedSubmissionHasDurableStartAndIsNeverRetried() throws Exception {
        var item = item(1);
        when(engine.scrapeRaw(item.product_url(), Duration.ZERO)).thenThrow(new AssertionError("simulated process exit"));
        var runner = runner(0, 10);
        assertThatThrownBy(() -> runner.run(List.of(item), output(), journal(), 3)).isInstanceOf(AssertionError.class);
        assertThat(first(journal()).path("case_id").asText()).isEqualTo(item.case_id());
        assertThat(Files.size(output())).isZero();
        var resumed = runner.run(List.of(item), output(), journal(), 3);
        assertThat(resumed.attempted()).isZero();
        assertThat(resumed.interrupted()).isEqualTo(1);
        verify(engine, times(1)).scrapeRaw(item.product_url(), Duration.ZERO);
    }

    @Test void modifiedAttemptedBaselineIsRefusedWithoutAnotherSubmission() throws Exception {
        var item = item(1);
        var runner = runner(0, 10);
        runner.run(List.of(item), output(), journal(), 3);
        var changed = new LiveExtractionEvaluation.Case(item.case_id(), item.product_url(), item.verified_at_utc(),
                600, "c".repeat(64), item.policy_sha256(), true);
        assertThatThrownBy(() -> runner.run(List.of(changed), output(), journal(), 3)).isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @Test void newBaselineForUnattemptedCaseCanBeAddedOnResume() throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        var second = item(2);
        var summary = runner.run(List.of(first, second), output(), journal(), 3);
        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(Files.readAllLines(output())).hasSize(2);
    }

    @Test void selectingOnlyNewCaseStillRejectsEditedHistoricalBaseline() throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        var changedUnselected = new LiveExtractionEvaluation.Case(first.case_id(), first.product_url(),
                first.verified_at_utc(), 600, "c".repeat(64), first.policy_sha256(), false);
        assertThatThrownBy(() -> runner.run(List.of(changedUnselected, item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @Test void omittedHistoricalBaselineCannotBeBypassedWithCaseSelection() throws Exception {
        var runner = runner(0, 10);
        runner.run(List.of(item(1)), output(), journal(), 3);
        assertThatThrownBy(() -> runner.run(List.of(item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @ParameterizedTest @ValueSource(strings = {"started_at", "expected_record_sha256", "policy_sha256"})
    void mismatchedTerminalRecordCannotBeResumed(String field) throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        var corrupted = (com.fasterxml.jackson.databind.node.ObjectNode) first(output());
        corrupted.put(field, field.equals("started_at") ? Instant.now().minusSeconds(600).toString() : "c".repeat(64));
        Files.writeString(output(), json.writeValueAsString(corrupted) + "\n");
        assertThatThrownBy(() -> runner.run(List.of(first, item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @Test void secretBearingCaseIdentifierNeverReachesJournalOrProvider() throws Exception {
        var normal = item(1);
        var unsafe = new LiveExtractionEvaluation.Case("fake-test-only-browser-key", normal.product_url(),
                normal.verified_at_utc(), 600, normal.expected_record_sha256(), normal.policy_sha256(), true);
        assertThatThrownBy(() -> runner(0, 10).run(List.of(unsafe), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(journal())).isEmpty();
        assertThat(Files.readString(output())).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test void staleBaselineStopsBeforePaidCall() throws Exception {
        var item = item(1);
        var stale = new LiveExtractionEvaluation.Case(item.case_id(), item.product_url(),
                Instant.now().minusSeconds(601).toString(), 600, item.expected_record_sha256(), item.policy_sha256(), true);
        var summary = runner(0, 10).run(List.of(stale), output(), journal(), 3);
        assertThat(summary.stop_reason()).isEqualTo("reference_evidence_stale");
        verifyNoInteractions(engine);
    }

    @Test void canonicalDuplicateIsRejectedBeforeAnyPaidSubmission() {
        when(canonicalizer.canonicalize(anyString())).thenReturn(new UrlCanonicalizer.Canonical(
                item(1).product_url(), "www.amazon.in", "identical-product", null));
        assertThatThrownBy(() -> runner(0, 10).run(List.of(item(1), item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verifyNoInteractions(engine);
    }

    @Test void providerAuthenticationFailureStopsFollowingProductsWithoutExposingRawMessages() throws Exception {
        when(engine.scrapeRaw(anyString(), eq(Duration.ZERO))).thenAnswer(call -> {
            paid.incrementAndGet();
            return new ScrapeResult(ScrapeOutcome.ERROR, null, "www.amazon.in", call.getArgument(0),
                    null, 0, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", "BrowserAct API returned HTTP 401.");
        });
        var summary = runner(0, 10).run(List.of(item(1), item(2)), output(), journal(), 3);
        assertThat(summary.stop_reason()).isEqualTo("authentication_rejected");
        assertThat(summary.attempted()).isEqualTo(1);
        assertThat(first(output()).has("message")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"NO_PRICE", "TIMEOUT", "ERROR"})
    void failedExtractionKeepsNoInventedPriceAndUsesSafeCategory(String outcome) throws Exception {
        when(engine.scrapeRaw(anyString(), eq(Duration.ZERO))).thenAnswer(call -> {
            paid.incrementAndGet();
            return new ScrapeResult(ScrapeOutcome.valueOf(outcome), null, "www.amazon.in", call.getArgument(0),
                    null, 0, 0, FetchedPage.FetchSource.BROWSERACT, "browseract", "untrusted raw provider content");
        });
        runner(0, 10).run(List.of(item(1)), output(), journal(), 3);
        assertThat(first(output()).path("returned_price").isNull()).isTrue();
        assertThat(Files.readString(output())).doesNotContain("untrusted raw provider content");
        assertThat(first(output()).path("outcome").asText()).isEqualTo(outcome);
    }

    @Test void echoedCredentialIsQuarantinedWithoutSerializingIt() throws Exception {
        var result = success(item(1).product_url(), "Provider echoed fake-test-only-browser-key");
        var record = runner(0, 10).resultRecord(item(1), "test-run", Instant.now(), Instant.now(), result, 1);
        assertThat(record).containsEntry("safe_failure_category", "unsafe_output").containsEntry("outcome", "ERROR");
        assertThat(json.writeValueAsString(record)).doesNotContain("fake-test-only-browser-key");
    }

    @Test void malformedJournalPreventsBlindRetry() throws Exception {
        Files.writeString(journal(), "{truncated");
        assertThatThrownBy(() -> runner(0, 10).run(List.of(item(1)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verifyNoInteractions(engine);
    }

    @Test void missingFinalNewlineStopsBeforeAnotherPaidCall() throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        Files.writeString(output(), Files.readString(output()).stripTrailing());
        assertThatThrownBy(() -> runner.run(List.of(first, item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @Test void duplicateJsonKeyStopsBeforeAnotherPaidCall() throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        Files.writeString(output(), Files.readString(output()).replace("{", "{\"schema_version\":1,"));
        assertThatThrownBy(() -> runner.run(List.of(first, item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @Test void incompleteTerminalRecordStopsBeforeAnotherPaidCall() throws Exception {
        var runner = runner(0, 10);
        var first = item(1);
        runner.run(List.of(first), output(), journal(), 3);
        var incomplete = (com.fasterxml.jackson.databind.node.ObjectNode) first(output());
        incomplete.remove("returned_price");
        Files.writeString(output(), json.writeValueAsString(incomplete) + "\n");
        assertThatThrownBy(() -> runner.run(List.of(first, item(2)), output(), journal(), 3))
                .isInstanceOf(java.io.IOException.class);
        verify(engine, times(1)).scrapeRaw(anyString(), eq(Duration.ZERO));
    }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:postgresql://example.com:5432/pricepilot", "jdbc:postgresql://localhost:5432/db?user=secret",
            "jdbc:postgresql://user:password@localhost:5432/db", "jdbc:postgresql://localhost:65536/db",
            "jdbc:postgresql://localhost:5432/db,other"})
    void remoteOrOverridableDatabaseUrlsAreRejected(String value) {
        assertThatThrownBy(() -> EvaluationRuntime.localDatabaseUrl(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void incompleteConfigurationFailsBeforeAnyDatabaseInitialization() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-only", Map.of(
                "pricepilot.browser-act.api-key", "fake-test-key",
                "pricepilot.browser-act.monthly-call-budget", "10")));
        assertThatThrownBy(() -> new EvaluationRuntime(environment, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("fake-test-key");
    }

    @Test void originalBudgetGuardAnnotationsAreHonoredByTheLocalProxy() {
        var repository = mock(ApiUsageRepository.class);
        when(repository.findByProviderAndPeriod(any(), anyString())).thenReturn(java.util.Optional.empty());
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var budget = EvaluationRuntime.transactionalBudget(repository, manager);
        assertThat(budget.callsThisMonth(ApiProvider.BROWSERACT)).isZero();
        assertThat(budget.tryConsumeCall(ApiProvider.BROWSERACT, 0)).isFalse();
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues().get(0).isReadOnly()).isTrue();
        assertThat(definitions.getAllValues().get(1).getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(manager, times(2)).commit(any());
        verify(repository, never()).ensureRow(anyString(), anyString());
    }
}
