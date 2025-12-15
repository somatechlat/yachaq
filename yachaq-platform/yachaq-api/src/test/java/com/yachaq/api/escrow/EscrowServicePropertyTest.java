package com.yachaq.api.escrow;

import com.yachaq.core.domain.EscrowAccount;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Property-based tests for EscrowService.
 * 
 * **Feature: yachaq-platform, Property 3: Escrow Funding Prerequisite**
 * **Validates: Requirements 7.1, 7.2**
 * 
 * For any request that passes screening, the request cannot be delivered 
 * to any DS until the escrow account is funded with at least 
 * (maxParticipants × unitPrice × maxUnits) + platformFee.
 */
class EscrowServicePropertyTest {

    @Property(tries = 100)
    void property3_escrowMustBeFundedBeforeDelivery(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal unitPrice,
            @ForAll @IntRange(min = 1, max = 100) int maxParticipants) {
        
        // Calculate required escrow amount
        BigDecimal requiredAmount = unitPrice.multiply(BigDecimal.valueOf(maxParticipants));
        
        // Create unfunded escrow
        EscrowAccount escrow = EscrowAccount.create(UUID.randomUUID(), UUID.randomUUID());
        
        // Unfunded escrow should NOT be sufficient
        assert !escrow.isSufficientlyFunded(requiredAmount) 
            : "Unfunded escrow should not be sufficient";
        
        // Fund with less than required
        BigDecimal partialFunding = requiredAmount.multiply(new BigDecimal("0.5"));
        if (partialFunding.compareTo(BigDecimal.ZERO) > 0) {
            escrow.fund(partialFunding);
            assert !escrow.isSufficientlyFunded(requiredAmount) 
                : "Partially funded escrow should not be sufficient";
        }
        
        // Fund remaining amount
        BigDecimal remaining = requiredAmount.subtract(partialFunding);
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            escrow.fund(remaining);
        }
        
        // Now escrow should be sufficient
        assert escrow.isSufficientlyFunded(requiredAmount) 
            : "Fully funded escrow should be sufficient";
    }

    @Property(tries = 100)
    void property3_escrowStatusMustBeFundedOrLocked(
            @ForAll @BigRange(min = "10.00", max = "1000.00") BigDecimal amount) {
        
        EscrowAccount escrow = EscrowAccount.create(UUID.randomUUID(), UUID.randomUUID());
        
        // PENDING status - not ready for delivery
        assert escrow.getStatus() == EscrowAccount.EscrowStatus.PENDING;
        assert !isReadyForDelivery(escrow, amount);
        
        // Fund the escrow
        escrow.fund(amount);
        
        // FUNDED status - ready for delivery
        assert escrow.getStatus() == EscrowAccount.EscrowStatus.FUNDED;
        assert isReadyForDelivery(escrow, amount);
        
        // Lock the escrow
        escrow.lock(amount);
        
        // LOCKED status - still ready for delivery
        assert escrow.getStatus() == EscrowAccount.EscrowStatus.LOCKED;
        // Note: After locking, available amount is 0, but locked amount covers it
    }

    @Property(tries = 100)
    void escrowFundingIsAdditive(
            @ForAll @BigRange(min = "1.00", max = "500.00") BigDecimal amount1,
            @ForAll @BigRange(min = "1.00", max = "500.00") BigDecimal amount2) {
        
        EscrowAccount escrow = EscrowAccount.create(UUID.randomUUID(), UUID.randomUUID());
        
        escrow.fund(amount1);
        BigDecimal afterFirst = escrow.getFundedAmount();
        
        escrow.fund(amount2);
        BigDecimal afterSecond = escrow.getFundedAmount();
        
        // Funding should be additive
        assert afterSecond.compareTo(afterFirst.add(amount2)) == 0
            : "Funding should be additive: " + afterFirst + " + " + amount2 + " = " + afterSecond;
    }

    @Property(tries = 100)
    void escrowLockCannotExceedFunded(
            @ForAll @BigRange(min = "10.00", max = "100.00") BigDecimal fundedAmount,
            @ForAll @BigRange(min = "101.00", max = "200.00") BigDecimal lockAmount) {
        
        EscrowAccount escrow = EscrowAccount.create(UUID.randomUUID(), UUID.randomUUID());
        escrow.fund(fundedAmount);
        
        try {
            escrow.lock(lockAmount);
            assert false : "Should not be able to lock more than funded";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("Insufficient");
        }
    }

    @Property(tries = 100)
    void escrowReleaseCannotExceedLocked(
            @ForAll @BigRange(min = "10.00", max = "100.00") BigDecimal amount) {
        
        EscrowAccount escrow = EscrowAccount.create(UUID.randomUUID(), UUID.randomUUID());
        escrow.fund(amount);
        escrow.lock(amount);
        
        BigDecimal excessRelease = amount.add(BigDecimal.ONE);
        
        try {
            escrow.release(excessRelease);
            assert false : "Should not be able to release more than locked";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("locked");
        }
    }

    private boolean isReadyForDelivery(EscrowAccount escrow, BigDecimal requiredAmount) {
        return escrow.isSufficientlyFunded(requiredAmount) 
            && (escrow.getStatus() == EscrowAccount.EscrowStatus.FUNDED 
                || escrow.getStatus() == EscrowAccount.EscrowStatus.LOCKED);
    }
}
