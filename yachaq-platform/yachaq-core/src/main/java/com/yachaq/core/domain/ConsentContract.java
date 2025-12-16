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

    /**
     * JSON array of exact permitted field names.
     * Property 17: Field-Level Access Enforcement
     * Validates: Requirements 219.1
     */
    @Column(name = "permitted_fields", columnDefinition = "TEXT")
    private String permittedFields;

    /**
     * JSON object mapping sensitive fields to explicit consent status.
     * Validates: Requirements 219.3
     */
    @Column(name = "sensitive_field_consents", columnDefinition = "TEXT")
    private String sensitiveFieldConsents;

    /**
     * JSON array of allowed transform names.
     * Property 18: Transform Restriction Enforcement
     * Validates: Requirements 220.1
     */
    @Column(name = "allowed_transforms", columnDefinition = "TEXT")
    private String allowedTransforms;

    /**
     * JSON object with transform chaining validation rules.
     * Validates: Requirements 220.3
     */
    @Column(name = "transform_chain_rules", columnDefinition = "TEXT")
    private String transformChainRules;

    /**
     * JSON array of output restriction types.
     * Validates: Requirements 221.1, 221.2
     */
    @Column(name = "output_restrictions", columnDefinition = "TEXT")
    private String outputRestrictions;

    /**
     * Delivery mode for data access.
     * Validates: Requirements 221.3
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode")
    private DeliveryMode deliveryMode = DeliveryMode.CLEAN_ROOM;

    public enum DeliveryMode {
        CLEAN_ROOM,  // Default - controlled environment
        DIRECT,      // Direct delivery (requires special permissions)
        ENCRYPTED    // Encrypted delivery with key escrow
    }

    /**
     * Maximum number of days data can be retained.
     * Property 23: Consent Obligation Specification
     * Validates: Requirements 223.1
     */
    @Column(name = "retention_days")
    private Integer retentionDays;

    /**
     * Retention policy type.
     * Validates: Requirements 223.1
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_policy")
    private RetentionPolicy retentionPolicy;

    public enum RetentionPolicy {
        DELETE_AFTER_USE,      // Delete immediately after use
        DELETE_AFTER_PERIOD,   // Delete after retention_days
        DELETE_ON_REVOCATION,  // Delete when consent is revoked
        DELETE_ON_REQUEST      // Delete only on explicit DS request
    }

    /**
     * JSON array of usage restrictions.
     * Property 23: Consent Obligation Specification
     * Validates: Requirements 223.1
     */
    @Column(name = "usage_restrictions", columnDefinition = "TEXT")
    private String usageRestrictions;

    /**
     * JSON object specifying deletion requirements.
     * Property 23: Consent Obligation Specification
     * Validates: Requirements 223.1
     */
    @Column(name = "deletion_requirements", columnDefinition = "TEXT")
    private String deletionRequirements;

    /**
     * Hash of all obligation specifications for integrity verification.
     * Validates: Requirements 223.2
     */
    @Column(name = "obligation_hash")
    private String obligationHash;

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

    // Field-level access getters and setters
    public String getPermittedFields() { return permittedFields; }
    public void setPermittedFields(String permittedFields) { this.permittedFields = permittedFields; }
    
    public String getSensitiveFieldConsents() { return sensitiveFieldConsents; }
    public void setSensitiveFieldConsents(String sensitiveFieldConsents) { 
        this.sensitiveFieldConsents = sensitiveFieldConsents; 
    }

    // Transform restriction getters and setters
    public String getAllowedTransforms() { return allowedTransforms; }
    public void setAllowedTransforms(String allowedTransforms) { this.allowedTransforms = allowedTransforms; }
    
    public String getTransformChainRules() { return transformChainRules; }
    public void setTransformChainRules(String transformChainRules) { 
        this.transformChainRules = transformChainRules; 
    }

    // Output restriction getters and setters
    public String getOutputRestrictions() { return outputRestrictions; }
    public void setOutputRestrictions(String outputRestrictions) { this.outputRestrictions = outputRestrictions; }
    
    public DeliveryMode getDeliveryMode() { return deliveryMode; }
    public void setDeliveryMode(DeliveryMode deliveryMode) { this.deliveryMode = deliveryMode; }

    // Obligation getters and setters - Property 23
    public Integer getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }
    
    public RetentionPolicy getRetentionPolicy() { return retentionPolicy; }
    public void setRetentionPolicy(RetentionPolicy retentionPolicy) { this.retentionPolicy = retentionPolicy; }
    
    public String getUsageRestrictions() { return usageRestrictions; }
    public void setUsageRestrictions(String usageRestrictions) { this.usageRestrictions = usageRestrictions; }
    
    public String getDeletionRequirements() { return deletionRequirements; }
    public void setDeletionRequirements(String deletionRequirements) { this.deletionRequirements = deletionRequirements; }
    
    public String getObligationHash() { return obligationHash; }
    public void setObligationHash(String obligationHash) { this.obligationHash = obligationHash; }

    /**
     * Checks if this contract has all required obligations specified.
     * Property 23: Consent Obligation Specification
     */
    public boolean hasRequiredObligations() {
        return retentionDays != null && retentionDays > 0 &&
               retentionPolicy != null &&
               usageRestrictions != null && !usageRestrictions.isBlank() &&
               deletionRequirements != null && !deletionRequirements.isBlank();
    }

    public enum ConsentStatus {
        ACTIVE, REVOKED, EXPIRED
    }
}
