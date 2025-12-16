package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Data handling obligation attached to a consent contract.
 * Property 23: Consent Obligation Specification
 * Validates: Requirements 223.1, 223.2
 */
@Entity
@Table(name = "consent_obligations", indexes = {
    @Index(name = "idx_obligation_contract", columnList = "consent_contract_id"),
    @Index(name = "idx_obligation_type", columnList = "obligation_type"),
    @Index(name = "idx_obligation_status", columnList = "status")
})
public class ConsentObligation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "obligation_type", nullable = false)
    private ObligationType obligationType;

    @NotNull
    @Column(name = "specification", nullable = false, columnDefinition = "TEXT")
    private String specification;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_level", nullable = false)
    private EnforcementLevel enforcementLevel;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObligationStatus status = ObligationStatus.ACTIVE;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "violated_at")
    private Instant violatedAt;

    @Version
    private Long version;

    public enum ObligationType {
        RETENTION_LIMIT,       // Maximum days data can be retained
        USAGE_RESTRICTION,     // Restrictions on how data can be used
        DELETION_REQUIREMENT,  // Requirements for data deletion
        ACCESS_LIMIT,          // Limits on who can access data
        SHARING_PROHIBITION,   // Prohibitions on sharing with third parties
        PURPOSE_LIMITATION     // Limitations on purpose of use
    }

    public enum EnforcementLevel {
        STRICT,     // Automatic enforcement, immediate penalty
        MONITORED,  // Logged and reviewed, delayed penalty
        ADVISORY    // Logged only, no automatic penalty
    }

    public enum ObligationStatus {
        ACTIVE,     // Obligation is in effect
        SATISFIED,  // Obligation has been fulfilled
        VIOLATED,   // Obligation has been violated
        EXPIRED     // Obligation has expired (consent ended)
    }

    protected ConsentObligation() {}

    /**
     * Creates a new consent obligation.
     */
    public static ConsentObligation create(
            UUID consentContractId,
            ObligationType obligationType,
            String specification,
            EnforcementLevel enforcementLevel) {
        
        var obligation = new ConsentObligation();
        obligation.consentContractId = consentContractId;
        obligation.obligationType = obligationType;
        obligation.specification = specification;
        obligation.enforcementLevel = enforcementLevel;
        obligation.status = ObligationStatus.ACTIVE;
        obligation.createdAt = Instant.now();
        return obligation;
    }

    /**
     * Marks this obligation as satisfied.
     */
    public void markSatisfied() {
        if (this.status == ObligationStatus.VIOLATED) {
            throw new IllegalStateException("Cannot satisfy a violated obligation");
        }
        this.status = ObligationStatus.SATISFIED;
        this.satisfiedAt = Instant.now();
    }

    /**
     * Marks this obligation as violated.
     */
    public void markViolated() {
        if (this.status == ObligationStatus.SATISFIED) {
            throw new IllegalStateException("Cannot violate a satisfied obligation");
        }
        this.status = ObligationStatus.VIOLATED;
        this.violatedAt = Instant.now();
    }

    /**
     * Marks this obligation as expired (consent ended).
     */
    public void markExpired() {
        if (this.status == ObligationStatus.ACTIVE) {
            this.status = ObligationStatus.EXPIRED;
        }
    }

    public boolean isActive() {
        return this.status == ObligationStatus.ACTIVE;
    }

    public boolean isViolated() {
        return this.status == ObligationStatus.VIOLATED;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getConsentContractId() { return consentContractId; }
    public ObligationType getObligationType() { return obligationType; }
    public String getSpecification() { return specification; }
    public EnforcementLevel getEnforcementLevel() { return enforcementLevel; }
    public ObligationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSatisfiedAt() { return satisfiedAt; }
    public Instant getViolatedAt() { return violatedAt; }
}
