package com.pricepilot.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricepilot.buying.BuyingPlanNotificationEvent;
import com.pricepilot.buying.BuyingPlanStore;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.domain.Tracker;
import com.pricepilot.repo.AppUserRepository;
import com.pricepilot.repo.TrackerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;

class BuyingPlanNotificationListenerTest {
    private final BuyingPlanStore store = mock(BuyingPlanStore.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final TrackerRepository trackers = mock(TrackerRepository.class);
    private final AlertMailer mailer = mock(AlertMailer.class);
    private final AppProperties props = new AppProperties();
    private final BuyingPlanNotificationListener listener = new BuyingPlanNotificationListener(store, users, trackers, mailer, props);
    private final BuyingPlanNotificationEvent event = new BuyingPlanNotificationEvent(1L, 2L, 3L, 4L, 5L,
            "TARGET_HIT", "Budget reached", "Verified source offer reached the target.", Instant.now());

    @BeforeEach void setup() {
        when(mailer.isEnabled()).thenReturn(true);
        when(store.claimNotification(1L)).thenReturn(true);
        var user = new AppUser();
        user.setId(3L); user.setEmail("owner@example.com"); user.setDisplayName("Owner");
        when(users.findById(3L)).thenReturn(Optional.of(user));
        var tracker = new Tracker(); tracker.setActive(true);
        when(trackers.findByUserIdAndProductId(3L, 4L)).thenReturn(Optional.of(tracker));
        var empty = new ObjectMapper().createObjectNode();
        when(store.find(3L, 4L)).thenReturn(Optional.of(new BuyingPlanStore.Stored(2L, 3L, 4L, 5L, 1L,
                empty, empty, Instant.now(), Instant.now(), Instant.now())));
        when(mailer.sendPlanNotification(any(), any(), any(), any(), any())).thenReturn(
                new AlertMailer.MailDeliveryResult(true, "smtp_accepted", "SMTP accepted the email."));
    }

    @Test void acceptedDeliveryUsesCurrentOwnerEmailAndAcknowledgesPersistedNotification() {
        listener.onNotification(event);
        var ordered = inOrder(store, users, mailer);
        ordered.verify(store).claimNotification(1L);
        ordered.verify(users).findById(3L);
        ordered.verify(mailer).sendPlanNotification("owner@example.com", "Owner", "Budget reached",
                "Verified source offer reached the target.", "http://localhost:5173/products/4#buying-plan");
        ordered.verify(store).markNotificationDelivered(1L);
        verify(store, never()).releaseNotification(any());
    }

    @Test void duplicateOrAlreadyDeliveredEventCannotSendAgain() {
        when(store.claimNotification(1L)).thenReturn(false);
        listener.onNotification(event);
        verify(mailer, never()).sendPlanNotification(any(), any(), any(), any(), any());
        verifyNoInteractions(users, trackers);
    }

    @Test void disabledMailDoesNotConsumeDeliveryAttempts() {
        when(mailer.isEnabled()).thenReturn(false);
        listener.onNotification(event);
        verifyNoInteractions(store, users, trackers);
    }

    @Test void inactiveTrackingCannotReceiveAnOldQueuedEmail() {
        when(trackers.findByUserIdAndProductId(3L, 4L)).thenReturn(Optional.empty());
        listener.onNotification(event);
        verify(mailer, never()).sendPlanNotification(any(), any(), any(), any(), any());
        verify(store).markNotificationUnconfirmed(1L, "recipient_or_tracking_unavailable");
    }

    @Test void knownAuthenticationFailureReleasesForBoundedRetry() {
        when(mailer.sendPlanNotification(any(), any(), any(), any(), any())).thenReturn(
                new AlertMailer.MailDeliveryResult(false, "smtp_authentication_failed", "SMTP authentication failed."));
        listener.onNotification(event);
        verify(store).releaseNotification(1L);
        verify(store, never()).markNotificationDelivered(any());
    }

    @Test void ambiguousTimeoutIsRetainedWithoutAutomaticResend() {
        when(mailer.sendPlanNotification(any(), any(), any(), any(), any())).thenReturn(
                new AlertMailer.MailDeliveryResult(false, "smtp_timeout", "Delivery unconfirmed."));
        listener.onNotification(event);
        verify(store).markNotificationUnconfirmed(1L, "delivery_unconfirmed");
        verify(store, never()).releaseNotification(any());
        verify(store, never()).markNotificationDelivered(any());
    }

    @Test void persistenceFailureAfterSmtpAcceptanceNeverReleasesForRetry() {
        doThrow(new IllegalStateException("private database details")).when(store).markNotificationDelivered(1L);
        listener.onNotification(event);
        verify(mailer).sendPlanNotification(any(), any(), any(), any(), any());
        verify(store).markNotificationUnconfirmed(1L, "delivery_unconfirmed");
        verify(store, never()).releaseNotification(any());
    }
}
