package com.yachaq.api.settlement;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.escrow.EscrowService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.repository.ConsentContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Settlement Service - Trigger settlement on consent completion.
 * 
 * Requirements: 11.1, 11.2
 * 
 * WHEN a user completes a participation unit THEN the System SHALL trigger
 * settlement and update user balance.
 * WHEN settlement occurs THEN the System SHALL generate an audit receipt
 * with amounts, timestamps, and receipt IDs.
 */
@Service
public class SettlementService {

    private final ConsentContractRepository consentContractRepository;
    private final EscrowService escrowService;
    private final AuditService auditService;
    private final DSBalanceRepository dsBalanceRepository;

    public SettlementService(
            ConsentContractRepository consentContractRepository,
            EscrowService escrowService,
            AuditService auditService,
            DSBalanceRepository dsBalanceRepository) {
        this.consentContractRepository = consentContractRepository;
        this.escrowService = escrowService;
        this.auditService = auditService;
        this.dsBalanceRepository = dsBalanceRepository;
    }

    /**
     * Trigger settlement for a completed participation unit.
     * Requirements: 11.1, 11.2
     * 
     * @param consentContractId The consent contract ID
     * @param unitCount Number of units completed
     * @return SettlementResult with receipt and updated balances
     */
    @Transactional
    public SettlementResult settleParticipation(UUID consentContractId, int unitCount) {
        ConsentContract contract = consentContractRepository.findById(consentContractId)
            .orElseThrow(() -> new SettlementException("Consent contract not found: " + consentContractId));

        if (contract.getStatus() != ConsentContract.ConsentStatus.ACTIVE) {
            throw new SettlementException("Cannot settle inactive consent: " + contract.getStatus());
        }

        // Calculate settlement amount
        BigDecimal unitPrice = contract.getCompensationAmount();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(unitCount));

        // Release funds from escrow
        var escrowOpt = escrowService.getEscrowByRequestId(contract.getRequestId());
        if (escrowOpt.isEmpty()) {
            throw new SettlementException("Escrow not found for request: " + contract.getRequestId());
        }
        var escrow = escrowOpt.get();

        escrowService.releaseEscrow(escrow.id(), totalAmount, contract.getDsId());

        // Update DS balance
        DSBalance dsBalance = getOrCreateDSBalance(contract.getDsId());
        dsBalance.setAvailableBalance(dsBalance.getAvailableBalance().add(totalAmount));
        dsBalance.setTotalEarned(dsBalance.getTotalEarned().add(totalAmount));
        dsBalance.setLastSettlementAt(Instant.now());
        dsBalanceRepository.save(dsBalance);

        // Generate audit receipt (Requirement 11.2)
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.SETTLEMENT,
            contract.getDsId(),
            ActorType.DS,
            consentContractId,
            "consent_contract",
            "Settlement: " + totalAmount + " YC for " + unitCount + " units"
        );

        return new SettlementResult(
            UUID.randomUUID(),
            consentContractId,
            contract.getDsId(),
            totalAmount,
            unitCount,
            receipt.getId(),
            Instant.now(),
            SettlementStatus.COMPLETED
        );
    }

    /**
     * Process batch settlement for multiple contracts.
     * 
     * @param settlements List of settlement requests
     * @return List of settlement results
     */
    @Transactional
    public List<SettlementResult> processBatchSettlement(List<SettlementRequest> settlements) {
        List<SettlementResult> results = new ArrayList<>();
        
        for (SettlementRequest request : settlements) {
            try {
                SettlementResult result = settleParticipation(request.consentContractId(), request.unitCount());
                results.add(result);
            } catch (Exception e) {
                results.add(new SettlementResult(
                    UUID.randomUUID(),
                    request.consentContractId(),
                    null,
                    BigDecimal.ZERO,
                    request.unitCount(),
                    null,
                    Instant.now(),
                    SettlementStatus.FAILED
                ));
            }
        }
        
        return results;
    }

    /**
     * Get DS balance, creating if not exists.
     */
    private DSBalance getOrCreateDSBalance(UUID dsId) {
        return dsBalanceRepository.findByDsId(dsId)
            .orElseGet(() -> {
                DSBalance balance = new DSBalance();
                balance.setDsId(dsId);
                balance.setAvailableBalance(BigDecimal.ZERO);
                balance.setPendingBalance(BigDecimal.ZERO);
                balance.setTotalEarned(BigDecimal.ZERO);
                balance.setTotalPaidOut(BigDecimal.ZERO);
                return dsBalanceRepository.save(balance);
            });
    }

    /**
     * Get current DS balance.
     */
    public DSBalance getDSBalance(UUID dsId) {
        return dsBalanceRepository.findByDsId(dsId)
            .orElse(null);
    }

    /**
     * Get or create DS balance (public version).
     */
    public DSBalance getOrCreateBalance(UUID dsId) {
        return getOrCreateDSBalance(dsId);
    }

    /**
     * Process settlement for a consent contract.
     * Used by SettlementController.
     */
    @Transactional
    public SettlementResult processSettlement(UUID consentContractId, UUID dsId, 
                                              UUID escrowAccountId, BigDecimal amount) {
        // Release funds from escrow
        escrowService.releaseEscrow(escrowAccountId, amount, dsId);

        // Update DS balance
        DSBalance dsBalance = getOrCreateDSBalance(dsId);
        dsBalance.setAvailableBalance(dsBalance.getAvailableBalance().add(amount));
        dsBalance.setTotalEarned(dsBalance.getTotalEarned().add(amount));
        dsBalance.setLastSettlementAt(Instant.now());
        dsBalanceRepository.save(dsBalance);

        // Generate audit receipt
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.SETTLEMENT,
            dsId,
            ActorType.DS,
            consentContractId,
            "consent_contract",
            "Settlement: " + amount + " for consent " + consentContractId
        );

        return new SettlementResult(
            UUID.randomUUID(),
            consentContractId,
            dsId,
            amount,
            1,
            receipt.getId(),
            Instant.now(),
            SettlementStatus.COMPLETED
        );
    }

    /**
     * Get settlement history for a DS.
     */
    public List<SettlementController.SettlementRecord> getSettlementHistory(UUID dsId) {
        // In production, this would query a settlements table
        return List.of();
    }

    /**
     * Get settlement by consent contract.
     */
    public SettlementController.SettlementRecord getSettlementByConsent(UUID contractId) {
        // In production, this would query a settlements table
        throw new SettlementNotFoundException("Settlement not found for contract: " + contractId);
    }

    // DTOs
    public record SettlementRequest(UUID consentContractId, int unitCount) {}

    public record SettlementResult(
        UUID settlementId,
        UUID consentContractId,
        UUID dsId,
        BigDecimal amount,
        int unitCount,
        UUID receiptId,
        Instant settledAt,
        SettlementStatus status
    ) {}

    public enum SettlementStatus {
        PENDING,
        COMPLETED,
        FAILED,
        REVERSED
    }

    public static class SettlementException extends RuntimeException {
        public SettlementException(String message) { super(message); }
    }

    public static class SettlementNotFoundException extends RuntimeException {
        public SettlementNotFoundException(String message) { super(message); }
    }

    public static class InsufficientEscrowException extends RuntimeException {
        public InsufficientEscrowException(String message) { super(message); }
    }
}
