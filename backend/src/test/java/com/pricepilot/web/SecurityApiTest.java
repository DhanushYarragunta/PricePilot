package com.pricepilot.web;

import com.pricepilot.config.AppProperties;
import com.pricepilot.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SecurityApiTest.Endpoints.class)
@Import({SecurityConfig.class,JwtAuthFilter.class,JwtService.class,ApiExceptionHandler.class,SecurityApiTest.Endpoints.class})
class SecurityApiTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @RestController static class Endpoints {
        @GetMapping("/api/admin/test") Map<String,String> admin() { return Map.of("ok","yes"); }
        @PostMapping(value="/api/auth/test-body",consumes=MediaType.APPLICATION_JSON_VALUE)
        Map<String,Object> body(@RequestBody Map<String,Object> value) { return value; }
    }
    @Test void validNonAdminGetsJson403WithoutErrorRedispatch() throws Exception {
        var result=mvc.perform(get("/api/admin/test").header("Authorization","Bearer "+jwt.issue(1L,"test@example.com","USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("forbidden")).andReturn();
        assertThat(result.getResponse().getErrorMessage()).isNull();
        assertThat(result.getResponse().getForwardedUrl()).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void adminSucceedsThenAnonymousIsNotAuthenticated() throws Exception {
        mvc.perform(get("/api/admin/test").header("Authorization","Bearer "+jwt.issue(1L,"test@example.com","ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/test")).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("unauthorized"));
    }
    @Test void invalidTokenGets401() throws Exception {
        mvc.perform(get("/api/admin/test").header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
    @Test void wrongOrMissingContentTypeReturns415() throws Exception {
        String token="Bearer "+jwt.issue(1L,"test@example.com","USER");
        mvc.perform(post("/api/auth/test-body").header("Authorization",token).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("code").value("unsupported_media_type"));
        mvc.perform(post("/api/auth/test-body").header("Authorization",token).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }
    @Test void malformedJsonStillReturns400() throws Exception {
        mvc.perform(post("/api/auth/test-body").header("Authorization","Bearer "+jwt.issue(1L,"test@example.com","USER"))
                .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
    }
}
