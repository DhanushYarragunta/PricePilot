package com.pricepilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the {@code Authorization: Bearer <token>} header and, if it verifies, puts the caller on the
 * security context for the rest of the request.
 *
 * <p>It authenticates but never rejects. A missing or bad token leaves the request anonymous and the
 * chain continues, so the decision about whether anonymous is acceptable belongs entirely to
 * {@link SecurityConfig}'s authorisation rules. Keeping that in one place is what stops an endpoint
 * ending up protected by two mechanisms that disagree — and it is why public endpoints need no
 * special case here.
 *
 * <p>Extends {@code OncePerRequestFilter} because forwards and error dispatches re-enter the filter
 * chain; without it, the token would be parsed two or three times for every request that renders an
 * error page.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthenticatedUser user = jwt.verify(token);
            if (user != null) {
                // Spring's role rules expect the ROLE_ prefix; the token stores the bare name so
                // that the claim stays readable and framework-independent.
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().toUpperCase()));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // The context is thread-local and the container pools threads. Without this, the next
            // request served by this thread could inherit the previous caller's identity — a quiet,
            // intermittent authorisation bug of the worst kind. Spring's own filter normally clears
            // it, and clearing it again here is cheap insurance against ordering changes.
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
