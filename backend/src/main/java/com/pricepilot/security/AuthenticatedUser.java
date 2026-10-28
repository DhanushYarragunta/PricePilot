package com.pricepilot.security;

/**
 * The authenticated caller, as carried on the security context for the life of one request.
 *
 * <p>Deliberately not the {@code AppUser} entity. Attaching a JPA entity to the security context
 * means every request either holds a detached object whose lazy fields explode on access, or pays a
 * database read to authenticate a token that is already cryptographically trustworthy. Everything a
 * controller normally needs — who is calling, and whether they are an admin — is in the token
 * itself, so the common path touches no tables at all. Handlers that need the full user row load it
 * explicitly by {@link #id()}, which makes that cost visible where it is incurred.
 *
 * @param id    the user's database id, taken from the token subject
 * @param email the address the account was registered with
 * @param role  {@code USER} or {@code ADMIN}
 */
public record AuthenticatedUser(Long id, String email, String role) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }
}
