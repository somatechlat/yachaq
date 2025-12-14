package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Double-entry journal entry for financial ledger.
 * Immutable once created.
 * 
 * Property 9: Double-Entry Balance
 * Validates: Requirements 186.1
 */
@Entity
@Table(name = "journal_entries", indexes = {
    @Index(name = "idx_journal_debit", columnList = "debit_account"),
    @Index(name = "idx_journal_credit", columnList = "credit_account"),
    @Index(name = "idx_journal_timestamp", columnList = "timestamp"),
    @Index(name = "idx_journal_idempotency", columnList = "idempotency_key", unique = true)
})
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(nullable = false)
    private Instant timestamp;

    @NotNull
    @Column(name = "debit_account", nullable = false)
    private String debitAccount;

    @NotNull
    @Column(name = "credit_account", nullable = false)
    private String creditAccount;

    @NotNull
    @Positive
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @NotNull
    @Column(nullable = false, length = 3)
    private String currency;

    @NotNull
    @Column(nullable = false)
    private String reference;

    @NotNull
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    protected JournalEntry() {}

    /**
     * Creates a double-entry journal entry.
     * Debit and credit must be different accounts.
     * Amount must be positive.
     */
    public static JournalEntry create(
            String debitAccount,
            String creditAccount,
            BigDecimal amount,
            String currency,
            String reference,
            String idempotencyKey) {
        
        if (debitAccount.equals(creditAccount)) {
            throw new IllegalArgumentException("Debit and credit accounts must be different");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        var entry = new JournalEntry();
        entry.timestamp = Instant.now();
        entry.debitAccount = debitAccount;
        entry.creditAccount = creditAccount;
        entry.amount = amount;
        entry.currency = currency;
        entry.reference = reference;
        entry.idempotencyKey = idempotencyKey;
        return entry;
    }

    // Getters (immutable - no setters)
    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getDebitAccount() { return debitAccount; }
    public String getCreditAccount() { return creditAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
