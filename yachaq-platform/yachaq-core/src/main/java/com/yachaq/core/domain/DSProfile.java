package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Data Sovereign profile - represents a user who owns and controls their data.
 * Supports DS-IND (individual), DS-COMP (company), DS-ORG (organization) account types.
 */
@Entity
@Table(name = "ds_profiles")
public class DSProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(unique = true, nullable = false)
    private String pseudonym;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DSStatus status;

    @Column(name = "preferences_hash")
    private String preferencesHash;

    @Column(name = "encryption_key_id")
    private String encryptionKeyId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private DSAccountType accountType;

    @Version
    private Long version;

    protected DSProfile() {}

    public DSProfile(String pseudonym, DSAccountType accountType) {
        this.pseudonym = pseudonym;
        this.accountType = accountType;
        this.status = DSStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getPseudonym() { return pseudonym; }
    public Instant getCreatedAt() { return createdAt; }
    public DSStatus getStatus() { return status; }
    public String getPreferencesHash() { return preferencesHash; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public DSAccountType getAccountType() { return accountType; }

    // Business methods
    public void suspend() {
        if (this.status == DSStatus.BANNED) {
            throw new IllegalStateException("Cannot suspend a banned profile");
        }
        this.status = DSStatus.SUSPENDED;
    }

    public void activate() {
        if (this.status == DSStatus.BANNED) {
            throw new IllegalStateException("Cannot activate a banned profile");
        }
        this.status = DSStatus.ACTIVE;
    }

    public void ban() {
        this.status = DSStatus.BANNED;
    }

    public void setPreferencesHash(String hash) {
        this.preferencesHash = hash;
    }

    public void setEncryptionKeyId(String keyId) {
        this.encryptionKeyId = keyId;
    }

    public enum DSStatus {
        ACTIVE, SUSPENDED, BANNED
    }

    public enum DSAccountType {
        DS_IND,   // Individual
        DS_COMP,  // Company
        DS_ORG    // Organization
    }
}
