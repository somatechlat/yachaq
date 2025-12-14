package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token for JWT token rotation.
 * Implements secure token rotation pattern - each refresh invalidates the previous token.
 * 
 * Validates: Requirements 1.4
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_ds", columnList = "ds_id"),
    @Index(name = "idx_refresh_device", columnList = "device_id"),
    @Index(name = "idx_refresh_hash", columnList = "token_hash")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @NotNull
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {}

    /**
     * Creates a new refresh token.
     * Token hash is SHA-256 of the actual token value (stored externally).
     */
    public static RefreshToken create(UUID dsId, UUID deviceId, String tokenHash, Instant expiresAt) {
        if (dsId == null) {
            throw new IllegalArgumentException("DS ID is required");
        }
        if (deviceId == null) {
            throw new IllegalArgumentException("Device ID is required");
        }
        if (tokenHash == null || tokenHash.length() != 64) {
            throw new IllegalArgumentException("Token hash must be 64 character SHA-256 hex");
        }
        if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Expiration must be in the future");
        }

        var token = new RefreshToken();
        token.dsId = dsId;
        token.deviceId = deviceId;
        token.tokenHash = tokenHash;
        token.issuedAt = Instant.now();
        token.expiresAt = expiresAt;
        return token;
    }

    /**
     * Revokes this token and marks it as replaced by a new token.
     * Used during token rotation.
     */
    public void revoke(UUID newTokenId) {
        if (this.revokedAt != null) {
            throw new IllegalStateException("Token already revoked");
        }
        this.revokedAt = Instant.now();
        this.replacedBy = newTokenId;
    }

    /**
     * Revokes this token without replacement (logout, security event).
     */
    public void revoke() {
        if (this.revokedAt != null) {
            throw new IllegalStateException("Token already revoked");
        }
        this.revokedAt = Instant.now();
    }

    public boolean isValid() {
        return this.revokedAt == null && Instant.now().isBefore(this.expiresAt);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDsId() { return dsId; }
    public UUID getDeviceId() { return deviceId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public UUID getReplacedBy() { return replacedBy; }
}
