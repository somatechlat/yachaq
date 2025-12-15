package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Data Access Request entity.
 * Real fields only; no placeholders.
 */
@Entity
@Table(name = "requests", indexes = {
        @Index(name = "idx_request_requester", columnList = "requester_id"),
        @Index(name = "idx_request_status", columnList = "status"),
        @Index(name = "idx_request_created", columnList = "created_at")
})
public class Request {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @NotNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String purpose;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scope;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_criteria_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> eligibilityCriteria;

    @NotNull
    @Column(name = "duration_start", nullable = false)
    private Instant durationStart;

    @NotNull
    @Column(name = "duration_end", nullable = false)
    private Instant durationEnd;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false)
    private UnitType unitType;

    @NotNull
    @Positive
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @NotNull
    @Positive
    @Column(name = "max_participants", nullable = false)
    private Integer maxParticipants;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal budget;

    @Column(name = "escrow_id")
    private UUID escrowId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Version
    private Long version;

    protected Request() {}

    public static Request create(
            UUID requesterId,
            String purpose,
            Map<String, Object> scope,
            Map<String, Object> eligibilityCriteria,
            Instant durationStart,
            Instant durationEnd,
            UnitType unitType,
            BigDecimal unitPrice,
            Integer maxParticipants,
            BigDecimal budget) {

        Request request = new Request();
        request.requesterId = requesterId;
        request.purpose = purpose;
        request.scope = scope;
        request.eligibilityCriteria = eligibilityCriteria;
        request.durationStart = durationStart;
        request.durationEnd = durationEnd;
        request.unitType = unitType;
        request.unitPrice = unitPrice;
        request.maxParticipants = maxParticipants;
        request.budget = budget;
        request.status = RequestStatus.DRAFT;
        request.createdAt = Instant.now();
        return request;
    }

    public void submitForScreening() {
        if (status != RequestStatus.DRAFT) {
            throw new IllegalStateException("Can only submit DRAFT requests for screening");
        }
        status = RequestStatus.SCREENING;
        submittedAt = Instant.now();
    }

    public void activate() {
        if (status != RequestStatus.SCREENING) {
            throw new IllegalStateException("Can only activate SCREENING requests");
        }
        status = RequestStatus.ACTIVE;
    }

    public void reject() {
        if (status != RequestStatus.SCREENING) {
            throw new IllegalStateException("Can only reject SCREENING requests");
        }
        status = RequestStatus.REJECTED;
    }

    public void cancel() {
        if (status == RequestStatus.COMPLETED || status == RequestStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel completed or cancelled requests");
        }
        status = RequestStatus.CANCELLED;
    }

    public void complete() {
        if (status != RequestStatus.ACTIVE) {
            throw new IllegalStateException("Can only complete ACTIVE requests");
        }
        status = RequestStatus.COMPLETED;
    }

    public void linkEscrow(UUID escrowId) {
        this.escrowId = escrowId;
    }

    public BigDecimal calculateRequiredEscrow() {
        return unitPrice.multiply(BigDecimal.valueOf(maxParticipants));
    }

    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public String getPurpose() { return purpose; }
    public Map<String, Object> getScope() { return scope; }
    public Map<String, Object> getEligibilityCriteria() { return eligibilityCriteria; }
    public Instant getDurationStart() { return durationStart; }
    public Instant getDurationEnd() { return durationEnd; }
    public UnitType getUnitType() { return unitType; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public Integer getMaxParticipants() { return maxParticipants; }
    public BigDecimal getBudget() { return budget; }
    public UUID getEscrowId() { return escrowId; }
    public RequestStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Long getVersion() { return version; }

    public enum UnitType {
        SURVEY,
        DATA_ACCESS,
        PARTICIPATION
    }

    public enum RequestStatus {
        DRAFT,
        SCREENING,
        ACTIVE,
        COMPLETED,
        CANCELLED,
        REJECTED
    }
}
