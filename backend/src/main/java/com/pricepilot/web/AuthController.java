package com.pricepilot.web;

import com.pricepilot.domain.AppUser;
import com.pricepilot.security.AuthenticatedUser;
import com.pricepilot.service.AccountService;
import com.pricepilot.web.dto.ApiRequests;
import com.pricepilot.web.dto.ApiViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, and "who am I".
 *
 * <p>The only unauthenticated endpoints in the application, which is why the rate limiting here is
 * keyed by client address rather than by user id: at the moment these are called there is no user id,
 * and an unauthenticated endpoint with no ceiling is an invitation to guess passwords in bulk.
 *
 * <p>The cap is deliberately generous enough to be invisible to a person getting their own password
 * wrong a few times, and low enough that an attacker gets a few dozen attempts an hour instead of
 * tens of thousands. Combined with BCrypt — which costs about a tenth of a second per attempt by
 * design — that is the right shape of defence for a single-box deployment. It is not a substitute for
 * account lockout on a larger system, and this comment is here so that is a decision rather than an
 * oversight.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int LOGIN_ATTEMPTS_PER_HOUR = 30;
    private static final int REGISTRATIONS_PER_HOUR = 5;

    private final AccountService accounts;
    private final RateLimiter limiter;

    public AuthController(AccountService accounts, RateLimiter limiter) {
        this.accounts = accounts;
        this.limiter = limiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiViews.Session register(@Valid @RequestBody ApiRequests.Register request,
                                     HttpServletRequest http) {
        limiter.check(callerKey(http), "registration", REGISTRATIONS_PER_HOUR,
                "Too many accounts have been created from this address in the last hour. "
                + "Please try again later.");
        return sessionView(accounts.register(
                request.email(), request.password(), request.displayName(), request.inviteCode()));
    }

    @PostMapping("/login")
    public ApiViews.Session login(@Valid @RequestBody ApiRequests.Login request,
                                  HttpServletRequest http) {
        limiter.check(callerKey(http), "sign-in", LOGIN_ATTEMPTS_PER_HOUR,
                "Too many sign-in attempts from this address. Please wait a little and try again.");
        return sessionView(accounts.login(request.email(), request.password()));
    }

    /**
     * The current user, re-read from the database.
     *
     * <p>Called by the frontend on every page load to decide whether the stored token is still good.
     * It goes to the database rather than simply decoding the token, because the one thing the token
     * cannot tell us is whether the account still exists — and a deleted user holding a valid token
     * should get a clean 401, not a dashboard assembled from nothing.
     */
    @GetMapping("/me")
    public ApiViews.User me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return userView(accounts.require(caller));
    }

    // ------------------------------------------------------------------ mapping

    private static ApiViews.Session sessionView(AccountService.Session session) {
        return new ApiViews.Session(
                session.token(), session.expiresInSeconds(), userView(session.user()));
    }

    static ApiViews.User userView(AppUser user) {
        return new ApiViews.User(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                AuthenticatedUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole()));
    }

    /**
     * The key the pre-authentication limits count against.
     *
     * <p>{@code X-Forwarded-For} is read first because in the deployed shape nginx terminates TLS and
     * proxies to the application, so {@code getRemoteAddr()} would be the proxy for every request —
     * one shared bucket, and the first person to mistype their password would lock out everyone else.
     * Only the first hop is used, since a client can append anything it likes to that header; the
     * leftmost entry is the one nginx wrote.
     *
     * <p>This does mean a direct connection that spoofs the header can choose its own bucket. That is
     * acceptable for a limit of this kind — it raises the cost of brute force without being the only
     * thing standing in the way — and the deployed configuration does not expose the application port
     * publicly.
     */
    private static String callerKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
