package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Records a violation of a consent obligation.
 * Property 23: Consent Obligation Specification
 * Validates: Requirements 223.3, 223.4
 */
@Entity
@Table(name = "obligation_violations", indexes = {
    @Index(name = "idx_oblig_violation_contract", columnList = "consent_contract_id"),
    @Index(name = "idx_oblig_violation_obligation", columnList = "obligation_id"),
    @Index(name = "idx_oblig_violation_type", columnList = "violation_type"),
    @Index(name = "idx_oblig_violation_status", columnList = "status"),
    @Index(name = "idx_oblig_violation_severity", columnList = "severity")
})
public class ObligationViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @NotNull
    @Column(name = "obligation_id", nullable = false)
    private UUID obligationId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "violation_type", nullable = false)
    private ViolationType violationType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @NotNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "evidence_hash")
    private String evidenceHash;

    @NotNull
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "penalty_applied", nullable = false)
    private boolean penaltyApplied = false;

    @Column(name = "penalty_amount", precision = 19, scale = 4)
    private BigDecimal penaltyAmount;

    @Column(name = "audit_receipt_id")
    private UUID auditReceiptId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ViolationStatus status = ViolationStatus.DETECTED;

    @Version
    private Long version;

    public enum ViolationType {
        RETENTION_EXCEEDED,     // Data retained beyond allowed period
        UNAUTHORIZED_USAGE,     // Data used for unauthorized purpose
        DELETION_FAILURE,       // Failed to delete data as required
        ACCESS_EXCEEDED,        // Unauthorized access to data
        UNAUTHORIZED_SHARING,   // Data shared without permission
        PURPOSE_VIOLATION       // Data used outside stated purpose
    }

    public enum Severity {
        CRITICAL,  // Immediate action required
        HIGH,      // Action within 24 hours
        MEDIUM,    // Action within 7 days
        LOW        // Advisory only
    }

    public enum ViolationStatus {
        DETECTED,      // Violation detected
        ACKNOWLEDGED,  // Violation acknowledged by requester
        INVESTIGATING, // Under investigation
        RESOLVED,      // Violation resolved
        ESCALATED,     // Escalated for further action
        DISMISSED      // Violation dismissed (false positive)
    }

    protected ObligationViolation() {}

    /**
     * Creates a new obligation violation record.
     */
    public static ObligationViolation create(
            UUID consentContractId,
            UUID obligationId,
            ViolationType violationType,
            Severity severity,
            String description,
            String evidenceHash) {
        
        var violation = new ObligationViolation();
        violation.consentContractId = consentContractId;
        violation.obligationId = obligationId;
        violation.violationType = violationType;
        violation.severity = severity;
        violation.description = description;
        violation.evidenceHash = evidenceHash;
        violation.detectedAt = Instant.now();
        violation.status = ViolationStatus.DETECTED;
        return violation;
    }

    /**
     * Acknowledges this violation.
     */
    public void acknowledge() {
        if (this.status != ViolationStatus.DETECTED) {
            throw new IllegalStateException("Can only acknowledge detected violations");
        }
        this.status = ViolationStatus.ACKNOWLEDGED;
        this.acknowledgedAt = Instant.now();
    }

    /**
     * Marks this violation as under investigation.
     */
    public void investigate() {
        if (this.status != ViolationStatus.ACKNOWLEDGED) {
            throw new IllegalStateException("Can only investigate acknowledged violations");
        }
        this.status = ViolationStatus.INVESTIGATING;
    }

    /**
     * Resolves this violation.
     */
    public void resolve() {
        this.status = ViolationStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    /**
     * Escalates this violation.
     */
    public void escalate() {
        this.status = ViolationStatus.ESCALATED;
    }

    /**
     * Dismisses this violation (false positive).
     */
    public void dismiss() {
        this.status = ViolationStatus.DISMISSED;
        this.resolvedAt = Instant.now();
    }

    /**
     * Applies a penalty for this violation.
     */
    public void applyPenalty(BigDecimal amount) {
        this.penaltyApplied = true;
        this.penaltyAmount = amount;
    }

    public void setAuditReceiptId(UUID auditReceiptId) {
        this.auditReceiptId = auditReceiptId;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getConsentContractId() { return consentContractId; }
    public UUID getObligationId() { return obligationId; }
    public ViolationType getViolationType() { return violationType; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public String getEvidenceHash() { return evidenceHash; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public boolean isPenaltyApplied() { return penaltyApplied; }
    public BigDecimal getPenaltyAmount() { return penaltyAmount; }
    public UUID getAuditReceiptId() { return auditReceiptId; }
    public ViolationStatus getStatus() { return status; }
}
