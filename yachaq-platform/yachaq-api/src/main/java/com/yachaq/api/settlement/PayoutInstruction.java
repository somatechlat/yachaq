package com.yachaq.api.settlement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payout Instruction - Tracks payout requests and their status.
 * 
 * Requirements: 11.4, 11.5
 */
@Entity
@Table(name = "payout_instructions")
public class PayoutInstruction {

    @Id
    private UUID id;

    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private PayoutService.PayoutMethod method;

    @Column(name = "destination_hash", nullable = false)
    private String destinationHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PayoutService.PayoutStatus status;

    @Column(name = "receipt_id")
    private UUID receiptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    public PayoutInstruction() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.status = PayoutService.PayoutStatus.PENDING;
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDsId() { return dsId; }
    public void setDsId(UUID dsId) { this.dsId = dsId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PayoutService.PayoutMethod getMethod() { return method; }
    public void setMethod(PayoutService.PayoutMethod method) { this.method = method; }

    public String getDestinationHash() { return destinationHash; }
    public void setDestinationHash(String destinationHash) { this.destinationHash = destinationHash; }

    public PayoutService.PayoutStatus getStatus() { return status; }
    public void setStatus(PayoutService.PayoutStatus status) { this.status = status; }

    public UUID getReceiptId() { return receiptId; }
    public void setReceiptId(UUID receiptId) { this.receiptId = receiptId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
