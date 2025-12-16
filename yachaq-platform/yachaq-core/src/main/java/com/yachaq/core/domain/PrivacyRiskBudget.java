package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Privacy Risk Budget (PRB) - Tracks privacy risk allocation for campaigns.
 * 
 * Property 28: PRB Allocation and Lock
 * *For any* campaign quoted, a Privacy Risk Budget must be allocated;
 * upon acceptance, the PRB must be locked and immutable.
 * 
 * Validates: Requirements 204.1, 204.2, 204.3, 204.4
 * 
 * Key features:
 * - PRB allocation at campaign quote time
 * - PRB locking at acceptance (immutable after lock)
 * - PRB consumption tracking per transform/export
 * - Blocking operations when PRB exhausted
 */
@Entity
@Table(name = "privacy_risk_budgets", indexes = {
    @Index(name = "idx_prb_campaign", columnList = "campaign_id"),
    @Index(name = "idx_prb_status", columnList = "status")
})
public class PrivacyRiskBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "campaign_id", nullable = false, unique = true)
    private UUID campaignId;

    /**
     * Total allocated privacy risk budget.
     * Requirement 204.1: Allocate PRB at campaign quote time.
     */
    @NotNull
    @PositiveOrZero
    @Column(name = "allocated", nullable = false, precision = 19, scale = 6)
    private BigDecimal allocated;

    /**
     * Consumed privacy risk budget.
     * Requirement 204.3: Decrement PRB on transforms/exports.
     */
    @NotNull
    @PositiveOrZero
    @Column(name = "consumed", nullable = false, precision = 19, scale = 6)
    private BigDecimal consumed;

    /**
     * Remaining privacy risk budget (allocated - consumed).
     */
    @NotNull
    @PositiveOrZero
    @Column(name = "remaining", nullable = false, precision = 19, scale = 6)
    private BigDecimal remaining;

    /**
     * Version of the ruleset used for PRB calculation.
     */
    @NotNull
    @Column(name = "ruleset_version", nullable = false)
    private String rulesetVersion;

    /**
     * Timestamp when PRB was locked (immutable after this).
     * Requirement 204.2: Lock PRB at acceptance.
     */
    @Column(name = "locked_at")
    private Instant lockedAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PRBStatus status;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Long version;

    public enum PRBStatus {
        DRAFT,      // PRB allocated but not yet locked
        LOCKED,     // PRB locked at acceptance (immutable)
        EXHAUSTED   // PRB fully consumed
    }

    protected PrivacyRiskBudget() {}

    /**
     * Creates a new PRB allocation for a campaign.
     * Property 28: PRB Allocation and Lock - allocation at quote time.
     * Validates: Requirements 204.1
     */
    public static PrivacyRiskBudget allocate(UUID campaignId, BigDecimal budget, String rulesetVersion) {
        if (campaignId == null) {
            throw new IllegalArgumentException("Campaign ID cannot be null");
        }
        if (budget == null || budget.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Budget must be non-negative");
        }
        if (rulesetVersion == null || rulesetVersion.isBlank()) {
            throw new IllegalArgumentException("Ruleset version cannot be null or blank");
        }

        var prb = new PrivacyRiskBudget();
        prb.campaignId = campaignId;
        prb.allocated = budget;
        prb.consumed = BigDecimal.ZERO;
        prb.remaining = budget;
        prb.rulesetVersion = rulesetVersion;
        prb.status = PRBStatus.DRAFT;
        prb.createdAt = Instant.now();
        prb.updatedAt = prb.createdAt;
        return prb;
    }

    /**
     * Locks the PRB at acceptance. After locking, the allocated amount is immutable.
     * Property 28: PRB Allocation and Lock - lock at acceptance.
     * Validates: Requirements 204.2
     */
    public void lock() {
        if (this.status == PRBStatus.LOCKED) {
            throw new IllegalStateException("PRB is already locked");
        }
        if (this.status == PRBStatus.EXHAUSTED) {
            throw new IllegalStateException("Cannot lock exhausted PRB");
        }
        this.status = PRBStatus.LOCKED;
        this.lockedAt = Instant.now();
        this.updatedAt = this.lockedAt;
    }

    /**
     * Consumes privacy risk budget for a transform/export operation.
     * Property 28: PRB consumption tracking.
     * Validates: Requirements 204.3, 204.4
     * 
     * @param riskCost The privacy risk cost to consume
     * @throws PRBExhaustedException if budget would be exceeded
     * @throws IllegalStateException if PRB is not locked
     */
    public void consume(BigDecimal riskCost) {
        if (riskCost == null || riskCost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Risk cost must be non-negative");
        }
        if (this.status == PRBStatus.DRAFT) {
            throw new IllegalStateException("Cannot consume from unlocked PRB");
        }
        if (this.status == PRBStatus.EXHAUSTED) {
            throw new PRBExhaustedException("PRB is exhausted. Remaining: 0");
        }
        if (riskCost.compareTo(this.remaining) > 0) {
            throw new PRBExhaustedException(
                "Insufficient PRB. Required: " + riskCost + ", Remaining: " + this.remaining);
        }

        this.consumed = this.consumed.add(riskCost);
        this.remaining = this.allocated.subtract(this.consumed);
        this.updatedAt = Instant.now();

        if (this.remaining.compareTo(BigDecimal.ZERO) <= 0) {
            this.status = PRBStatus.EXHAUSTED;
        }
    }

    /**
     * Checks if the PRB can accommodate a given risk cost.
     * Validates: Requirements 204.4
     */
    public boolean canConsume(BigDecimal riskCost) {
        if (riskCost == null || riskCost.compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        if (this.status == PRBStatus.DRAFT || this.status == PRBStatus.EXHAUSTED) {
            return false;
        }
        return riskCost.compareTo(this.remaining) <= 0;
    }

    /**
     * Checks if the PRB is locked and ready for consumption.
     */
    public boolean isLocked() {
        return this.status == PRBStatus.LOCKED;
    }

    /**
     * Checks if the PRB is exhausted.
     */
    public boolean isExhausted() {
        return this.status == PRBStatus.EXHAUSTED;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getCampaignId() { return campaignId; }
    public BigDecimal getAllocated() { return allocated; }
    public BigDecimal getConsumed() { return consumed; }
    public BigDecimal getRemaining() { return remaining; }
    public String getRulesetVersion() { return rulesetVersion; }
    public Instant getLockedAt() { return lockedAt; }
    public PRBStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Exception thrown when PRB is exhausted or insufficient.
     */
    public static class PRBExhaustedException extends RuntimeException {
        public PRBExhaustedException(String message) {
            super(message);
        }
    }
}
