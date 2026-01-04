package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a legally and technically binding agreement between a Data Subject (DS)
 * and a Data Requester, defining the terms of data access.
 * This entity is the cornerstone of the YACHAQ platform's consent management system.
 * <p>
 * An instance of this class is designed to be immutable in its core terms once created.
 * State changes, such as revocation, are recorded by updating status and timestamp fields
 * rather than modifying the original agreement details.
 * </p>
 * Validates: Requirements 3.1, 3.2, 3.4
 */
@Entity
@Table(name = "consent_contracts", indexes = {
    @Index(name = "idx_consent_ds", columnList = "ds_id"),
    @Index(name = "idx_consent_requester", columnList = "requester_id"),
    @Index(name = "idx_consent_status", columnList = "status")
})
public class ConsentContract {

    /**
     * The unique identifier for the consent contract.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The unique identifier of the Data Subject (user) who owns the data.
     */
    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    /**
     * The unique identifier of the Data Requester who is granted access.
     */
    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    /**
     * The unique identifier of the original data {@link Request} this consent pertains to.
     */
    @NotNull
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    /**
     * A cryptographic hash representing the specific data scope being consented to.
     * This ensures the integrity of the agreed-upon data fields.
     */
    @NotNull
    @Column(name = "scope_hash", nullable = false)
    private String scopeHash;

    /**
     * A cryptographic hash representing the stated purpose for the data access.
     * This ensures the integrity of the stated reason for data use.
     */
    @NotNull
    @Column(name = "purpose_hash", nullable = false)
    private String purposeHash;

    /**
     * The ISO 8601 timestamp when the consent becomes active.
     */
    @NotNull
    @Column(name = "duration_start", nullable = false)
    private Instant durationStart;

    /**
     * The ISO 8601 timestamp when the consent expires and is no longer valid.
     */
    @NotNull
    @Column(name = "duration_end", nullable = false)
    private Instant durationEnd;

    /**
     * The current lifecycle status of the consent contract.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentStatus status;

    /**
     * The financial compensation amount agreed upon for this consent.
     */
    @NotNull
    @Positive
    @Column(name = "compensation_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal compensationAmount;

    /**
     * The transaction hash from the blockchain anchoring this consent event, if applicable.
     */
    @Column(name = "blockchain_anchor_hash")
    private String blockchainAnchorHash;

    /**
     * The timestamp when the contract was revoked, if applicable. Null if not revoked.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * A JSON array of exact permitted field names (e.g., ["heart_rate", "blood_pressure"]).
     * Used for enforcing field-level access control.
     * <p>Property 17: Field-Level Access Enforcement</p>
     * <p>Validates: Requirements 219.1</p>
     */
    @Column(name = "permitted_fields", columnDefinition = "TEXT")
    private String permittedFields;

    /**
     * A JSON object mapping sensitive fields to an explicit consent status (e.g., {"genetic_data": "GRANTED"}).
     * <p>Validates: Requirements 219.3</p>
     */
    @Column(name = "sensitive_field_consents", columnDefinition = "TEXT")
    private String sensitiveFieldConsents;

    /**
     * A JSON array of allowed transform function names (e.g., ["anonymize", "aggregate_daily"]).
     * <p>Property 18: Transform Restriction Enforcement</p>
     * <p>Validates: Requirements 220.1</p>
     */
    @Column(name = "allowed_transforms", columnDefinition = "TEXT")
    private String allowedTransforms;

    /**
     * A JSON object defining rules for how transforms can be chained together.
     * <p>Validates: Requirements 220.3</p>
     */
    @Column(name = "transform_chain_rules", columnDefinition = "TEXT")
    private String transformChainRules;

    /**
     * A JSON array of output restriction types (e.g., ["AGGREGATE_ONLY", "NO_DOWNLOAD"]).
     * <p>Validates: Requirements 221.1, 221.2</p>
     */
    @Column(name = "output_restrictions", columnDefinition = "TEXT")
    private String outputRestrictions;

    /**
     * The required delivery mode for the data accessed under this consent.
     * <p>Validates: Requirements 221.3</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode")
    private DeliveryMode deliveryMode = DeliveryMode.CLEAN_ROOM;

    /**
     * Defines the mode of data delivery to the requester.
     */
    public enum DeliveryMode {
        /** Data access and processing occurs only within a secure, controlled environment. */
        CLEAN_ROOM,
        /** Data is delivered directly to the requester. Requires special permissions. */
        DIRECT,
        /** Data is delivered in an encrypted format with a key managed via an escrow system. */
        ENCRYPTED
    }

    /**
     * The maximum number of days the requester is permitted to retain the data.
     * <p>Property 23: Consent Obligation Specification</p>
     * <p>Validates: Requirements 223.1</p>
     */
    @Column(name = "retention_days")
    private Integer retentionDays;

    /**
     * The policy defining the trigger for data deletion.
     * <p>Validates: Requirements 223.1</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_policy")
    private RetentionPolicy retentionPolicy;

    /**
     * Defines the trigger for when data must be deleted by the requester.
     */
    public enum RetentionPolicy {
        /** Data must be deleted immediately after the query or analysis is complete. */
        DELETE_AFTER_USE,
        /** Data must be deleted after the specified {@code retention_days} have passed. */
        DELETE_AFTER_PERIOD,
        /** Data must be deleted immediately upon consent revocation. */
        DELETE_ON_REVOCATION,
        /** Data is retained until the Data Subject explicitly requests its deletion. */
        DELETE_ON_REQUEST
    }

    /**
     * A JSON array of usage restrictions (e.g., ["NO_COMMERCIAL_USE", "RESEARCH_ONLY"]).
     * <p>Property 23: Consent Obligation Specification</p>
     * <p>Validates: Requirements 223.1</p>
     */
    @Column(name = "usage_restrictions", columnDefinition = "TEXT")
    private String usageRestrictions;

    /**
     * A JSON object specifying detailed deletion requirements (e.g., cryptographic wipe standards).
     * <p>Property 23: Consent Obligation Specification</p>
     * <p>Validates: Requirements 223.1</p>
     */
    @Column(name = "deletion_requirements", columnDefinition = "TEXT")
    private String deletionRequirements;

    /**
     * A cryptographic hash of all obligation specifications (retention, usage, deletion)
     * to ensure their integrity.
     * <p>Validates: Requirements 223.2</p>
     */
    @Column(name = "obligation_hash")
    private String obligationHash;

    /**
     * The timestamp when this consent contract was created.
     */
    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The version number for optimistic locking.
     */
    @Version
    private Long version;

    /**
     * JPA-required no-argument constructor.
     */
    protected ConsentContract() {}

    /**
     * Factory method to create a new, valid consent contract.
     * Initializes the contract with an ACTIVE status and current creation timestamp.
     * <p>Property 1: Consent Contract Creation Completeness</p>
     * @return A new {@link ConsentContract} instance.
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
     * Revokes this consent contract, setting its status to REVOKED and recording the time.
     * This is a terminal state.
     * <p>Property 2: Revocation SLA Enforcement - must be enforced within 60 seconds.</p>
     * @throws IllegalStateException if the contract is already revoked or expired.
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

    /**
     * Checks if the contract is currently active.
     * An active contract has the ACTIVE status and the current time is before its end duration.
     * @return true if the contract is active, false otherwise.
     */
    public boolean isActive() {
        return this.status == ConsentStatus.ACTIVE &&
               Instant.now().isBefore(this.durationEnd);
    }

    /**
     * Checks if the contract has expired.
     * Expiration occurs when the current time is after the contract's end duration.
     * @return true if the contract is expired, false otherwise.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(this.durationEnd);
    }

    //<editor-fold desc="Getters and Setters">
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

    public String getPermittedFields() { return permittedFields; }
    public void setPermittedFields(String permittedFields) { this.permittedFields = permittedFields; }
    
    public String getSensitiveFieldConsents() { return sensitiveFieldConsents; }
    public void setSensitiveFieldConsents(String sensitiveFieldConsents) { 
        this.sensitiveFieldConsents = sensitiveFieldConsents; 
    }

    public String getAllowedTransforms() { return allowedTransforms; }
    public void setAllowedTransforms(String allowedTransforms) { this.allowedTransforms = allowedTransforms; }
    
    public String getTransformChainRules() { return transformChainRules; }
    public void setTransformChainRules(String transformChainRules) { 
        this.transformChainRules = transformChainRules; 
    }

    public String getOutputRestrictions() { return outputRestrictions; }
    public void setOutputRestrictions(String outputRestrictions) { this.outputRestrictions = outputRestrictions; }
    
    public DeliveryMode getDeliveryMode() { return deliveryMode; }
    public void setDeliveryMode(DeliveryMode deliveryMode) { this.deliveryMode = deliveryMode; }

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
    //</editor-fold>

    /**
     * Checks if this contract has all required obligation fields specified.
     * <p>Property 23: Consent Obligation Specification</p>
     * @return true if all required obligation fields are non-null and not blank.
     */
    public boolean hasRequiredObligations() {
        return retentionDays != null && retentionDays > 0 &&
               retentionPolicy != null &&
               usageRestrictions != null && !usageRestrictions.isBlank() &&
               deletionRequirements != null && !deletionRequirements.isBlank();
    }

    /**
     * Enumerates the possible lifecycle statuses of a ConsentContract.
     */
    public enum ConsentStatus {
        /** The consent is active and can be used for data access. */
        ACTIVE,
        /** The consent has been permanently revoked by the Data Subject. */
        REVOKED,
        /** The consent has passed its `durationEnd` and is no longer valid. */
        EXPIRED
    }
}
