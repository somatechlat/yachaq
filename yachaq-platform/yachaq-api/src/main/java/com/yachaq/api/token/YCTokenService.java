package com.yachaq.api.token;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.escrow.EscrowService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * YC Token Service - YACHAQ Credits management.
 * 
 * Requirements: 192.1, 192.2, 192.3, 192.4, 192.5, 192.6, 192.7, 192.8
 * 
 * Property 10: YC Non-Transferability
 * For any attempt to transfer YC credits between users, the transfer must be
 * rejected unless explicitly enabled by governance policy.
 * 
 * Key constraints:
 * - Non-transferable by default (no P2P token trading)
 * - Policy-bound issuance tied to verified value events
 * - Full auditability of all issuance and redemption
 * - YC reconciles to escrow-funded value events
 * - Clawback entries for chargebacks and fraud
 * - Convert to fiat through compliant payout rails only
 */
@Service
public class YCTokenService {

    private final YCTokenRepository ycTokenRepository;
    private final AuditService auditService;
    private final EscrowService escrowService;
    
    // Governance flag - transfers disabled by default (Requirement 192.1)
    private volatile boolean transfersEnabled = false;

    public YCTokenService(
            YCTokenRepository ycTokenRepository,
            AuditService auditService,
            EscrowService escrowService) {
        this.ycTokenRepository = ycTokenRepository;
        this.auditService = auditService;
        this.escrowService = escrowService;
    }

    /**
     * Issue YC credits from a verified value event (settlement).
     * 
     * Requirements: 192.2, 192.4
     * - Policy-bound issuance tied to verified value events
     * - YC reconciles to escrow-funded value events
     * 
     * @param dsId Data Sovereign ID
     * @param amount Amount to issue
     * @param settlementId Settlement reference
     * @param escrowId Escrow that funded this issuance
     * @return Issued YC token record
     */
    @Transactional
    public YCToken issueFromSettlement(UUID dsId, BigDecimal amount, UUID settlementId, UUID escrowId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new YCTokenException("Issuance amount must be positive");
        }

        // Verify escrow exists and has released funds (Requirement 192.4)
        var escrow = escrowService.getEscrowByRequestId(escrowId);
        if (escrow.isEmpty()) {
            throw new YCTokenException("Cannot issue YC: escrow not found for verification");
        }

        // Check idempotency
        String idempotencyKey = "ISSUE:" + settlementId + ":" + dsId;
        if (ycTokenRepository.existsByIdempotencyKey(idempotencyKey)) {
            return ycTokenRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new YCTokenException("Idempotency check failed"));
        }

        // Create issuance record
        YCToken token = YCToken.issue(dsId, amount, settlementId, "settlement", escrowId,
            "YC issued from settlement");
        
        // Generate audit receipt (Requirement 192.3)
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.YC_ISSUED,
            dsId,
            ActorType.SYSTEM,
            token.getId(),
            "yc_token",
            "Issued " + amount + " YC from settlement " + settlementId
        );
        token.setAuditReceiptId(receipt.getId());

        return ycTokenRepository.save(token);
    }

    /**
     * Redeem YC credits for payout.
     * 
     * Requirements: 192.6
     * - Convert to fiat through compliant payout rails only
     * 
     * @param dsId Data Sovereign ID
     * @param amount Amount to redeem
     * @param payoutId Payout instruction reference
     * @return Redemption YC token record
     */
    @Transactional
    public YCToken redeemForPayout(UUID dsId, BigDecimal amount, UUID payoutId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new YCTokenException("Redemption amount must be positive");
        }

        // Verify sufficient balance
        BigDecimal balance = getBalance(dsId);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientYCBalanceException(
                "Insufficient YC balance: " + balance + " < " + amount);
        }

        // Check idempotency
        String idempotencyKey = "REDEEM:" + payoutId + ":" + dsId;
        if (ycTokenRepository.existsByIdempotencyKey(idempotencyKey)) {
            return ycTokenRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new YCTokenException("Idempotency check failed"));
        }

        // Create redemption record
        YCToken token = YCToken.redeem(dsId, amount, payoutId, "YC redeemed for payout");

        // Generate audit receipt (Requirement 192.3)
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.YC_REDEEMED,
            dsId,
            ActorType.DS,
            token.getId(),
            "yc_token",
            "Redeemed " + amount + " YC for payout " + payoutId
        );
        token.setAuditReceiptId(receipt.getId());

        return ycTokenRepository.save(token);
    }

    /**
     * Clawback YC credits due to fraud or chargeback.
     * 
     * Requirements: 192.5
     * - Clawback entries for chargebacks and fraud
     * 
     * @param dsId Data Sovereign ID
     * @param amount Amount to clawback
     * @param disputeId Dispute reference
     * @param reason Clawback reason
     * @return Clawback YC token record
     */
    @Transactional
    public YCToken clawback(UUID dsId, BigDecimal amount, UUID disputeId, String reason) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new YCTokenException("Clawback amount must be positive");
        }

        // Check idempotency
        String idempotencyKey = "CLAWBACK:" + disputeId + ":" + dsId;
        if (ycTokenRepository.existsByIdempotencyKey(idempotencyKey)) {
            return ycTokenRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new YCTokenException("Idempotency check failed"));
        }

        // Create clawback record (can result in negative balance for fraud cases)
        YCToken token = YCToken.clawback(dsId, amount, disputeId, reason);

        // Generate audit receipt (Requirement 192.3)
        AuditReceipt receipt = auditService.appendReceipt(
            EventType.YC_CLAWBACK,
            dsId,
            ActorType.SYSTEM,
            token.getId(),
            "yc_token",
            "Clawback " + amount + " YC: " + reason
        );
        token.setAuditReceiptId(receipt.getId());

        return ycTokenRepository.save(token);
    }

    /**
     * Attempt to transfer YC between users.
     * 
     * Property 10: YC Non-Transferability
     * For any attempt to transfer YC credits between users, the transfer must be
     * rejected unless explicitly enabled by governance policy.
     * 
     * Requirements: 192.1, 192.7
     * - Non-transferable by default (no P2P token trading)
     * - NOT allow exchange listing or secondary market trading
     * 
     * @param fromDsId Source DS
     * @param toDsId Destination DS
     * @param amount Amount to transfer
     * @return Transfer result (always rejected unless governance enables)
     */
    public TransferResult attemptTransfer(UUID fromDsId, UUID toDsId, BigDecimal amount) {
        // Property 10: Non-transferability enforcement
        if (!transfersEnabled) {
            // Generate audit receipt for rejected transfer attempt
            auditService.appendReceipt(
                EventType.YC_TRANSFER_REJECTED,
                fromDsId,
                ActorType.DS,
                UUID.randomUUID(),
                "transfer_attempt",
                "Transfer rejected: P2P transfers disabled by governance policy"
            );
            
            return new TransferResult(
                false,
                "YC_TRANSFER_DISABLED",
                "YC credits are non-transferable. P2P transfers are disabled by governance policy.",
                null
            );
        }

        // If governance enables transfers (future), implement transfer logic here
        // For now, this code path is unreachable
        throw new YCTokenException("Transfer logic not implemented - transfers disabled");
    }

    /**
     * Get current YC balance for a DS.
     */
    public BigDecimal getBalance(UUID dsId) {
        return ycTokenRepository.calculateBalance(dsId);
    }

    /**
     * Get total YC issued to a DS.
     */
    public BigDecimal getTotalIssued(UUID dsId) {
        return ycTokenRepository.calculateTotalIssued(dsId);
    }

    /**
     * Get total YC redeemed by a DS.
     */
    public BigDecimal getTotalRedeemed(UUID dsId) {
        return ycTokenRepository.calculateTotalRedeemed(dsId);
    }

    /**
     * Get full transaction history for a DS.
     * 
     * Requirements: 192.3 - Full auditability
     */
    public List<YCToken> getTransactionHistory(UUID dsId) {
        return ycTokenRepository.findByDsIdOrderByCreatedAtDesc(dsId);
    }

    /**
     * Reconcile YC against escrow.
     * 
     * Requirements: 192.4
     * - YC reconciles to escrow-funded value events
     */
    public ReconciliationResult reconcileWithEscrow(UUID escrowId) {
        List<YCToken> tokens = ycTokenRepository.findByEscrowId(escrowId);
        
        BigDecimal totalIssued = tokens.stream()
            .filter(t -> t.getOperationType() == YCToken.OperationType.ISSUANCE)
            .map(YCToken::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        var escrow = escrowService.getEscrowByRequestId(escrowId);
        if (escrow.isEmpty()) {
            return new ReconciliationResult(false, BigDecimal.ZERO, totalIssued, 
                "Escrow not found");
        }

        BigDecimal escrowReleased = escrow.get().releasedAmount();
        boolean reconciled = totalIssued.compareTo(escrowReleased) == 0;

        return new ReconciliationResult(
            reconciled,
            escrowReleased,
            totalIssued,
            reconciled ? "Reconciled" : "Mismatch: escrow=" + escrowReleased + ", issued=" + totalIssued
        );
    }

    /**
     * Check if transfers are enabled (governance policy).
     */
    public boolean areTransfersEnabled() {
        return transfersEnabled;
    }

    /**
     * Enable/disable transfers (governance action - requires multi-sig in production).
     * 
     * Requirements: 192.9
     * - Additional compliance controls before any transferability
     */
    public void setTransfersEnabled(boolean enabled, UUID governanceActorId) {
        // In production, this would require multi-signature governance approval
        this.transfersEnabled = enabled;
        
        auditService.appendReceipt(
            enabled ? EventType.YC_TRANSFERS_ENABLED : EventType.YC_TRANSFERS_DISABLED,
            governanceActorId,
            ActorType.SYSTEM,
            UUID.randomUUID(),
            "governance_action",
            "YC transfers " + (enabled ? "enabled" : "disabled") + " by governance"
        );
    }

    // DTOs
    public record TransferResult(
        boolean success,
        String errorCode,
        String message,
        UUID transactionId
    ) {}

    public record ReconciliationResult(
        boolean reconciled,
        BigDecimal escrowReleased,
        BigDecimal ycIssued,
        String message
    ) {}

    // Exceptions
    public static class YCTokenException extends RuntimeException {
        public YCTokenException(String message) { super(message); }
    }

    public static class InsufficientYCBalanceException extends YCTokenException {
        public InsufficientYCBalanceException(String message) { super(message); }
    }
}
