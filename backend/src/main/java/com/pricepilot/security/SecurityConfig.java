package com.pricepilot.security;

import com.pricepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Who may call what.
 *
 * <p>The whole authorisation model is the one {@code authorizeHttpRequests} block below, read top to
 * bottom, ending in {@code anyRequest().authenticated()}. That default-deny last line is the
 * important one: a controller added later is protected before anyone remembers to think about it,
 * and making an endpoint public is a visible, deliberate edit to this file rather than an omission
 * somewhere else.
 *
 * <h2>Why sessions and CSRF are off</h2>
 * Both are disabled because this is a token-authenticated API with a separate frontend, not a
 * server-rendered form application. CSRF attacks work by riding a cookie the browser attaches
 * automatically; a bearer token in an {@code Authorization} header is never attached automatically,
 * so there is nothing to ride. Leaving CSRF on would break every write request while protecting
 * against nothing. The corollary is a rule the frontend must keep: the token lives in memory, never
 * in a cookie — storing it in a cookie would reintroduce exactly the attack this reasoning assumes
 * away.
 *
 * <h2>Why CORS is explicit</h2>
 * The browser enforces the same-origin policy, and in production the React app is served from the
 * same nginx that proxies this API, so no cross-origin request happens at all. The allowed-origin
 * list exists for development, where Vite runs on port 5173 and Spring on 8080. It is configured
 * rather than wildcarded because a wildcard here, combined with a future decision to put the token
 * in a cookie, is a credential-leak waiting to happen.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthFilter jwtFilter;
    private final AppProperties props;

    public SecurityConfig(JwtAuthFilter jwtFilter, AppProperties props) {
        this.jwtFilter = jwtFilter;
        this.props = props;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // --- public: the frontend needs these before anyone has a token ---
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // The ops page is the project's shop window. A recruiter opening the link
                        // should see live reliability figures without being asked to sign up, which
                        // is the entire point of measuring them. It exposes aggregates only — no
                        // URLs, no email addresses, no per-user data.
                        .requestMatchers(HttpMethod.GET, "/api/metrics/**").permitAll()
                        // What this deployment can do. Read before anyone has a token, because the
                        // sign-up form needs to know whether an invite code is required and the
                        // landing page needs the list of supported retailers. Configuration flags
                        // and display names only.
                        .requestMatchers(HttpMethod.GET, "/api/meta/**").permitAll()
                        // Load balancer and uptime-monitor probes.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // CORS preflight carries no credentials and must never be challenged.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // --- admin: operational detail that would help someone attack the scraper
                        //     or run up the API bill ---
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        .anyRequest().authenticated())
                // Before the username/password filter because by the time that one runs, the
                // request must already carry its identity; the token is the only credential this
                // API accepts after login.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        // Spring's defaults redirect a browser to a login page that does not exist
                        // here. An API should answer with a status code the frontend can act on.
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(response, 401, "unauthorized", "Authentication required"))
                        .accessDeniedHandler((request, response, failure) ->
                                writeError(response, 403, "forbidden", "Not allowed")))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults()));

        return http.build();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response,
                                   int status, String code, String message) throws java.io.IOException {
        // sendError starts a second /error dispatch after the stateless context is cleared.
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"timestamp\":\"" + java.time.Instant.now() + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = props.getCors().getAllowedOrigins();
        CorsConfiguration config = new CorsConfiguration();

        if (origins == null || origins.isEmpty()) {
            // Same-origin deployment: nginx serves the app and proxies /api, so the browser never
            // makes a cross-origin request and no CORS headers are needed. Saying so out loud at
            // startup saves an hour of debugging when someone later runs the frontend separately
            // and wonders why the browser is blocking them.
            log.info("No CORS origins configured — assuming the frontend is served from the same "
                     + "origin as the API. Set CORS_ORIGINS for a split deployment.");
        } else {
            log.info("CORS enabled for {}", origins);
            config.setAllowedOrigins(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            // No setAllowCredentials(true): the token goes in a header, so the browser has no
            // credentials to withhold, and enabling it would forbid ever widening the origin list.
            config.setMaxAge(3600L);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /**
     * BCrypt at strength 10.
     *
     * <p>Strength is the base-2 log of the iteration count, so 10 is 1,024 rounds — roughly 50–80ms
     * per hash on a t3.small. That is slow enough to make offline cracking of a stolen hash
     * expensive and fast enough that a login does not feel laggy. Raising it to 12 would quadruple
     * both, which on a shared single-core burstable instance means a handful of concurrent logins
     * can visibly stall the scrape loop.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
