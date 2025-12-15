package com.yachaq.api.device;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * On-Device Data Store Entry - Encrypted local data record.
 * 
 * Requirements: 202.1, 202.4
 * - Encrypted local database storing shareable data
 * - Field-level query and redaction support
 * 
 * Property 12: Edge-First Data Locality
 * For any data collection event, the raw data must be stored on the DS device,
 * not on centralized platform servers, unless explicit consent for cloud storage exists.
 */
@Entity
@Table(name = "ods_entries", indexes = {
    @Index(name = "idx_ods_device", columnList = "device_id"),
    @Index(name = "idx_ods_ds", columnList = "ds_id"),
    @Index(name = "idx_ods_category", columnList = "category"),
    @Index(name = "idx_ods_timestamp", columnList = "timestamp")
})
public class ODSEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @NotNull
    @Column(nullable = false)
    private String category;

    @NotNull
    @Column(nullable = false)
    private Instant timestamp;

    @NotNull
    @Column(name = "encrypted_payload", nullable = false, columnDefinition = "TEXT")
    private String encryptedPayload;

    @NotNull
    @Column(name = "encryption_key_id", nullable = false)
    private String encryptionKeyId;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_location", nullable = false)
    private StorageLocation storageLocation;

    @Column(name = "cloud_consent_id")
    private UUID cloudConsentId;

    @NotNull
    @Column(name = "retention_policy", nullable = false)
    private String retentionPolicy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ODSEntry() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    /**
     * Create an ODS entry stored on device (edge-first).
     * Property 12: Raw data stored on DS device by default.
     */
    public static ODSEntry createOnDevice(
            UUID deviceId,
            UUID dsId,
            String category,
            String encryptedPayload,
            String encryptionKeyId,
            String payloadHash,
            String retentionPolicy) {
        
        ODSEntry entry = new ODSEntry();
        entry.deviceId = deviceId;
        entry.dsId = dsId;
        entry.category = category;
        entry.timestamp = Instant.now();
        entry.encryptedPayload = encryptedPayload;
        entry.encryptionKeyId = encryptionKeyId;
        entry.payloadHash = payloadHash;
        entry.storageLocation = StorageLocation.DEVICE;
        entry.retentionPolicy = retentionPolicy;
        return entry;
    }

    /**
     * Create an ODS entry with explicit cloud consent.
     * Only allowed when DS has granted explicit consent for cloud storage.
     */
    public static ODSEntry createWithCloudConsent(
            UUID deviceId,
            UUID dsId,
            String category,
            String encryptedPayload,
            String encryptionKeyId,
            String payloadHash,
            String retentionPolicy,
            UUID cloudConsentId) {
        
        if (cloudConsentId == null) {
            throw new IllegalArgumentException(
                "Cloud storage requires explicit consent ID");
        }
        
        ODSEntry entry = createOnDevice(deviceId, dsId, category, 
            encryptedPayload, encryptionKeyId, payloadHash, retentionPolicy);
        entry.storageLocation = StorageLocation.CLOUD_WITH_CONSENT;
        entry.cloudConsentId = cloudConsentId;
        return entry;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public UUID getDsId() { return dsId; }
    public String getCategory() { return category; }
    public Instant getTimestamp() { return timestamp; }
    public String getEncryptedPayload() { return encryptedPayload; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public String getPayloadHash() { return payloadHash; }
    public StorageLocation getStorageLocation() { return storageLocation; }
    public UUID getCloudConsentId() { return cloudConsentId; }
    public String getRetentionPolicy() { return retentionPolicy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }

    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void markDeleted() { this.deletedAt = Instant.now(); }

    /**
     * Check if data is stored on device (edge-first).
     */
    public boolean isStoredOnDevice() {
        return storageLocation == StorageLocation.DEVICE;
    }

    /**
     * Check if cloud storage has valid consent.
     */
    public boolean hasValidCloudConsent() {
        return storageLocation == StorageLocation.CLOUD_WITH_CONSENT 
            && cloudConsentId != null;
    }

    public enum StorageLocation {
        DEVICE,              // Edge-first: stored on user device
        CLOUD_WITH_CONSENT   // Cloud storage with explicit consent
    }
}
