package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Device registered to a Data Sovereign.
 * Supports attestation verification and trust scoring.
 * 
 * Validates: Requirements 102.1, 217.1
 */
@Entity
@Table(name = "devices", indexes = {
    @Index(name = "idx_device_ds", columnList = "ds_id"),
    @Index(name = "idx_device_status", columnList = "attestation_status")
})
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @NotNull
    @Column(name = "public_key", nullable = false, length = 2048)
    private String publicKey;

    @NotNull
    @Column(name = "risk_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal riskScore;

    @NotNull
    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @NotNull
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "attestation_status", nullable = false)
    private AttestationStatus attestationStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false)
    private DeviceType deviceType;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    @Version
    private Long version;

    protected Device() {}

    /**
     * Creates a new device for enrollment.
     * Initial risk score is 0.5 (neutral), attestation pending.
     */
    public static Device enroll(UUID dsId, String publicKey, DeviceType deviceType) {
        if (dsId == null) {
            throw new IllegalArgumentException("DS ID is required");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("Public key is required");
        }
        
        var device = new Device();
        device.dsId = dsId;
        device.publicKey = publicKey;
        device.deviceType = deviceType;
        device.riskScore = new BigDecimal("0.5000");
        device.enrolledAt = Instant.now();
        device.lastSeen = Instant.now();
        device.attestationStatus = AttestationStatus.PENDING;
        return device;
    }

    public void updateLastSeen() {
        this.lastSeen = Instant.now();
    }

    public void verifyAttestation() {
        this.attestationStatus = AttestationStatus.VERIFIED;
    }

    public void failAttestation() {
        this.attestationStatus = AttestationStatus.FAILED;
    }

    public void updateRiskScore(BigDecimal newScore) {
        if (newScore.compareTo(BigDecimal.ZERO) < 0 || newScore.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Risk score must be between 0 and 1");
        }
        this.riskScore = newScore;
    }

    public boolean isVerified() {
        return this.attestationStatus == AttestationStatus.VERIFIED;
    }

    public boolean isTrusted() {
        return isVerified() && this.riskScore.compareTo(new BigDecimal("0.3")) <= 0;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDsId() { return dsId; }
    public String getPublicKey() { return publicKey; }
    public BigDecimal getRiskScore() { return riskScore; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getLastSeen() { return lastSeen; }
    public AttestationStatus getAttestationStatus() { return attestationStatus; }
    public DeviceType getDeviceType() { return deviceType; }
    public String getDeviceFingerprint() { return deviceFingerprint; }

    public void setDeviceFingerprint(String fingerprint) {
        this.deviceFingerprint = fingerprint;
    }

    public enum AttestationStatus {
        PENDING, VERIFIED, FAILED
    }

    public enum DeviceType {
        MOBILE_ANDROID,
        MOBILE_IOS,
        DESKTOP,
        IOT
    }
}
