package com.pricepilot.web;

import com.pricepilot.buying.BuyingPlanService;
import com.pricepilot.security.JwtAuthFilter;
import com.pricepilot.security.JwtService;
import com.pricepilot.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BuyingPlanController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, JwtService.class, ApiExceptionHandler.class})
class BuyingPlanControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean BuyingPlanService plans;
    @MockBean RateLimiter limiter;
    private static final String PATH = "/api/products/7/buying-plan";

    @Test void anonymousRequestsCannotReadOrMutatePlans() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"preferences\":{}}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(plans, limiter);
    }

    @Test void authenticatedIdentityIsPassedToOwnerScopedService() throws Exception {
        when(plans.get(1L, 7L)).thenThrow(ApiException.notYours("Product"));
        mvc.perform(get(PATH).header("Authorization", token())).andExpect(status().isNotFound());
        verify(plans).get(1L, 7L);
    }

    @Test void missingVersionAndEmptyConversationAreRejectedBeforeWork() throws Exception {
        mvc.perform(patch(PATH).header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferences\":{\"budget\":1000}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH + "/conversation").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(plans, limiter);
    }

    @Test void conversationIsRateLimitedBeforeModelInterpretation() throws Exception {
        mvc.perform(post(PATH + "/conversation").header("Authorization", token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"message\":\"My budget is 1000\"}"))
                .andExpect(status().isOk());
        var ordered = inOrder(limiter, plans);
        ordered.verify(limiter).check(eq("1"), eq("buying-plan-conversation"), eq(20), anyString());
        ordered.verify(plans).converse(eq(1L), eq(7L), any());
    }

    private String token() { return "Bearer " + jwt.issue(1L, "buyer@example.com", "USER"); }
}
