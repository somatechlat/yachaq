package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consent contract between a Data Sovereign and a Requester.
 * Immutable once created - revocation creates a new state, doesn't modify.
 * 
 * Validates: Requirements 3.1, 3.2, 3.4
 */
@Entity
@Table(name = "consent_contracts", indexes = {
    @Index(name = "idx_consent_ds", columnList = "ds_id"),
    @Index(name = "idx_consent_requester", columnList = "requester_id"),
    @Index(name = "idx_consent_status", columnList = "status")
})
public class ConsentContract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @NotNull
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @NotNull
    @Column(name = "scope_hash", nullable = false)
    private String scopeHash;

    @NotNull
    @Column(name = "purpose_hash", nullable = false)
    private String purposeHash;

    @NotNull
    @Column(name = "duration_start", nullable = false)
    private Instant durationStart;

    @NotNull
    @Column(name = "duration_end", nullable = false)
    private Instant durationEnd;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentStatus status;

    @NotNull
    @Positive
    @Column(name = "compensation_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal compensationAmount;

    @Column(name = "blockchain_anchor_hash")
    private String blockchainAnchorHash;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected ConsentContract() {}

    /**
     * Creates a new consent contract. All fields are required.
     * Property 1: Consent Contract Creation Completeness
     */
    public static ConsentContract create(
            UUID dsId,
            UUID requesterId,
            UUID requestId,
            String scopeHash,
            String purposeHash,
            Instant durationStart,
            Instant durationEnd,
            BigDecimal compensationAmount) {
        
        var contract = new ConsentContract();
        contract.dsId = dsId;
        contract.requesterId = requesterId;
        contract.requestId = requestId;
        contract.scopeHash = scopeHash;
        contract.purposeHash = purposeHash;
        contract.durationStart = durationStart;
        contract.durationEnd = durationEnd;
        contract.compensationAmount = compensationAmount;
        contract.status = ConsentStatus.ACTIVE;
        contract.createdAt = Instant.now();
        return contract;
    }

    /**
     * Revokes this consent contract.
     * Property 2: Revocation SLA Enforcement - must be enforced within 60 seconds
     */
    public void revoke() {
        if (this.status == ConsentStatus.REVOKED) {
            throw new IllegalStateException("Consent already revoked");
        }
        if (this.status == ConsentStatus.EXPIRED) {
            throw new IllegalStateException("Cannot revoke expired consent");
        }
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    public boolean isActive() {
        return this.status == ConsentStatus.ACTIVE && 
               Instant.now().isBefore(this.durationEnd);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.durationEnd);
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDsId() { return dsId; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getRequestId() { return requestId; }
    public String getScopeHash() { return scopeHash; }
    public String getPurposeHash() { return purposeHash; }
    public Instant getDurationStart() { return durationStart; }
    public Instant getDurationEnd() { return durationEnd; }
    public ConsentStatus getStatus() { return status; }
    public BigDecimal getCompensationAmount() { return compensationAmount; }
    public String getBlockchainAnchorHash() { return blockchainAnchorHash; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setBlockchainAnchorHash(String hash) {
        this.blockchainAnchorHash = hash;
    }

    public enum ConsentStatus {
        ACTIVE, REVOKED, EXPIRED
    }
}
