package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single, immutable entry in the platform's audit trail.
 * Each significant action on the platform generates an AuditReceipt, forming a
 * cryptographically-linked chain (hash chain) to ensure the integrity and
 * non-repudiation of the entire event history.
 *
 * <p>Property 5: Audit Receipt Generation</p>
 * <p>Validates: Requirements 12.1</p>
 */
@Entity
@Table(name = "audit_receipts", indexes = {
    @Index(name = "idx_audit_actor", columnList = "actor_id"),
    @Index(name = "idx_audit_resource", columnList = "resource_id"),
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_event_type", columnList = "event_type")
})
public class AuditReceipt {

    /**
     * The unique identifier for the audit receipt.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The specific type of event that occurred.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    /**
     * The timestamp when the event occurred, recorded in UTC.
     */
    @NotNull
    @Column(nullable = false)
    private Instant timestamp;

    /**
     * The identifier of the entity (user, system, etc.) that performed the action.
     */
    @NotNull
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    /**
     * The type of the actor that performed the action.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    /**
     * The identifier of the primary resource involved in the event (e.g., ConsentContract ID).
     */
    @NotNull
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    /**
     * The type of the primary resource (e.g., "ConsentContract", "QueryPlan").
     */
    @NotNull
    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    /**
     * A cryptographic hash of the detailed event payload, ensuring the integrity of event data.
     */
    @NotNull
    @Column(name = "details_hash", nullable = false)
    private String detailsHash;

    /**
     * A Merkle proof for this receipt, used for anchoring to an external blockchain.
     */
    @Column(name = "merkle_proof")
    private String merkleProof;

    /**
     * The cryptographic hash of the preceding audit receipt in the chain for this actor or resource.
     * This forms the hash chain.
     */
    @Column(name = "previous_receipt_hash")
    private String previousReceiptHash;

    /**
     * The cryptographic hash of this entire receipt's contents, making it tamper-evident.
     */
    @Column(name = "receipt_hash")
    private String receiptHash;

    /**
     * JPA-required no-argument constructor.
     */
    protected AuditReceipt() {}

    /**
     * Factory method to create a new {@link AuditReceipt}.
     *
     * @param eventType The type of event.
     * @param actorId The ID of the actor performing the event.
     * @param actorType The type of the actor.
     * @param resourceId The ID of the resource being acted upon.
     * @param resourceType The type of the resource.
     * @param detailsHash A hash of the event-specific details.
     * @param previousReceiptHash The hash of the previous receipt in the chain.
     * @return A new, unhashed {@link AuditReceipt} instance.
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

    //<editor-fold desc="Getters and Setters">
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
    //</editor-fold>

    /**
     * Defines the comprehensive taxonomy of all auditable events within the YACHAQ platform.
     */
    public enum EventType {
        // --- Core Lifecycle Events ---
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

        // --- YC Token Events (Requirement 192) ---
        YC_ISSUED,
        YC_REDEEMED,
        YC_CLAWBACK,
        YC_TRANSFER_REJECTED,
        YC_TRANSFERS_ENABLED,
        YC_TRANSFERS_DISABLED,

        // --- Query Plan Security Events (Requirement 216) ---
        QUERY_PLAN_SIGNED,
        QUERY_PLAN_VERIFIED,
        QUERY_PLAN_REJECTED,

        // --- Replay Protection Events (Requirement 218) ---
        NONCE_REGISTERED,
        NONCE_VALIDATED,
        CAPSULE_REPLAY_REJECTED,

        // --- Field-Level Access Events (Requirement 219) ---
        FIELD_ACCESS_GRANTED,
        FIELD_ACCESS_DENIED,
        UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,

        // --- Transform Restriction Events (Requirement 220) ---
        TRANSFORM_EXECUTED,
        TRANSFORM_REJECTED,
        UNAUTHORIZED_TRANSFORM_ATTEMPT,

        // --- Output Restriction Events (Requirement 221) ---
        CLEAN_ROOM_SESSION_STARTED,
        CLEAN_ROOM_SESSION_TERMINATED,
        OUTPUT_RESTRICTION_VIOLATION,
        EXPORT_BLOCKED,
        COPY_BLOCKED,
        SCREENSHOT_BLOCKED,

        // --- Secure Deletion Events (Requirement 222) ---
        SECURE_DELETION_INITIATED,
        KEY_DESTROYED,
        STORAGE_DELETED,
        STORAGE_OVERWRITTEN,
        SECURE_DELETION_COMPLETED,
        SECURE_DELETION_VERIFIED,
        SECURE_DELETION_FAILED,
        DECRYPTION_BLOCKED_KEY_DESTROYED,

        // --- Consent Obligation Events (Requirement 223) ---
        OBLIGATION_CREATED,
        OBLIGATION_CHECKED,
        OBLIGATION_SATISFIED,
        OBLIGATION_VIOLATED,
        VIOLATION_ACKNOWLEDGED,
        VIOLATION_RESOLVED,
        PENALTY_APPLIED,
        RETENTION_CHECK,
        DELETION_TRIGGERED,

        // --- Model-Data Lineage Events (Requirement 230) ---
        MODEL_TRAINING_STARTED,
        MODEL_TRAINING_COMPLETED,
        MODEL_TRAINING_FAILED,
        DS_CONTRIBUTION_RECORDED,

        // --- Canonical Event System Events (Requirement 191) ---
        REQUEST_MATCHED,
        REQUEST_COMPLETED,
        REQUEST_CANCELLED,
        MATCH_COMPLETED,
        MATCH_FAILED,
        TOKEN_ISSUED,
        TOKEN_REVOKED,
        TOKEN_EXPIRED,
        P2P_INTENT_CREATED,
        P2P_PAYMENT_CONFIRMED,
        P2P_DELIVERY_COMPLETED,
        INDEX_UPDATED,
        INDEX_SYNCED,

        // --- Account Management Events (Requirement 225-227) ---
        ACCOUNT_CREATED,
        ACCOUNT_ACTIVATED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_BANNED,
        KYB_VERIFICATION_COMPLETED,
        FLEET_LIMIT_UPDATED,

        // --- Guardian Relationship Events ---
        GUARDIAN_RELATIONSHIP_CREATED,
        GUARDIAN_RELATIONSHIP_VERIFIED,
        GUARDIAN_RELATIONSHIP_REVOKED,

        // --- Privacy Governor Events (Requirement 204) ---
        PRB_ALLOCATED,
        PRB_LOCKED,
        PRB_CONSUMED,
        PRB_EXHAUSTED,
        COHORT_CHECK_PASSED,
        COHORT_CHECK_BLOCKED,
        LINKAGE_RATE_LIMITED
    }

    /**
     * Defines the types of actors that can initiate events on the platform.
     */
    public enum ActorType {
        /** An end-user, typically the owner of the data. */
        DS,
        /** A third-party entity requesting access to data. */
        REQUESTER,
        /** The YACHAQ platform itself, performing automated tasks. */
        SYSTEM
    }
}
