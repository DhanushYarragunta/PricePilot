package com.pricepilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.domain.ApiProvider;
import com.pricepilot.domain.ScrapeOutcome;
import com.pricepilot.scrape.ScrapeEngine;
import com.pricepilot.scrape.ScrapeResult;
import com.pricepilot.scrape.UrlCanonicalizer;
import com.pricepilot.scrape.rules.DomainRules;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Explicit opt-in local CLI, in test sources so it cannot add a production endpoint or bean.
 * Output is an immutable extraction record; expected prices never enter the scrape engine.
 */
public final class LiveExtractionEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RESULT_FIELDS = Set.of("schema_version", "run_id", "case_id", "submitted_url",
            "final_url", "started_at", "completed_at", "observed_at", "outcome", "returned_title", "returned_price",
            "returned_currency", "returned_stock", "strategy", "source", "reuse", "safe_failure_category",
            "fallback_used", "workflow_submissions", "expected_record_sha256", "policy_sha256");
    private static final Set<String> JOURNAL_FIELDS = Set.of("schema_version", "event", "run_id", "case_id",
            "submitted_url", "canonical_url", "started_at", "expected_record_sha256", "policy_sha256");
    private final ScrapeEngine engine;
    private final UrlCanonicalizer canonicalizer;
    private final DomainRules rules;
    private final IntSupplier usage;
    private final LongSupplier submissions;
    private final int monthlyLimit;
    private final List<String> secrets;

    record Case(String case_id, String product_url, String verified_at_utc,
                int max_observation_gap_seconds, String expected_record_sha256, String policy_sha256,
                boolean selected) { }
    record Prepared(Case item, UrlCanonicalizer.Canonical canonical) { }
    record Summary(int attempted, int already_started, int interrupted, String stop_reason) { }

    LiveExtractionEvaluation(ScrapeEngine engine, UrlCanonicalizer canonicalizer, DomainRules rules,
                             IntSupplier usage, int monthlyLimit, LongSupplier submissions, List<String> secrets) {
        this.engine = engine;
        this.canonicalizer = canonicalizer;
        this.rules = rules;
        this.usage = usage;
        this.monthlyLimit = monthlyLimit;
        this.submissions = submissions;
        this.secrets = secrets;
    }

    public static void main(String[] args) {
        // Provider bodies, JDBC details and credentials must not escape through diagnostic logs.
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.OFF);
        java.util.logging.LogManager.getLogManager().reset();
        int exitCode = 0;
        try {
            Map<String, String> options = options(args);
            boolean preflight = options.containsKey("--preflight");
            try (EvaluationRuntime runtime = new EvaluationRuntime(EvaluationRuntime.environment(), preflight)) {
                int used = runtime.budget.callsThisMonth(ApiProvider.BROWSERACT);
                int cap = runtime.properties.getBrowserAct().getMonthlyCallBudget();
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("status", "preflight_ok");
                check.put("local_database", true);
                check.put("budget_period", com.pricepilot.usage.BudgetGuard.currentPeriod());
                check.put("budget_used", used);
                check.put("budget_limit", cap);
                check.put("budget_available", cap == -1 || used < cap);
                check.put("provider_authentication_checked", false);
                check.put("paid_submissions", 0);
                System.out.println(JSON.writeValueAsString(check));
                if (!preflight) {
                    List<Case> cases = JSON.readerForListOf(Case.class)
                            .readValue(Files.readString(Path.of(required(options, "--manifest"))));
                    int limit = Integer.parseInt(options.getOrDefault("--limit", "3"));
                    var runner = new LiveExtractionEvaluation(runtime.engine, runtime.canonicalizer, runtime.rules,
                            () -> runtime.budget.callsThisMonth(ApiProvider.BROWSERACT), cap,
                            () -> runtime.metrics.snapshot().retailerRequests(), runtime.secrets);
                    Summary summary = runner.run(cases, Path.of(required(options, "--output")),
                            Path.of(required(options, "--journal")), limit);
                    System.out.println(JSON.writeValueAsString(summary));
                }
            }
        } catch (Exception failure) {
            // Do not print the exception: causes can contain credentials or raw provider text.
            System.out.println("{\"status\":\"evaluation_stopped\",\"safe_failure_category\":\""
                    + setupFailureCategory(failure) + "\"}");
            exitCode = 2;
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static String setupFailureCategory(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof EvaluationRuntime.ConfigurationFailure configuration) return configuration.category;
            if (cause instanceof java.sql.SQLException || cause instanceof org.springframework.dao.DataAccessException
                    || cause instanceof org.hibernate.HibernateException) return "local_database_unavailable_or_usage_ledger_missing";
        }
        if (failure instanceof IOException) return "evidence_file_error";
        if (failure instanceof IllegalArgumentException) return "configuration_or_arguments_invalid";
        return "local_evaluation_setup_failed";
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evaluation option.");
        return value;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!List.of("--preflight", "--manifest", "--output", "--journal", "--limit").contains(key)
                    || options.containsKey(key)) throw new IllegalArgumentException("Invalid evaluation option.");
            options.put(key, key.equals("--preflight") ? "true" : (i + 1 < args.length ? args[++i] : null));
        }
        return options;
    }

    Summary run(List<Case> cases, Path output, Path journal, int limit) throws IOException {
        if (limit < 1 || limit > 50 || cases.isEmpty() || cases.size() > 100) {
            throw new IllegalArgumentException("Limit must be 1..50 and dataset 1..100 cases.");
        }
        if (output.toAbsolutePath().normalize().equals(journal.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Results and journal must be different files.");
        }
        // Exclusive locks cover both files even when another invocation uses a different journal.
        try (FileChannel out = channel(output); FileLock outLock = out.tryLock();
             FileChannel ledger = channel(journal); FileLock journalLock = ledger.tryLock()) {
            if (outLock == null || journalLock == null) throw new IOException("Evaluation already running.");
            Map<String, JsonNode> started = records(journal, true);
            Map<String, JsonNode> completed = records(output, false);
            for (var entry : completed.entrySet()) {
                JsonNode start = started.get(entry.getKey());
                JsonNode result = entry.getValue();
                if (start == null || !start.path("submitted_url").equals(result.path("submitted_url"))
                        || !start.path("run_id").equals(result.path("run_id"))
                        || !start.path("started_at").equals(result.path("started_at"))
                        || !start.path("expected_record_sha256").equals(result.path("expected_record_sha256"))
                        || !start.path("policy_sha256").equals(result.path("policy_sha256"))) {
                    throw new IOException("Results do not match the attempt journal.");
                }
            }
            String runId = started.isEmpty() ? UUID.randomUUID().toString()
                    : started.values().iterator().next().path("run_id").asText();
            if (containsSecret(runId)) throw new IOException("Unsafe evaluation metadata.");
            List<Prepared> prepared = prepare(cases, started);
            int interrupted = (int) started.keySet().stream().filter(id -> !completed.containsKey(id)).count();
            int attempted = 0;
            int previous = 0;
            String stop = "selected_limit_reached";
            for (Prepared candidate : prepared) {
                Case item = candidate.item();
                if (!item.selected()) continue;
                if (started.containsKey(item.case_id())) { previous++; continue; }
                if (attempted >= limit) break;
                if (monthlyLimit != -1 && (monthlyLimit <= 0 || usage.getAsInt() >= monthlyLimit)) {
                    stop = "budget_exhausted";
                    break;
                }
                if (Thread.currentThread().isInterrupted()) { stop = "interrupted"; break; }
                Instant began = Instant.now();
                Instant verified = Instant.parse(item.verified_at_utc());
                if (verified.isAfter(began) || Duration.between(verified, began).getSeconds() > item.max_observation_gap_seconds()) {
                    stop = "reference_evidence_stale";
                    break;
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("schema_version", 1);
                event.put("event", "started");
                event.put("run_id", runId);
                event.put("case_id", item.case_id());
                event.put("submitted_url", item.product_url());
                event.put("canonical_url", candidate.canonical().url());
                event.put("started_at", began.toString());
                event.put("expected_record_sha256", item.expected_record_sha256());
                event.put("policy_sha256", item.policy_sha256());
                append(ledger, event); // Durable BEFORE any potential paid call; never retry this ID.
                long before = submissions.getAsLong();
                ScrapeResult result;
                try {
                    result = engine.scrapeRaw(item.product_url(), Duration.ZERO);
                } catch (RuntimeException failure) {
                    result = ScrapeResult.aborted(ScrapeOutcome.ERROR, candidate.canonical().host(),
                            candidate.canonical().url(), null, 0, null);
                }
                Instant ended = Instant.now();
                long used = submissions.getAsLong() - before;
                Map<String, Object> record = resultRecord(item, runId, began, ended, result, used);
                append(out, record);
                attempted++;
                String category = (String) record.get("safe_failure_category");
                if (List.of("authentication_rejected", "provider_rate_limited", "configuration_missing",
                        "budget_exhausted", "unsafe_output").contains(category)) {
                    stop = category;
                    break;
                }
                if (used == 0 && result.outcome() == ScrapeOutcome.ERROR) {
                    // A database/budget failure can be collapsed by the original client to ERROR.
                    // Conservatively stop instead of trying many more URLs against broken setup.
                    stop = "global_pre_submission_failure";
                    break;
                }
            }
            long selectedCount = prepared.stream().filter(candidate -> candidate.item().selected()).count();
            if ("selected_limit_reached".equals(stop) && attempted + previous == selectedCount) stop = "dataset_exhausted";
            return new Summary(attempted, previous, interrupted, stop);
        }
    }

    private List<Prepared> prepare(List<Case> cases, Map<String, JsonNode> started) throws IOException {
        HashSet<String> identifiers = new HashSet<>();
        HashSet<String> products = new HashSet<>();
        List<Prepared> prepared = new ArrayList<>();
        for (Case item : cases) {
            if (item.case_id() == null || !item.case_id().matches("[A-Za-z0-9_-]{1,80}")
                    || !identifiers.add(item.case_id()) || item.product_url() == null
                    || item.expected_record_sha256() == null || !item.expected_record_sha256().matches("[a-f0-9]{64}")
                    || item.policy_sha256() == null || !item.policy_sha256().matches("[a-f0-9]{64}")
                    || item.max_observation_gap_seconds() < 1 || item.max_observation_gap_seconds() > 86400) {
                throw new IOException("Invalid or duplicate case identifier.");
            }
            URI uri = URI.create(item.product_url());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || !rules.isKnownHost(uri.getHost()) || containsSecret(item.product_url())
                    || containsSecret(item.case_id()) || containsSecret(item.verified_at_utc())
                    || containsSecret(item.expected_record_sha256()) || containsSecret(item.policy_sha256())) {
                throw new IOException("Unsafe or unsupported product URL.");
            }
            UrlCanonicalizer.Canonical canonical = canonicalizer.canonicalize(item.product_url());
            if (containsSecret(canonical.url())) throw new IOException("Unsafe canonical product URL.");
            if (!products.add(canonical.hash())) throw new IOException("Dataset repeats a canonical product.");
            JsonNode old = started.get(item.case_id());
            if (old != null && (!item.product_url().equals(old.path("submitted_url").asText())
                    || !canonical.url().equals(old.path("canonical_url").asText())
                    || !item.expected_record_sha256().equals(old.path("expected_record_sha256").asText())
                    || !item.policy_sha256().equals(old.path("policy_sha256").asText()))) {
                throw new IOException("An attempted case was changed; use its original evidence files.");
            }
            for (JsonNode historical : started.values()) {
                if (!historical.path("case_id").asText().equals(item.case_id())
                        && historical.path("canonical_url").asText().equals(canonical.url())) {
                    throw new IOException("A product was already attempted under another case identifier.");
                }
            }
            prepared.add(new Prepared(item, canonical));
        }
        if (!identifiers.containsAll(started.keySet())) {
            throw new IOException("An attempted case baseline is missing or no longer verified.");
        }
        return prepared;
    }

    Map<String, Object> resultRecord(Case item, String runId, Instant started, Instant finished,
                                     ScrapeResult result, long submissions) {
        Map<String, Object> row = new LinkedHashMap<>();
        var snapshot = result.snapshot();
        row.put("schema_version", 1);
        row.put("run_id", runId);
        row.put("case_id", item.case_id());
        row.put("expected_record_sha256", item.expected_record_sha256());
        row.put("policy_sha256", item.policy_sha256());
        row.put("submitted_url", item.product_url());
        row.put("final_url", result.finalUrl());
        row.put("started_at", started.toString());
        row.put("completed_at", finished.toString());
        row.put("observed_at", result.observedAt() == null ? null : result.observedAt().toString());
        row.put("outcome", result.outcome().name());
        row.put("returned_title", snapshot == null ? null : snapshot.title());
        row.put("returned_price", snapshot == null || snapshot.price() == null ? null : snapshot.price().toPlainString());
        row.put("returned_currency", snapshot == null ? null : snapshot.currency());
        row.put("returned_stock", snapshot == null ? null : snapshot.inStock());
        row.put("strategy", result.strategy());
        row.put("source", result.source() == null ? null : result.source().name());
        row.put("reuse", result.reuse().name());
        row.put("safe_failure_category", failureCategory(result));
        row.put("fallback_used", false);
        row.put("workflow_submissions", submissions);
        // Unexpected provider echoing is quarantined, not repaired into an apparent extraction.
        if (row.values().stream().anyMatch(value -> value instanceof String s && containsSecret(s))) {
            for (String field : List.of("returned_title", "returned_price", "returned_currency", "returned_stock",
                    "final_url", "strategy", "observed_at")) row.put(field, null);
            row.put("outcome", "ERROR");
            row.put("safe_failure_category", "unsafe_output");
        }
        return row;
    }

    private boolean containsSecret(String text) {
        return text != null && secrets.stream().filter(value -> value != null && !value.isEmpty())
                .anyMatch(value -> value.length() >= 8 ? text.contains(value) : text.equals(value));
    }

    static String failureCategory(ScrapeResult result) {
        String message = result.message();
        if ("BrowserAct API returned HTTP 401.".equals(message)
                || "BrowserAct API returned HTTP 403.".equals(message)) return "authentication_rejected";
        if ("BrowserAct API returned HTTP 429.".equals(message)) return "provider_rate_limited";
        if ("BrowserAct is not configured or submissions are disabled.".equals(message)) return "configuration_missing";
        if ("The BrowserAct submission budget is exhausted.".equals(message)) return "budget_exhausted";
        return switch (result.outcome()) {
            case SUCCESS -> "none";
            case NO_PRICE -> "invalid_output_or_identity";
            case TIMEOUT -> "timeout";
            case BLOCKED -> "blocked";
            case HTTP_ERROR -> "provider_error";
            case SKIPPED_FRESH -> "unexpected_cache_reuse";
            case ERROR -> "provider_error";
        };
    }

    private static FileChannel channel(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Evidence paths must not be symbolic links.");
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static Map<String, JsonNode> records(Path path, boolean journal) throws IOException {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        String runId = null;
        String contents = Files.readString(path);
        if (!contents.isEmpty() && !contents.endsWith("\n")) throw new IOException("Unterminated evidence file.");
        for (String line : contents.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
            validateRecord(row, journal);
            String id = row.path("case_id").asText();
            if (row.path("schema_version").asInt() != 1 || id.isBlank() || row.path("run_id").asText().isBlank()
                    || row.path("submitted_url").asText().isBlank()
                    || !row.path("expected_record_sha256").asText().matches("[a-f0-9]{64}")
                    || !row.path("policy_sha256").asText().matches("[a-f0-9]{64}")
                    || row.path("started_at").asText().isBlank()
                    || (journal && (!"started".equals(row.path("event").asText()) || row.path("canonical_url").asText().isBlank()))
                    || records.putIfAbsent(id, row) != null) throw new IOException("Invalid evidence file.");
            if (runId != null && !runId.equals(row.path("run_id").asText())) throw new IOException("Mixed evidence runs.");
            runId = row.path("run_id").asText();
        }
        return records;
    }

    private static void validateRecord(JsonNode row, boolean journal) throws IOException {
        Set<String> actualFields = new HashSet<>();
        row.fieldNames().forEachRemaining(actualFields::add);
        if (!row.isObject() || !actualFields.equals(journal ? JOURNAL_FIELDS : RESULT_FIELDS)
                || !row.path("schema_version").isIntegralNumber()
                || !row.path("case_id").asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
                || !row.path("run_id").asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            throw new IOException("Invalid evidence record schema.");
        }
        for (String field : List.of("case_id", "run_id", "submitted_url", "started_at", "expected_record_sha256", "policy_sha256")) {
            if (!row.path(field).isTextual()) throw new IOException("Invalid evidence field type.");
        }
        try {
            Instant started = Instant.parse(row.path("started_at").asText());
            if (started.isAfter(Instant.now())) throw new IllegalArgumentException();
            if (journal) {
                if (!row.path("canonical_url").isTextual() || !row.path("event").isTextual()) throw new IllegalArgumentException();
                return;
            }
            Instant completed = Instant.parse(row.path("completed_at").asText());
            if (completed.isBefore(started) || completed.isAfter(Instant.now())) throw new IllegalArgumentException();
            if (!row.path("observed_at").isNull()) Instant.parse(row.path("observed_at").asText());
            ScrapeOutcome.valueOf(row.path("outcome").asText());
            ScrapeResult.Reuse.valueOf(row.path("reuse").asText());
            if (!row.path("fallback_used").isBoolean() || row.path("fallback_used").asBoolean()
                    || !row.path("workflow_submissions").isIntegralNumber()
                    || row.path("workflow_submissions").asInt() < 0 || row.path("workflow_submissions").asInt() > 1
                    || (!row.path("returned_stock").isNull() && !row.path("returned_stock").isBoolean())) throw new IllegalArgumentException();
            for (String field : List.of("final_url", "returned_title", "returned_price", "returned_currency",
                    "strategy", "source", "safe_failure_category", "observed_at")) {
                if (!row.path(field).isNull() && !row.path(field).isTextual()) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid evidence field type or timestamp.");
        }
    }

    private static void append(FileChannel channel, Map<String, Object> value) throws IOException {
        ByteBuffer line = ByteBuffer.wrap((JSON.writeValueAsString(value) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        channel.position(channel.size());
        while (line.hasRemaining()) channel.write(line);
        channel.force(true);
    }
}
