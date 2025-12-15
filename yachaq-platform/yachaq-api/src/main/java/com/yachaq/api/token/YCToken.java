package com.yachaq.api.token;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * YC Token - YACHAQ Credits entity.
 * 
 * Requirements: 192.1, 192.2, 192.3, 192.4
 * 
 * YC is a non-speculative utility credit for settlement accounting.
 * Non-transferable by default (no P2P token trading).
 */
@Entity
@Table(name = "yc_tokens")
public class YCToken {

    @Id
    private UUID id;

    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private OperationType operationType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "escrow_id")
    private UUID escrowId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "audit_receipt_id")
    private UUID auditReceiptId;

    public YCToken() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }

    public enum OperationType {
        ISSUANCE,      // Credits issued from settlement
        REDEMPTION,    // Credits redeemed for payout
        CLAWBACK,      // Credits reversed due to fraud/chargeback
        ADJUSTMENT     // Administrative adjustment
    }

    // Factory methods
    public static YCToken issue(UUID dsId, BigDecimal amount, UUID referenceId, 
                                String referenceType, UUID escrowId, String description) {
        YCToken token = new YCToken();
        token.dsId = dsId;
        token.amount = amount;
        token.operationType = OperationType.ISSUANCE;
        token.referenceId = referenceId;
        token.referenceType = referenceType;
        token.escrowId = escrowId;
        token.description = description;
        token.idempotencyKey = "ISSUE:" + referenceId + ":" + dsId;
        return token;
    }

    public static YCToken redeem(UUID dsId, BigDecimal amount, UUID payoutId, String description) {
        YCToken token = new YCToken();
        token.dsId = dsId;
        token.amount = amount.negate(); // Negative for redemption
        token.operationType = OperationType.REDEMPTION;
        token.referenceId = payoutId;
        token.referenceType = "payout_instruction";
        token.description = description;
        token.idempotencyKey = "REDEEM:" + payoutId + ":" + dsId;
        return token;
    }

    public static YCToken clawback(UUID dsId, BigDecimal amount, UUID disputeId, String reason) {
        YCToken token = new YCToken();
        token.dsId = dsId;
        token.amount = amount.negate(); // Negative for clawback
        token.operationType = OperationType.CLAWBACK;
        token.referenceId = disputeId;
        token.referenceType = "dispute";
        token.description = reason;
        token.idempotencyKey = "CLAWBACK:" + disputeId + ":" + dsId;
        return token;
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDsId() { return dsId; }
    public void setDsId(UUID dsId) { this.dsId = dsId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public OperationType getOperationType() { return operationType; }
    public void setOperationType(OperationType operationType) { this.operationType = operationType; }

    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID referenceId) { this.referenceId = referenceId; }

    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public UUID getEscrowId() { return escrowId; }
    public void setEscrowId(UUID escrowId) { this.escrowId = escrowId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getAuditReceiptId() { return auditReceiptId; }
    public void setAuditReceiptId(UUID auditReceiptId) { this.auditReceiptId = auditReceiptId; }
}
