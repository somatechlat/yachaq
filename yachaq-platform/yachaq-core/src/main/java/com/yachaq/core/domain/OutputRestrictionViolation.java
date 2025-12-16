package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Output Restriction Violation - Tracks violation attempts in clean room sessions.
 * 
 * Validates: Requirements 221.3, 221.4
 * 
 * Records attempts to violate output restrictions for audit and enforcement.
 */
@Entity
@Table(name = "output_restriction_violations", indexes = {
    @Index(name = "idx_violation_session", columnList = "session_id"),
    @Index(name = "idx_violation_type", columnList = "violation_type"),
    @Index(name = "idx_violation_timestamp", columnList = "occurred_at")
})
public class OutputRestrictionViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_type", nullable = false)
    private ViolationType violationType;

    @Column(name = "restriction_violated", nullable = false)
    private String restrictionViolated;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "blocked", nullable = false)
    private boolean blocked = true;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "audit_receipt_id")
    private UUID auditReceiptId;

    public enum ViolationType {
        EXPORT_ATTEMPT,
        COPY_ATTEMPT,
        SCREENSHOT_ATTEMPT,
        DOWNLOAD_ATTEMPT,
        PRINT_ATTEMPT,
        RAW_ACCESS_ATTEMPT
    }

    protected OutputRestrictionViolation() {}

    /**
     * Creates a new violation record.
     * 
     * @param sessionId The clean room session ID
     * @param violationType Type of violation attempted
     * @param restrictionViolated The restriction that was violated
     * @param blocked Whether the violation was blocked
     * @param details Additional details about the violation
     */
    public static OutputRestrictionViolation create(
            UUID sessionId,
            ViolationType violationType,
            String restrictionViolated,
            boolean blocked,
            String details) {
        
        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID cannot be null");
        }
        if (violationType == null) {
            throw new IllegalArgumentException("Violation type cannot be null");
        }
        if (restrictionViolated == null || restrictionViolated.isBlank()) {
            throw new IllegalArgumentException("Restriction violated cannot be null or blank");
        }

        OutputRestrictionViolation violation = new OutputRestrictionViolation();
        violation.sessionId = sessionId;
        violation.violationType = violationType;
        violation.restrictionViolated = restrictionViolated;
        violation.blocked = blocked;
        violation.details = details;
        violation.occurredAt = Instant.now();
        return violation;
    }

    /**
     * Links this violation to an audit receipt.
     */
    public void linkToAuditReceipt(UUID auditReceiptId) {
        if (auditReceiptId == null) {
            throw new IllegalArgumentException("Audit receipt ID cannot be null");
        }
        this.auditReceiptId = auditReceiptId;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public ViolationType getViolationType() { return violationType; }
    public String getRestrictionViolated() { return restrictionViolated; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isBlocked() { return blocked; }
    public String getDetails() { return details; }
    public UUID getAuditReceiptId() { return auditReceiptId; }
}
