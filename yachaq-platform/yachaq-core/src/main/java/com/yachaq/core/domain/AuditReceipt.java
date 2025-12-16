package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit receipt for all platform events.
 * Forms a hash chain for integrity verification.
 * 
 * Property 5: Audit Receipt Generation
 * Validates: Requirements 12.1
 */
@Entity
@Table(name = "audit_receipts", indexes = {
    @Index(name = "idx_audit_actor", columnList = "actor_id"),
    @Index(name = "idx_audit_resource", columnList = "resource_id"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_event_type", columnList = "event_type")
})
public class AuditReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @NotNull
    @Column(nullable = false)
    private Instant timestamp;

    @NotNull
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @NotNull
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @NotNull
    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @NotNull
    @Column(name = "details_hash", nullable = false)
    private String detailsHash;

    @Column(name = "merkle_proof")
    private String merkleProof;

    @Column(name = "previous_receipt_hash")
    private String previousReceiptHash;

    @Column(name = "receipt_hash")
    private String receiptHash;

    protected AuditReceipt() {}

    /**
     * Creates a new audit receipt with hash chaining.
     */
    public static AuditReceipt create(
            EventType eventType,
            UUID actorId,
            ActorType actorType,
            UUID resourceId,
            String resourceType,
            String detailsHash,
            String previousReceiptHash) {
        
        var receipt = new AuditReceipt();
        receipt.eventType = eventType;
        receipt.timestamp = Instant.now();
        receipt.actorId = actorId;
        receipt.actorType = actorType;
        receipt.resourceId = resourceId;
        receipt.resourceType = resourceType;
        receipt.detailsHash = detailsHash;
        receipt.previousReceiptHash = previousReceiptHash;
        return receipt;
    }

    // Getters
    public UUID getId() { return id; }
    public EventType getEventType() { return eventType; }
    public Instant getTimestamp() { return timestamp; }
    public UUID getActorId() { return actorId; }
    public ActorType getActorType() { return actorType; }
    public UUID getResourceId() { return resourceId; }
    public String getResourceType() { return resourceType; }
    public String getDetailsHash() { return detailsHash; }
    public String getMerkleProof() { return merkleProof; }
    public String getPreviousReceiptHash() { return previousReceiptHash; }
    public String getReceiptHash() { return receiptHash; }

    public void setMerkleProof(String proof) { this.merkleProof = proof; }
    public void setReceiptHash(String hash) { this.receiptHash = hash; }

    public enum EventType {
        CONSENT_GRANTED,
        CONSENT_REVOKED,
        DATA_ACCESS,
        QUERY_EXECUTED,
        CAPSULE_CREATED,
        CAPSULE_ACCESSED,
        CAPSULE_EXPIRED,
        SETTLEMENT,
        SETTLEMENT_POSTED,
        PAYOUT_REQUESTED,
        PAYOUT_COMPLETED,
        DEVICE_ENROLLED,
        DEVICE_REMOVED,
        DEVICE_ATTESTATION,
        PROFILE_CREATED,
        PROFILE_UPDATED,
        REQUEST_CREATED,
        REQUEST_SCREENED,
        ESCROW_CREATED,
        ESCROW_FUNDED,
        ESCROW_LOCKED,
        ESCROW_RELEASED,
        ESCROW_REFUNDED,
        // YC Token events (Requirement 192)
        YC_ISSUED,
        YC_REDEEMED,
        YC_CLAWBACK,
        YC_TRANSFER_REJECTED,
        YC_TRANSFERS_ENABLED,
        YC_TRANSFERS_DISABLED,
        // Query Plan Security events (Requirement 216)
        QUERY_PLAN_SIGNED,
        QUERY_PLAN_VERIFIED,
        QUERY_PLAN_REJECTED,
        // Replay Protection events (Requirement 218)
        NONCE_REGISTERED,
        NONCE_VALIDATED,
        CAPSULE_REPLAY_REJECTED,
        // Field-Level Access events (Requirement 219)
        FIELD_ACCESS_GRANTED,
        FIELD_ACCESS_DENIED,
        UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
        // Transform Restriction events (Requirement 220)
        TRANSFORM_EXECUTED,
        TRANSFORM_REJECTED,
        UNAUTHORIZED_TRANSFORM_ATTEMPT,
        // Output Restriction events (Requirement 221)
        CLEAN_ROOM_SESSION_STARTED,
        CLEAN_ROOM_SESSION_TERMINATED,
        OUTPUT_RESTRICTION_VIOLATION,
        EXPORT_BLOCKED,
        COPY_BLOCKED,
        SCREENSHOT_BLOCKED,
        // Secure Deletion events (Requirement 222)
        SECURE_DELETION_INITIATED,
        KEY_DESTROYED,
        STORAGE_DELETED,
        STORAGE_OVERWRITTEN,
        SECURE_DELETION_COMPLETED,
        SECURE_DELETION_VERIFIED,
        SECURE_DELETION_FAILED,
        DECRYPTION_BLOCKED_KEY_DESTROYED,
        // Consent Obligation events (Requirement 223)
        OBLIGATION_CREATED,
        OBLIGATION_CHECKED,
        OBLIGATION_SATISFIED,
        OBLIGATION_VIOLATED,
        VIOLATION_ACKNOWLEDGED,
        VIOLATION_RESOLVED,
        PENALTY_APPLIED,
        RETENTION_CHECK,
        DELETION_TRIGGERED,
        // Model-Data Lineage events (Requirement 230)
        MODEL_TRAINING_STARTED,
        MODEL_TRAINING_COMPLETED,
        MODEL_TRAINING_FAILED,
        DS_CONTRIBUTION_RECORDED,
        // Canonical Event System events (Requirement 191)
        // Request lifecycle
        REQUEST_MATCHED,
        REQUEST_COMPLETED,
        REQUEST_CANCELLED,
        // Match events
        MATCH_COMPLETED,
        MATCH_FAILED,
        // Token events
        TOKEN_ISSUED,
        TOKEN_REVOKED,
        TOKEN_EXPIRED,
        // P2P events
        P2P_INTENT_CREATED,
        P2P_PAYMENT_CONFIRMED,
        P2P_DELIVERY_COMPLETED,
        // Index events
        INDEX_UPDATED,
        INDEX_SYNCED,
        // Account events (Requirement 225-227)
        ACCOUNT_CREATED,
        ACCOUNT_ACTIVATED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_BANNED,
        KYB_VERIFICATION_COMPLETED,
        FLEET_LIMIT_UPDATED,
        // Guardian events
        GUARDIAN_RELATIONSHIP_CREATED,
        GUARDIAN_RELATIONSHIP_VERIFIED,
        GUARDIAN_RELATIONSHIP_REVOKED,
        // Privacy Governor events (Requirement 204)
        PRB_ALLOCATED,
        PRB_LOCKED,
        PRB_CONSUMED,
        PRB_EXHAUSTED,
        COHORT_CHECK_PASSED,
        COHORT_CHECK_BLOCKED,
        LINKAGE_RATE_LIMITED
    }

    public enum ActorType {
        DS,         // Data Sovereign
        REQUESTER,  // Data Requester
        SYSTEM      // Platform system
    }
}
