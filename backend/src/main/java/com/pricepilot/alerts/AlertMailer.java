package com.pricepilot.alerts;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AlertKind;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * disabled at startup and quietly does nothing thereafter.
 */
@Component
public class AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(AlertMailer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AlertWriter writer;
    private final AppProperties props;
    private final boolean enabled;

    public AlertMailer(ObjectProvider<JavaMailSender> mailSender,
                       AlertWriter writer,
                       AppProperties props) {
        this.mailSender = mailSender;
        this.writer = writer;
        this.props = props;
        this.enabled = props.getAlerts().isEnabled();

        if (!enabled) {
            log.info("Email alerts are off (MAIL_ENABLED=false). Alerts are still recorded and "
                     + "shown in the app's alert feed.");
        }
    }

    public boolean isEnabled() {
        return enabled && mailSender.getIfAvailable() != null;
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
        if (!isEnabled()) {
            // Not an error, and not worth a row-level delivery_error either — the user chose not to
            // configure email. The alerts remain in the feed.
            log.debug("{} alert(s) recorded; email delivery is disabled", pending.size());
            return;
        }

        JavaMailSender sender = mailSender.getIfAvailable();
        for (PendingAlert alert : pending) {
            if (!alert.hasEmail()) {
                writer.markFailed(alert.alertLogId(), "No email address on the account");
                continue;
            }
            try {
                sender.send(build(sender, alert));
                writer.markDelivered(alert.alertLogId());
                log.info("Delivered {} alert for product {} to {}",
                        alert.kind(), alert.productId(), mask(alert.email()));
            } catch (Exception e) {
                // Deliberately broad. JavaMail throws a wide and poorly-documented range of
                // exceptions, and any of them means exactly one thing here: this email did not go
                // out. Narrowing the catch would only let an unanticipated type escape onto a
                // background thread where it would kill the rest of the batch.
                log.error("Could not email the {} alert for product {} to {}: {}",
                        alert.kind(), alert.productId(), mask(alert.email()), e.toString());
                writer.markFailed(alert.alertLogId(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private MimeMessage build(JavaMailSender sender, PendingAlert alert) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper =
                new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(props.getAlerts().getFrom());
        helper.setTo(alert.email());
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
