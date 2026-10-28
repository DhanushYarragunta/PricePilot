package com.pricepilot.security;

import com.pricepilot.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies the bearer tokens the API authenticates with.
 *
 * <p>Stateless by design: there is no session table and no server-side token store, so a token is
 * valid because its signature checks out and it has not expired. That is what lets the API scale to
 * nothing — a single EC2 instance restarting loses no login state — at the cost of not being able to
 * revoke an individual token before it expires. With a 72-hour default lifetime and a single-user
 * personal deployment, that trade is clearly the right way round; the mitigation if it ever is not
 * is to rotate {@code JWT_SECRET}, which invalidates every token at once.
 *
 * <h2>About the secret</h2>
 * HS256 requires at least 256 bits of key material, so the configured secret must be at least 32
 * characters. The two bad cases are handled differently on purpose:
 *
 * <ul>
 *   <li><b>Not set at all</b> — a random key is generated and the app starts, with a warning. This
 *       is a first run or a local dev session, and failing to boot would be unhelpful. Tokens do not
 *       survive a restart, and the log says so.
 *   <li><b>Set but too short</b> — startup fails with a message naming the variable and the
 *       required length. Somebody configured this and got it wrong; quietly substituting a random
 *       key would leave them believing a secret is in force when it is not, which is worse than a
 *       failed deploy.
 * </ul>
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** 256 bits, the minimum HS256 will accept. */
    private static final int MIN_SECRET_CHARS = 32;

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration ttl;
    private final boolean ephemeralKey;

    public JwtService(AppProperties props) {
        String secret = props.getAuth().getJwtSecret();
        this.ttl = Duration.ofHours(Math.max(1, props.getAuth().getJwtTtlHours()));

        if (secret == null || secret.isBlank()) {
            this.key = Jwts.SIG.HS256.key().build();
            this.ephemeralKey = true;
            log.warn("JWT_SECRET is not set. A random signing key was generated for this run, so "
                     + "everyone will be logged out when the app restarts. Set JWT_SECRET to at "
                     + "least {} characters before deploying.", MIN_SECRET_CHARS);
        } else if (secret.strip().length() < MIN_SECRET_CHARS) {
            throw new IllegalStateException(
                    "JWT_SECRET is only " + secret.strip().length() + " characters. HS256 needs at "
                    + "least " + MIN_SECRET_CHARS + ". Generate one with: openssl rand -base64 48");
        } else {
            this.key = Keys.hmacShaKeyFor(secret.strip().getBytes(StandardCharsets.UTF_8));
            this.ephemeralKey = false;
            log.info("JWT signing key loaded; tokens are valid for {} hours", ttl.toHours());
        }
    }

    /** True when tokens will not survive a restart, which the ops page surfaces as a warning. */
    public boolean isUsingEphemeralKey() {
        return ephemeralKey;
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /**
     * Mints a token for a user.
     *
     * <p>The role travels in the token rather than being looked up per request. The consequence
     * worth knowing: promoting someone to admin does not take effect until their next login. For a
     * deployment where the admin is the person who owns the server, that is a fair price for making
     * authentication a pure signature check.
     */
    public String issue(Long userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role == null ? AuthenticatedUser.ROLE_USER : role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token and returns who it belongs to.
     *
     * <p>Returns {@code null} for anything it cannot trust — bad signature, expired, malformed,
     * missing or unparseable subject — rather than throwing. The caller is a servlet filter whose
     * only reasonable response to any of those is identical: treat the request as anonymous and let
     * the authorisation rules decide whether that is allowed. Distinguishing the causes would mean
     * telling an unauthenticated client which of its guesses was closer.
     */
    public AuthenticatedUser verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.parseLong(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            return new AuthenticatedUser(userId, email,
                    role == null ? AuthenticatedUser.ROLE_USER : role);
        } catch (JwtException | IllegalArgumentException e) {
            // Debug, not warn. Expired tokens are the single most common event in a healthy system
            // — every user hits one every three days — and logging them at warn would bury real
            // problems under routine noise.
            log.debug("Rejected a bearer token: {}", e.getMessage());
            return null;
        }
    }
}
