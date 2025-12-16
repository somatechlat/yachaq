package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Device registered to a Data Sovereign.
 * Supports attestation verification, trust scoring, and multi-device management.
 * 
 * Validates: Requirements 102.1, 217.1, 251.1, 251.2, 251.3, 251.4, 251.5
 * Property 24: Multi-Device Identity Linking
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

    // Multi-device support fields (Requirement 251.x)
    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "os_version", length = 50)
    private String osVersion;

    @Column(name = "hardware_class", length = 50)
    private String hardwareClass;

    @NotNull
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary = false;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "replacement_device_id")
    private UUID replacementDeviceId;

    @NotNull
    @Column(name = "trust_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal trustScore = new BigDecimal("0.5000");

    @Column(name = "data_categories", columnDefinition = "TEXT")
    private String dataCategories; // JSON array of data categories

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
        device.trustScore = new BigDecimal("0.5000");
        device.enrolledAt = Instant.now();
        device.lastSeen = Instant.now();
        device.attestationStatus = AttestationStatus.PENDING;
        device.isPrimary = false;
        return device;
    }

    /**
     * Creates a new device with full details for enrollment.
     * Property 24: Multi-Device Identity Linking
     */
    public static Device enrollWithDetails(
            UUID dsId, 
            String publicKey, 
            DeviceType deviceType,
            String deviceName,
            String osVersion,
            String hardwareClass) {
        
        Device device = enroll(dsId, publicKey, deviceType);
        device.deviceName = deviceName;
        device.osVersion = osVersion;
        device.hardwareClass = hardwareClass;
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

    /**
     * Updates the trust score based on attestation and health events.
     * Requirement 251.1: DS↔Device graph with trust score
     */
    public void updateTrustScore(BigDecimal newScore) {
        if (newScore.compareTo(BigDecimal.ZERO) < 0 || newScore.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Trust score must be between 0 and 1");
        }
        this.trustScore = newScore;
    }

    /**
     * Sets this device as the primary device for the DS.
     * Only one device can be primary per DS.
     */
    public void setPrimary(boolean primary) {
        this.isPrimary = primary;
    }

    /**
     * Disables this device (e.g., for compromise response).
     * Requirement 252.1: Remote disable of participation
     */
    public void disable() {
        if (this.disabledAt != null) {
            throw new IllegalStateException("Device is already disabled");
        }
        this.disabledAt = Instant.now();
    }

    /**
     * Re-enables a disabled device after review.
     */
    public void enable() {
        this.disabledAt = null;
    }

    /**
     * Marks this device as replaced by another device.
     * Requirement 251.5: Device replacement with key rotation
     */
    public void replaceWith(UUID newDeviceId) {
        if (newDeviceId == null) {
            throw new IllegalArgumentException("Replacement device ID is required");
        }
        this.replacementDeviceId = newDeviceId;
        this.disable();
    }

    /**
     * Rotates the public key for this device.
     * Requirement 251.5: Key rotation on device replacement
     */
    public void rotateKey(String newPublicKey) {
        if (newPublicKey == null || newPublicKey.isBlank()) {
            throw new IllegalArgumentException("New public key is required");
        }
        this.publicKey = newPublicKey;
        this.attestationStatus = AttestationStatus.PENDING; // Require re-attestation
    }

    public boolean isVerified() {
        return this.attestationStatus == AttestationStatus.VERIFIED;
    }

    public boolean isTrusted() {
        return isVerified() && this.riskScore.compareTo(new BigDecimal("0.3")) <= 0;
    }

    /**
     * Checks if this device is active (not disabled).
     */
    public boolean isActive() {
        return this.disabledAt == null;
    }

    /**
     * Checks if this device is eligible for queries.
     * Must be active, verified, and have acceptable trust score.
     */
    public boolean isEligibleForQueries() {
        return isActive() && isVerified() && trustScore.compareTo(new BigDecimal("0.3")) >= 0;
    }

    /**
     * Checks if this device is a mobile device.
     */
    public boolean isMobile() {
        return deviceType == DeviceType.MOBILE_ANDROID || deviceType == DeviceType.MOBILE_IOS;
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
    public String getDeviceName() { return deviceName; }
    public String getOsVersion() { return osVersion; }
    public String getHardwareClass() { return hardwareClass; }
    public boolean isPrimary() { return isPrimary; }
    public Instant getDisabledAt() { return disabledAt; }
    public UUID getReplacementDeviceId() { return replacementDeviceId; }
    public BigDecimal getTrustScore() { return trustScore; }
    public String getDataCategories() { return dataCategories; }

    public void setDeviceFingerprint(String fingerprint) {
        this.deviceFingerprint = fingerprint;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public void setHardwareClass(String hardwareClass) {
        this.hardwareClass = hardwareClass;
    }

    public void setDataCategories(String dataCategories) {
        this.dataCategories = dataCategories;
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
