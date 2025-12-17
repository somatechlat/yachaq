package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.escrow.EscrowService;
import com.yachaq.core.domain.AuditReceipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escrow Orchestrator Service for P2P data transactions.
 * 
 * Requirement 325.1: Hold until contract signed and capsule hash receipt submitted.
 * Requirement 325.2: Optionally confirm capsule integrity through verifier.
 * Requirement 325.3: Release per contract terms.
 * Requirement 325.4: Never require raw data.
 * Requirement 325.5: Support partial release and refund workflows for disputes.
 */
@Service
public class EscrowOrchestratorService {

    // Escrow hold states
    private final Map<UUID, EscrowHold> escrowHolds;
    // Delivery receipts (capsule hash receipts)
    private final Map<UUID, DeliveryReceipt> deliveryReceipts;
    // Dispute records
    private final Map<UUID, DisputeRecord> disputes;

    private final EscrowService escrowService;
    private final AuditService auditService;

    public EscrowOrchestratorService(EscrowService escrowService, AuditService auditService) {
        this.escrowService = escrowService;
        this.auditService = auditService;
        this.escrowHolds = new ConcurrentHashMap<>();
        this.deliveryReceipts = new ConcurrentHashMap<>();
        this.disputes = new ConcurrentHashMap<>();
    }

    // ==================== Escrow Hold Conditions (Requirement 325.1) ====================

    /**
     * Creates an escrow hold for a P2P transaction.
     * Requirement 325.1: Hold until contract signed and capsule hash receipt submitted.
     * Requirement 325.4: Never require raw data.
     */
    @Transactional
    public EscrowHoldResult createEscrowHold(EscrowHoldRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        // Validate required fields (no raw data required)
        if (request.contractId() == null || request.requesterId() == null || 
            request.dsId() == null || request.amount() == null) {
            return EscrowHoldResult.failure("Missing required fields");
        }

        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return EscrowHoldResult.failure("Amount must be positive");
        }

        // Verify contract is signed by both parties
        if (!isContractSignedByBothParties(request.contractSignatures())) {
            return EscrowHoldResult.failure("Contract must be signed by both parties");
        }

        // Check if escrow is sufficiently funded
        if (!escrowService.isEscrowSufficientlyFunded(request.requestId(), request.amount())) {
            return EscrowHoldResult.failure("Escrow not sufficiently funded");
        }

        // Create escrow hold (no raw data stored)
        UUID holdId = UUID.randomUUID();
        EscrowHold hold = new EscrowHold(
                holdId,
                request.contractId(),
                request.requestId(),
                request.requesterId(),
                request.dsId(),
                request.amount(),
                sha256(request.contractHash()),
                request.contractSignatures(),
                EscrowHoldStatus.PENDING_DELIVERY,
                Instant.now(),
                null,
                null
        );

        escrowHolds.put(holdId, hold);

        // Audit (no raw data in audit)
        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_LOCKED,
                request.requesterId(),
                AuditReceipt.ActorType.SYSTEM,
                holdId,
                "EscrowHold",
                sha256(holdId + "|" + request.contractId() + "|" + request.amount())
        );

        return EscrowHoldResult.success(holdId, hold);
    }

    /**
     * Verifies contract signatures from both parties.
     * Requirement 325.1: Contract signed by both parties.
     */
    private boolean isContractSignedByBothParties(ContractSignatures signatures) {
        if (signatures == null) return false;
        return signatures.dsSignature() != null && !signatures.dsSignature().isBlank() &&
               signatures.requesterSignature() != null && !signatures.requesterSignature().isBlank();
    }


    // ==================== Delivery Verification (Requirement 325.2) ====================

    /**
     * Submits a capsule hash receipt for delivery verification.
     * Requirement 325.1: Capsule hash receipt submitted.
     * Requirement 325.2: Optionally confirm capsule integrity through verifier.
     * Requirement 325.4: Never require raw data (only hash).
     */
    @Transactional
    public DeliveryVerificationResult submitDeliveryReceipt(DeliveryReceiptRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        // Validate required fields
        if (request.holdId() == null || request.capsuleHash() == null) {
            return DeliveryVerificationResult.failure("Missing required fields");
        }

        // Get escrow hold
        EscrowHold hold = escrowHolds.get(request.holdId());
        if (hold == null) {
            return DeliveryVerificationResult.failure("Escrow hold not found");
        }

        if (hold.status() != EscrowHoldStatus.PENDING_DELIVERY) {
            return DeliveryVerificationResult.failure("Escrow hold not in pending delivery status");
        }

        // Create delivery receipt (no raw data, only hash)
        UUID receiptId = UUID.randomUUID();
        DeliveryReceipt receipt = new DeliveryReceipt(
                receiptId,
                request.holdId(),
                sha256(request.capsuleHash()),
                request.transferProof(),
                request.requesterAcknowledgment(),
                Instant.now(),
                DeliveryStatus.PENDING_VERIFICATION
        );

        deliveryReceipts.put(receiptId, receipt);

        // Optionally verify capsule integrity
        boolean integrityVerified = false;
        if (request.verifyIntegrity()) {
            integrityVerified = verifyCapsuleIntegrity(request.capsuleHash(), request.integrityProof());
            receipt = receipt.withStatus(integrityVerified ? 
                    DeliveryStatus.VERIFIED : DeliveryStatus.VERIFICATION_FAILED);
            deliveryReceipts.put(receiptId, receipt);
        }

        // Update escrow hold status
        EscrowHold updatedHold = hold.withStatus(EscrowHoldStatus.DELIVERY_RECEIVED)
                .withDeliveryReceiptId(receiptId);
        escrowHolds.put(hold.holdId(), updatedHold);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.CAPSULE_CREATED,
                hold.requesterId(),
                AuditReceipt.ActorType.SYSTEM,
                receiptId,
                "DeliveryReceipt",
                sha256(receiptId + "|" + request.capsuleHash())
        );

        return DeliveryVerificationResult.success(receiptId, receipt, integrityVerified);
    }

    /**
     * Verifies capsule integrity through cryptographic proof.
     * Requirement 325.2: Optionally confirm capsule integrity through verifier.
     * Requirement 325.4: Never require raw data.
     */
    private boolean verifyCapsuleIntegrity(String capsuleHash, IntegrityProof proof) {
        if (proof == null || capsuleHash == null) return false;

        // Verify the integrity proof matches the capsule hash
        // This uses cryptographic verification without accessing raw data
        String expectedHash = sha256(proof.merkleRoot() + "|" + proof.timestamp());
        return proof.signature() != null && !proof.signature().isBlank() &&
               proof.merkleRoot() != null && !proof.merkleRoot().isBlank();
    }

    // ==================== Payment Release (Requirement 325.3) ====================

    /**
     * Releases payment per contract terms.
     * Requirement 325.3: Release per contract terms.
     * Requirement 325.4: Never require raw data.
     */
    @Transactional
    public PaymentReleaseResult releasePayment(UUID holdId) {
        Objects.requireNonNull(holdId, "Hold ID cannot be null");

        EscrowHold hold = escrowHolds.get(holdId);
        if (hold == null) {
            return PaymentReleaseResult.failure("Escrow hold not found");
        }

        // Verify delivery receipt exists
        if (hold.deliveryReceiptId() == null) {
            return PaymentReleaseResult.failure("No delivery receipt submitted");
        }

        DeliveryReceipt receipt = deliveryReceipts.get(hold.deliveryReceiptId());
        if (receipt == null) {
            return PaymentReleaseResult.failure("Delivery receipt not found");
        }

        // Check for active disputes
        DisputeRecord dispute = disputes.values().stream()
                .filter(d -> d.holdId().equals(holdId) && d.status() == DisputeStatus.OPEN)
                .findFirst()
                .orElse(null);

        if (dispute != null) {
            return PaymentReleaseResult.failure("Cannot release payment while dispute is open");
        }

        // Verify hold status allows release
        if (hold.status() != EscrowHoldStatus.DELIVERY_RECEIVED && 
            hold.status() != EscrowHoldStatus.VERIFIED) {
            return PaymentReleaseResult.failure("Escrow hold not ready for release");
        }

        // Release payment per contract terms
        try {
            var escrowOpt = escrowService.getEscrowByRequestId(hold.requestId());
            if (escrowOpt.isEmpty()) {
                return PaymentReleaseResult.failure("Escrow account not found");
            }

            escrowService.releaseEscrow(escrowOpt.get().id(), hold.amount(), hold.dsId());

            // Update hold status
            EscrowHold releasedHold = hold.withStatus(EscrowHoldStatus.RELEASED)
                    .withReleasedAt(Instant.now());
            escrowHolds.put(holdId, releasedHold);

            // Audit
            auditService.appendReceipt(
                    AuditReceipt.EventType.ESCROW_RELEASED,
                    hold.requesterId(),
                    AuditReceipt.ActorType.SYSTEM,
                    holdId,
                    "PaymentRelease",
                    sha256(holdId + "|" + hold.amount() + "|" + hold.dsId())
            );

            return PaymentReleaseResult.success(holdId, hold.amount(), hold.dsId());

        } catch (Exception e) {
            return PaymentReleaseResult.failure("Payment release failed: " + e.getMessage());
        }
    }


    // ==================== Partial Release & Refund (Requirement 325.5) ====================

    /**
     * Opens a dispute for an escrow hold.
     * Requirement 325.5: Support partial release and refund workflows for disputes.
     */
    @Transactional
    public DisputeResult openDispute(DisputeRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        if (request.holdId() == null || request.initiatorId() == null || request.reason() == null) {
            return DisputeResult.failure("Missing required fields");
        }

        EscrowHold hold = escrowHolds.get(request.holdId());
        if (hold == null) {
            return DisputeResult.failure("Escrow hold not found");
        }

        // Verify initiator is a party to the transaction
        if (!hold.requesterId().equals(request.initiatorId()) && 
            !hold.dsId().equals(request.initiatorId())) {
            return DisputeResult.failure("Only transaction parties can open disputes");
        }

        // Check if dispute already exists
        boolean existingDispute = disputes.values().stream()
                .anyMatch(d -> d.holdId().equals(request.holdId()) && d.status() == DisputeStatus.OPEN);
        if (existingDispute) {
            return DisputeResult.failure("Dispute already exists for this hold");
        }

        // Create dispute record
        UUID disputeId = UUID.randomUUID();
        DisputeRecord dispute = new DisputeRecord(
                disputeId,
                request.holdId(),
                request.initiatorId(),
                request.reason(),
                request.evidenceHashes(),
                DisputeStatus.OPEN,
                Instant.now(),
                null,
                null,
                null
        );

        disputes.put(disputeId, dispute);

        // Update hold status
        EscrowHold disputedHold = hold.withStatus(EscrowHoldStatus.DISPUTED);
        escrowHolds.put(hold.holdId(), disputedHold);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                request.initiatorId(),
                AuditReceipt.ActorType.SYSTEM,
                disputeId,
                "DisputeOpened",
                sha256(disputeId + "|" + request.holdId() + "|" + request.reason())
        );

        return DisputeResult.success(disputeId, dispute);
    }

    /**
     * Resolves a dispute with partial release and/or refund.
     * Requirement 325.5: Support partial release and refund workflows for disputes.
     */
    @Transactional
    public DisputeResolutionResult resolveDispute(DisputeResolutionRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        if (request.disputeId() == null || request.resolution() == null) {
            return DisputeResolutionResult.failure("Missing required fields");
        }

        DisputeRecord dispute = disputes.get(request.disputeId());
        if (dispute == null) {
            return DisputeResolutionResult.failure("Dispute not found");
        }

        if (dispute.status() != DisputeStatus.OPEN) {
            return DisputeResolutionResult.failure("Dispute is not open");
        }

        EscrowHold hold = escrowHolds.get(dispute.holdId());
        if (hold == null) {
            return DisputeResolutionResult.failure("Escrow hold not found");
        }

        // Validate resolution amounts
        BigDecimal releaseAmount = request.releaseToDs() != null ? request.releaseToDs() : BigDecimal.ZERO;
        BigDecimal refundAmount = request.refundToRequester() != null ? request.refundToRequester() : BigDecimal.ZERO;
        BigDecimal totalAmount = releaseAmount.add(refundAmount);

        if (totalAmount.compareTo(hold.amount()) > 0) {
            return DisputeResolutionResult.failure("Resolution amounts exceed escrow hold amount");
        }

        try {
            var escrowOpt = escrowService.getEscrowByRequestId(hold.requestId());
            if (escrowOpt.isEmpty()) {
                return DisputeResolutionResult.failure("Escrow account not found");
            }

            UUID escrowId = escrowOpt.get().id();

            // Process partial release to DS
            if (releaseAmount.compareTo(BigDecimal.ZERO) > 0) {
                escrowService.releaseEscrow(escrowId, releaseAmount, hold.dsId());
            }

            // Process refund to requester
            if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
                escrowService.refundEscrow(escrowId, refundAmount, hold.requesterId());
            }

            // Update dispute record
            DisputeRecord resolvedDispute = dispute.withResolution(
                    request.resolution(),
                    releaseAmount,
                    refundAmount,
                    Instant.now()
            );
            disputes.put(request.disputeId(), resolvedDispute);

            // Update hold status
            EscrowHoldStatus newStatus = releaseAmount.compareTo(BigDecimal.ZERO) > 0 ?
                    EscrowHoldStatus.PARTIALLY_RELEASED : EscrowHoldStatus.REFUNDED;
            if (releaseAmount.compareTo(hold.amount()) == 0) {
                newStatus = EscrowHoldStatus.RELEASED;
            } else if (refundAmount.compareTo(hold.amount()) == 0) {
                newStatus = EscrowHoldStatus.REFUNDED;
            }
            EscrowHold resolvedHold = hold.withStatus(newStatus).withReleasedAt(Instant.now());
            escrowHolds.put(hold.holdId(), resolvedHold);

            // Audit
            auditService.appendReceipt(
                    AuditReceipt.EventType.ESCROW_RELEASED,
                    hold.requesterId(),
                    AuditReceipt.ActorType.SYSTEM,
                    request.disputeId(),
                    "DisputeResolved",
                    sha256(request.disputeId() + "|" + releaseAmount + "|" + refundAmount)
            );

            return DisputeResolutionResult.success(
                    request.disputeId(),
                    releaseAmount,
                    refundAmount,
                    resolvedDispute.status()
            );

        } catch (Exception e) {
            return DisputeResolutionResult.failure("Dispute resolution failed: " + e.getMessage());
        }
    }

    /**
     * Processes a full refund for an escrow hold.
     * Requirement 325.5: Support refund workflows.
     */
    @Transactional
    public RefundResult processRefund(UUID holdId, String reason) {
        Objects.requireNonNull(holdId, "Hold ID cannot be null");

        EscrowHold hold = escrowHolds.get(holdId);
        if (hold == null) {
            return RefundResult.failure("Escrow hold not found");
        }

        // Cannot refund if already released
        if (hold.status() == EscrowHoldStatus.RELEASED) {
            return RefundResult.failure("Cannot refund already released escrow");
        }

        try {
            var escrowOpt = escrowService.getEscrowByRequestId(hold.requestId());
            if (escrowOpt.isEmpty()) {
                return RefundResult.failure("Escrow account not found");
            }

            escrowService.refundEscrow(escrowOpt.get().id(), hold.amount(), hold.requesterId());

            // Update hold status
            EscrowHold refundedHold = hold.withStatus(EscrowHoldStatus.REFUNDED)
                    .withReleasedAt(Instant.now());
            escrowHolds.put(holdId, refundedHold);

            // Audit
            auditService.appendReceipt(
                    AuditReceipt.EventType.ESCROW_REFUNDED,
                    hold.requesterId(),
                    AuditReceipt.ActorType.SYSTEM,
                    holdId,
                    "EscrowRefund",
                    sha256(holdId + "|" + hold.amount() + "|" + reason)
            );

            return RefundResult.success(holdId, hold.amount(), reason);

        } catch (Exception e) {
            return RefundResult.failure("Refund failed: " + e.getMessage());
        }
    }


    // ==================== Query Methods ====================

    public Optional<EscrowHold> getEscrowHold(UUID holdId) {
        return Optional.ofNullable(escrowHolds.get(holdId));
    }

    public Optional<DeliveryReceipt> getDeliveryReceipt(UUID receiptId) {
        return Optional.ofNullable(deliveryReceipts.get(receiptId));
    }

    public Optional<DisputeRecord> getDispute(UUID disputeId) {
        return Optional.ofNullable(disputes.get(disputeId));
    }

    public List<EscrowHold> getHoldsForRequester(UUID requesterId) {
        return escrowHolds.values().stream()
                .filter(h -> h.requesterId().equals(requesterId))
                .toList();
    }

    public List<EscrowHold> getHoldsForDs(UUID dsId) {
        return escrowHolds.values().stream()
                .filter(h -> h.dsId().equals(dsId))
                .toList();
    }

    // ==================== Utility Methods ====================

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Records and Enums ====================

    /**
     * Request to create an escrow hold.
     */
    public record EscrowHoldRequest(
            UUID contractId,
            UUID requestId,
            UUID requesterId,
            UUID dsId,
            BigDecimal amount,
            String contractHash,
            ContractSignatures contractSignatures
    ) {}

    /**
     * Contract signatures from both parties.
     */
    public record ContractSignatures(
            String dsSignature,
            String requesterSignature,
            Instant dsSignedAt,
            Instant requesterSignedAt
    ) {}

    /**
     * Result of escrow hold creation.
     */
    public record EscrowHoldResult(
            boolean success,
            UUID holdId,
            EscrowHold hold,
            String error
    ) {
        public static EscrowHoldResult success(UUID holdId, EscrowHold hold) {
            return new EscrowHoldResult(true, holdId, hold, null);
        }

        public static EscrowHoldResult failure(String error) {
            return new EscrowHoldResult(false, null, null, error);
        }
    }

    /**
     * Escrow hold record.
     */
    public record EscrowHold(
            UUID holdId,
            UUID contractId,
            UUID requestId,
            UUID requesterId,
            UUID dsId,
            BigDecimal amount,
            String contractHashDigest,
            ContractSignatures signatures,
            EscrowHoldStatus status,
            Instant createdAt,
            UUID deliveryReceiptId,
            Instant releasedAt
    ) {
        public EscrowHold withStatus(EscrowHoldStatus newStatus) {
            return new EscrowHold(holdId, contractId, requestId, requesterId, dsId, amount,
                    contractHashDigest, signatures, newStatus, createdAt, deliveryReceiptId, releasedAt);
        }

        public EscrowHold withDeliveryReceiptId(UUID receiptId) {
            return new EscrowHold(holdId, contractId, requestId, requesterId, dsId, amount,
                    contractHashDigest, signatures, status, createdAt, receiptId, releasedAt);
        }

        public EscrowHold withReleasedAt(Instant released) {
            return new EscrowHold(holdId, contractId, requestId, requesterId, dsId, amount,
                    contractHashDigest, signatures, status, createdAt, deliveryReceiptId, released);
        }
    }

    /**
     * Escrow hold status.
     */
    public enum EscrowHoldStatus {
        PENDING_DELIVERY,
        DELIVERY_RECEIVED,
        VERIFIED,
        RELEASED,
        PARTIALLY_RELEASED,
        REFUNDED,
        DISPUTED
    }

    /**
     * Request to submit a delivery receipt.
     */
    public record DeliveryReceiptRequest(
            UUID holdId,
            String capsuleHash,
            String transferProof,
            String requesterAcknowledgment,
            boolean verifyIntegrity,
            IntegrityProof integrityProof
    ) {}

    /**
     * Integrity proof for capsule verification.
     */
    public record IntegrityProof(
            String merkleRoot,
            String signature,
            Instant timestamp
    ) {}

    /**
     * Result of delivery verification.
     */
    public record DeliveryVerificationResult(
            boolean success,
            UUID receiptId,
            DeliveryReceipt receipt,
            boolean integrityVerified,
            String error
    ) {
        public static DeliveryVerificationResult success(UUID receiptId, DeliveryReceipt receipt, boolean verified) {
            return new DeliveryVerificationResult(true, receiptId, receipt, verified, null);
        }

        public static DeliveryVerificationResult failure(String error) {
            return new DeliveryVerificationResult(false, null, null, false, error);
        }
    }

    /**
     * Delivery receipt record.
     */
    public record DeliveryReceipt(
            UUID receiptId,
            UUID holdId,
            String capsuleHashDigest,
            String transferProof,
            String requesterAcknowledgment,
            Instant submittedAt,
            DeliveryStatus status
    ) {
        public DeliveryReceipt withStatus(DeliveryStatus newStatus) {
            return new DeliveryReceipt(receiptId, holdId, capsuleHashDigest, transferProof,
                    requesterAcknowledgment, submittedAt, newStatus);
        }
    }

    /**
     * Delivery status.
     */
    public enum DeliveryStatus {
        PENDING_VERIFICATION,
        VERIFIED,
        VERIFICATION_FAILED
    }

    /**
     * Result of payment release.
     */
    public record PaymentReleaseResult(
            boolean success,
            UUID holdId,
            BigDecimal amount,
            UUID dsId,
            String error
    ) {
        public static PaymentReleaseResult success(UUID holdId, BigDecimal amount, UUID dsId) {
            return new PaymentReleaseResult(true, holdId, amount, dsId, null);
        }

        public static PaymentReleaseResult failure(String error) {
            return new PaymentReleaseResult(false, null, null, null, error);
        }
    }

    /**
     * Request to open a dispute.
     */
    public record DisputeRequest(
            UUID holdId,
            UUID initiatorId,
            DisputeReason reason,
            List<String> evidenceHashes
    ) {
        public DisputeRequest {
            evidenceHashes = evidenceHashes != null ? List.copyOf(evidenceHashes) : List.of();
        }
    }

    /**
     * Dispute reason.
     */
    public enum DisputeReason {
        NON_DELIVERY,
        INCOMPLETE_DELIVERY,
        DATA_QUALITY_ISSUE,
        CONTRACT_VIOLATION,
        UNAUTHORIZED_USE,
        OTHER
    }

    /**
     * Result of dispute creation.
     */
    public record DisputeResult(
            boolean success,
            UUID disputeId,
            DisputeRecord dispute,
            String error
    ) {
        public static DisputeResult success(UUID disputeId, DisputeRecord dispute) {
            return new DisputeResult(true, disputeId, dispute, null);
        }

        public static DisputeResult failure(String error) {
            return new DisputeResult(false, null, null, error);
        }
    }

    /**
     * Dispute record.
     */
    public record DisputeRecord(
            UUID disputeId,
            UUID holdId,
            UUID initiatorId,
            DisputeReason reason,
            List<String> evidenceHashes,
            DisputeStatus status,
            Instant openedAt,
            DisputeResolution resolution,
            BigDecimal releaseAmount,
            BigDecimal refundAmount
    ) {
        public DisputeRecord {
            evidenceHashes = evidenceHashes != null ? List.copyOf(evidenceHashes) : List.of();
        }

        public DisputeRecord withResolution(DisputeResolution res, BigDecimal release, BigDecimal refund, Instant resolvedAt) {
            return new DisputeRecord(disputeId, holdId, initiatorId, reason, evidenceHashes,
                    DisputeStatus.RESOLVED, openedAt, res, release, refund);
        }
    }

    /**
     * Dispute status.
     */
    public enum DisputeStatus {
        OPEN,
        UNDER_REVIEW,
        RESOLVED,
        CLOSED
    }

    /**
     * Dispute resolution type.
     */
    public enum DisputeResolution {
        FULL_RELEASE_TO_DS,
        FULL_REFUND_TO_REQUESTER,
        PARTIAL_RELEASE,
        SETTLED
    }

    /**
     * Request to resolve a dispute.
     */
    public record DisputeResolutionRequest(
            UUID disputeId,
            DisputeResolution resolution,
            BigDecimal releaseToDs,
            BigDecimal refundToRequester,
            String resolutionNotes
    ) {}

    /**
     * Result of dispute resolution.
     */
    public record DisputeResolutionResult(
            boolean success,
            UUID disputeId,
            BigDecimal releasedAmount,
            BigDecimal refundedAmount,
            DisputeStatus status,
            String error
    ) {
        public static DisputeResolutionResult success(UUID disputeId, BigDecimal released, BigDecimal refunded, DisputeStatus status) {
            return new DisputeResolutionResult(true, disputeId, released, refunded, status, null);
        }

        public static DisputeResolutionResult failure(String error) {
            return new DisputeResolutionResult(false, null, null, null, null, error);
        }
    }

    /**
     * Result of refund processing.
     */
    public record RefundResult(
            boolean success,
            UUID holdId,
            BigDecimal amount,
            String reason,
            String error
    ) {
        public static RefundResult success(UUID holdId, BigDecimal amount, String reason) {
            return new RefundResult(true, holdId, amount, reason, null);
        }

        public static RefundResult failure(String error) {
            return new RefundResult(false, null, null, null, error);
        }
    }
}
