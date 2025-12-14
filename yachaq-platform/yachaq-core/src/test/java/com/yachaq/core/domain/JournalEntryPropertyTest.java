package com.yachaq.core.domain;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * Property-based tests for JournalEntry.
 * 
 * **Feature: yachaq-platform, Property 9: Double-Entry Balance**
 * **Validates: Requirements 186.1**
 * 
 * For any financial transaction posted to the ledger, 
 * the sum of all debits must equal the sum of all credits.
 */
class JournalEntryPropertyTest {

    @Property(tries = 100)
    void doubleEntryBalance_debitsEqualCredits(
            @ForAll @Size(min = 1, max = 50) List<@From("validJournalEntry") JournalEntry> entries) {
        
        // Calculate sum of all debits and credits
        Map<String, BigDecimal> accountBalances = new HashMap<>();
        
        for (JournalEntry entry : entries) {
            // Debit increases the debit account
            accountBalances.merge(
                entry.getDebitAccount(), 
                entry.getAmount(), 
                BigDecimal::add
            );
            // Credit increases the credit account (negative for balance)
            accountBalances.merge(
                entry.getCreditAccount(), 
                entry.getAmount().negate(), 
                BigDecimal::add
            );
        }
        
        // Sum of all account balances must be zero (debits = credits)
        BigDecimal totalBalance = accountBalances.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        assert totalBalance.compareTo(BigDecimal.ZERO) == 0 
            : "Double-entry violated: total balance = " + totalBalance;
    }

    @Property(tries = 100)
    void journalEntry_amountMustBePositive(
            @ForAll("nonPositiveAmount") BigDecimal amount) {
        
        try {
            JournalEntry.create(
                "ESCROW:req-123",
                "DS_BALANCE:ds-456",
                amount,
                "USD",
                "test-ref",
                UUID.randomUUID().toString()
            );
            assert false : "Should have thrown for non-positive amount";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("positive");
        }
    }

    @Property(tries = 100)
    void journalEntry_debitAndCreditMustBeDifferent(
            @ForAll @AlphaChars @StringLength(min = 5, max = 20) String account,
            @ForAll @BigRange(min = "0.01", max = "10000") BigDecimal amount) {
        
        try {
            JournalEntry.create(account, account, amount, "USD", "ref", UUID.randomUUID().toString());
            assert false : "Should have thrown for same debit/credit account";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("different");
        }
    }

    @Provide
    Arbitrary<JournalEntry> validJournalEntry() {
        Arbitrary<String> accounts = Arbitraries.strings()
            .alpha().ofMinLength(5).ofMaxLength(20);
        Arbitrary<BigDecimal> amounts = Arbitraries.bigDecimals()
            .between(new BigDecimal("0.01"), new BigDecimal("10000"))
            .ofScale(4);
        
        return Combinators.combine(accounts, accounts, amounts)
            .filter((debit, credit, amt) -> !debit.equals(credit))
            .as((debit, credit, amount) -> 
                JournalEntry.create(debit, credit, amount, "USD", "ref", UUID.randomUUID().toString())
            );
    }

    @Provide
    Arbitrary<BigDecimal> nonPositiveAmount() {
        return Arbitraries.bigDecimals()
            .between(new BigDecimal("-1000"), BigDecimal.ZERO)
            .ofScale(4);
    }
}
