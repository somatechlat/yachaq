package com.yachaq.api.replay;

import com.yachaq.core.domain.NonceRegistry;
import com.yachaq.core.domain.NonceRegistry.NonceStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Replay Protection.
 * Tests domain logic and invariants without Spring context.
 * 
 * **Feature: yachaq-platform, Property 16: Capsule Replay Protection**
 * For any time capsule created with a nonce, accessing the capsule
 * with the same nonce a second time must be rejected.
 * 
 * **Validates: Requirements 218.1, 218.2**
 */
class ReplayProtectionPropertyTest {

    /**
     * **Feature: yachaq-platform, Property 16: Capsule Replay Protection**
     * **Validates: Requirements 218.1, 218.2**
     * 
     * For any valid nonce, creating a registry entry must result in
     * an ACTIVE status that can be used exactly once.
     */
    @Property(tries = 100)
    void property16_nonceCanBeUsedExactlyOnce(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId,
            @ForAll @IntRange(min = 1, max = 168) int ttlHours) {
        
        Instant expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS);

        // Create nonce registry
        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        // Assert - Initial state is ACTIVE
        assertThat(registry.getStatus()).isEqualTo(NonceStatus.ACTIVE);
        assertThat(registry.isValid()).isTrue();
        assertThat(registry.isReplayAttempt()).isFalse();

        // First use - should succeed
        registry.markUsed();

        // Assert - After first use, status is USED
        assertThat(registry.getStatus()).isEqualTo(NonceStatus.USED);
        assertThat(registry.isValid()).isFalse();
        assertThat(registry.isReplayAttempt()).isTrue();
        assertThat(registry.getUsedAt()).isNotNull();

        // Property 16: Second use must be rejected
        assertThatThrownBy(() -> registry.markUsed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been used");
    }

    /**
     * **Feature: yachaq-platform, Property 16: Capsule Replay Protection**
     * **Validates: Requirements 218.2**
     * 
     * For any used nonce, isReplayAttempt() must return true.
     */
    @Property(tries = 100)
    void property16_usedNonceIsReplayAttempt(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        // Before use - not a replay attempt
        assertThat(registry.isReplayAttempt()).isFalse();

        // Use the nonce
        registry.markUsed();

        // After use - is a replay attempt
        assertThat(registry.isReplayAttempt()).isTrue();
    }

    /**
     * Property: Expired nonce cannot be used.
     */
    @Property(tries = 100)
    void expiredNonceCannotBeUsed(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        // Create with past expiration
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.HOURS);
        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        // Assert - Nonce is expired
        assertThat(registry.isExpired()).isTrue();
        assertThat(registry.isValid()).isFalse();

        // Attempting to use expired nonce should fail
        assertThatThrownBy(() -> registry.markUsed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    /**
     * Property: Nonce creation must reject null/blank values.
     */
    @Property(tries = 100)
    void nonceCreationRejectsInvalidInputs(
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        // Null nonce
        assertThatThrownBy(() -> NonceRegistry.create(null, capsuleId, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nonce");

        // Blank nonce
        assertThatThrownBy(() -> NonceRegistry.create("", capsuleId, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nonce");

        // Null capsule ID
        assertThatThrownBy(() -> NonceRegistry.create("valid-nonce", null, expiresAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Capsule ID");

        // Null expiration
        assertThatThrownBy(() -> NonceRegistry.create("valid-nonce", capsuleId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expiration");
    }

    /**
     * Property: Nonce validity respects both status and expiration.
     */
    @Property(tries = 100)
    void nonceValidityRespectsStatusAndExpiration(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        // Future expiration - should be valid
        Instant futureExpiry = Instant.now().plus(24, ChronoUnit.HOURS);
        NonceRegistry validRegistry = NonceRegistry.create(nonce, capsuleId, futureExpiry);
        assertThat(validRegistry.isValid()).isTrue();

        // Past expiration - should not be valid
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        NonceRegistry expiredRegistry = NonceRegistry.create(nonce + "-expired", capsuleId, pastExpiry);
        assertThat(expiredRegistry.isValid()).isFalse();

        // Used nonce - should not be valid
        NonceRegistry usedRegistry = NonceRegistry.create(nonce + "-used", capsuleId, futureExpiry);
        usedRegistry.markUsed();
        assertThat(usedRegistry.isValid()).isFalse();
    }

    /**
     * Property: markExpired changes status correctly.
     */
    @Property(tries = 100)
    void markExpiredChangesStatus(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        assertThat(registry.getStatus()).isEqualTo(NonceStatus.ACTIVE);

        registry.markExpired();

        assertThat(registry.getStatus()).isEqualTo(NonceStatus.EXPIRED);
        assertThat(registry.isValid()).isFalse();
    }

    /**
     * Property: Nonce registry preserves all creation data.
     */
    @Property(tries = 100)
    void nonceRegistryPreservesCreationData(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId,
            @ForAll @IntRange(min = 1, max = 168) int ttlHours) {
        
        Instant expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS);

        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        // All creation data must be preserved
        assertThat(registry.getNonce()).isEqualTo(nonce);
        assertThat(registry.getCapsuleId()).isEqualTo(capsuleId);
        assertThat(registry.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(registry.getCreatedAt()).isNotNull();
        assertThat(registry.getUsedAt()).isNull();
    }

    /**
     * Property: Used timestamp is set on markUsed.
     */
    @Property(tries = 100)
    void usedTimestampIsSetOnMarkUsed(
            @ForAll("validNonces") String nonce,
            @ForAll("validCapsuleIds") UUID capsuleId) {
        
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);

        assertThat(registry.getUsedAt()).isNull();

        Instant beforeUse = Instant.now();
        registry.markUsed();
        Instant afterUse = Instant.now();

        assertThat(registry.getUsedAt()).isNotNull();
        assertThat(registry.getUsedAt()).isAfterOrEqualTo(beforeUse);
        assertThat(registry.getUsedAt()).isBeforeOrEqualTo(afterUse);
    }

    // Arbitraries
    @Provide
    Arbitrary<String> validNonces() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(32)
                .ofMaxLength(64);
    }

    @Provide
    Arbitrary<UUID> validCapsuleIds() {
        return Arbitraries.create(UUID::randomUUID);
    }
}
