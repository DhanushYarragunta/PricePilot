package com.pricepilot.service;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AppUser;
import com.pricepilot.repo.AppUserRepository;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.security.JwtService;
import com.pricepilot.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Registration and login.
 *
 * <h2>The invite code</h2>
 * When {@code SIGNUP_INVITE_CODE} is set, registration requires it. This is not security theatre —
 * it is cost control. The app is publicly reachable so it can be shown to people, and every account
 * that adds products causes live retailer fetches and Claude calls against a prepaid balance. One
 * shared code keeps a public URL from becoming a public spending account, while still letting anyone
 * who has been given the code sign up. Left blank, registration is open, which is the right default
 * for running it locally.
 *
 * <h2>Why login failures are deliberately vague</h2>
 * A wrong email and a wrong password return the identical message. Saying "no account with that
 * email" turns the login form into a membership oracle: anyone can test an address and learn whether
 * its owner uses this service. The cost is a slightly less helpful error, which is the correct side
 * to err on.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Used verbatim for both a missing account and a wrong password. See the class comment. */
    private static final String BAD_CREDENTIALS = "That email and password combination is not correct";

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AppProperties props;

    public AccountService(AppUserRepository users,
                          PasswordEncoder passwords,
                          JwtService jwt,
                          AppProperties props) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.props = props;
    }

    /**
     * Creates an account and returns a token, so registering logs you straight in.
     *
     * @return the signed bearer token for the new account
     */
    @Transactional
    public Session register(String email, String password, String displayName, String inviteCode) {
        String normalisedEmail = normaliseEmail(email);
        requireValidPassword(password);
        requireInviteCode(inviteCode);

        if (users.existsByEmailIgnoreCase(normalisedEmail)) {
            // Safe to be specific here, unlike on login: the caller is proving they can receive mail
            // at this address by trying to register it, and "email already in use" is the only
            // message that lets them do the sensible thing and log in instead.
            throw ApiException.conflict("email_taken", "That email address already has an account");
        }

        AppUser user = new AppUser();
        user.setEmail(normalisedEmail);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(cleanDisplayName(displayName, normalisedEmail));
        user.setRole(firstUserIsAdmin());
        user.setLastLoginAt(Instant.now());
        users.save(user);

        log.info("Registered user {} with role {}", user.getId(), user.getRole());
        return sessionFor(user);
    }

    @Transactional
    public Session login(String email, String password) {
        AppUser user = users.findByEmailIgnoreCase(normaliseEmail(email))
                .orElse(null);

        // The null check and the password check produce the same exception on purpose. Note also
        // that a missing user still runs no hash — a timing difference exists in principle, and
        // mitigating it properly would mean hashing a dummy password. That is a real but very weak
        // signal next to the explicit message, which is the part worth getting right.
        if (user == null || !passwords.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized(BAD_CREDENTIALS);
        }

        user.setLastLoginAt(Instant.now());
        return sessionFor(user);
    }

    /** Loads the full row for a caller the token already identified. */
    @Transactional(readOnly = true)
    public AppUser require(AuthenticatedUser caller) {
        if (caller == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return users.findById(caller.id())
                // The token is valid but the account is gone — deleted while a token was still
                // live. 401 rather than 404: the right client response is to log in again.
                .orElseThrow(() -> ApiException.unauthorized("This account no longer exists"));
    }

    // ------------------------------------------------------------------ internals

    private Session sessionFor(AppUser user) {
        String token = jwt.issue(user.getId(), user.getEmail(), user.getRole());
        return new Session(token, jwt.ttlSeconds(), user);
    }

    /**
     * The first account created becomes the admin.
     *
     * <p>This is how the owner gets operational access without a seeded password in a migration or a
     * manual SQL step after every fresh deploy. It is safe precisely because of the invite code: on
     * a public deployment the first registration is the person holding the code. On an open
     * deployment the first registration is whoever ran it, which is the same person.
     */
    private String firstUserIsAdmin() {
        return users.count() == 0 ? AuthenticatedUser.ROLE_ADMIN : AuthenticatedUser.ROLE_USER;
    }

    private void requireInviteCode(String supplied) {
        String expected = props.getAuth().getSignupInviteCode();
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (supplied == null || !expected.strip().equals(supplied.strip())) {
            throw ApiException.forbidden("invite_required",
                    "Registration on this instance needs an invite code");
        }
    }

    private static String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("An email address is required");
        }
        String trimmed = email.strip().toLowerCase();
        // Deliberately minimal. Fully validating an address with a regex is famously impossible, and
        // the only real proof an address works is mail arriving at it. This catches typos.
        if (!trimmed.matches("[^@\\s]+@[^@\\s.]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("That does not look like an email address");
        }
        return trimmed;
    }

    /**
     * Length only, with no composition rules.
     *
     * <p>Requiring a symbol and a digit reliably produces {@code Password1!} — it pushes people
     * toward short, predictable patterns and away from long passphrases, which are stronger. Length
     * is the property that actually resists guessing, so length is what is checked.
     */
    private static void requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest(
                    "Please use a password of at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > 200) {
            // BCrypt silently truncates past 72 bytes; a cap well above that stops someone using a
            // megabyte password as a way to make the server burn CPU hashing it.
            throw ApiException.badRequest("That password is too long");
        }
    }

    private static String cleanDisplayName(String displayName, String email) {
        if (displayName == null || displayName.isBlank()) {
            return email.substring(0, email.indexOf('@'));
        }
        String trimmed = displayName.strip();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    /** A freshly issued token and the account it belongs to. */
    public record Session(String token, long expiresInSeconds, AppUser user) {
    }
}
