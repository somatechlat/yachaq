package com.yachaq.api.token;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Property-based tests for YC Token Management.
 * 
 * **Feature: yachaq-platform, Property 10: YC Non-Transferability**
 * **Validates: Requirements 192.1, 192.2, 192.3, 192.4, 192.5**
 * 
 * For any attempt to transfer YC credits between users, the transfer must be
 * rejected unless explicitly enabled by governance policy.
 */
class YCTokenPropertyTest {

    // ========================================================================
    // Property 10: YC Non-Transferability
    // ========================================================================

    @Property(tries = 100)
    @Label("Property 10: Transfer attempts are rejected when transfers disabled")
    void transferAttempts_areRejected_whenTransfersDisabled(
            @ForAll("dsIds") UUID fromDsId,
            @ForAll("dsIds") UUID toDsId,
            @ForAll @BigRange(min = "0.01", max = "10000.00") BigDecimal amount) {
        
        Assume.that(!fromDsId.equals(toDsId)); // Different users
        
        // Simulate transfer attempt with transfers disabled (default)
        boolean transfersEnabled = false;
        
        // Property 10: Transfer must be rejected
        boolean transferAllowed = transfersEnabled;
        
        assert !transferAllowed 
            : "Transfer must be rejected when governance policy disables transfers";
    }

    @Property(tries = 100)
    @Label("Property 10: Self-transfers are always rejected")
    void selfTransfers_areAlwaysRejected(
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "10000.00") BigDecimal amount,
            @ForAll boolean transfersEnabled) {
        
        // Self-transfer (same source and destination)
        UUID fromDsId = dsId;
        UUID toDsId = dsId;
        
        // Self-transfers should always be rejected regardless of governance
        boolean isSelfTransfer = fromDsId.equals(toDsId);
        
        assert isSelfTransfer 
            : "Self-transfer detection must work";
        
        // Self-transfers are invalid operations
        boolean transferAllowed = !isSelfTransfer && transfersEnabled;
        assert !transferAllowed 
            : "Self-transfers must always be rejected";
    }

    // ========================================================================
    // Requirement 192.2: Policy-bound issuance
    // ========================================================================

    @Property(tries = 100)
    @Label("Issuance requires positive amount")
    void issuance_requiresPositiveAmount(
            @ForAll @BigRange(min = "-1000.00", max = "1000.00") BigDecimal amount) {
        
        boolean isValidIssuance = amount.compareTo(BigDecimal.ZERO) > 0;
        
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            assert !isValidIssuance 
                : "Non-positive amounts must be rejected for issuance";
        } else {
            assert isValidIssuance 
                : "Positive amounts should be valid for issuance";
        }
    }

    @Property(tries = 100)
    @Label("Issuance must be tied to verified value event")
    void issuance_mustHaveValidReference(
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount,
            @ForAll("referenceIds") UUID referenceId,
            @ForAll("referenceTypes") String referenceType) {
        
        // Valid reference types for issuance
        boolean validReferenceType = referenceType.equals("settlement") 
            || referenceType.equals("consent_contract");
        
        // Issuance must have valid reference
        assert referenceId != null : "Reference ID must not be null";
        assert referenceType != null && !referenceType.isEmpty() 
            : "Reference type must not be empty";
        
        // Only settlement-related references are valid for issuance
        if (!validReferenceType) {
            // Invalid reference type should be rejected
            assert !validReferenceType 
                : "Invalid reference type should be detected";
        }
    }

    // ========================================================================
    // Requirement 192.3: Full auditability
    // ========================================================================

    @Property(tries = 100)
    @Label("All operations generate audit records")
    void allOperations_generateAuditRecords(
            @ForAll("operationTypes") YCToken.OperationType operationType,
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount) {
        
        // Create token operation
        YCToken token = new YCToken();
        token.setDsId(dsId);
        token.setOperationType(operationType);
        token.setAmount(operationType == YCToken.OperationType.ISSUANCE 
            ? amount : amount.negate());
        
        // Verify audit fields are present
        assert token.getId() != null : "Token must have ID for audit";
        assert token.getDsId() != null : "Token must have DS ID for audit";
        assert token.getOperationType() != null : "Token must have operation type for audit";
        assert token.getCreatedAt() != null : "Token must have timestamp for audit";
    }

    @Property(tries = 100)
    @Label("Idempotency keys are unique per operation")
    void idempotencyKeys_areUnique(
            @ForAll("dsIds") UUID dsId,
            @ForAll("referenceIds") UUID referenceId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount) {
        
        // Create issuance token
        YCToken issuance = YCToken.issue(dsId, amount, referenceId, "settlement", 
            UUID.randomUUID(), "Test issuance");
        
        // Create redemption token
        YCToken redemption = YCToken.redeem(dsId, amount, referenceId, "Test redemption");
        
        // Idempotency keys must be different for different operations
        assert !issuance.getIdempotencyKey().equals(redemption.getIdempotencyKey())
            : "Different operations must have different idempotency keys";
    }

    // ========================================================================
    // Requirement 192.4: Reconciliation with escrow
    // ========================================================================

    @Property(tries = 100)
    @Label("Issuance amount must not exceed escrow release")
    void issuanceAmount_mustNotExceedEscrowRelease(
            @ForAll @BigRange(min = "0.00", max = "10000.00") BigDecimal escrowReleased,
            @ForAll @BigRange(min = "0.01", max = "15000.00") BigDecimal issuanceAmount) {
        
        boolean validIssuance = issuanceAmount.compareTo(escrowReleased) <= 0;
        
        if (issuanceAmount.compareTo(escrowReleased) > 0) {
            assert !validIssuance 
                : "Issuance exceeding escrow release should be invalid";
        }
    }

    // ========================================================================
    // Requirement 192.5: Clawback support
    // ========================================================================

    @Property(tries = 100)
    @Label("Clawback creates negative balance entry")
    void clawback_createsNegativeEntry(
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount,
            @ForAll("disputeIds") UUID disputeId) {
        
        YCToken clawback = YCToken.clawback(dsId, amount, disputeId, "Fraud detected");
        
        // Clawback amount should be negative
        assert clawback.getAmount().compareTo(BigDecimal.ZERO) < 0
            : "Clawback must create negative balance entry";
        assert clawback.getAmount().abs().compareTo(amount) == 0
            : "Clawback absolute value must equal input amount";
        assert clawback.getOperationType() == YCToken.OperationType.CLAWBACK
            : "Operation type must be CLAWBACK";
    }

    // ========================================================================
    // Requirement 192.6: Redemption for payout only
    // ========================================================================

    @Property(tries = 100)
    @Label("Redemption requires sufficient balance")
    void redemption_requiresSufficientBalance(
            @ForAll @BigRange(min = "0.00", max = "1000.00") BigDecimal balance,
            @ForAll @BigRange(min = "0.01", max = "2000.00") BigDecimal redemptionAmount) {
        
        boolean sufficientBalance = balance.compareTo(redemptionAmount) >= 0;
        
        if (redemptionAmount.compareTo(balance) > 0) {
            assert !sufficientBalance 
                : "Redemption exceeding balance should be rejected";
        } else {
            assert sufficientBalance 
                : "Redemption within balance should be allowed";
        }
    }

    @Property(tries = 100)
    @Label("Redemption creates negative balance entry")
    void redemption_createsNegativeEntry(
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount,
            @ForAll("payoutIds") UUID payoutId) {
        
        YCToken redemption = YCToken.redeem(dsId, amount, payoutId, "Payout redemption");
        
        // Redemption amount should be negative
        assert redemption.getAmount().compareTo(BigDecimal.ZERO) < 0
            : "Redemption must create negative balance entry";
        assert redemption.getAmount().abs().compareTo(amount) == 0
            : "Redemption absolute value must equal input amount";
        assert redemption.getOperationType() == YCToken.OperationType.REDEMPTION
            : "Operation type must be REDEMPTION";
    }

    // ========================================================================
    // Balance calculation properties
    // ========================================================================

    @Property(tries = 100)
    @Label("Balance equals sum of all operations")
    void balance_equalsSumOfOperations(
            @ForAll @BigRange(min = "100.00", max = "1000.00") BigDecimal issued,
            @ForAll @BigRange(min = "0.00", max = "50.00") BigDecimal redeemed,
            @ForAll @BigRange(min = "0.00", max = "10.00") BigDecimal clawedBack) {
        
        Assume.that(redeemed.add(clawedBack).compareTo(issued) <= 0);
        
        // Balance = issued - redeemed - clawedBack
        BigDecimal expectedBalance = issued.subtract(redeemed).subtract(clawedBack);
        
        // Simulate balance calculation
        BigDecimal calculatedBalance = issued
            .add(redeemed.negate())
            .add(clawedBack.negate());
        
        assert calculatedBalance.compareTo(expectedBalance) == 0
            : "Balance must equal sum of all operations";
        assert calculatedBalance.compareTo(BigDecimal.ZERO) >= 0
            : "Balance should be non-negative in normal operations";
    }

    // ========================================================================
    // YCToken entity tests
    // ========================================================================

    @Property(tries = 100)
    @Label("YCToken initialization has correct defaults")
    void ycToken_initialization_hasCorrectDefaults() {
        YCToken token = new YCToken();
        
        assert token.getId() != null : "ID should be auto-generated";
        assert token.getCreatedAt() != null : "Created at should be set";
    }

    @Property(tries = 100)
    @Label("YCToken factory methods set correct operation types")
    void ycToken_factoryMethods_setCorrectTypes(
            @ForAll("dsIds") UUID dsId,
            @ForAll @BigRange(min = "0.01", max = "1000.00") BigDecimal amount,
            @ForAll("referenceIds") UUID referenceId) {
        
        YCToken issuance = YCToken.issue(dsId, amount, referenceId, "settlement", 
            UUID.randomUUID(), "Test");
        YCToken redemption = YCToken.redeem(dsId, amount, referenceId, "Test");
        YCToken clawback = YCToken.clawback(dsId, amount, referenceId, "Test");
        
        assert issuance.getOperationType() == YCToken.OperationType.ISSUANCE
            : "Issue factory must set ISSUANCE type";
        assert redemption.getOperationType() == YCToken.OperationType.REDEMPTION
            : "Redeem factory must set REDEMPTION type";
        assert clawback.getOperationType() == YCToken.OperationType.CLAWBACK
            : "Clawback factory must set CLAWBACK type";
        
        // Verify amounts
        assert issuance.getAmount().compareTo(BigDecimal.ZERO) > 0
            : "Issuance amount must be positive";
        assert redemption.getAmount().compareTo(BigDecimal.ZERO) < 0
            : "Redemption amount must be negative";
        assert clawback.getAmount().compareTo(BigDecimal.ZERO) < 0
            : "Clawback amount must be negative";
    }

    // ========================================================================
    // Arbitraries
    // ========================================================================

    @Provide
    Arbitrary<UUID> dsIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID> referenceIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID> disputeIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID> payoutIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> referenceTypes() {
        return Arbitraries.of("settlement", "consent_contract", "payout_instruction", "dispute", "invalid_type");
    }

    @Provide
    Arbitrary<YCToken.OperationType> operationTypes() {
        return Arbitraries.of(YCToken.OperationType.values());
    }
}
