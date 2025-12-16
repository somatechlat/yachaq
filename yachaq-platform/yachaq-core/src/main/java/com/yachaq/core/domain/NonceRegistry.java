package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Nonce Registry - Tracks nonces for capsule replay protection.
 * 
 * Property 16: Capsule Replay Protection
 * Validates: Requirements 218.1, 218.2
 * 
 * For any time capsule created with a nonce, accessing the capsule
 * with the same nonce a second time must be rejected.
 */
@Entity
@Table(name = "nonce_registry")
public class NonceRegistry {

    @Id
    @Column(name = "nonce", length = 64)
    private String nonce;

    @Column(name = "capsule_id", nullable = false)
    private UUID capsuleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NonceStatus status;

    public enum NonceStatus {
        ACTIVE,   // Nonce is valid and unused
        USED,     // Nonce has been used (replay attempt would fail)
        EXPIRED   // Nonce TTL has passed
    }

    protected NonceRegistry() {}

    /**
     * Creates a new nonce registry entry.
     * 
     * @param nonce Unique nonce value
     * @param capsuleId Associated capsule ID
     * @param expiresAt When the nonce expires (should match capsule TTL)
     */
    public static NonceRegistry create(String nonce, UUID capsuleId, Instant expiresAt) {
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("Nonce cannot be null or blank");
        }
        if (capsuleId == null) {
            throw new IllegalArgumentException("Capsule ID cannot be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiration time cannot be null");
        }

        NonceRegistry registry = new NonceRegistry();
        registry.nonce = nonce;
        registry.capsuleId = capsuleId;
        registry.createdAt = Instant.now();
        registry.expiresAt = expiresAt;
        registry.status = NonceStatus.ACTIVE;
        return registry;
    }

    /**
     * Marks the nonce as used.
     * Property 16: Once used, subsequent access attempts must be rejected.
     */
    public void markUsed() {
        if (status == NonceStatus.USED) {
            throw new IllegalStateException("Nonce has already been used - replay attack detected");
        }
        if (status == NonceStatus.EXPIRED || isExpired()) {
            throw new IllegalStateException("Nonce has expired");
        }
        this.usedAt = Instant.now();
        this.status = NonceStatus.USED;
    }

    /**
     * Marks the nonce as expired.
     */
    public void markExpired() {
        this.status = NonceStatus.EXPIRED;
    }

    /**
     * Checks if the nonce is valid for use.
     * A nonce is valid if it's ACTIVE and not expired.
     */
    public boolean isValid() {
        return status == NonceStatus.ACTIVE && !isExpired();
    }

    /**
     * Checks if the nonce has expired based on TTL.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this is a replay attempt (nonce already used).
     */
    public boolean isReplayAttempt() {
        return status == NonceStatus.USED;
    }

    // Getters
    public String getNonce() { return nonce; }
    public UUID getCapsuleId() { return capsuleId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public NonceStatus getStatus() { return status; }
}
