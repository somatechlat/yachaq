package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Escrow account for holding requester funds until consent completion.
 * 
 * Property 3: Escrow Funding Prerequisite
 * Validates: Requirements 7.1, 7.2
 */
@Entity
@Table(name = "escrow_accounts", indexes = {
    @Index(name = "idx_escrow_requester", columnList = "requester_id"),
    @Index(name = "idx_escrow_request", columnList = "request_id"),
    @Index(name = "idx_escrow_status", columnList = "status")
})
public class EscrowAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @NotNull
    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @NotNull
    @PositiveOrZero
    @Column(name = "funded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal fundedAmount;

    @NotNull
    @PositiveOrZero
    @Column(name = "locked_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lockedAmount;

    @NotNull
    @PositiveOrZero
    @Column(name = "released_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal releasedAmount;

    @NotNull
    @PositiveOrZero
    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundedAmount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscrowStatus status;

    @Column(name = "blockchain_tx_hash")
    private String blockchainTxHash;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected EscrowAccount() {}

    public static EscrowAccount create(UUID requesterId, UUID requestId) {
        var account = new EscrowAccount();
        account.requesterId = requesterId;
        account.requestId = requestId;
        account.fundedAmount = BigDecimal.ZERO;
        account.lockedAmount = BigDecimal.ZERO;
        account.releasedAmount = BigDecimal.ZERO;
        account.refundedAmount = BigDecimal.ZERO;
        account.status = EscrowStatus.PENDING;
        account.createdAt = Instant.now();
        return account;
    }

    public void fund(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        this.fundedAmount = this.fundedAmount.add(amount);
        this.status = EscrowStatus.FUNDED;
    }

    public void lock(BigDecimal amount) {
        if (amount.compareTo(getAvailableAmount()) > 0) {
            throw new IllegalStateException("Insufficient funds to lock");
        }
        this.lockedAmount = this.lockedAmount.add(amount);
        this.status = EscrowStatus.LOCKED;
    }

    public void release(BigDecimal amount) {
        if (amount.compareTo(this.lockedAmount) > 0) {
            throw new IllegalStateException("Cannot release more than locked");
        }
        this.lockedAmount = this.lockedAmount.subtract(amount);
        this.releasedAmount = this.releasedAmount.add(amount);
        if (this.lockedAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.status = EscrowStatus.SETTLED;
        }
    }

    public void refund(BigDecimal amount) {
        if (amount.compareTo(getAvailableAmount()) > 0) {
            throw new IllegalStateException("Insufficient funds to refund");
        }
        this.fundedAmount = this.fundedAmount.subtract(amount);
        this.refundedAmount = this.refundedAmount.add(amount);
        if (this.fundedAmount.compareTo(BigDecimal.ZERO) == 0) {
            this.status = EscrowStatus.REFUNDED;
        }
    }

    public BigDecimal getAvailableAmount() {
        return fundedAmount.subtract(lockedAmount).subtract(releasedAmount);
    }

    public boolean isSufficientlyFunded(BigDecimal requiredAmount) {
        return fundedAmount.compareTo(requiredAmount) >= 0;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getRequestId() { return requestId; }
    public BigDecimal getFundedAmount() { return fundedAmount; }
    public BigDecimal getLockedAmount() { return lockedAmount; }
    public BigDecimal getReleasedAmount() { return releasedAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public EscrowStatus getStatus() { return status; }
    public String getBlockchainTxHash() { return blockchainTxHash; }
    public Instant getCreatedAt() { return createdAt; }

    public void setBlockchainTxHash(String hash) { this.blockchainTxHash = hash; }

    public enum EscrowStatus {
        PENDING, FUNDED, LOCKED, SETTLED, REFUNDED
    }
}
