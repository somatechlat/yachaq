package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Device attestation proof record.
 * Stores SafetyNet (Android), DeviceCheck (iOS), or TPM attestation proofs.
 * 
 * Validates: Requirements 217.1, 217.2, 217.3
 * Property 22: Device Attestation Collection
 * For any device enrollment where platform attestation is available,
 * the attestation data must be collected and stored with the device record.
 */
@Entity
@Table(name = "device_attestations", indexes = {
    @Index(name = "idx_attestation_device", columnList = "device_id"),
    @Index(name = "idx_attestation_status", columnList = "status"),
    @Index(name = "idx_attestation_expires", columnList = "expires_at")
})
public class DeviceAttestation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "attestation_type", nullable = false)
    private AttestationType attestationType;

    @NotNull
    @Column(name = "attestation_proof", nullable = false, columnDefinition = "TEXT")
    private String attestationProof;

    @Column(name = "attestation_hash", nullable = false)
    private String attestationHash;

    @NotNull
    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false)
    private TrustLevel trustLevel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttestationStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "nonce", nullable = false)
    private String nonce;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected DeviceAttestation() {
        this.createdAt = Instant.now();
    }

    /**
     * Creates a new attestation record for verification.
     * Property 22: Attestation data collected and stored with device record.
     */
    public static DeviceAttestation create(
            UUID deviceId,
            Platform platform,
            AttestationType attestationType,
            String attestationProof,
            String attestationHash,
            String nonce) {
        
        if (deviceId == null) {
            throw new IllegalArgumentException("Device ID is required");
        }
        if (attestationProof == null || attestationProof.isBlank()) {
            throw new IllegalArgumentException("Attestation proof is required");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("Nonce is required for replay protection");
        }

        var attestation = new DeviceAttestation();
        attestation.deviceId = deviceId;
        attestation.platform = platform;
        attestation.attestationType = attestationType;
        attestation.attestationProof = attestationProof;
        attestation.attestationHash = attestationHash;
        attestation.nonce = nonce;
        attestation.status = AttestationStatus.PENDING;
        attestation.trustLevel = TrustLevel.UNVERIFIED;
        attestation.verifiedAt = Instant.now();
        attestation.expiresAt = Instant.now().plusSeconds(86400); // 24h default
        return attestation;
    }

    /**
     * Marks attestation as verified with computed trust level.
     */
    public void verify(TrustLevel trustLevel, Instant expiresAt) {
        if (this.status == AttestationStatus.VERIFIED) {
            throw new IllegalStateException("Attestation already verified");
        }
        this.status = AttestationStatus.VERIFIED;
        this.trustLevel = trustLevel;
        this.verifiedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    /**
     * Marks attestation as failed with reason.
     */
    public void fail(String reason) {
        this.status = AttestationStatus.FAILED;
        this.trustLevel = TrustLevel.UNVERIFIED;
        this.failureReason = reason;
    }

    /**
     * Checks if attestation is currently valid.
     */
    public boolean isValid() {
        return this.status == AttestationStatus.VERIFIED 
            && Instant.now().isBefore(this.expiresAt);
    }

    /**
     * Checks if attestation has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public Platform getPlatform() { return platform; }
    public AttestationType getAttestationType() { return attestationType; }
    public String getAttestationProof() { return attestationProof; }
    public String getAttestationHash() { return attestationHash; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public TrustLevel getTrustLevel() { return trustLevel; }
    public AttestationStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getNonce() { return nonce; }
    public Instant getCreatedAt() { return createdAt; }

    public enum Platform {
        ANDROID,
        IOS,
        DESKTOP
    }

    public enum AttestationType {
        SAFETYNET,      // Android SafetyNet/Play Integrity
        DEVICECHECK,    // iOS DeviceCheck/App Attest
        TPM,            // Desktop TPM attestation
        NONE            // No attestation available
    }

    public enum TrustLevel {
        HIGH,       // Hardware-backed attestation verified
        MEDIUM,     // Software attestation verified
        LOW,        // Basic checks passed
        UNVERIFIED  // Not yet verified or failed
    }

    public enum AttestationStatus {
        PENDING,    // Awaiting verification
        VERIFIED,   // Successfully verified
        FAILED,     // Verification failed
        EXPIRED     // Was verified but has expired
    }
}
