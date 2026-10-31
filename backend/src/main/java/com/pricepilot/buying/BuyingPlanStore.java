package com.pricepilot.buying;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.pricepilot.buying.BuyingModels.*;

/** Short JDBC transactions atomically save a plan, its conversation, and deduplicated notification. */
@Repository
public class BuyingPlanStore {
    public record Stored(Long id, Long userId, Long productId, Long listingId, long version,
                         JsonNode preferences, JsonNode snapshot, Instant nextReassessmentAt,
                         Instant lastObservedAt, Instant updatedAt) { }
    public record Notice(String kind, String dedupKey, String subject, String message) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public BuyingPlanStore(JdbcTemplate jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.mapper = mapper; this.events = events;
    }

    public Optional<Stored> find(Long userId, Long productId) {
        return jdbc.query("SELECT * FROM buying_plan WHERE user_id=? AND product_id=?", row(), userId, productId).stream().findFirst();
    }

    @Transactional
    public Stored initialize(Long userId, Long productId, Preferences preferences) {
        jdbc.update("""
                INSERT INTO buying_plan(user_id,product_id,listing_id,preferences)
                VALUES (?,?,?,CAST(? AS jsonb)) ON CONFLICT(user_id,product_id) DO NOTHING
                """, userId, productId, preferences.listingId(), json(preferences));
        return find(userId, productId).orElseThrow();
    }

    @Transactional
    public Stored save(Stored previous, Preferences preferences, Snapshot snapshot,
                       String userMessage, String assistantMessage, Notice notice, Instant now) {
        int changed = jdbc.update("""
                UPDATE buying_plan SET listing_id=?,preferences=CAST(? AS jsonb),snapshot=CAST(? AS jsonb),
                  next_reassessment_at=?,last_observed_at=?,updated_at=?,version=version+1
                WHERE id=? AND user_id=? AND version=?
                """, preferences.listingId(), json(preferences), json(snapshot), stamp(snapshot.plan().reassessAt()),
                stamp(snapshot.observedAt()), stamp(now), previous.id(), previous.userId(), previous.version());
        if (changed != 1) throw ApiException.conflict("buying_plan_changed", "Your buying plan changed in another request. Reload it before applying this edit.");
        String constraints = constraintKey(preferences);
        if (!constraintKey(mapper.convertValue(previous.preferences(), Preferences.class)).equals(constraints)) {
            jdbc.update("UPDATE buying_plan_notification SET obsolete_at=? WHERE plan_id=? AND delivered_at IS NULL AND obsolete_at IS NULL",
                    stamp(now), previous.id());
        }
        String qualifyingKind = notice == null ? "" : notice.kind();
        jdbc.update("""
                UPDATE buying_plan_notification SET obsolete_at=? WHERE plan_id=? AND delivered_at IS NULL AND obsolete_at IS NULL
                  AND ((kind='TARGET_REACHED' AND ?<>'TARGET_REACHED') OR (kind='DEADLINE_OVER_BUDGET' AND ?<>'DEADLINE_OVER_BUDGET'))
                """, stamp(now), previous.id(), qualifyingKind, qualifyingKind);
        if (!previous.snapshot().path("plan").path("outcome").asText("").equals(snapshot.plan().outcome().name())) {
            jdbc.update("""
                    UPDATE buying_plan_notification SET obsolete_at=? WHERE plan_id=? AND delivered_at IS NULL
                      AND obsolete_at IS NULL AND kind='PLAN_CHANGED'
                    """, stamp(now), previous.id());
        }
        if (userMessage != null) conversation(previous.id(), "user", userMessage, now);
        if (assistantMessage != null) conversation(previous.id(), "assistant", assistantMessage, now);
        if (notice != null) {
            List<Long> ids = jdbc.query("""
                    INSERT INTO buying_plan_notification(plan_id,kind,dedup_key,payload,created_at,next_attempt_at)
                    VALUES (?,?,?,CAST(? AS jsonb),?,?) ON CONFLICT(plan_id,dedup_key) DO UPDATE
                      SET obsolete_at=NULL,payload=EXCLUDED.payload,created_at=EXCLUDED.created_at,
                          next_attempt_at=EXCLUDED.next_attempt_at,claimed_until=NULL,delivery_attempts=0
                      WHERE buying_plan_notification.obsolete_at IS NOT NULL AND buying_plan_notification.delivered_at IS NULL
                        AND buying_plan_notification.unconfirmed_at IS NULL
                    RETURNING id
                    """, (rs, index) -> rs.getLong(1), previous.id(), notice.kind(), notice.dedupKey() + ":" + constraints, json(notice), stamp(now), stamp(now));
            if (!ids.isEmpty()) {
                BuyingPlanNotificationEvent event = new BuyingPlanNotificationEvent(ids.get(0), previous.id(), previous.userId(),
                        previous.productId(), preferences.listingId(), notice.kind(), notice.subject(), notice.message(), now);
                afterCommit(() -> publish(event));
            }
        }
        return find(previous.userId(), previous.productId()).orElseThrow();
    }

    public List<Conversation> conversation(Long planId) {
        List<Conversation> turns = new ArrayList<>(jdbc.query("""
                SELECT id,role,content,created_at FROM buying_plan_conversation WHERE plan_id=? ORDER BY id DESC LIMIT 60
                """, (rs, index) -> new Conversation(rs.getLong("id"), rs.getString("role"), rs.getString("content"), instant(rs, "created_at")), planId));
        Collections.reverse(turns);
        return List.copyOf(turns);
    }

    public List<Notification> notifications(Long planId) {
        return jdbc.query("""
                SELECT id,kind,payload->>'message' AS message,created_at,delivered_at
                FROM buying_plan_notification WHERE plan_id=? AND obsolete_at IS NULL ORDER BY id DESC LIMIT 20
                """, (rs, index) -> new Notification(rs.getLong("id"), rs.getString("kind"), rs.getString("message"),
                instant(rs, "created_at"), instant(rs, "delivered_at")), planId);
    }

    public List<Stored> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT bp.* FROM buying_plan bp
                WHERE EXISTS(SELECT 1 FROM tracker t WHERE t.user_id=bp.user_id AND t.product_id=bp.product_id AND t.active=true)
                  AND (bp.next_reassessment_at IS NULL OR bp.next_reassessment_at<=?
                    OR EXISTS(SELECT 1 FROM listing l WHERE l.id=bp.listing_id AND l.status IS DISTINCT FROM (bp.snapshot->>'listingStatus'))
                    OR EXISTS(SELECT 1 FROM price_point pp WHERE pp.listing_id=bp.listing_id
                      AND pp.currency=COALESCE(bp.snapshot->>'currency','INR')
                      AND pp.captured_at>COALESCE(bp.last_observed_at,TIMESTAMPTZ '1970-01-01')))
                ORDER BY bp.next_reassessment_at NULLS FIRST,bp.id LIMIT ?
                """, row(), stamp(now), limit);
    }

    public void publishPending(Instant now, int limit) {
        List<BuyingPlanNotificationEvent> pending = jdbc.query("""
                SELECT n.id,n.plan_id,bp.user_id,bp.product_id,bp.listing_id,n.kind,n.payload,n.created_at
                FROM buying_plan_notification n JOIN buying_plan bp ON bp.id=n.plan_id
                WHERE n.delivered_at IS NULL AND n.unconfirmed_at IS NULL AND n.obsolete_at IS NULL AND n.delivery_attempts<3 AND n.next_attempt_at<=?
                  AND (n.claimed_until IS NULL OR n.claimed_until<=?)
                  AND EXISTS(SELECT 1 FROM tracker t WHERE t.user_id=bp.user_id AND t.product_id=bp.product_id AND t.active=true)
                ORDER BY n.id LIMIT ?
                """, (rs, index) -> {
                    JsonNode payload = parse(rs.getString("payload"));
                    return new BuyingPlanNotificationEvent(rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("user_id"),
                            rs.getLong("product_id"), rs.getObject("listing_id", Long.class), rs.getString("kind"),
                            payload.path("subject").asText(), payload.path("message").asText(), instant(rs, "created_at"));
                }, stamp(now), stamp(now), limit);
        pending.forEach(this::publish);
    }

    /** Prevents concurrent listeners from sending the same pending notification. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimNotification(Long notificationId) {
        Instant now = Instant.now();
        return jdbc.update("""
                UPDATE buying_plan_notification SET claimed_until=?,delivery_attempts=delivery_attempts+1
                WHERE id=? AND delivered_at IS NULL AND unconfirmed_at IS NULL AND obsolete_at IS NULL AND delivery_attempts<3
                  AND next_attempt_at<=? AND (claimed_until IS NULL OR claimed_until<=?)
                  AND EXISTS(SELECT 1 FROM buying_plan bp JOIN tracker t ON t.user_id=bp.user_id AND t.product_id=bp.product_id
                    JOIN listing l ON l.id=bp.listing_id
                    WHERE bp.id=buying_plan_notification.plan_id AND t.active=true AND l.status<>'DEAD'
                      AND NOT EXISTS(SELECT 1 FROM price_point pp WHERE pp.listing_id=bp.listing_id
                        AND pp.currency=COALESCE(bp.snapshot->>'currency','INR')
                        AND pp.captured_at>COALESCE(bp.last_observed_at,TIMESTAMPTZ '1970-01-01'))
                      AND bp.next_reassessment_at>? AND NOT COALESCE((bp.snapshot->'evidence'->>'stale')::boolean,true)
                      AND COALESCE((bp.snapshot->'evidence'->>'currentInStock')::boolean,false)
                      AND (buying_plan_notification.kind<>'TARGET_REACHED'
                        OR ((bp.snapshot->'evidence'->>'currentPrice')::numeric<=(bp.snapshot->'plan'->>'targetPrice')::numeric
                          AND (NOT COALESCE((bp.preferences->>'hardBudget')::boolean,true)
                            OR (bp.snapshot->'evidence'->>'currentPrice')::numeric<=(bp.preferences->>'budget')::numeric)))
                      AND (buying_plan_notification.kind<>'DEADLINE_OVER_BUDGET'
                        OR (bp.snapshot->'evidence'->>'currentPrice')::numeric>(bp.preferences->>'budget')::numeric))
                """, stamp(now.plus(Duration.ofMinutes(5))), notificationId, stamp(now), stamp(now), stamp(now)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseNotification(Long notificationId) {
        jdbc.update("UPDATE buying_plan_notification SET claimed_until=NULL,next_attempt_at=? WHERE id=? AND delivered_at IS NULL",
                stamp(Instant.now().plus(Duration.ofMinutes(15))), notificationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNotificationDelivered(Long notificationId) {
        jdbc.update("UPDATE buying_plan_notification SET delivered_at=?,claimed_until=NULL WHERE id=? AND delivered_at IS NULL",
                stamp(Instant.now()), notificationId);
    }

    /** SMTP acceptance may have happened before a timeout; never automatically resend that case. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markNotificationUnconfirmed(Long notificationId, String safeCode) {
        String code = safeCode != null && safeCode.matches("[a-z_]{1,80}") ? safeCode : "delivery_unconfirmed";
        jdbc.update("""
                UPDATE buying_plan_notification SET unconfirmed_at=?,delivery_error=?,claimed_until=NULL
                WHERE id=? AND delivered_at IS NULL
                """, stamp(Instant.now()), code, notificationId);
    }

    private void conversation(Long planId, String role, String content, Instant now) {
        jdbc.update("INSERT INTO buying_plan_conversation(plan_id,role,content,created_at) VALUES (?,?,?,?)", planId, role, content, stamp(now));
    }

    private RowMapper<Stored> row() {
        return (rs, index) -> new Stored(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("product_id"),
                rs.getObject("listing_id", Long.class), rs.getLong("version"), parse(rs.getString("preferences")), parse(rs.getString("snapshot")),
                instant(rs, "next_reassessment_at"), instant(rs, "last_observed_at"), instant(rs, "updated_at"));
    }

    private void publish(BuyingPlanNotificationEvent event) {
        // The durable row remains pending if no listener is installed or delivery fails.
        try { events.publishEvent(event); } catch (RuntimeException ignored) { }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); } catch (Exception ex) { throw new IllegalStateException("Stored buying-plan JSON is invalid", ex); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("Buying-plan data could not be encoded", ex); }
    }
    private static Timestamp stamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }

    private static String constraintKey(Preferences preferences) {
        String value = preferences.listingId() + "|" + normalized(preferences.budget()) + "|" + preferences.hardBudget()
                + "|" + preferences.deadlineDate() + "|" + preferences.deadlineKind() + "|" + preferences.earliestDate()
                + "|" + normalized(preferences.targetPrice()) + "|" + normalized(preferences.desiredSavingsPercent())
                + "|" + preferences.allowAlternatives() + "|" + preferences.urgency();
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String normalized(java.math.BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
}
