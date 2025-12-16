package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Canonical Event - Standardized event for system-wide communication.
 * 
 * Implements the canonical event bus pattern with:
 * - Correlation IDs for distributed tracing
 * - Idempotency keys for at-least-once delivery
 * - Schema versioning for backward compatibility
 * - No raw PII (only hashes)
 * 
 * Validates: Requirement 191 (Canonical Event Bus)
 */
@Entity
@Table(name = "canonical_events", indexes = {
    @Index(name = "idx_event_type", columnList = "event_type"),
    @Index(name = "idx_event_trace_id", columnList = "trace_id"),
    @Index(name = "idx_event_correlation_id", columnList = "correlation_id"),
    @Index(name = "idx_event_idempotency_key", columnList = "idempotency_key"),
    @Index(name = "idx_event_timestamp", columnList = "timestamp"),
    @Index(name = "idx_event_status", columnList = "status")
})
public class CanonicalEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventCategory eventType;

    @NotNull
    @Column(name = "event_name", nullable = false)
    private String eventName;

    @NotNull
    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @NotNull
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "consent_contract_id")
    private UUID consentContractId;

    @Column(name = "policy_version")
    private String policyVersion;

    @NotNull
    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "payload_summary", length = 1000)
    private String payloadSummary;

    @NotNull
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count")
    private int retryCount;

    @NotNull
    @Column(name = "schema_version", nullable = false)
    private String schemaVersion;

    /**
     * Event categories per Requirement 191.
     */
    public enum EventCategory {
        REQUEST,      // request.created, request.screened, etc.
        CONSENT,      // consent.accepted, consent.revoked
        TOKEN,        // token.issued, token.revoked
        DATA,         // data.accessed
        SETTLEMENT,   // settlement.posted
        PAYOUT,       // payout.initiated, payout.completed
        P2P,          // p2p.intent.created, p2p.payment.confirmed, p2p.delivery.completed
        DEVICE,       // device.enrolled, device.removed
        MATCH,        // match.completed
        CAPSULE,      // capsule.created, capsule.accessed, capsule.expired
        CLEAN_ROOM,   // clean_room.session_started, clean_room.session_terminated
        ACCOUNT,      // account.created, account.activated
        INDEX,        // index.updated, index.synced
        TRAINING      // training.started, training.completed
    }

    public enum ActorType {
        DS,           // Data Sovereign
        REQUESTER,    // Data Requester
        SYSTEM,       // Platform system
        GUARDIAN      // Guardian account
    }

    public enum EventStatus {
        PENDING,      // Event created, not yet processed
        PROCESSING,   // Event being processed
        COMPLETED,    // Event successfully processed
        FAILED,       // Event processing failed
        DEAD_LETTER   // Event moved to dead-letter queue
    }

    protected CanonicalEvent() {}

    private CanonicalEvent(Builder builder) {
        this.id = UUID.randomUUID();
        this.eventType = builder.eventType;
        this.eventName = builder.eventName;
        this.traceId = builder.traceId != null ? builder.traceId : UUID.randomUUID();
        this.correlationId = builder.correlationId;
        this.idempotencyKey = builder.idempotencyKey;
        this.actorId = builder.actorId;
        this.actorType = builder.actorType;
        this.resourceId = builder.resourceId;
        this.resourceType = builder.resourceType;
        this.consentContractId = builder.consentContractId;
        this.policyVersion = builder.policyVersion;
        this.payloadHash = builder.payloadHash;
        this.payloadSummary = builder.payloadSummary;
        this.timestamp = Instant.now();
        this.status = EventStatus.PENDING;
        this.retryCount = 0;
        this.schemaVersion = "1.0";
    }


    /**
     * Marks the event as processing.
     */
    public void markProcessing() {
        this.status = EventStatus.PROCESSING;
    }

    /**
     * Marks the event as completed.
     */
    public void markCompleted() {
        this.status = EventStatus.COMPLETED;
        this.processedAt = Instant.now();
    }

    /**
     * Marks the event as failed.
     */
    public void markFailed(String errorMessage) {
        this.status = EventStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
    }

    /**
     * Moves the event to dead-letter queue.
     */
    public void moveToDeadLetter(String reason) {
        this.status = EventStatus.DEAD_LETTER;
        this.errorMessage = reason;
    }

    /**
     * Checks if the event can be retried.
     */
    public boolean canRetry(int maxRetries) {
        return this.status == EventStatus.FAILED && this.retryCount < maxRetries;
    }

    // Getters
    public UUID getId() { return id; }
    public EventCategory getEventType() { return eventType; }
    public String getEventName() { return eventName; }
    public UUID getTraceId() { return traceId; }
    public UUID getCorrelationId() { return correlationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getActorId() { return actorId; }
    public ActorType getActorType() { return actorType; }
    public UUID getResourceId() { return resourceId; }
    public String getResourceType() { return resourceType; }
    public UUID getConsentContractId() { return consentContractId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getPayloadHash() { return payloadHash; }
    public String getPayloadSummary() { return payloadSummary; }
    public Instant getTimestamp() { return timestamp; }
    public EventStatus getStatus() { return status; }
    public Instant getProcessedAt() { return processedAt; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public String getSchemaVersion() { return schemaVersion; }

    /**
     * Builder for creating CanonicalEvent instances.
     */
    public static class Builder {
        private EventCategory eventType;
        private String eventName;
        private UUID traceId;
        private UUID correlationId;
        private String idempotencyKey;
        private UUID actorId;
        private ActorType actorType;
        private UUID resourceId;
        private String resourceType;
        private UUID consentContractId;
        private String policyVersion;
        private String payloadHash;
        private String payloadSummary;

        public Builder eventType(EventCategory eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder eventName(String eventName) {
            this.eventName = eventName;
            return this;
        }

        public Builder traceId(UUID traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder correlationId(UUID correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder actorId(UUID actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder actorType(ActorType actorType) {
            this.actorType = actorType;
            return this;
        }

        public Builder resourceId(UUID resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder consentContractId(UUID consentContractId) {
            this.consentContractId = consentContractId;
            return this;
        }

        public Builder policyVersion(String policyVersion) {
            this.policyVersion = policyVersion;
            return this;
        }

        public Builder payloadHash(String payloadHash) {
            this.payloadHash = payloadHash;
            return this;
        }

        public Builder payloadSummary(String payloadSummary) {
            this.payloadSummary = payloadSummary;
            return this;
        }

        public CanonicalEvent build() {
            if (eventType == null) {
                throw new IllegalStateException("Event type is required");
            }
            if (eventName == null || eventName.isBlank()) {
                throw new IllegalStateException("Event name is required");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalStateException("Idempotency key is required");
            }
            if (actorId == null) {
                throw new IllegalStateException("Actor ID is required");
            }
            if (actorType == null) {
                throw new IllegalStateException("Actor type is required");
            }
            if (payloadHash == null || payloadHash.isBlank()) {
                throw new IllegalStateException("Payload hash is required");
            }
            return new CanonicalEvent(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
