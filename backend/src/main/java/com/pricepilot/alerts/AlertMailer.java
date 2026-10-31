package com.pricepilot.alerts;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AlertKind;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.AddressException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Sends alert emails, on a background thread, and never breaks anything by failing.
 *
 * <p>Email here is a convenience on top of the in-app feed, not the system of record. By the time
 * this class runs, the alert is already stored and already visible in the app; all that is at stake
 * is whether a message also lands in an inbox. So every failure path ends in a logged error and a
 * {@code delivery_error} on the row, never in an exception that could propagate back into the
 * scrape pipeline and cost the observation that triggered it.
 *
 * <p>{@link Async} because SMTP is slow and sometimes very slow. A manual refresh from the web UI
 * goes through the same alert path as a scheduled check, and a user pressing "refresh now" should
 * not wait ten seconds for a Gmail handshake before seeing their price. It is a method on a separate
 * bean from its caller for the usual proxy reason: {@code @Async} on a method invoked from within
 * the same object does nothing at all.
 *
 * <p>Entirely optional. With {@code MAIL_ENABLED=false} or no SMTP credentials this reports itself
 * disabled at startup. Undelivered alerts retain a safe explanation in the alert feed.
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AlertWriter writer;
    private final AppProperties props;
    private final MailProperties smtp;
    private final boolean enabled;

    /** Accepted means the SMTP server accepted the message, not confirmed inbox delivery. */
    public record MailDeliveryResult(boolean accepted, String code, String message) { }

    public AlertMailer(ObjectProvider<JavaMailSender> mailSender,
                       AlertWriter writer,
                       AppProperties props,
                       MailProperties smtp) {
        this.mailSender = mailSender;
        this.writer = writer;
        this.props = props;
        this.smtp = smtp;
        this.enabled = props.getAlerts().isEnabled();

        if (!enabled) {
            log.info("Email alerts are off (MAIL_ENABLED=false). Alerts are still recorded and "
                     + "shown in the app's alert feed.");
        }
    }

    public boolean isEnabled() {
        return enabled && isConfigured();
    }

    public boolean isConfigured() {
        return hasText(smtp.getHost()) && hasText(smtp.getUsername())
                && hasText(smtp.getPassword()) && hasText(props.getAlerts().getFrom())
                && mailSender.getIfAvailable() != null;
    }

    /** Called synchronously by an authenticated endpoint with the signed-in account's address. */
    public MailDeliveryResult sendTestEmail(String recipient, String greeting) {
        return sendPlainEmail(recipient, "[PricePilot test] Email delivery check",
                "Hi " + greeting(greeting) + ",\n\nThis is the email delivery test you requested from PricePilot.\n"
                        + "Your signed-in account receives alerts from the configured sender.\n"
                        + "No price alert or price-history entry was created by this test.\n");
    }

    /**
     * Sends a plan event after its owner and notification preferences are checked by the caller.
     * The caller owns plan-event persistence and deduplication; this does not write price alerts.
     */
    public MailDeliveryResult sendPlanNotification(String recipient, String greeting, String planTitle,
                                                   String summary, String planUrl) {
        String title = shorten(planTitle == null ? "Your buying plan" : planTitle, 100).replace('\r', ' ').replace('\n', ' ');
        String body = "Hi " + greeting(greeting) + ",\n\n"
                + shorten(summary == null ? "Your buying plan was updated." : summary, 8000);
        if (planUrl != null && !planUrl.isBlank()) {
            try {
                java.net.URI url = java.net.URI.create(planUrl);
                if (url.getHost() != null && url.getUserInfo() == null
                        && ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))) {
                    body += "\n\nView your buying plan: " + planUrl;
                }
            } catch (IllegalArgumentException ignored) { /* Omit invalid links. */ }
        }
        return sendPlainEmail(recipient, "[PricePilot] Buying plan update: " + title, body + "\n");
    }

    private static String greeting(String value) {
        return shorten(value == null || value.isBlank() ? "there" : value, 80);
    }

    private MailDeliveryResult sendPlainEmail(String recipient, String subject, String body) {
        try {
            MailDeliveryResult unavailable = unavailable();
            if (unavailable != null) return unavailable;
            JavaMailSender sender = mailSender.getIfAvailable();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(singleAddress(props.getAlerts().getFrom()));
            helper.setTo(singleAddress(recipient));
            helper.setSubject(subject);
            helper.setText(body);
            sender.send(message);
            return new MailDeliveryResult(true, "smtp_accepted", "SMTP accepted the email. Check the inbox and spam folder.");
        } catch (Exception failure) {
            return classifyFailure(failure);
        }
    }

    private MailDeliveryResult unavailable() {
        if (!enabled) return new MailDeliveryResult(false, "mail_disabled", "Email delivery is disabled by MAIL_ENABLED.");
        if (!isEnabled()) return new MailDeliveryResult(false, "mail_not_configured",
                "Email delivery is not configured. Check MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM.");
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Delivers a batch of already-recorded alerts.
     *
     * <p>Returns {@code void} rather than a future: nothing waits on the outcome, and handing back a
     * future nobody inspects is how delivery failures become invisible. The outcome is recorded on
     * the alert row instead, where the ops page can see it.
     */
    @Async
    public void deliver(List<PendingAlert> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        MailDeliveryResult unavailable = unavailable();
        if (unavailable != null) {
            pending.stream().filter(java.util.Objects::nonNull)
                    .forEach(alert -> recordFailure(alert.alertLogId(), unavailable.message()));
            return;
        }

        JavaMailSender sender = mailSender.getIfAvailable();
        Set<Long> processed = new HashSet<>();
        for (PendingAlert alert : pending) {
            if (alert == null || !processed.add(alert.alertLogId())) continue;
            if (!alert.hasEmail()) {
                recordFailure(alert.alertLogId(), "No email address on the account.");
                continue;
            }
            try {
                sender.send(build(sender, alert));
            } catch (Exception e) {
                // Deliberately broad. JavaMail throws a wide and poorly-documented range of
                // exceptions, and any of them means exactly one thing here: this email did not go
                // out. Narrowing the catch would only let an unanticipated type escape onto a
                // background thread where it would kill the rest of the batch.
                log.error("Could not email the {} alert for product {} to {} ({})",
                        alert.kind(), alert.productId(), mask(alert.email()), e.getClass().getSimpleName());
                recordFailure(alert.alertLogId(), classifyFailure(e).message());
                continue;
            }
            try {
                writer.markDelivered(alert.alertLogId());
            } catch (RuntimeException e) {
                // SMTP has already accepted the message. Do not mislabel it as a send failure or resend.
                log.error("SMTP accepted alert {}; delivery-state persistence failed", alert.alertLogId());
            }
            log.info("SMTP accepted {} alert for product {} to {}",
                    alert.kind(), alert.productId(), mask(alert.email()));
        }
    }

    private void recordFailure(Long id, String message) {
        try { writer.markFailed(id, message); }
        catch (RuntimeException e) { log.error("Could not persist email delivery failure for alert {}", id); }
    }

    static MailDeliveryResult classifyFailure(Throwable failure) {
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        boolean timeout = false, tls = false, address = false, rejected = false, connection = false;
        boolean authentication = false, possiblyAccepted = false;
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable next = pending.remove();
            if (!seen.add(next)) continue;
            if (next instanceof MailAuthenticationException || next instanceof AuthenticationFailedException) {
                authentication = true;
            }
            timeout |= next instanceof java.net.SocketTimeoutException;
            // A generic SSLException can happen after DATA was accepted; only a handshake failure
            // is safely classified as occurring before the message was submitted.
            tls |= next instanceof javax.net.ssl.SSLHandshakeException;
            address |= next instanceof AddressException;
            rejected |= next instanceof SendFailedException;
            if (next instanceof SendFailedException send && send.getValidSentAddresses() != null
                    && send.getValidSentAddresses().length > 0) possiblyAccepted = true;
            connection |= next instanceof java.net.ConnectException || next instanceof java.net.UnknownHostException;
            if (next.getCause() != null) pending.add(next.getCause());
            if (next instanceof MessagingException mail && mail.getNextException() != null) pending.add(mail.getNextException());
            if (next instanceof MailSendException mail) {
                for (Exception nested : mail.getMessageExceptions()) if (nested != null) pending.add(nested);
            }
        }
        if (possiblyAccepted) return new MailDeliveryResult(false, "smtp_delivery_unconfirmed",
                "SMTP may have accepted the email. Delivery is unconfirmed; no automatic resend was attempted.");
        if (authentication) return new MailDeliveryResult(false, "smtp_authentication_failed",
                "SMTP authentication failed. Check MAIL_USERNAME and MAIL_PASSWORD; Gmail requires an App Password.");
        if (tls) return new MailDeliveryResult(false, "smtp_tls_failed", "SMTP TLS negotiation failed. Check the SMTP host and STARTTLS configuration.");
        if (timeout) return new MailDeliveryResult(false, "smtp_timeout", "SMTP timed out. Delivery is unconfirmed; no automatic resend was attempted.");
        if (address) return new MailDeliveryResult(false, "invalid_email_address", "The sender or recipient email address is invalid.");
        if (rejected) return new MailDeliveryResult(false, "smtp_rejected", "SMTP rejected the email. Check the sender and recipient settings.");
        if (connection) return new MailDeliveryResult(false, "smtp_connection_failed", "Could not connect to SMTP. Check MAIL_HOST, MAIL_PORT and network access.");
        return new MailDeliveryResult(false, "smtp_send_failed", "Email delivery failed. Check SMTP configuration and provider availability.");
    }

    private static InternetAddress singleAddress(String value) throws AddressException {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new AddressException();
        InternetAddress[] addresses = InternetAddress.parse(value, true);
        if (addresses.length != 1 || addresses[0].isGroup()) throw new AddressException();
        addresses[0].validate();
        return addresses[0];
    }

    private MimeMessage build(JavaMailSender sender, PendingAlert alert) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper =
                new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(singleAddress(props.getAlerts().getFrom()));
        helper.setTo(singleAddress(alert.email()));
        helper.setSubject(subject(alert));
        helper.setText(plainBody(alert), htmlBody(alert));
        return message;
    }

    /**
     * Subject lines lead with the price, because that is the only thing the recipient needs from
     * the notification list view — most alert emails are never opened, and they should not have to
     * be.
     */
    private String subject(PendingAlert alert) {
        String price = Money.inr(alert.price());
        String name = shorten(alert.productTitle(), 60);
        return switch (alert.kind()) {
            case TARGET_HIT -> price + " — your target was hit: " + name;
            case ALL_TIME_LOW -> price + " — lowest ever: " + name;
            case BACK_IN_STOCK -> "Back in stock at " + price + ": " + name;
            case DEAL_DETECTED -> price + " — price drop: " + name;
        };
    }

    private String plainBody(PendingAlert alert) {
        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(alert.greetingName()).append(",\n\n");
        body.append(alert.headline()).append("\n\n");
        body.append("Now:        ").append(Money.inr(alert.price())).append('\n');
        if (alert.previousPrice() != null) {
            body.append("Previously: ").append(Money.inr(alert.previousPrice())).append('\n');
        }
        if (alert.targetPrice() != null) {
            body.append("Your target: ").append(Money.inr(alert.targetPrice())).append('\n');
        }
        if (alert.retailer() != null) {
            body.append("Retailer:   ").append(Money.retailerName(alert.retailer())).append('\n');
        }
        body.append("\nSee the full price history: ").append(productLink(alert)).append("\n\n");
        body.append("This price was read from the retailer's own product page at the time of the "
                    + "check. Prices change quickly — confirm on the site before buying.\n");
        return body.toString();
    }

    /**
     * A deliberately plain HTML email. Table-based layout with inline styles, no external CSS and no
     * images, because mail clients strip stylesheets and block remote content by default — a design
     * that depends on either arrives looking broken.
     */
    private String htmlBody(PendingAlert alert) {
        String accent = alert.kind() == AlertKind.ALL_TIME_LOW ? "#047857" : "#1d4ed8";
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;")
            .append("max-width:560px;margin:0 auto;color:#111827;line-height:1.5\">");
        html.append("<p style=\"margin:0 0 16px\">Hi ").append(escape(alert.greetingName())).append(",</p>");
        html.append("<p style=\"margin:0 0 20px;font-size:16px\">")
            .append(escape(alert.headline())).append("</p>");

        html.append("<div style=\"border:1px solid #e5e7eb;border-radius:10px;padding:18px;margin-bottom:20px\">");
        html.append("<div style=\"font-size:30px;font-weight:650;color:").append(accent).append("\">")
            .append(escape(Money.inr(alert.price()))).append("</div>");
        if (alert.previousPrice() != null) {
            html.append("<div style=\"margin-top:4px;color:#6b7280\">was <s>")
                .append(escape(Money.inr(alert.previousPrice()))).append("</s>");
            String saved = Money.savings(alert.previousPrice(), alert.price());
            if (saved != null) {
                html.append(" &middot; you save ").append(escape(saved));
            }
            html.append("</div>");
        }
        if (alert.retailer() != null) {
            html.append("<div style=\"margin-top:8px;color:#6b7280;font-size:14px\">at ")
                .append(escape(Money.retailerName(alert.retailer()))).append("</div>");
        }
        html.append("</div>");

        html.append("<p style=\"margin:0 0 24px\"><a href=\"").append(escape(productLink(alert)))
            .append("\" style=\"background:").append(accent)
            .append(";color:#ffffff;padding:11px 20px;border-radius:8px;text-decoration:none;")
            .append("display:inline-block;font-weight:600\">View price history</a></p>");

        html.append("<p style=\"margin:0;color:#9ca3af;font-size:12px\">This price was read from the ")
            .append("retailer's own product page at the time of the check. Prices change quickly &mdash; ")
            .append("confirm on the site before buying.</p>");
        html.append("</div>");
        return html.toString();
    }

    private String productLink(PendingAlert alert) {
        String base = props.getPublicUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/products/" + alert.productId();
    }

    /** Product titles come from scraped pages, so they are untrusted input in an HTML document. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String shorten(String value, int limit) {
        if (value == null) {
            return "your tracked product";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit - 1) + "…";
    }

    /** Keeps whole addresses out of the logs while leaving them recognisable for support. */
    private static String mask(String email) {
        if (email == null || !email.contains("@")) {
            return "(no address)";
        }
        int at = email.indexOf('@');
        String name = email.substring(0, at);
        String visible = name.length() <= 2 ? name : name.substring(0, 2);
        return visible + "***" + email.substring(at);
    }
}
