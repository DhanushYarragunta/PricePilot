package com.pricepilot.web;

import com.pricepilot.ai.DealAnalyzer;
import com.pricepilot.security.JwtAuthFilter;
import com.pricepilot.security.JwtService;
import com.pricepilot.security.SecurityConfig;
import com.pricepilot.service.AccountService;
import com.pricepilot.service.DashboardService;
import com.pricepilot.service.ManualRefreshJobs;
import com.pricepilot.service.ProductTrackingService;
import com.pricepilot.service.SiblingDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, ApiExceptionHandler.class})
class ManualRefreshControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean ProductTrackingService tracking;
    @MockBean DashboardService dashboard;
    @MockBean AccountService accounts;
    @MockBean DealAnalyzer analyzer;
    @MockBean SiblingDiscoveryService discovery;
    @MockBean RateLimiter limiter;
    @MockBean ManualRefreshJobs jobs;

    private static final String PATH = "/api/products/7/refresh";
    private static final String JOB = "f8c7f47a-8d7a-4c5d-a214-0e899c104c67";

    @BeforeEach void ignoreContextLifecycleIntrospection() { clearInvocations(jobs); }

    @Test void anonymousStartAndPollCannotReadOrStartJobs() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/" + JOB)).andExpect(status().isUnauthorized());
        verifyNoInteractions(tracking, jobs, limiter);
    }

    @Test void nonTrackerCannotStartOrPollEvenWhenJobIdIsKnown() throws Exception {
        doThrow(ApiException.notYours("Product")).when(tracking).requireTracking(1L, 7L);
        mvc.perform(post(PATH).header("Authorization", token(1))).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + JOB).header("Authorization", token(1))).andExpect(status().isNotFound());
        verifyNoInteractions(jobs, limiter);
    }

    @Test void startChecksTrackingAndRateLimitThenReturnsAcceptedWithoutCallingSynchronousRefresh() throws Exception {
        when(jobs.start(7L)).thenReturn(queued());
        when(limiter.remaining(eq(1L), anyString(), anyInt())).thenReturn(29);
        mvc.perform(post(PATH).header("Authorization", token(1)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("jobId").value(JOB))
                .andExpect(jsonPath("status").value("QUEUED"))
                .andExpect(jsonPath("terminal").value(false)).andExpect(jsonPath("total").value(2))
                .andExpect(jsonPath("checked").value(0)).andExpect(jsonPath("results").isArray())
                .andExpect(jsonPath("refreshRemaining").value(29));
        var order = inOrder(tracking, limiter, jobs);
        order.verify(tracking).requireTracking(1L, 7L);
        order.verify(limiter).check(eq(1L), eq("refresh"), anyInt());
        order.verify(jobs).start(7L);
        verify(tracking, never()).refreshNow(anyLong());
    }

    @Test void authorizedUsersCanJoinTheSameProductJob() throws Exception {
        when(jobs.start(7L)).thenReturn(queued());
        mvc.perform(post(PATH).header("Authorization", token(1)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("jobId").value(JOB));
        mvc.perform(post(PATH).header("Authorization", token(2)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("jobId").value(JOB));
        verify(tracking).requireTracking(1L, 7L);
        verify(tracking).requireTracking(2L, 7L);
    }

    @Test void statusRechecksTrackingWithoutSpendingAnotherRefreshAllowance() throws Exception {
        when(jobs.status(7L, JOB)).thenReturn(queued());
        mvc.perform(get(PATH + "/" + JOB).header("Authorization", token(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("QUEUED"));
        var order = inOrder(tracking, jobs);
        order.verify(tracking).requireTracking(1L, 7L);
        order.verify(jobs).status(7L, JOB);
        verify(limiter, never()).check(anyLong(), anyString(), anyInt());
        verify(jobs, never()).start(anyLong());
    }

    @Test void rateLimitRefusesWorkBeforeQueueAdmission() throws Exception {
        doThrow(ApiException.rateLimited("Refresh allowance exhausted.")).when(limiter).check(eq(1L), eq("refresh"), anyInt());
        mvc.perform(post(PATH).header("Authorization", token(1))).andExpect(status().isTooManyRequests());
        verifyNoInteractions(jobs);
    }

    @Test void unknownJobIsReportedTruthfullyWithoutAutomaticResubmission() throws Exception {
        when(jobs.status(7L, JOB)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "refresh_job_unknown",
                "The status expired or the server restarted. Reload saved prices."));
        mvc.perform(get(PATH + "/" + JOB).header("Authorization", token(1)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("refresh_job_unknown"));
        verify(jobs, never()).start(anyLong());
    }

    @Test void queueSaturationHasExplicitRetryableResponse() throws Exception {
        when(jobs.start(7L)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "refresh_queue_full",
                "No new check was started."));
        mvc.perform(post(PATH).header("Authorization", token(1)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("refresh_queue_full"));
    }

    private static ManualRefreshJobs.Snapshot queued() {
        return new ManualRefreshJobs.Snapshot(JOB, 7L, ManualRefreshJobs.Status.QUEUED, false,
                2, 0, 0, 0, 0, 0, List.of(), Instant.parse("2026-10-31T12:00:00Z"),
                null, null, null, "Your retailer refresh is queued.");
    }

    private String token(long id) { return "Bearer " + jwt.issue(id, "buyer" + id + "@example.com", "USER"); }
}
