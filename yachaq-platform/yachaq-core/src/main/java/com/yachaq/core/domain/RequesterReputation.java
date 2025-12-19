package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for requester reputation scores.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Entity
@Table(name = "requester_reputation", indexes = {
    @Index(name = "idx_rr_requester", columnList = "requester_id", unique = true)
})
public class RequesterReputation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "requester_id", nullable = false, unique = true)
    private UUID requesterId;

    @NotNull
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "dispute_count")
    private int disputeCount;

    @Column(name = "violation_count")
    private int violationCount;

    @Column(name = "targeting_attempts")
    private int targetingAttempts;

    @NotNull
    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @Version
    private Long version;

    protected RequesterReputation() {}

    public static RequesterReputation create(UUID requesterId, BigDecimal initialScore) {
        RequesterReputation rep = new RequesterReputation();
        rep.requesterId = requesterId;
        rep.score = initialScore;
        rep.disputeCount = 0;
        rep.violationCount = 0;
        rep.targetingAttempts = 0;
        rep.lastUpdated = Instant.now();
        return rep;
    }

    public void recordDispute(BigDecimal penalty) {
        this.score = this.score.subtract(penalty).max(BigDecimal.ZERO);
        this.disputeCount++;
        this.lastUpdated = Instant.now();
    }

    public void recordViolation(BigDecimal penalty) {
        this.score = this.score.subtract(penalty).max(BigDecimal.ZERO);
        this.violationCount++;
        this.lastUpdated = Instant.now();
    }

    public void recordTargetingAttempt() {
        this.score = this.score.subtract(new BigDecimal("20")).max(BigDecimal.ZERO);
        this.targetingAttempts++;
        this.lastUpdated = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public BigDecimal getScore() { return score; }
    public int getDisputeCount() { return disputeCount; }
    public int getViolationCount() { return violationCount; }
    public int getTargetingAttempts() { return targetingAttempts; }
    public Instant getLastUpdated() { return lastUpdated; }
}
