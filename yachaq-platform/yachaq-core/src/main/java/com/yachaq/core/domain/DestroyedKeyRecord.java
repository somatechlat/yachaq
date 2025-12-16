package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Record of a destroyed encryption key for crypto-shred verification.
 * 
 * Property 19: Secure Deletion Verification
 * Validates: Requirements 222.1, 222.2
 */
@Entity
@Table(name = "destroyed_keys_registry", indexes = {
    @Index(name = "idx_destroyed_keys_key_id", columnList = "key_id"),
    @Index(name = "idx_destroyed_keys_resource", columnList = "associated_resource_type, associated_resource_id")
})
public class DestroyedKeyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "key_id", nullable = false, unique = true)
    private String keyId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false)
    private KeyType keyType;

    @Column(name = "associated_resource_type")
    private String associatedResourceType;

    @Column(name = "associated_resource_id")
    private UUID associatedResourceId;

    @NotNull
    @Column(name = "destroyed_at", nullable = false)
    private Instant destroyedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "destruction_method", nullable = false)
    private DestructionMethod destructionMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certificate_id")
    private SecureDeletionCertificate certificate;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DestroyedKeyRecord() {}

    /**
     * Creates a new destroyed key record.
     */
    public static DestroyedKeyRecord create(
            String keyId,
            KeyType keyType,
            String associatedResourceType,
            UUID associatedResourceId,
            DestructionMethod destructionMethod,
            SecureDeletionCertificate certificate) {
        
        var record = new DestroyedKeyRecord();
        record.keyId = keyId;
        record.keyType = keyType;
        record.associatedResourceType = associatedResourceType;
        record.associatedResourceId = associatedResourceId;
        record.destructionMethod = destructionMethod;
        record.certificate = certificate;
        record.destroyedAt = Instant.now();
        record.createdAt = Instant.now();
        return record;
    }

    // Getters
    public UUID getId() { return id; }
    public String getKeyId() { return keyId; }
    public KeyType getKeyType() { return keyType; }
    public String getAssociatedResourceType() { return associatedResourceType; }
    public UUID getAssociatedResourceId() { return associatedResourceId; }
    public Instant getDestroyedAt() { return destroyedAt; }
    public DestructionMethod getDestructionMethod() { return destructionMethod; }
    public SecureDeletionCertificate getCertificate() { return certificate; }
    public Instant getCreatedAt() { return createdAt; }

    public enum KeyType {
        DATA_ENCRYPTION_KEY,    // DEK for data encryption
        KEY_ENCRYPTION_KEY,     // KEK for envelope encryption
        CATEGORY_KEY,           // K-CAT category-specific key
        DS_KEY,                 // K-DS Data Sovereign key
        SESSION_KEY             // Temporary session key
    }

    public enum DestructionMethod {
        ZEROED,                 // Key bytes zeroed out
        OVERWRITTEN,            // Key bytes overwritten with random data
        DELETED_FROM_HSM,       // Key deleted from HSM
        REVOKED                 // Key revoked (for asymmetric keys)
    }
}
