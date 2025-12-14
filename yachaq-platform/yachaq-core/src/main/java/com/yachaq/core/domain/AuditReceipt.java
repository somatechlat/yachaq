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
        SETTLEMENT_POSTED,
        PAYOUT_REQUESTED,
        PAYOUT_COMPLETED,
        DEVICE_ENROLLED,
        DEVICE_REMOVED,
        PROFILE_CREATED,
        PROFILE_UPDATED,
        REQUEST_CREATED,
        REQUEST_SCREENED,
        ESCROW_FUNDED,
        ESCROW_RELEASED
    }

    public enum ActorType {
        DS,         // Data Sovereign
        REQUESTER,  // Data Requester
        SYSTEM      // Platform system
    }
}
