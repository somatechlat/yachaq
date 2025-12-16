package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Field Access Log - Tracks field-level access with cryptographic hashes.
 * 
 * Property 17: Field-Level Access Enforcement
 * Validates: Requirements 219.2, 219.5
 * 
 * Logs field access with hash in receipt for audit trail.
 */
@Entity
@Table(name = "field_access_logs", indexes = {
    @Index(name = "idx_field_access_consent", columnList = "consent_contract_id"),
    @Index(name = "idx_field_access_query_plan", columnList = "query_plan_id"),
    @Index(name = "idx_field_access_timestamp", columnList = "accessed_at"),
    @Index(name = "idx_field_access_accessor", columnList = "accessor_id")
})
public class FieldAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @Column(name = "query_plan_id", nullable = false)
    private UUID queryPlanId;

    /**
     * JSON array of accessed field names.
     */
    @Column(name = "accessed_fields", nullable = false, columnDefinition = "TEXT")
    private String accessedFields;

    /**
     * JSON object mapping field names to their cryptographic hashes.
     * Validates: Requirements 219.5
     */
    @Column(name = "field_hashes", nullable = false, columnDefinition = "TEXT")
    private String fieldHashes;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    @Column(name = "accessor_id", nullable = false)
    private UUID accessorId;

    @Column(name = "audit_receipt_id")
    private UUID auditReceiptId;

    protected FieldAccessLog() {}

    /**
     * Creates a new field access log entry.
     * 
     * @param consentContractId The consent contract authorizing access
     * @param queryPlanId The query plan being executed
     * @param accessedFields JSON array of accessed field names
     * @param fieldHashes JSON object mapping field names to hashes
     * @param accessorId The requester accessing the fields
     */
    public static FieldAccessLog create(
            UUID consentContractId,
            UUID queryPlanId,
            String accessedFields,
            String fieldHashes,
            UUID accessorId) {
        
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }
        if (queryPlanId == null) {
            throw new IllegalArgumentException("Query plan ID cannot be null");
        }
        if (accessedFields == null || accessedFields.isBlank()) {
            throw new IllegalArgumentException("Accessed fields cannot be null or blank");
        }
        if (fieldHashes == null || fieldHashes.isBlank()) {
            throw new IllegalArgumentException("Field hashes cannot be null or blank");
        }
        if (accessorId == null) {
            throw new IllegalArgumentException("Accessor ID cannot be null");
        }

        FieldAccessLog log = new FieldAccessLog();
        log.consentContractId = consentContractId;
        log.queryPlanId = queryPlanId;
        log.accessedFields = accessedFields;
        log.fieldHashes = fieldHashes;
        log.accessorId = accessorId;
        log.accessedAt = Instant.now();
        return log;
    }

    /**
     * Links this access log to an audit receipt.
     */
    public void linkToAuditReceipt(UUID auditReceiptId) {
        if (auditReceiptId == null) {
            throw new IllegalArgumentException("Audit receipt ID cannot be null");
        }
        this.auditReceiptId = auditReceiptId;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getConsentContractId() { return consentContractId; }
    public UUID getQueryPlanId() { return queryPlanId; }
    public String getAccessedFields() { return accessedFields; }
    public String getFieldHashes() { return fieldHashes; }
    public Instant getAccessedAt() { return accessedAt; }
    public UUID getAccessorId() { return accessorId; }
    public UUID getAuditReceiptId() { return auditReceiptId; }
}
