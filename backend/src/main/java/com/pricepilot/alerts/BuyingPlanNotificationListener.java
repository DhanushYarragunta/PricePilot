package com.pricepilot.alerts;

import com.pricepilot.buying.BuyingPlanNotificationEvent;
import com.pricepilot.buying.BuyingPlanStore;
import com.pricepilot.config.AppProperties;
import com.pricepilot.repo.AppUserRepository;
import com.pricepilot.repo.TrackerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Delivers committed plan notifications while the store owns claims, history and deduplication. */
@Component
public class BuyingPlanNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(BuyingPlanNotificationListener.class);
    private static final Set<String> RETRYABLE_PRE_SEND_FAILURES = Set.of(
            "mail_disabled", "mail_not_configured", "smtp_authentication_failed", "smtp_tls_failed",
            "invalid_email_address", "smtp_rejected");
    private final BuyingPlanStore store;
    private final AppUserRepository users;
    private final TrackerRepository trackers;
    private final AlertMailer mailer;
    private final AppProperties props;

    public BuyingPlanNotificationListener(BuyingPlanStore store, AppUserRepository users,
                                          TrackerRepository trackers, AlertMailer mailer, AppProperties props) {
        this.store = store;
        this.users = users;
        this.trackers = trackers;
        this.mailer = mailer;
        this.props = props;
    }

    public void onNotification(BuyingPlanNotificationEvent event) {
        if (event == null || event.notificationId() == null || event.userId() == null
                || event.productId() == null || event.planId() == null || !mailer.isEnabled()) return;
        boolean claimed = false;
        boolean sendAttempted = false;
        try {
            if (!store.claimNotification(event.notificationId())) return;
            claimed = true;
            var user = users.findById(event.userId()).orElse(null);
            var plan = store.find(event.userId(), event.productId()).orElse(null);
            boolean active = trackers.findByUserIdAndProductId(event.userId(), event.productId())
                    .map(tracker -> tracker.isActive()).orElse(false);
            if (user == null || plan == null || !event.planId().equals(plan.id()) || !active) {
                store.markNotificationUnconfirmed(event.notificationId(), "recipient_or_tracking_unavailable");
                return;
            }
            String base = props.getPublicUrl();
            String link = base == null || base.isBlank() ? null
                    : base.replaceAll("/+$", "") + "/products/" + event.productId() + "#buying-plan";
            sendAttempted = true;
            var result = mailer.sendPlanNotification(user.getEmail(), user.getDisplayName(), event.subject(), event.message(), link);
            if (result != null && result.accepted()) {
                store.markNotificationDelivered(event.notificationId());
            } else if (result != null && result.code() != null && RETRYABLE_PRE_SEND_FAILURES.contains(result.code())) {
                store.releaseNotification(event.notificationId());
            } else {
                // A timeout/disconnect may occur after SMTP accepts DATA. Never blindly resend.
                store.markNotificationUnconfirmed(event.notificationId(), "delivery_unconfirmed");
            }
        } catch (RuntimeException failure) {
            log.warn("Could not complete plan notification delivery for notification {}", event.notificationId());
            if (claimed) {
                try {
                    if (sendAttempted) store.markNotificationUnconfirmed(event.notificationId(), "delivery_unconfirmed");
                    else store.releaseNotification(event.notificationId());
                } catch (RuntimeException persistenceFailure) {
                    log.error("Could not persist plan notification delivery state for notification {}", event.notificationId());
                }
            }
        }
    }
}
