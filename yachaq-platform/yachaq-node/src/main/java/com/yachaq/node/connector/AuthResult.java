package com.yachaq.node.connector;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Result of a connector authorization attempt.
 * Requirement 305.3: Execute appropriate OAuth or OS permission flow.
 */
public record AuthResult(
        boolean success,
        AuthStatus status,
        Set<String> grantedScopes,
        Set<String> deniedScopes,
        Instant expiresAt,
        String errorCode,
        String errorMessage
) {
    public AuthResult {
        grantedScopes = grantedScopes != null ? Set.copyOf(grantedScopes) : Set.of();
        deniedScopes = deniedScopes != null ? Set.copyOf(deniedScopes) : Set.of();
    }

    /**
     * Creates a successful authorization result.
     */
    public static AuthResult success(Set<String> grantedScopes, Instant expiresAt) {
        return new AuthResult(true, AuthStatus.AUTHORIZED, grantedScopes, Set.of(), expiresAt, null, null);
    }

    /**
     * Creates a partial authorization result (some scopes denied).
     */
    public static AuthResult partial(Set<String> grantedScopes, Set<String> deniedScopes, Instant expiresAt) {
        return new AuthResult(true, AuthStatus.PARTIAL, grantedScopes, deniedScopes, expiresAt, null, null);
    }

    /**
     * Creates a failed authorization result.
     */
    public static AuthResult failure(String errorCode, String errorMessage) {
        return new AuthResult(false, AuthStatus.DENIED, Set.of(), Set.of(), null, errorCode, errorMessage);
    }

    /**
     * Creates a result indicating user cancelled authorization.
     */
    public static AuthResult cancelled() {
        return new AuthResult(false, AuthStatus.CANCELLED, Set.of(), Set.of(), null, "USER_CANCELLED", "User cancelled authorization");
    }

    /**
     * Creates a result indicating authorization is pending (e.g., waiting for user).
     */
    public static AuthResult pending() {
        return new AuthResult(false, AuthStatus.PENDING, Set.of(), Set.of(), null, null, null);
    }

    /**
     * Returns whether authorization has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns the expiration time if present.
     */
    public Optional<Instant> getExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /**
     * Authorization status.
     */
    public enum AuthStatus {
        AUTHORIZED,
        PARTIAL,
        DENIED,
        CANCELLED,
        PENDING,
        EXPIRED
    }
}
