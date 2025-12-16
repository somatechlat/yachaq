package com.yachaq.api.attestation;

import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.DeviceAttestation;
import com.yachaq.core.domain.DeviceAttestation.Platform;
import com.yachaq.core.domain.DeviceAttestation.AttestationType;
import com.yachaq.core.domain.DeviceAttestation.TrustLevel;
import com.yachaq.core.domain.DeviceAttestation.AttestationStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Device Attestation.
 * Tests domain logic and invariants without Spring context.
 * 
 * **Feature: yachaq-platform, Property 22: Device Attestation Collection**
 * For any device enrollment where platform attestation is available,
 * the attestation data must be collected and stored with the device record.
 * 
 * **Validates: Requirements 217.1**
 */
class DeviceAttestationPropertyTest {

    /**
     * **Feature: yachaq-platform, Property 22: Device Attestation Collection**
     * **Validates: Requirements 217.1**
     * 
     * For any device with platform attestation available, creating an attestation
     * must result in an attestation record containing all required fields.
     */
    @Property(tries = 100)
    void property22_attestationCreationContainsAllRequiredFields(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform,
            @ForAll("validAttestationProofs") String attestationProof,
            @ForAll("validNonces") String nonce) {
        
        // Determine attestation type based on platform
        AttestationType attestationType = switch (platform) {
            case ANDROID -> AttestationType.SAFETYNET;
            case IOS -> AttestationType.DEVICECHECK;
            case DESKTOP -> AttestationType.TPM;
        };
        
        String attestationHash = computeHash(attestationProof);

        // Act - Create attestation
        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                attestationType,
                attestationProof,
                attestationHash,
                nonce
        );

        // Assert - Property 22: All required fields must be present
        assertThat(attestation).isNotNull();
        assertThat(attestation.getDeviceId()).isEqualTo(deviceId);
        assertThat(attestation.getPlatform()).isEqualTo(platform);
        assertThat(attestation.getAttestationType()).isEqualTo(attestationType);
        assertThat(attestation.getAttestationProof()).isEqualTo(attestationProof);
        assertThat(attestation.getAttestationHash()).isEqualTo(attestationHash);
        assertThat(attestation.getNonce()).isEqualTo(nonce);
        assertThat(attestation.getStatus()).isEqualTo(AttestationStatus.PENDING);
        assertThat(attestation.getTrustLevel()).isEqualTo(TrustLevel.UNVERIFIED);
        assertThat(attestation.getCreatedAt()).isNotNull();
    }

    /**
     * Property: Attestation creation must reject null device ID.
     */
    @Property(tries = 100)
    void attestationCreationRejectsNullDeviceId(
            @ForAll("platforms") Platform platform,
            @ForAll @StringLength(min = 100, max = 500) String attestationProof,
            @ForAll @StringLength(min = 32, max = 64) String nonce) {
        
        assertThatThrownBy(() -> 
                DeviceAttestation.create(
                        null, // null device ID
                        platform,
                        AttestationType.SAFETYNET,
                        attestationProof,
                        computeHash(attestationProof),
                        nonce))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Device ID");
    }

    /**
     * Property: Attestation creation must reject null/blank proof.
     */
    @Property(tries = 100)
    void attestationCreationRejectsBlankProof(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform,
            @ForAll @StringLength(min = 32, max = 64) String nonce) {
        
        assertThatThrownBy(() -> 
                DeviceAttestation.create(
                        deviceId,
                        platform,
                        AttestationType.SAFETYNET,
                        "", // blank proof
                        "hash",
                        nonce))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proof");
    }

    /**
     * Property: Attestation creation must reject null/blank nonce.
     */
    @Property(tries = 100)
    void attestationCreationRejectsBlankNonce(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform,
            @ForAll("validAttestationProofs") String attestationProof) {
        
        assertThatThrownBy(() -> 
                DeviceAttestation.create(
                        deviceId,
                        platform,
                        AttestationType.SAFETYNET,
                        attestationProof,
                        computeHash(attestationProof),
                        "")) // blank nonce
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nonce");
    }

    /**
     * Property: Verification must update trust level and status.
     */
    @Property(tries = 100)
    void verificationMustUpdateTrustLevelAndStatus(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform,
            @ForAll("trustLevels") TrustLevel trustLevel) {
        
        // Create pending attestation
        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                AttestationType.SAFETYNET,
                "header.payload.signature",
                "hash123",
                "nonce-" + UUID.randomUUID()
        );

        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        // Act - Verify attestation
        attestation.verify(trustLevel, expiresAt);

        // Assert - Status and trust level must be updated
        assertThat(attestation.getStatus()).isEqualTo(AttestationStatus.VERIFIED);
        assertThat(attestation.getTrustLevel()).isEqualTo(trustLevel);
        assertThat(attestation.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(attestation.getVerifiedAt()).isNotNull();
    }

    /**
     * Property: Double verification must be rejected.
     */
    @Property(tries = 100)
    void doubleVerificationMustBeRejected(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform) {
        
        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                AttestationType.SAFETYNET,
                "header.payload.signature",
                "hash123",
                "nonce-" + UUID.randomUUID()
        );

        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        attestation.verify(TrustLevel.HIGH, expiresAt);

        // Act & Assert - Second verification must fail
        assertThatThrownBy(() -> 
                attestation.verify(TrustLevel.MEDIUM, expiresAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already verified");
    }

    /**
     * Property: Failed attestation must record failure reason.
     */
    @Property(tries = 100)
    void failedAttestationMustRecordReason(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform,
            @ForAll @StringLength(min = 10, max = 200) String failureReason) {
        
        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                AttestationType.SAFETYNET,
                "header.payload.signature",
                "hash123",
                "nonce-" + UUID.randomUUID()
        );

        // Act - Fail attestation
        attestation.fail(failureReason);

        // Assert
        assertThat(attestation.getStatus()).isEqualTo(AttestationStatus.FAILED);
        assertThat(attestation.getTrustLevel()).isEqualTo(TrustLevel.UNVERIFIED);
        assertThat(attestation.getFailureReason()).isEqualTo(failureReason);
    }

    /**
     * Property: Verified attestation validity check must respect expiration.
     */
    @Property(tries = 100)
    void validityCheckMustRespectExpiration(
            @ForAll("validDeviceIds") UUID deviceId,
            @ForAll("platforms") Platform platform) {
        
        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                AttestationType.SAFETYNET,
                "header.payload.signature",
                "hash123",
                "nonce-" + UUID.randomUUID()
        );

        // Verify with future expiration
        Instant futureExpiry = Instant.now().plus(24, ChronoUnit.HOURS);
        attestation.verify(TrustLevel.HIGH, futureExpiry);
        assertThat(attestation.isValid()).isTrue();
        assertThat(attestation.isExpired()).isFalse();

        // Create another with past expiration
        DeviceAttestation expiredAttestation = DeviceAttestation.create(
                deviceId,
                platform,
                AttestationType.SAFETYNET,
                "header.payload.signature",
                "hash456",
                "nonce2-" + UUID.randomUUID()
        );
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        expiredAttestation.verify(TrustLevel.HIGH, pastExpiry);
        
        assertThat(expiredAttestation.isValid()).isFalse();
        assertThat(expiredAttestation.isExpired()).isTrue();
    }

    /**
     * Property: Attestation hash must be deterministic for same proof.
     */
    @Property(tries = 100)
    void attestationHashMustBeDeterministic(
            @ForAll("validAttestationProofs") String attestationProof) {
        
        UUID deviceId = UUID.randomUUID();
        String hash = computeHash(attestationProof);

        DeviceAttestation att1 = DeviceAttestation.create(
                deviceId, Platform.ANDROID, AttestationType.SAFETYNET,
                attestationProof, hash, "nonce1-" + UUID.randomUUID());

        DeviceAttestation att2 = DeviceAttestation.create(
                deviceId, Platform.ANDROID, AttestationType.SAFETYNET,
                attestationProof, hash, "nonce2-" + UUID.randomUUID());

        // Same proof must produce same hash
        assertThat(att1.getAttestationHash()).isEqualTo(att2.getAttestationHash());
    }

    // Arbitraries
    @Provide
    Arbitrary<UUID> validDeviceIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<Platform> platforms() {
        return Arbitraries.of(Platform.values());
    }

    @Provide
    Arbitrary<TrustLevel> trustLevels() {
        return Arbitraries.of(TrustLevel.HIGH, TrustLevel.MEDIUM, TrustLevel.LOW);
    }

    @Provide
    Arbitrary<String> validAttestationProofs() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(100)
                .ofMaxLength(1000);
    }

    @Provide
    Arbitrary<String> validNonces() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(32)
                .ofMaxLength(64);
    }

    private String computeHash(String input) {
        try {
            java.security.MessageDigest digest = 
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
