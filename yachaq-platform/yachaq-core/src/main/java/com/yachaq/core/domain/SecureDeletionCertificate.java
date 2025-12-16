package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Secure Deletion Certificate - Verifiable proof of data deletion.
 * 
 * Property 19: Secure Deletion Verification
 * Validates: Requirements 222.1, 222.2, 222.3, 222.4, 222.9
 * 
 * For any data deletion request using crypto-shred, the encryption keys
 * must be destroyed and subsequent decryption attempts must fail.
 */
@Entity
@Table(name = "secure_deletion_certificates", indexes = {
    @Index(name = "idx_deletion_cert_resource", columnList = "resource_type, resource_id"),
    @Index(name = "idx_deletion_cert_status", columnList = "status"),
    @Index(name = "idx_deletion_cert_initiated", columnList = "initiated_at")
})
public class SecureDeletionCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @NotNull
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "deletion_method", nullable = false)
    private DeletionMethod deletionMethod;

    @Column(name = "key_destroyed", nullable = false)
    private boolean keyDestroyed = false;

    @Column(name = "storage_deleted", nullable = false)
    private boolean storageDeleted = false;

    @Column(name = "storage_overwritten", nullable = false)
    private boolean storageOverwritten = false;

    @NotNull
    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @NotNull
    @Column(name = "certificate_hash", nullable = false, length = 64)
    private String certificateHash;

    @Column(name = "scope_description", columnDefinition = "TEXT")
    private String scopeDescription;

    @Column(name = "storage_locations", columnDefinition = "TEXT")
    private String storageLocations; // JSON array

    @Column(name = "deletion_reason")
    private String deletionReason;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DeletionStatus status = DeletionStatus.INITIATED;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum DeletionMethod {
        CRYPTO_SHRED,   // Destroy encryption keys only
        OVERWRITE,      // Overwrite storage with random data
        BOTH            // Crypto-shred + overwrite
    }

    public enum DeletionStatus {
        INITIATED,
        KEY_DESTROYED,
        STORAGE_DELETED,
        COMPLETED,
        VERIFIED,
        FAILED
    }

    protected SecureDeletionCertificate() {}

    /**
     * Creates a new secure deletion certificate.
     */
    public static SecureDeletionCertificate create(
            String resourceType,
            UUID resourceId,
            DeletionMethod deletionMethod,
            String scopeDescription,
            String deletionReason,
            UUID requestedBy) {
        
        var cert = new SecureDeletionCertificate();
        cert.resourceType = resourceType;
        cert.resourceId = resourceId;
        cert.deletionMethod = deletionMethod;
        cert.scopeDescription = scopeDescription;
        cert.deletionReason = deletionReason;
        cert.requestedBy = requestedBy;
        cert.initiatedAt = Instant.now();
        cert.createdAt = Instant.now();
        cert.status = DeletionStatus.INITIATED;
        cert.certificateHash = cert.computeHash();
        return cert;
    }

    /**
     * Marks the encryption key as destroyed.
     * Property 19: Key destruction is required for crypto-shred.
     */
    public void markKeyDestroyed() {
        this.keyDestroyed = true;
        if (this.status == DeletionStatus.INITIATED) {
            this.status = DeletionStatus.KEY_DESTROYED;
        }
        checkCompletion();
        updateHash();
    }

    /**
     * Marks the storage as deleted.
     */
    public void markStorageDeleted() {
        this.storageDeleted = true;
        if (this.status == DeletionStatus.KEY_DESTROYED || 
            this.status == DeletionStatus.INITIATED) {
            this.status = DeletionStatus.STORAGE_DELETED;
        }
        checkCompletion();
        updateHash();
    }

    /**
     * Marks the storage as overwritten with random data.
     * Requirements 222.3: Overwrite storage with random data.
     */
    public void markStorageOverwritten() {
        this.storageOverwritten = true;
        checkCompletion();
        updateHash();
    }

    /**
     * Sets the storage locations that were deleted.
     */
    public void setStorageLocations(String locations) {
        this.storageLocations = locations;
        updateHash();
    }

    /**
     * Marks the deletion as verified.
     * Requirements 222.9: Coordinate deletion across all storage locations.
     */
    public void markVerified() {
        if (this.status != DeletionStatus.COMPLETED) {
            throw new IllegalStateException("Cannot verify incomplete deletion");
        }
        this.verifiedAt = Instant.now();
        this.status = DeletionStatus.VERIFIED;
        updateHash();
    }

    /**
     * Marks the deletion as failed.
     */
    public void markFailed(String reason) {
        this.status = DeletionStatus.FAILED;
        this.failureReason = reason;
        updateHash();
    }

    /**
     * Checks if deletion is complete based on method.
     */
    private void checkCompletion() {
        boolean complete = switch (deletionMethod) {
            case CRYPTO_SHRED -> keyDestroyed;
            case OVERWRITE -> storageDeleted && storageOverwritten;
            case BOTH -> keyDestroyed && storageDeleted && storageOverwritten;
        };
        
        if (complete) {
            this.completedAt = Instant.now();
            this.status = DeletionStatus.COMPLETED;
        }
    }

    /**
     * Computes the certificate hash for integrity verification.
     */
    private String computeHash() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("id=").append(id != null ? id : "");
            sb.append("|resourceType=").append(resourceType);
            sb.append("|resourceId=").append(resourceId);
            sb.append("|deletionMethod=").append(deletionMethod);
            sb.append("|keyDestroyed=").append(keyDestroyed);
            sb.append("|storageDeleted=").append(storageDeleted);
            sb.append("|storageOverwritten=").append(storageOverwritten);
            sb.append("|initiatedAt=").append(initiatedAt);
            sb.append("|completedAt=").append(completedAt);
            sb.append("|scope=").append(scopeDescription != null ? scopeDescription : "");
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void updateHash() {
        this.certificateHash = computeHash();
    }

    /**
     * Verifies the certificate hash integrity.
     */
    public boolean verifyIntegrity() {
        return certificateHash.equals(computeHash());
    }

    /**
     * Checks if deletion is complete.
     */
    public boolean isComplete() {
        return status == DeletionStatus.COMPLETED || status == DeletionStatus.VERIFIED;
    }

    // Getters
    public UUID getId() { return id; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public DeletionMethod getDeletionMethod() { return deletionMethod; }
    public boolean isKeyDestroyed() { return keyDestroyed; }
    public boolean isStorageDeleted() { return storageDeleted; }
    public boolean isStorageOverwritten() { return storageOverwritten; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getCertificateHash() { return certificateHash; }
    public String getScopeDescription() { return scopeDescription; }
    public String getStorageLocations() { return storageLocations; }
    public String getDeletionReason() { return deletionReason; }
    public UUID getRequestedBy() { return requestedBy; }
    public DeletionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
