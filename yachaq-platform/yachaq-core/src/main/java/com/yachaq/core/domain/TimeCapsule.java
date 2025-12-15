package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Time Capsule - Encrypted, time-limited data delivery container.
 * 
 * Property 13: Time Capsule TTL Enforcement
 * Validates: Requirements 206.2
 * 
 * For any time capsule with a TTL, the capsule must be automatically deleted
 * (crypto-shred + storage deletion) within 1 hour of TTL expiration.
 */
@Entity
@Table(name = "time_capsules")
public class TimeCapsule {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @Column(name = "field_manifest_hash", nullable = false)
    private String fieldManifestHash;

    @Column(name = "encrypted_payload", columnDefinition = "BYTEA")
    private byte[] encryptedPayload;

    @Column(name = "encryption_key_id", nullable = false)
    private String encryptionKeyId;

    @Column(name = "ttl", nullable = false)
    private Instant ttl;

    @Column(name = "nonce", nullable = false, unique = true)
    private String nonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CapsuleStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_receipt_id")
    private UUID deletionReceiptId;

    public enum CapsuleStatus {
        CREATED,
        DELIVERED,
        EXPIRED,
        DELETED
    }

    public TimeCapsule() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.status = CapsuleStatus.CREATED;
        this.nonce = UUID.randomUUID().toString();
    }

    // Property 13: Check if TTL has expired
    public boolean isExpired() {
        return Instant.now().isAfter(ttl);
    }

    // Property 13: Check if capsule should be deleted (TTL + 1 hour grace)
    public boolean shouldBeDeleted() {
        return Instant.now().isAfter(ttl.plusSeconds(3600));
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public UUID getConsentContractId() { return consentContractId; }
    public void setConsentContractId(UUID consentContractId) { this.consentContractId = consentContractId; }

    public String getFieldManifestHash() { return fieldManifestHash; }
    public void setFieldManifestHash(String fieldManifestHash) { this.fieldManifestHash = fieldManifestHash; }

    public byte[] getEncryptedPayload() { return encryptedPayload; }
    public void setEncryptedPayload(byte[] encryptedPayload) { this.encryptedPayload = encryptedPayload; }

    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }

    public Instant getTtl() { return ttl; }
    public void setTtl(Instant ttl) { this.ttl = ttl; }

    // Alias for TTL (expiration time)
    public Instant getExpiresAt() { return ttl; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public CapsuleStatus getStatus() { return status; }
    public void setStatus(CapsuleStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public UUID getDeletionReceiptId() { return deletionReceiptId; }
    public void setDeletionReceiptId(UUID deletionReceiptId) { this.deletionReceiptId = deletionReceiptId; }
}
