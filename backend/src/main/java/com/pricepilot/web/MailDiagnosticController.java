package com.pricepilot.web;

import com.pricepilot.alerts.AlertMailer;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.repo.AppUserRepository;
import com.pricepilot.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mail diagnostics for the current account; no recipient is accepted from request content. */
@RestController
@RequestMapping("/api/alerts")
public class MailDiagnosticController {
    private static final int TESTS_PER_HOUR = 2;
    private final AppUserRepository users;
    private final AlertMailer mailer;
    private final AppProperties props;
    private final RateLimiter limiter;

    public MailDiagnosticController(AppUserRepository users, AlertMailer mailer,
                                    AppProperties props, RateLimiter limiter) {
        this.users = users;
        this.mailer = mailer;
        this.props = props;
        this.limiter = limiter;
    }

    public record MailStatus(boolean enabled, boolean configured, boolean schedulerEnabled, String message) { }

    @GetMapping("/mail-status")
    public MailStatus status(@AuthenticationPrincipal AuthenticatedUser caller) {
        account(caller);
        boolean enabled = props.getAlerts().isEnabled();
        boolean configured = mailer.isConfigured();
        boolean scheduler = props.getScheduler().isEnabled();
        String message = !enabled ? "Email alerts are disabled on this server."
                : !configured ? "SMTP is not fully configured. A test email cannot be sent yet."
                : "SMTP is configured. Send a test email to check whether the server accepts delivery.";
        if (!scheduler) message += " Automatic price checks are paused; manual refresh can still trigger eligible alerts.";
        return new MailStatus(enabled, configured, scheduler, message);
    }

    @PostMapping("/test-email")
    public AlertMailer.MailDeliveryResult testEmail(@AuthenticationPrincipal AuthenticatedUser caller) {
        AppUser user = account(caller);
        limiter.check(String.valueOf(user.getId()), "test-email", TESTS_PER_HOUR,
                "You have used your two test emails for this hour. Please try again later.");
        return mailer.sendTestEmail(user.getEmail(), user.getDisplayName());
    }

    private AppUser account(AuthenticatedUser caller) {
        if (caller == null || caller.id() == null) throw ApiException.unauthorized("Sign in to check email delivery.");
        return users.findById(caller.id()).orElseThrow(() -> ApiException.unauthorized("Your account is no longer available. Sign in again."));
    }
}
