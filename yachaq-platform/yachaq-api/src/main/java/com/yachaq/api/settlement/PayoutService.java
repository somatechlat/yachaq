package com.yachaq.api.settlement;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payout Service - Orchestrate payouts to Data Sovereigns.
 * 
 * Requirements: 11.3, 11.4, 11.5, 110.1
 * 
 * WHEN a user requests payout THEN the System SHALL perform fraud/abuse checks
 * and compliance verification.
 * WHEN payout is approved THEN the System SHALL transfer funds via region-appropriate
 * methods (bank transfer, mobile money, local providers).
 * WHEN payout completes THEN the System SHALL generate a payout receipt and mark
 * YC credits as settled.
 */
@Service
public class PayoutService {

    private final DSBalanceRepository dsBalanceRepository;
    private final PayoutInstructionRepository payoutInstructionRepository;
    private final AuditService auditService;

    @Value("${yachaq.payout.min-amount:10.00}")
    private BigDecimal minPayoutAmount;

    @Value("${yachaq.payout.max-daily-amount:10000.00}")
    private BigDecimal maxDailyPayoutAmount;

    @Value("${yachaq.payout.fraud-velocity-threshold:5}")
    private int fraudVelocityThreshold;

    public PayoutService(
            DSBalanceRepository dsBalanceRepository,
            PayoutInstructionRepository payoutInstructionRepository,
            AuditService auditService) {
        this.dsBalanceRepository = dsBalanceRepository;
        this.payoutInstructionRepository = payoutInstructionRepository;
        this.auditService = auditService;
    }

    /**
     * Request a payout for a Data Sovereign.
     * Requirements: 11.3, 11.4
     * 
     * @param dsId The Data Sovereign ID
     * @param amount The amount to pay out
     * @param method The payout method
     * @param destinationHash Hashed destination (bank account, mobile money, etc.)
     * @return PayoutResult with status and receipt
     */
    @Transactional
    public PayoutResult requestPayout(UUID dsId, BigDecimal amount, PayoutMethod method, String destinationHash) {
        // Get DS balance
        DSBalance balance = dsBalanceRepository.findByDsId(dsId)
            .orElseThrow(() -> new PayoutException("DS balance not found: " + dsId));

        // Validate amount
        if (amount.compareTo(minPayoutAmount) < 0) {
            return createFailedResult(dsId, amount, "Amount below minimum: " + minPayoutAmount);
        }

        if (amount.compareTo(balance.getAvailableBalance()) > 0) {
            return createFailedResult(dsId, amount, "Insufficient balance");
        }

        // Fraud checks (Requirement 11.3)
        FraudCheckResult fraudCheck = performFraudChecks(dsId, amount, method);
        if (!fraudCheck.passed()) {
            return createFailedResult(dsId, amount, "Fraud check failed: " + fraudCheck.reason());
        }

        // Create payout instruction
        PayoutInstruction instruction = new PayoutInstruction();
        instruction.setDsId(dsId);
        instruction.setAmount(amount);
        instruction.setCurrency("YC");
        instruction.setMethod(method);
        instruction.setDestinationHash(destinationHash);
        instruction.setStatus(PayoutStatus.PENDING);
        payoutInstructionRepository.save(instruction);

        // Deduct from available balance, add to pending
        balance.setAvailableBalance(balance.getAvailableBalance().subtract(amount));
        balance.setPendingBalance(balance.getPendingBalance().add(amount));
        dsBalanceRepository.save(balance);

        // Generate audit receipt
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.PAYOUT_REQUESTED,
            dsId,
            ActorType.DS,
            instruction.getId(),
            "payout_instruction",
            "Payout requested: " + amount + " YC via " + method
        );

        return new PayoutResult(
            instruction.getId(),
            dsId,
            amount,
            method,
            receipt.getId(),
            Instant.now(),
            PayoutStatus.PENDING,
            "Payout request submitted"
        );
    }

    /**
     * Process a pending payout (called by payout processor).
     * Requirements: 11.4, 11.5
     */
    @Transactional
    public PayoutResult processPayout(UUID payoutId) {
        PayoutInstruction instruction = payoutInstructionRepository.findById(payoutId)
            .orElseThrow(() -> new PayoutException("Payout not found: " + payoutId));

        if (instruction.getStatus() != PayoutStatus.PENDING) {
            throw new PayoutException("Payout not in pending status: " + instruction.getStatus());
        }

        // Update status to processing
        instruction.setStatus(PayoutStatus.PROCESSING);
        payoutInstructionRepository.save(instruction);

        // Simulate payout processing (in production, call actual payment provider)
        boolean success = executePayoutTransfer(instruction);

        if (success) {
            // Update instruction
            instruction.setStatus(PayoutStatus.COMPLETED);
            instruction.setCompletedAt(Instant.now());
            payoutInstructionRepository.save(instruction);

            // Update DS balance
            DSBalance balance = dsBalanceRepository.findByDsId(instruction.getDsId())
                .orElseThrow(() -> new PayoutException("DS balance not found"));
            balance.setPendingBalance(balance.getPendingBalance().subtract(instruction.getAmount()));
            balance.setTotalPaidOut(balance.getTotalPaidOut().add(instruction.getAmount()));
            balance.setLastPayoutAt(Instant.now());
            dsBalanceRepository.save(balance);

            // Generate completion receipt (Requirement 11.5)
            AuditReceipt receipt = auditService.appendReceipt(
                EventType.PAYOUT_COMPLETED,
                instruction.getDsId(),
                ActorType.SYSTEM,
                instruction.getId(),
                "payout_instruction",
                "Payout completed: " + instruction.getAmount() + " YC"
            );

            return new PayoutResult(
                payoutId,
                instruction.getDsId(),
                instruction.getAmount(),
                instruction.getMethod(),
                receipt.getId(),
                Instant.now(),
                PayoutStatus.COMPLETED,
                "Payout completed successfully"
            );
        } else {
            // Revert to pending for retry
            instruction.setStatus(PayoutStatus.FAILED);
            payoutInstructionRepository.save(instruction);

            // Restore balance
            DSBalance balance = dsBalanceRepository.findByDsId(instruction.getDsId())
                .orElseThrow(() -> new PayoutException("DS balance not found"));
            balance.setPendingBalance(balance.getPendingBalance().subtract(instruction.getAmount()));
            balance.setAvailableBalance(balance.getAvailableBalance().add(instruction.getAmount()));
            dsBalanceRepository.save(balance);

            return new PayoutResult(
                payoutId,
                instruction.getDsId(),
                instruction.getAmount(),
                instruction.getMethod(),
                null,
                Instant.now(),
                PayoutStatus.FAILED,
                "Payout transfer failed"
            );
        }
    }

    /**
     * Perform fraud checks before payout.
     * Requirements: 11.3, 110.1
     */
    private FraudCheckResult performFraudChecks(UUID dsId, BigDecimal amount, PayoutMethod method) {
        // Check velocity (number of payouts in last 24 hours)
        long recentPayouts = payoutInstructionRepository.countRecentPayouts(dsId, 
            Instant.now().minusSeconds(86400));
        if (recentPayouts >= fraudVelocityThreshold) {
            return new FraudCheckResult(false, "Velocity threshold exceeded");
        }

        // Check daily amount limit
        BigDecimal dailyTotal = payoutInstructionRepository.sumRecentPayouts(dsId,
            Instant.now().minusSeconds(86400));
        if (dailyTotal != null && dailyTotal.add(amount).compareTo(maxDailyPayoutAmount) > 0) {
            return new FraudCheckResult(false, "Daily payout limit exceeded");
        }

        // Additional checks would include:
        // - Device fingerprint verification
        // - Behavioral anomaly detection
        // - Account age verification
        // - Geographic consistency

        return new FraudCheckResult(true, "All checks passed");
    }

    /**
     * Execute the actual payout transfer.
     * In production, this would call the appropriate payment provider.
     */
    private boolean executePayoutTransfer(PayoutInstruction instruction) {
        // Simulate successful transfer
        // In production: call bank API, mobile money API, etc.
        return true;
    }

    private PayoutResult createFailedResult(UUID dsId, BigDecimal amount, String reason) {
        return new PayoutResult(
            null,
            dsId,
            amount,
            null,
            null,
            Instant.now(),
            PayoutStatus.FAILED,
            reason
        );
    }

    // DTOs and Enums
    public record PayoutResult(
        UUID payoutId,
        UUID dsId,
        BigDecimal amount,
        PayoutMethod method,
        UUID receiptId,
        Instant timestamp,
        PayoutStatus status,
        String message
    ) {}

    public record FraudCheckResult(boolean passed, String reason) {}

    public enum PayoutMethod {
        BANK_TRANSFER,
        MOBILE_MONEY,
        CRYPTO,
        LOCAL_PROVIDER
    }

    public enum PayoutStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static class PayoutException extends RuntimeException {
        public PayoutException(String message) { super(message); }
    }
}
