package com.yachaq.api.settlement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DS Balance - Tracks Data Sovereign earnings and payouts.
 * 
 * Requirements: 11.1, 11.2
 */
@Entity
@Table(name = "ds_balances")
public class DSBalance {

    @Id
    private UUID id;

    @Column(name = "ds_id", nullable = false, unique = true)
    private UUID dsId;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "pending_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal pendingBalance;

    @Column(name = "total_earned", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalEarned;

    @Column(name = "total_paid_out", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalPaidOut;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "last_settlement_at")
    private Instant lastSettlementAt;

    @Column(name = "last_payout_at")
    private Instant lastPayoutAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DSBalance() {
        this.id = UUID.randomUUID();
        this.availableBalance = BigDecimal.ZERO;
        this.pendingBalance = BigDecimal.ZERO;
        this.totalEarned = BigDecimal.ZERO;
        this.totalPaidOut = BigDecimal.ZERO;
        this.currency = "YC"; // YACHAQ Credits
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDsId() { return dsId; }
    public void setDsId(UUID dsId) { this.dsId = dsId; }

    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal availableBalance) { 
        this.availableBalance = availableBalance;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getPendingBalance() { return pendingBalance; }
    public void setPendingBalance(BigDecimal pendingBalance) { 
        this.pendingBalance = pendingBalance;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getTotalEarned() { return totalEarned; }
    public void setTotalEarned(BigDecimal totalEarned) { 
        this.totalEarned = totalEarned;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getTotalPaidOut() { return totalPaidOut; }
    public void setTotalPaidOut(BigDecimal totalPaidOut) { 
        this.totalPaidOut = totalPaidOut;
        this.updatedAt = Instant.now();
    }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getLastSettlementAt() { return lastSettlementAt; }
    public void setLastSettlementAt(Instant lastSettlementAt) { this.lastSettlementAt = lastSettlementAt; }

    public Instant getLastPayoutAt() { return lastPayoutAt; }
    public void setLastPayoutAt(Instant lastPayoutAt) { this.lastPayoutAt = lastPayoutAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
