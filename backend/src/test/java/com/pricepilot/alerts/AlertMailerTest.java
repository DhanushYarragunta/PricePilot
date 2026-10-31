package com.pricepilot.alerts;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AlertKind;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import jakarta.mail.MessagingException;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AlertMailerTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final AlertWriter writer = mock(AlertWriter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private final AppProperties props = new AppProperties();
    private final MailProperties smtp = new MailProperties();

    @BeforeEach
    void setUp() {
        props.getAlerts().setEnabled(true);
        props.getAlerts().setFrom("PricePilot <sender@gmail.com>");
        smtp.setHost("smtp.gmail.com");
        smtp.setUsername("sender@gmail.com");
        smtp.setPassword("test-app-password");
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(call ->
                new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void sendsEachAlertToItsAccountEmailAndUsesConfiguredSender() throws Exception {
        AlertMailer mailer = new AlertMailer(provider, writer, props, smtp);
        mailer.deliver(List.of(alert(1L, "first@example.com"), alert(2L, "second@example.com")));

        ArgumentCaptor<MimeMessage> messages = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("first@example.com");
        assertThat(messages.getAllValues().get(1).getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("second@example.com");
        assertThat(messages.getValue().getFrom()[0].toString()).isEqualTo("PricePilot <sender@gmail.com>");
        verify(writer).markDelivered(1L);
        verify(writer).markDelivered(2L);
    }

    @Test
    void missingSmtpPasswordDoesNotAdvertiseEmailAsEnabled() {
        smtp.setPassword(" ");
        AlertMailer mailer = new AlertMailer(provider, writer, props, smtp);
        assertThat(mailer.isEnabled()).isFalse();
        mailer.deliver(List.of(alert(1L, "first@example.com")));
        verify(sender, never()).send(any(MimeMessage.class));
        verify(writer).markFailed(eq(1L), contains("not configured"));
    }

    @Test
    void disabledSettingPreventsMailEvenWithCredentials() {
        props.getAlerts().setEnabled(false);
        AlertMailer mailer = new AlertMailer(provider, writer, props, smtp);
        assertThat(mailer.isEnabled()).isFalse();
        mailer.deliver(List.of(alert(1L, "first@example.com")));
        verify(sender, never()).send(any(MimeMessage.class));
        verify(writer).markFailed(eq(1L), contains("MAIL_ENABLED"));
    }

    @Test
    void failedDeliveryIsRecordedWithoutExposingProviderErrorText() {
        doThrow(new MailAuthenticationException("secret-provider-detail"))
                .when(sender).send(any(MimeMessage.class));
        new AlertMailer(provider, writer, props, smtp)
                .deliver(List.of(alert(1L, "first@example.com")));
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(writer).markFailed(eq(1L), error.capture());
        assertThat(error.getValue()).contains("SMTP").doesNotContain("secret-provider-detail");
        verify(writer, never()).markDelivered(any());
    }

    @Test
    void synchronousTestEmailUsesRequestedAccountWithoutCreatingPriceAlerts() throws Exception {
        var result = new AlertMailer(provider, writer, props, smtp).sendTestEmail("signed-in@example.com", "Shopper");
        assertThat(result.accepted()).isTrue();
        assertThat(result.code()).isEqualTo("smtp_accepted");
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getSubject()).isEqualTo("[PricePilot test] Email delivery check");
        assertThat(message.getValue().getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("signed-in@example.com");
        assertThat(message.getValue().getContent().toString()).contains("No price alert or price-history entry");
        verifyNoInteractions(writer);
    }

    @Test
    void synchronousFailureIsSanitizedAndNeverRetried() {
        doThrow(new MailAuthenticationException("PASSWORD secret SMTP transcript")).when(sender).send(any(MimeMessage.class));
        var result = new AlertMailer(provider, writer, props, smtp).sendTestEmail("signed-in@example.com", "Shopper");
        assertThat(result.accepted()).isFalse();
        assertThat(result.code()).isEqualTo("smtp_authentication_failed");
        assertThat(result.message()).contains("App Password").doesNotContain("secret", "transcript", "test-app-password");
        verify(sender).send(any(MimeMessage.class));
        verifyNoInteractions(writer);
    }

    @Test
    void planMessageUsesOwnerAddressAndDoesNotWritePriceAlertHistory() throws Exception {
        var result = new AlertMailer(provider, writer, props, smtp).sendPlanNotification(
                "owner@example.com", "Shopper", "Phone plan", "A verified offer reached your budget.",
                "http://localhost:5173/buying-plans/7");
        assertThat(result.accepted()).isTrue();
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("owner@example.com");
        assertThat(message.getValue().getSubject()).isEqualTo("[PricePilot] Buying plan update: Phone plan");
        assertThat(message.getValue().getContent().toString()).contains("verified offer", "/buying-plans/7");
        verifyNoInteractions(writer);
    }

    @Test
    void testEmailRejectsMultipleRecipientsAndHeaderInjection() {
        var mailer = new AlertMailer(provider, writer, props, smtp);
        assertThat(mailer.sendTestEmail("first@example.com, second@example.com", "Shopper").code()).isEqualTo("invalid_email_address");
        assertThat(mailer.sendTestEmail("first@example.com\r\nBcc: other@example.com", "Shopper").code()).isEqualTo("invalid_email_address");
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void duplicateIdsInOneBatchAreSentOnlyOnce() {
        PendingAlert same = alert(1L, "first@example.com");
        new AlertMailer(provider, writer, props, smtp).deliver(List.of(same, same));
        verify(sender).send(any(MimeMessage.class));
        verify(writer).markDelivered(1L);
    }

    @Test
    void stateWriteFailureAfterSmtpAcceptanceDoesNotResendOrMarkSmtpFailed() {
        doThrow(new IllegalStateException("private database detail")).when(writer).markDelivered(1L);
        new AlertMailer(provider, writer, props, smtp).deliver(List.of(alert(1L, "first@example.com"), alert(2L, "second@example.com")));
        verify(sender, times(2)).send(any(MimeMessage.class));
        verify(writer, never()).markFailed(any(), any());
        verify(writer).markDelivered(2L);
    }

    @Test
    void failedStateWriteDoesNotAbandonRestOfBatch() {
        doThrow(new MailAuthenticationException("private SMTP detail")).when(sender).send(any(MimeMessage.class));
        doThrow(new IllegalStateException("private database detail")).when(writer).markFailed(eq(1L), any());
        new AlertMailer(provider, writer, props, smtp).deliver(List.of(alert(1L, "first@example.com"), alert(2L, "second@example.com")));
        verify(sender, times(2)).send(any(MimeMessage.class));
        verify(writer).markFailed(eq(2L), contains("SMTP authentication failed"));
    }

    @Test
    void nestedSmtpFailuresHaveFixedCategories() {
        var timeout = new MailSendException("private provider detail", new MessagingException("hidden", new java.net.SocketTimeoutException("secret")));
        assertThat(AlertMailer.classifyFailure(timeout).code()).isEqualTo("smtp_timeout");
        assertThat(AlertMailer.classifyFailure(new javax.net.ssl.SSLHandshakeException("secret")).code()).isEqualTo("smtp_tls_failed");
        assertThat(AlertMailer.classifyFailure(new javax.net.ssl.SSLException("secret")).code()).isEqualTo("smtp_send_failed");
        assertThat(AlertMailer.classifyFailure(new java.net.ConnectException("secret")).code()).isEqualTo("smtp_connection_failed");
    }

    @Test
    void partiallyAcceptedSmtpExceptionIsNeverClassifiedAsSafeToRetry() throws Exception {
        var accepted = new jakarta.mail.Address[]{new jakarta.mail.internet.InternetAddress("owner@example.com")};
        var error = new jakarta.mail.SendFailedException("private response", null, accepted, null, null);
        assertThat(AlertMailer.classifyFailure(error).code()).isEqualTo("smtp_delivery_unconfirmed");
    }

    private PendingAlert alert(Long id, String recipient) {
        return new PendingAlert(id, recipient, "Shopper", AlertKind.TARGET_HIT,
                10L, "USB C hub", "amazon.in", new BigDecimal("1500"),
                new BigDecimal("1800"), new BigDecimal("1600"), "Your target price was reached.");
    }
}
