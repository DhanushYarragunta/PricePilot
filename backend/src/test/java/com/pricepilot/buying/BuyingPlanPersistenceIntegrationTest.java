package com.pricepilot.buying;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.research.ResearchCache;
import com.pricepilot.research.ResearchReport;
import com.pricepilot.web.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pricepilot.buying.BuyingModels.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in real PostgreSQL coverage; never reads application configuration or .env.
 *
 * <p>From backend/, against an already running local PostgreSQL server, run:
 * <pre>
 * PP_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/pricepilot_test \
 * PP_TEST_DB_USERNAME=postgres PP_TEST_DB_PASSWORD="$LOCAL_TEST_DB_PASSWORD" \
 * ./mvnw -Dtest=BuyingPlanPersistenceIntegrationTest test
 * </pre>
 * The database must already exist and the role must be able to create schemas.
 * The test creates and drops only its own randomly named schema. Username defaults
 * to postgres and password to empty; URL options and embedded credentials are rejected.
 */
class BuyingPlanPersistenceIntegrationTest {
    private static final Pattern LOCAL_URL = Pattern.compile(
            "^jdbc:postgresql://(127\\.0\\.0\\.1|localhost|\\[::1\\]):([1-9][0-9]{0,4})/([A-Za-z0-9_][A-Za-z0-9_-]{0,62})$");
    private static final String SKIP_REASON = "Opt-in PostgreSQL test: set PP_TEST_DB_URL to an explicit local JDBC URL; no application database is used by default.";

    @Test
    @EnabledIfEnvironmentVariable(named = "PP_TEST_DB_URL", matches = ".+", disabledReason = SKIP_REASON)
    void migratesExistingDataAndPersistsPlansConversationsAndResearchInPostgres() throws Exception {
        String url = localJdbcUrl(System.getenv("PP_TEST_DB_URL"));
        String schema = "pp_persistence_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(dataSource(url, "pg_catalog"));
        boolean created = false;
        try {
            admin.execute("CREATE SCHEMA \"" + schema + "\"");
            created = true;
            DriverManagerDataSource dataSource = dataSource(url, schema);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            Instant now = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
            assertThat(migration(dataSource, schema, "1").migrate().migrationsExecuted).isEqualTo(1);
            Seed seed = seed(jdbc, now);
            Map<String, List<String>> originalRows = sourceRows(jdbc);

            assertThat(migration(dataSource, schema, "3").migrate().migrationsExecuted).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3");
            assertThat(sourceRows(jdbc)).isEqualTo(originalRows);

            List<Object> events = new ArrayList<>();
            BuyingPlanStore store = new BuyingPlanStore(jdbc, mapper, events::add);
            Preferences defaults = Preferences.defaults(seed.listingId());
            assertThat(store.find(seed.userId(), seed.productId())).isEmpty();
            BuyingPlanStore.Stored initial = transaction.execute(status -> store.initialize(seed.userId(), seed.productId(), defaults));
            assertThat(initial).isNotNull();
            assertThat(initial.version()).isZero();
            assertThat(initial.preferences()).isEqualTo(json(mapper, defaults));
            assertThat(initial.snapshot().isObject()).isTrue();
            assertThat(initial.snapshot()).isEmpty();

            LocalDate deadline = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(14);
            Preferences preferences = new Preferences(seed.listingId(), new BigDecimal("42000.50"), true,
                    deadline, DeadlineKind.HARD, deadline.minusDays(7), new BigDecimal("39000.25"),
                    new BigDecimal("8.50"), true, "NORMAL", "Need a phone with reliable battery life");
            BuyingPlanStore.Stored repeated = transaction.execute(status -> store.initialize(seed.userId(), seed.productId(), preferences));
            assertThat(repeated.id()).isEqualTo(initial.id());
            assertThat(repeated.version()).isZero();
            assertThat(repeated.preferences()).isEqualTo(initial.preferences());
            assertThat(store.find(seed.otherUserId(), seed.productId())).isEmpty();
            BuyingPlanStore.Stored otherOwner = transaction.execute(status -> store.initialize(seed.otherUserId(), seed.productId(), defaults));
            assertThat(otherOwner.id()).isNotEqualTo(initial.id());

            Snapshot snapshot = new Snapshot(new Plan(Outcome.BUY_AT_TARGET_PRICE,
                    "Wait for the target price", List.of("The budget is a firm limit"), new BigDecimal("39000.25"),
                    now.plusSeconds(900), deadline, now, List.of("A new retailer price is observed")),
                    new Forecast("INSUFFICIENT_DATA", "NONE", deadline, null, null, null,
                            "Only observed prices are available", List.of(), List.of("More history is required")),
                    null, "INR", now, "Is battery life your main priority?", List.of("No guaranteed future price"));
            BuyingPlanStore.Notice notice = new BuyingPlanStore.Notice("PLAN_CHANGED", "target-price-39000",
                    "Your buying plan changed", "We will reassess after the next observation.");
            BuyingPlanStore.Stored saved = transaction.execute(status -> {
                BuyingPlanStore.Stored result = store.save(initial, preferences, snapshot,
                        "My budget is ₹42,000.50; battery life matters.", "I saved your budget and target price.", notice, now);
                assertThat(events).as("Notifications must wait for the database commit").isEmpty();
                return result;
            });
            assertThat(saved.version()).isEqualTo(1);
            assertThat(saved.preferences()).isEqualTo(json(mapper, preferences));
            assertThat(saved.snapshot()).isEqualTo(json(mapper, snapshot));
            assertThat(saved.nextReassessmentAt()).isEqualTo(now.plusSeconds(900));
            assertThat(saved.lastObservedAt()).isEqualTo(now);
            assertThat(saved.updatedAt()).isEqualTo(now);
            assertThat(jdbc.queryForObject("SELECT pg_typeof(preferences)::text FROM buying_plan WHERE id=?", String.class, saved.id()))
                    .isEqualTo("jsonb");
            assertThat(events).hasSize(1).allSatisfy(event -> assertThat(event).isInstanceOf(BuyingPlanNotificationEvent.class));
            assertThat(store.conversation(saved.id())).extracting(Conversation::role).containsExactly("user", "assistant");
            assertThat(store.conversation(saved.id())).extracting(Conversation::content)
                    .containsExactly("My budget is ₹42,000.50; battery life matters.", "I saved your budget and target price.");
            assertThat(store.conversation(saved.id())).extracting(Conversation::createdAt).containsOnly(now);
            assertThat(store.conversation(otherOwner.id())).isEmpty();
            assertThat(store.find(seed.otherUserId(), seed.productId()).orElseThrow().version()).isZero();

            assertThatThrownBy(() -> transaction.execute(status -> {
                store.save(saved, defaults, snapshot, "Rolled-back message", "Rolled-back answer",
                        new BuyingPlanStore.Notice("PLAN_CHANGED", "rolled-back", "Rollback", "Not committed"), now.plusSeconds(1));
                throw new IllegalStateException("Force transaction rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("Force transaction rollback");
            assertThat(store.find(seed.userId(), seed.productId()).orElseThrow()).isEqualTo(saved);
            assertThat(store.conversation(saved.id())).hasSize(2);
            assertThat(store.notifications(saved.id())).hasSize(1);
            assertThat(events).hasSize(1);

            BuyingPlanStore.Stored updated = transaction.execute(status -> store.save(saved, preferences, snapshot,
                    null, null, notice, now.plusSeconds(2)));
            assertThat(updated.version()).isEqualTo(2);
            assertThat(store.notifications(saved.id())).singleElement().satisfies(notification -> {
                assertThat(notification.kind()).isEqualTo("PLAN_CHANGED");
                assertThat(notification.message()).isEqualTo(notice.message());
                assertThat(notification.deliveredAt()).isNull();
            });
            assertThat(events).as("The same notification deduplication key must not publish twice").hasSize(1);
            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> transaction.execute(status -> store.save(initial, defaults, snapshot,
                            "Stale message", "Stale answer", null, now.plusSeconds(3))))
                    .satisfies(error -> assertThat(error.getCode()).isEqualTo("buying_plan_changed"));
            assertThat(store.find(seed.userId(), seed.productId()).orElseThrow()).isEqualTo(updated);
            assertThat(store.conversation(saved.id())).hasSize(2);
            assertThat(store.due(now.plusSeconds(901), 10)).extracting(BuyingPlanStore.Stored::id).containsExactly(saved.id());

            ResearchCache research = new ResearchCache(jdbc, mapper);
            String cacheKey = "a".repeat(64);
            assertThat(research.find(cacheKey)).isEmpty();
            ResearchReport report = research(seed, now, "Good fit when battery life is the priority.");
            transaction.executeWithoutResult(status -> research.save(cacheKey, report));
            assertThat(research.find(cacheKey)).contains(report);
            ResearchReport refreshed = research(seed, now.plusSeconds(30), "Check the quoted owner feedback before buying.");
            transaction.executeWithoutResult(status -> research.save(cacheKey, refreshed));
            assertThat(research.find(cacheKey)).contains(refreshed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM product_research", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT pg_typeof(report_json)::text FROM product_research WHERE cache_key=?", String.class, cacheKey))
                    .isEqualTo("jsonb");
            assertThat(jdbc.queryForObject("SELECT researched_at FROM product_research WHERE cache_key=?", Timestamp.class, cacheKey).toInstant())
                    .isEqualTo(refreshed.researchAt());
            assertThat(jdbc.queryForObject("SELECT expires_at FROM product_research WHERE cache_key=?", Timestamp.class, cacheKey).toInstant())
                    .isEqualTo(refreshed.expiresAt());
            assertThat(sourceRows(jdbc)).as("Planning and research must preserve original products, listings, prices, and alerts")
                    .isEqualTo(originalRows);
        } finally {
            if (created) {
                admin.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    @Test
    void acceptsOnlyExplicitLoopbackHostsAndNormalizesLocalhostWithoutDns() {
        assertThat(localJdbcUrl("jdbc:postgresql://127.0.0.1:5432/pricepilot_test"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/pricepilot_test");
        assertThat(localJdbcUrl("jdbc:postgresql://localhost:15432/pricepilot-test"))
                .isEqualTo("jdbc:postgresql://127.0.0.1:15432/pricepilot-test");
        assertThat(localJdbcUrl("jdbc:postgresql://[::1]:5432/pricepilot_test"))
                .isEqualTo("jdbc:postgresql://[::1]:5432/pricepilot_test");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql:pricepilot", "jdbc:postgresql://127.0.0.1/pricepilot",
            "jdbc:postgresql://database.example:5432/pricepilot", "jdbc:postgresql://127.0.0.1.example:5432/pricepilot",
            "jdbc:postgresql://127.0.0.1:5432,remote.example:5432/pricepilot", "jdbc:postgresql://user:secret@127.0.0.1:5432/pricepilot",
            "jdbc:postgresql://127.0.0.1:0/pricepilot", "jdbc:postgresql://127.0.0.1:65536/pricepilot",
            "jdbc:postgresql://127.0.0.1:5432/pricepilot?currentSchema=public", "jdbc:postgresql://127.0.0.1:5432/pricepilot?options=-c%20search_path=public",
            "jdbc:postgresql://127.0.0.1:5432/pricepilot?service=remote", "jdbc:postgresql://127.0.0.1:5432/pricepilot?socketFactory=custom.Factory",
            "jdbc:postgresql://127.0.0.1:5432/pricepilot?sslFactory=custom.Factory", "jdbc:postgresql://127.0.0.1:5432/pricepilot#fragment",
            "jdbc:postgresql://127.0.0.1:5432/%2e%2e", "jdbc:postgresql://127.0.0.1:5432/pricepilot/other",
            "jdbc:postgresql://[::ffff:192.168.1.1]:5432/pricepilot", "jdbc:postgresql://%31%32%37.0.0.1:5432/pricepilot"
    })
    void rejectsRemoteOrAmbiguousUrlsAndEveryConnectionOverride(String url) {
        assertThatIllegalArgumentException().isThrownBy(() -> localJdbcUrl(url))
                .withMessage("PP_TEST_DB_URL must be jdbc:postgresql://127.0.0.1:PORT/DATABASE (localhost or [::1] also allowed), without URL options or credentials.");
    }

    private static String localJdbcUrl(String url) {
        Matcher match = LOCAL_URL.matcher(url == null ? "" : url);
        if (!match.matches() || Integer.parseInt(match.group(2)) > 65535) {
            throw new IllegalArgumentException("PP_TEST_DB_URL must be jdbc:postgresql://127.0.0.1:PORT/DATABASE (localhost or [::1] also allowed), without URL options or credentials.");
        }
        String host = match.group(1).equals("localhost") ? "127.0.0.1" : match.group(1);
        return "jdbc:postgresql://" + host + ":" + match.group(2) + "/" + match.group(3);
    }

    private static DriverManagerDataSource dataSource(String url, String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("PP_TEST_DB_USERNAME", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("PP_TEST_DB_PASSWORD", ""));
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        properties.setProperty("ApplicationName", "PricePilot isolated persistence test");
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    private static Flyway migration(DriverManagerDataSource dataSource, String schema, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).createSchemas(false).cleanDisabled(true).target(target).load();
    }

    private static JsonNode json(ObjectMapper mapper, Object value) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    private record Seed(Long userId, Long otherUserId, Long productId, Long listingId) { }

    private static Seed seed(JdbcTemplate jdbc, Instant now) {
        Long user = jdbc.queryForObject("INSERT INTO app_user(email,password_hash) VALUES ('owner@example.invalid','test-only') RETURNING id", Long.class);
        Long otherUser = jdbc.queryForObject("INSERT INTO app_user(email,password_hash) VALUES ('other@example.invalid','test-only') RETURNING id", Long.class);
        Long product = jdbc.queryForObject("INSERT INTO product(title,brand) VALUES ('Acme Phone 128GB','Acme') RETURNING id", Long.class);
        Long listing = jdbc.queryForObject("INSERT INTO listing(product_id,retailer,url,url_hash,title) VALUES (?,'AMAZON','https://www.amazon.in/dp/B012345678',?,'Acme Phone 128GB') RETURNING id",
                Long.class, product, "b".repeat(64));
        Long tracker = jdbc.queryForObject("INSERT INTO tracker(user_id,product_id,target_price) VALUES (?,?,39000.25) RETURNING id", Long.class, user, product);
        jdbc.update("INSERT INTO price_point(listing_id,price,list_price,captured_at,source_strategy) VALUES (?,41000.50,45000.00,?,'JSON_LD')", listing, Timestamp.from(now));
        jdbc.update("INSERT INTO alert_log(tracker_id,kind,price,message,created_at) VALUES (?,'ALL_TIME_LOW',41000.50,'Existing observed-price alert',?)", tracker, Timestamp.from(now));
        return new Seed(user, otherUser, product, listing);
    }

    private static Map<String, List<String>> sourceRows(JdbcTemplate jdbc) {
        return Map.of(
                "product", jdbc.queryForList("SELECT to_jsonb(p)::text FROM product p ORDER BY id", String.class),
                "listing", jdbc.queryForList("SELECT to_jsonb(l)::text FROM listing l ORDER BY id", String.class),
                "price_point", jdbc.queryForList("SELECT to_jsonb(p)::text FROM price_point p ORDER BY id", String.class),
                "alert_log", jdbc.queryForList("SELECT to_jsonb(a)::text FROM alert_log a ORDER BY id", String.class));
    }

    private static ResearchReport research(Seed seed, Instant now, String verdict) {
        String sourceUrl = "https://manufacturer.example.invalid/phone/specs";
        var identity = new ResearchReport.Identity(seed.productId(), seed.listingId(), "Acme Phone 128GB", "Acme", "AX-100",
                "128GB", "https://www.amazon.in/dp/B012345678", Map.of("model", "AX-100", "storage", "128GB"), false, List.of());
        var citation = new ResearchReport.Citation("manufacturer", sourceUrl, "Battery capacity: 5,000 mAh");
        var claim = new ResearchReport.Claim("5,000 mAh battery", "battery", "manufacturer_spec", "HIGH", List.of(citation));
        var source = new ResearchReport.Source("manufacturer", "Acme official specifications", sourceUrl, "manufacturer", now,
                "2026-01-01", "Battery capacity: 5,000 mAh; quoted text is preserved.", "HTML");
        return new ResearchReport(identity, List.of(claim), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                verdict, List.of("Owner reports do not predict every device's experience"), List.of(source), now, now.plusSeconds(3600), false, 1);
    }
}
