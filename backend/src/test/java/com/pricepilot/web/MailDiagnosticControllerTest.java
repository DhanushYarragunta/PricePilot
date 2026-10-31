package com.pricepilot.web;

import com.pricepilot.alerts.AlertMailer;
import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.repo.AppUserRepository;
import com.pricepilot.security.JwtAuthFilter;
import com.pricepilot.security.JwtService;
import com.pricepilot.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MailDiagnosticController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, ApiExceptionHandler.class, RateLimiter.class})
class MailDiagnosticControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AppProperties props;
    @MockBean AppUserRepository users;
    @MockBean AlertMailer mailer;
    private static final AtomicLong NEXT_ID = new AtomicLong(100);
    private AppUser user;

    @BeforeEach void setup() {
        user = new AppUser();
        user.setId(NEXT_ID.incrementAndGet());
        user.setEmail("current-account@example.com");
        user.setDisplayName("Current shopper");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(mailer.sendTestEmail(user.getEmail(), user.getDisplayName())).thenReturn(
                new AlertMailer.MailDeliveryResult(true, "smtp_accepted", "SMTP accepted the email."));
        props.getAlerts().setEnabled(true);
        props.getScheduler().setEnabled(false);
    }

    @Test void anonymousCallerCannotInspectOrSendMail() throws Exception {
        mvc.perform(get("/api/alerts/mail-status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/alerts/test-email")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users, mailer);
    }

    @Test void testUsesDatabaseAddressIgnoringTokenAndRequestRecipient() throws Exception {
        mvc.perform(post("/api/alerts/test-email").header("Authorization", token())
                        .param("recipient", "query-attacker@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"recipient\":\"body-attacker@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("accepted").value(true))
                .andExpect(jsonPath("code").value("smtp_accepted"));
        verify(users).findById(user.getId());
        verify(mailer).sendTestEmail("current-account@example.com", "Current shopper");
        verify(mailer, never()).sendTestEmail(eq("stale-token@example.com"), any());
    }

    @Test void missingAccountCannotSendEvenWithValidToken() throws Exception {
        when(users.findById(user.getId())).thenReturn(Optional.empty());
        mvc.perform(post("/api/alerts/test-email").header("Authorization", token())).andExpect(status().isUnauthorized());
        verifyNoInteractions(mailer);
    }

    @Test void thirdTestWithinHourIsBlockedBeforeSmtp() throws Exception {
        for (int count = 0; count < 2; count++) {
            mvc.perform(post("/api/alerts/test-email").header("Authorization", token())).andExpect(status().isOk());
        }
        mvc.perform(post("/api/alerts/test-email").header("Authorization", token()))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("message").value(
                        "You have used your two test emails for this hour. Please try again later."));
        verify(mailer, times(2)).sendTestEmail(user.getEmail(), user.getDisplayName());
    }

    @Test void statusExposesOnlyFlagsAndExplainsPausedScheduler() throws Exception {
        when(mailer.isConfigured()).thenReturn(true);
        mvc.perform(get("/api/alerts/mail-status").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(jsonPath("enabled").value(true))
                .andExpect(jsonPath("configured").value(true)).andExpect(jsonPath("schedulerEnabled").value(false))
                .andExpect(jsonPath("message").value(org.hamcrest.Matchers.containsString("Automatic price checks are paused")))
                .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("email").doesNotExist());
        verify(mailer, never()).sendTestEmail(any(), any());
    }

    @Test void sanitizedSmtpFailureIsReturnedWithoutClaimingSuccess() throws Exception {
        when(mailer.sendTestEmail(user.getEmail(), user.getDisplayName())).thenReturn(
                new AlertMailer.MailDeliveryResult(false, "smtp_authentication_failed", "SMTP authentication failed."));
        mvc.perform(post("/api/alerts/test-email").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(jsonPath("accepted").value(false))
                .andExpect(jsonPath("code").value("smtp_authentication_failed"));
    }

    private String token() { return "Bearer " + jwt.issue(user.getId(), "stale-token@example.com", "USER"); }
}
