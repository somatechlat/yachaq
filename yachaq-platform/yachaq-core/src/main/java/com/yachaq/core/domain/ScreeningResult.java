package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Screening result for a request.
 * Records the decision and reason codes from the screening engine.
 * 
 * Validates: Requirements 6.1, 6.2, 6.3
 */
@Entity
@Table(name = "screening_results", indexes = {
    @Index(name = "idx_screening_request", columnList = "request_id"),
    @Index(name = "idx_screening_decision", columnList = "decision"),
    @Index(name = "idx_screening_appeal", columnList = "appeal_status")
})
public class ScreeningResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScreeningDecision decision;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reason_codes", columnDefinition = "text[]")
    private List<String> reasonCodes = new ArrayList<>();

    @NotNull
    @Column(name = "risk_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal riskScore;

    @NotNull
    @Column(name = "cohort_size_estimate", nullable = false)
    private Integer cohortSizeEstimate;

    @NotNull
    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @NotNull
    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "screened_by", nullable = false)
    private ScreenedBy screenedBy;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "appeal_status")
    private AppealStatus appealStatus;

    @Column(name = "appeal_submitted_at")
    private Instant appealSubmittedAt;

    @Column(name = "appeal_resolved_at")
    private Instant appealResolvedAt;

    protected ScreeningResult() {}

    /**
     * Creates a new screening result.
     */
    public static ScreeningResult create(
            UUID requestId,
            ScreeningDecision decision,
            List<String> reasonCodes,
            BigDecimal riskScore,
            Integer cohortSizeEstimate,
            String policyVersion,
            ScreenedBy screenedBy) {
        
        var result = new ScreeningResult();
        result.requestId = requestId;
        result.decision = decision;
        result.reasonCodes = reasonCodes != null ? new ArrayList<>(reasonCodes) : new ArrayList<>();
        result.riskScore = riskScore;
        result.cohortSizeEstimate = cohortSizeEstimate;
        result.policyVersion = policyVersion;
        result.screenedAt = Instant.now();
        result.screenedBy = screenedBy;
        result.appealStatus = AppealStatus.NONE;
        return result;
    }

    /**
     * Submits an appeal for this screening result.
     */
    public void submitAppeal() {
        if (this.decision != ScreeningDecision.REJECTED) {
            throw new IllegalStateException("Can only appeal REJECTED decisions");
        }
        if (this.appealStatus != AppealStatus.NONE) {
            throw new IllegalStateException("Appeal already submitted");
        }
        this.appealStatus = AppealStatus.PENDING;
        this.appealSubmittedAt = Instant.now();
    }

    /**
     * Approves the appeal.
     */
    public void approveAppeal(UUID reviewerId) {
        if (this.appealStatus != AppealStatus.PENDING) {
            throw new IllegalStateException("No pending appeal to approve");
        }
        this.appealStatus = AppealStatus.APPROVED;
        this.appealResolvedAt = Instant.now();
        this.reviewerId = reviewerId;
        this.decision = ScreeningDecision.APPROVED;
    }

    /**
     * Rejects the appeal.
     */
    public void rejectAppeal(UUID reviewerId) {
        if (this.appealStatus != AppealStatus.PENDING) {
            throw new IllegalStateException("No pending appeal to reject");
        }
        this.appealStatus = AppealStatus.REJECTED;
        this.appealResolvedAt = Instant.now();
        this.reviewerId = reviewerId;
    }

    /**
     * Checks if the cohort meets minimum size requirement (k >= 50).
     * Property 11: Anti-Targeting Cohort Threshold
     */
    public boolean meetsCohortThreshold() {
        return cohortSizeEstimate >= 50;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public ScreeningDecision getDecision() { return decision; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public BigDecimal getRiskScore() { return riskScore; }
    public Integer getCohortSizeEstimate() { return cohortSizeEstimate; }
    public String getPolicyVersion() { return policyVersion; }
    public Instant getScreenedAt() { return screenedAt; }
    public ScreenedBy getScreenedBy() { return screenedBy; }
    public UUID getReviewerId() { return reviewerId; }
    public AppealStatus getAppealStatus() { return appealStatus; }
    public Instant getAppealSubmittedAt() { return appealSubmittedAt; }
    public Instant getAppealResolvedAt() { return appealResolvedAt; }

    public enum ScreeningDecision {
        APPROVED,
        REJECTED,
        MANUAL_REVIEW
    }

    public enum ScreenedBy {
        AUTOMATED,
        MANUAL
    }

    public enum AppealStatus {
        NONE,
        PENDING,
        APPROVED,
        REJECTED
    }
}
