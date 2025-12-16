package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Transform Execution Log - Tracks transform applications with input/output hashes.
 * 
 * Property 18: Transform Restriction Enforcement
 * Validates: Requirements 220.2, 220.6
 * 
 * Logs transform executions for audit trail and rejection tracking.
 */
@Entity
@Table(name = "transform_execution_logs", indexes = {
    @Index(name = "idx_transform_exec_consent", columnList = "consent_contract_id"),
    @Index(name = "idx_transform_exec_query_plan", columnList = "query_plan_id"),
    @Index(name = "idx_transform_exec_timestamp", columnList = "executed_at"),
    @Index(name = "idx_transform_exec_status", columnList = "status")
})
public class TransformExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @Column(name = "query_plan_id", nullable = false)
    private UUID queryPlanId;

    /**
     * JSON array of applied transform names.
     */
    @Column(name = "applied_transforms", nullable = false, columnDefinition = "TEXT")
    private String appliedTransforms;

    /**
     * JSON array showing transform execution order.
     */
    @Column(name = "transform_chain", nullable = false, columnDefinition = "TEXT")
    private String transformChain;

    /**
     * SHA-256 hash of input data before transforms.
     */
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    /**
     * SHA-256 hash of output data after transforms.
     */
    @Column(name = "output_hash", nullable = false, length = 64)
    private String outputHash;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "executor_id", nullable = false)
    private UUID executorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "audit_receipt_id")
    private UUID auditReceiptId;

    public enum ExecutionStatus {
        SUCCESS,
        REJECTED,
        FAILED
    }

    protected TransformExecutionLog() {}

    /**
     * Creates a successful transform execution log.
     */
    public static TransformExecutionLog createSuccess(
            UUID consentContractId,
            UUID queryPlanId,
            String appliedTransforms,
            String transformChain,
            String inputHash,
            String outputHash,
            UUID executorId) {
        
        validateInputs(consentContractId, queryPlanId, appliedTransforms, 
                transformChain, inputHash, outputHash, executorId);

        TransformExecutionLog log = new TransformExecutionLog();
        log.consentContractId = consentContractId;
        log.queryPlanId = queryPlanId;
        log.appliedTransforms = appliedTransforms;
        log.transformChain = transformChain;
        log.inputHash = inputHash;
        log.outputHash = outputHash;
        log.executorId = executorId;
        log.executedAt = Instant.now();
        log.status = ExecutionStatus.SUCCESS;
        return log;
    }

    /**
     * Creates a rejected transform execution log.
     * Requirement 220.6: Reject unauthorized transforms with logging.
     */
    public static TransformExecutionLog createRejected(
            UUID consentContractId,
            UUID queryPlanId,
            String appliedTransforms,
            String transformChain,
            String inputHash,
            UUID executorId,
            String rejectionReason) {
        
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }
        if (queryPlanId == null) {
            throw new IllegalArgumentException("Query plan ID cannot be null");
        }
        if (appliedTransforms == null || appliedTransforms.isBlank()) {
            throw new IllegalArgumentException("Applied transforms cannot be null or blank");
        }
        if (executorId == null) {
            throw new IllegalArgumentException("Executor ID cannot be null");
        }
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason cannot be null or blank");
        }

        TransformExecutionLog log = new TransformExecutionLog();
        log.consentContractId = consentContractId;
        log.queryPlanId = queryPlanId;
        log.appliedTransforms = appliedTransforms;
        log.transformChain = transformChain != null ? transformChain : "[]";
        log.inputHash = inputHash != null ? inputHash : "";
        log.outputHash = "";
        log.executorId = executorId;
        log.executedAt = Instant.now();
        log.status = ExecutionStatus.REJECTED;
        log.rejectionReason = rejectionReason;
        return log;
    }

    private static void validateInputs(
            UUID consentContractId,
            UUID queryPlanId,
            String appliedTransforms,
            String transformChain,
            String inputHash,
            String outputHash,
            UUID executorId) {
        
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }
        if (queryPlanId == null) {
            throw new IllegalArgumentException("Query plan ID cannot be null");
        }
        if (appliedTransforms == null || appliedTransforms.isBlank()) {
            throw new IllegalArgumentException("Applied transforms cannot be null or blank");
        }
        if (transformChain == null || transformChain.isBlank()) {
            throw new IllegalArgumentException("Transform chain cannot be null or blank");
        }
        if (inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException("Input hash cannot be null or blank");
        }
        if (outputHash == null || outputHash.isBlank()) {
            throw new IllegalArgumentException("Output hash cannot be null or blank");
        }
        if (executorId == null) {
            throw new IllegalArgumentException("Executor ID cannot be null");
        }
    }

    /**
     * Links this execution log to an audit receipt.
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
    public String getAppliedTransforms() { return appliedTransforms; }
    public String getTransformChain() { return transformChain; }
    public String getInputHash() { return inputHash; }
    public String getOutputHash() { return outputHash; }
    public Instant getExecutedAt() { return executedAt; }
    public UUID getExecutorId() { return executorId; }
    public ExecutionStatus getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getAuditReceiptId() { return auditReceiptId; }
}
