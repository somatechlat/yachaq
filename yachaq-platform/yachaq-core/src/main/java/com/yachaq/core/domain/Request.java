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
 * Represents a Data Access Request initiated by a Data Requester. This entity captures
 * the "what, why, who, and how" of a requester's intent to access data from a cohort
 * of Data Subjects. It serves as the basis for generating consent contracts and
 * orchestrating queries.
 */
@Entity
@Table(name = "requests", indexes = {
        @Index(name = "idx_request_requester", columnList = "requester_id"),
        @Index(name = "idx_request_status", columnList = "status"),
        @Index(name = "idx_request_created", columnList = "created_at")
})
public class Request {

    /**
     * The unique identifier for the data request.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The unique identifier of the Data Requester who created this request.
     */
    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    /**
     * A human-readable description of the purpose for this data request.
     */
    @NotNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String purpose;

    /**
     * A JSON object defining the specific data fields being requested (the "what").
     * Example: {"fields": ["heart_rate", "steps_taken"], "granularity": "daily"}
     */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> scope;

    /**
     * A JSON object defining the criteria for Data Subjects who are eligible for this request.
     * Example: {"age_range": [30, 50], "location": "USA"}
     */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_criteria_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> eligibilityCriteria;

    /**
     * The start of the time window for the data being requested.
     */
    @NotNull
    @Column(name = "duration_start", nullable = false)
    private Instant durationStart;

    /**
     * The end of the time window for the data being requested.
     */
    @NotNull
    @Column(name = "duration_end", nullable = false)
    private Instant durationEnd;

    /**
     * The type of unit for which compensation is offered.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false)
    private UnitType unitType;

    /**
     * The financial compensation offered per unit of participation.
     */
    @NotNull
    @Positive
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /**
     * The maximum number of Data Subjects that can participate in this request.
     */
    @NotNull
    @Positive
    @Column(name = "max_participants", nullable = false)
    private Integer maxParticipants;

    /**
     * The total budget allocated for this request, which must be funded in escrow.
     */
    @NotNull
    @Positive
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal budget;

    /**
     * The identifier of the associated escrow account that holds the funds for this request.
     */
    @Column(name = "escrow_id")
    private UUID escrowId;

    /**
     * The current lifecycle status of the data request.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    /**
     * The timestamp when this request was first created (in DRAFT status).
     */
    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * The timestamp when this request was submitted for screening.
     */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * The version number for optimistic locking.
     */
    @Version
    private Long version;

    /**
     * JPA-required no-argument constructor.
     */
    protected Request() {}

    /**
     * Factory method to create a new {@link Request} in the DRAFT status.
     * @return a new {@link Request} instance.
     */
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

    /**
     * Transitions the request from DRAFT to SCREENING status.
     * @throws IllegalStateException if the request is not in DRAFT status.
     */
    public void submitForScreening() {
        if (status != RequestStatus.DRAFT) {
            throw new IllegalStateException("Can only submit DRAFT requests for screening");
        }
        status = RequestStatus.SCREENING;
        submittedAt = Instant.now();
    }

    /**
     * Transitions the request from SCREENING to ACTIVE status, making it visible to eligible Data Subjects.
     * @throws IllegalStateException if the request is not in SCREENING status.
     */
    public void activate() {
        if (status != RequestStatus.SCREENING) {
            throw new IllegalStateException("Can only activate SCREENING requests");
        }
        status = RequestStatus.ACTIVE;
    }

    /**
     * Transitions the request from SCREENING to REJECTED status.
     * @throws IllegalStateException if the request is not in SCREENING status.
     */
    public void reject() {
        if (status != RequestStatus.SCREENING) {
            throw new IllegalStateException("Can only reject SCREENING requests");
        }
        status = RequestStatus.REJECTED;
    }

    /**
     * Transitions the request to the CANCELLED status.
     * @throws IllegalStateException if the request is already completed or cancelled.
     */
    public void cancel() {
        if (status == RequestStatus.COMPLETED || status == RequestStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel completed or cancelled requests");
        }
        status = RequestStatus.CANCELLED;
    }

    /**
     * Transitions the request from ACTIVE to COMPLETED status.
     * @throws IllegalStateException if the request is not in ACTIVE status.
     */
    public void complete() {
        if (status != RequestStatus.ACTIVE) {
            throw new IllegalStateException("Can only complete ACTIVE requests");
        }
        status = RequestStatus.COMPLETED;
    }

    /**
     * Associates an escrow account with this request after the escrow has been created.
     * @param escrowId The unique identifier of the escrow account.
     */
    public void linkEscrow(UUID escrowId) {
        this.escrowId = escrowId;
    }

    /**
     * Calculates the total required funds to be held in escrow for this request.
     * @return The total escrow amount (unitPrice * maxParticipants).
     */
    public BigDecimal calculateRequiredEscrow() {
        return unitPrice.multiply(BigDecimal.valueOf(maxParticipants));
    }

    //<editor-fold desc="Getters">
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
    //</editor-fold>

    /**
     * Defines the type of unit for which compensation is offered.
     */
    public enum UnitType {
        /** Compensation is for completing a survey. */
        SURVEY,
        /** Compensation is for providing access to a dataset. */
        DATA_ACCESS,
        /** Compensation is for active participation in a study or trial. */
        PARTICIPATION
    }

    /**
     * Defines the lifecycle status of a data request.
     */
    public enum RequestStatus {
        /** The request has been created but not yet submitted for review. */
        DRAFT,
        /** The request has been submitted and is undergoing automated or manual screening. */
        SCREENING,
        /** The request has been approved and is now active and visible to eligible Data Subjects. */
        ACTIVE,
        /** The request has reached its goal or end date and is now complete. */
        COMPLETED,
        /** The request was cancelled by the requester before completion. */
        CANCELLED,
        /** The request was rejected during the screening process. */
        REJECTED
    }
}
