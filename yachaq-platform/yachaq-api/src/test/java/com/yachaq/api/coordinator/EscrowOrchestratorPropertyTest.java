package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.escrow.EscrowService;
import com.yachaq.api.coordinator.EscrowOrchestratorService.*;
import com.yachaq.core.domain.AuditReceipt;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for EscrowOrchestratorService.
 * 
 * Validates Requirements:
 * - 325.1: Hold until contract signed and capsule hash receipt submitted
 * - 325.2: Optionally confirm capsule integrity through verifier
 * - 325.3: Release per contract terms
 * - 325.4: Never require raw data
 * - 325.5: Support partial release and refund workflows for disputes
 */
class EscrowOrchestratorPropertyTest {

    private TestEscrowService testEscrowService;
    private TestAuditService testAuditService;
    private EscrowOrchestratorService service;

    @BeforeEach
    void setUp() {
        initializeService();
    }

    private void initializeService() {
        testEscrowService = new TestEscrowService();
        testAuditService = new TestAuditService();
        service = new EscrowOrchestratorService(testEscrowService, testAuditService);
    }

    private EscrowOrchestratorService getService() {
        if (service == null) {
            initializeService();
        }
        return service;
    }

    private TestEscrowService getEscrowService() {
        if (testEscrowService == null) {
            initializeService();
        }
        return testEscrowService;
    }

    private void setup() {
        initializeService();
    }

    // ==================== Requirement 325.1: Escrow Hold Conditions ====================

    @Property(tries = 50)
    @Label("325.1: Escrow hold requires contract signed by both parties")
    void requirement325_1_escrowHoldRequiresContractSignedByBothParties(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        // Configure escrow service to return funded
        getEscrowService().setFunded(true);

        // Test with missing DS signature
        ContractSignatures missingDsSig = new ContractSignatures(
                null, "requester-sig", null, Instant.now());
        EscrowHoldRequest requestMissingDs = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", missingDsSig);
        
        EscrowHoldResult resultMissingDs = getService().createEscrowHold(requestMissingDs);
        assertThat(resultMissingDs.success()).isFalse();
        assertThat(resultMissingDs.error()).contains("signed by both parties");

        // Test with missing requester signature
        ContractSignatures missingRequesterSig = new ContractSignatures(
                "ds-sig", null, Instant.now(), null);
        EscrowHoldRequest requestMissingRequester = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", missingRequesterSig);
        
        EscrowHoldResult resultMissingRequester = getService().createEscrowHold(requestMissingRequester);
        assertThat(resultMissingRequester.success()).isFalse();

        // Test with both signatures present
        ContractSignatures validSigs = new ContractSignatures(
                "ds-sig", "requester-sig", Instant.now(), Instant.now());
        EscrowHoldRequest validRequest = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", validSigs);
        
        EscrowHoldResult validResult = getService().createEscrowHold(validRequest);
        assertThat(validResult.success()).as("Should succeed with both signatures: " + validResult.error()).isTrue();
    }

    @Property(tries = 50)
    @Label("325.1: Escrow hold requires sufficient funding")
    void requirement325_1_escrowHoldRequiresSufficientFunding(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        ContractSignatures validSigs = new ContractSignatures(
                "ds-sig", "requester-sig", Instant.now(), Instant.now());

        // Test with insufficient funding
        getEscrowService().setFunded(false);
        
        EscrowHoldRequest request = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", validSigs);
        
        EscrowHoldResult result = getService().createEscrowHold(request);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not sufficiently funded");
    }

    @Property(tries = 50)
    @Label("325.1: Capsule hash receipt required for release")
    void requirement325_1_capsuleHashReceiptRequiredForRelease(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Try to release without delivery receipt
        PaymentReleaseResult result = getService().releasePayment(holdId);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No delivery receipt");
    }


    // ==================== Requirement 325.2: Delivery Verification ====================

    @Property(tries = 50)
    @Label("325.2: Delivery verification with integrity check")
    void requirement325_2_deliveryVerificationWithIntegrityCheck(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt with integrity verification
        IntegrityProof proof = new IntegrityProof(
                "merkle-root-hash", "signature", Instant.now());
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-abc123", "transfer-proof", "ack", true, proof);

        DeliveryVerificationResult result = getService().submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Delivery receipt should be accepted: " + result.error()).isTrue();
        assertThat(result.receiptId()).isNotNull();
        assertThat(result.receipt()).isNotNull();
    }

    @Property(tries = 50)
    @Label("325.2: Delivery verification without integrity check (optional)")
    void requirement325_2_deliveryVerificationWithoutIntegrityCheck(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt without integrity verification (optional)
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-abc123", "transfer-proof", "ack", false, null);

        DeliveryVerificationResult result = getService().submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Delivery receipt should be accepted without integrity check").isTrue();
        assertThat(result.integrityVerified()).isFalse();
    }

    // ==================== Requirement 325.3: Payment Release ====================

    @Property(tries = 50)
    @Label("325.3: Payment release per contract terms")
    void requirement325_3_paymentReleasePerContractTerms(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create a valid escrow hold with delivery receipt
        UUID holdId = createValidEscrowHoldWithDelivery(amount);

        // Release payment
        PaymentReleaseResult result = getService().releasePayment(holdId);
        assertThat(result.success()).as("Payment release should succeed: " + result.error()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo(amount);

        // Verify escrow service was called
        assertThat(getEscrowService().getReleaseCount()).isGreaterThan(0);
    }

    @Property(tries = 50)
    @Label("325.3: Payment release blocked during dispute")
    void requirement325_3_paymentReleaseBlockedDuringDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create a valid escrow hold with delivery receipt
        UUID holdId = createValidEscrowHoldWithDelivery(amount);

        // Open a dispute
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.DATA_QUALITY_ISSUE, List.of());
        DisputeResult disputeResult = getService().openDispute(disputeRequest);
        assertThat(disputeResult.success()).as("Dispute should be opened").isTrue();

        // Try to release payment
        PaymentReleaseResult result = getService().releasePayment(holdId);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("dispute is open");
    }

    // ==================== Requirement 325.4: No Raw Data ====================

    @Property(tries = 50)
    @Label("325.4: No raw data stored in escrow hold")
    void requirement325_4_noRawDataStoredInEscrowHold(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Verify hold contains only hashes, not raw data
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();
        
        // Contract hash should be a digest (SHA-256 = 64 hex chars)
        assertThat(hold.contractHashDigest()).isNotNull();
        assertThat(hold.contractHashDigest()).hasSize(64);
        
        // No raw payload fields should exist in the record
        // The record only contains IDs, amounts, and hashes - no raw data
    }

    @Property(tries = 50)
    @Label("325.4: No raw data stored in delivery receipt")
    void requirement325_4_noRawDataStoredInDeliveryReceipt(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create escrow hold and submit delivery receipt
        UUID holdId = createValidEscrowHold(amount);
        
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "raw-capsule-hash-data", "transfer-proof", "ack", false, null);
        DeliveryVerificationResult result = getService().submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).isTrue();

        // Verify receipt contains only hash digest, not raw capsule data
        DeliveryReceipt receipt = result.receipt();
        assertThat(receipt.capsuleHashDigest()).isNotNull();
        assertThat(receipt.capsuleHashDigest()).hasSize(64);
    }


    // ==================== Requirement 325.5: Partial Release & Refund ====================

    @Property(tries = 50)
    @Label("325.5: Partial release workflow")
    void requirement325_5_partialReleaseWorkflow(
            @ForAll @BigRange(min = "100.00", max = "1000.00") BigDecimal totalAmount) {
        
        setup();
        
        // Create escrow hold with delivery
        UUID holdId = createValidEscrowHoldWithDelivery(totalAmount);
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();

        // Open dispute
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.INCOMPLETE_DELIVERY, List.of("evidence-hash-1"));
        DisputeResult disputeResult = getService().openDispute(disputeRequest);
        assertThat(disputeResult.success()).isTrue();

        // Resolve with partial release (70% to DS, 30% refund)
        BigDecimal releaseAmount = totalAmount.multiply(new BigDecimal("0.70"));
        BigDecimal refundAmount = totalAmount.multiply(new BigDecimal("0.30"));

        DisputeResolutionRequest resolutionRequest = new DisputeResolutionRequest(
                disputeResult.disputeId(),
                DisputeResolution.PARTIAL_RELEASE,
                releaseAmount,
                refundAmount,
                "Partial delivery verified"
        );

        DisputeResolutionResult result = getService().resolveDispute(resolutionRequest);
        assertThat(result.success()).as("Partial release should succeed: " + result.error()).isTrue();
        assertThat(result.releasedAmount()).isEqualByComparingTo(releaseAmount);
        assertThat(result.refundedAmount()).isEqualByComparingTo(refundAmount);

        // Verify both operations were called
        assertThat(getEscrowService().getReleaseCount()).isGreaterThan(0);
        assertThat(getEscrowService().getRefundCount()).isGreaterThan(0);
    }

    @Property(tries = 50)
    @Label("325.5: Full refund workflow")
    void requirement325_5_fullRefundWorkflow(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create escrow hold (no delivery)
        UUID holdId = createValidEscrowHold(amount);
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();

        // Process full refund
        RefundResult result = getService().processRefund(holdId, "DS failed to deliver");
        assertThat(result.success()).as("Full refund should succeed: " + result.error()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo(amount);

        // Verify refund was called
        assertThat(getEscrowService().getRefundCount()).isGreaterThan(0);

        // Verify hold status updated
        EscrowHold updatedHold = getService().getEscrowHold(holdId).orElseThrow();
        assertThat(updatedHold.status()).isEqualTo(EscrowHoldStatus.REFUNDED);
    }

    @Property(tries = 50)
    @Label("325.5: Dispute resolution cannot exceed hold amount")
    void requirement325_5_disputeResolutionCannotExceedHoldAmount(
            @ForAll @BigRange(min = "100.00", max = "500.00") BigDecimal totalAmount) {
        
        setup();
        
        // Create escrow hold with delivery
        UUID holdId = createValidEscrowHoldWithDelivery(totalAmount);
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();

        // Open dispute
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.requesterId(), DisputeReason.CONTRACT_VIOLATION, List.of());
        DisputeResult disputeResult = getService().openDispute(disputeRequest);
        assertThat(disputeResult.success()).isTrue();

        // Try to resolve with amounts exceeding total
        BigDecimal excessRelease = totalAmount.multiply(new BigDecimal("0.80"));
        BigDecimal excessRefund = totalAmount.multiply(new BigDecimal("0.50"));

        DisputeResolutionRequest resolutionRequest = new DisputeResolutionRequest(
                disputeResult.disputeId(),
                DisputeResolution.PARTIAL_RELEASE,
                excessRelease,
                excessRefund,
                "Invalid resolution"
        );

        DisputeResolutionResult result = getService().resolveDispute(resolutionRequest);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceed");
    }

    @Property(tries = 50)
    @Label("325.5: Only transaction parties can open dispute")
    void requirement325_5_onlyTransactionPartiesCanOpenDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();

        // Try to open dispute as non-party
        UUID nonPartyId = UUID.randomUUID();
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, nonPartyId, DisputeReason.OTHER, List.of());

        DisputeResult result = getService().openDispute(disputeRequest);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("transaction parties");

        // DS should be able to open dispute
        DisputeRequest dsDisputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.UNAUTHORIZED_USE, List.of());
        DisputeResult dsResult = getService().openDispute(dsDisputeRequest);
        assertThat(dsResult.success()).as("DS should be able to open dispute: " + dsResult.error()).isTrue();
    }

    @Property(tries = 50)
    @Label("325.5: Cannot open duplicate dispute")
    void requirement325_5_cannotOpenDuplicateDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        setup();
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);
        EscrowHold hold = getService().getEscrowHold(holdId).orElseThrow();

        // Open first dispute
        DisputeRequest firstDispute = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.NON_DELIVERY, List.of());
        DisputeResult firstResult = getService().openDispute(firstDispute);
        assertThat(firstResult.success()).isTrue();

        // Try to open second dispute
        DisputeRequest secondDispute = new DisputeRequest(
                holdId, hold.requesterId(), DisputeReason.DATA_QUALITY_ISSUE, List.of());
        DisputeResult secondResult = getService().openDispute(secondDispute);
        assertThat(secondResult.success()).isFalse();
        assertThat(secondResult.error()).contains("already exists");
    }

    // ==================== Helper Methods ====================

    private UUID createValidEscrowHold(BigDecimal amount) {
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        getEscrowService().setFunded(true);
        getEscrowService().setRequestId(requestId);

        ContractSignatures validSigs = new ContractSignatures(
                "ds-signature-abc", "requester-signature-xyz", Instant.now(), Instant.now());
        EscrowHoldRequest request = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "contract-hash", validSigs);

        EscrowHoldResult result = getService().createEscrowHold(request);
        assertThat(result.success()).as("Failed to create escrow hold: " + result.error()).isTrue();
        return result.holdId();
    }

    private UUID createValidEscrowHoldWithDelivery(BigDecimal amount) {
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-delivery", "transfer-proof", "ack", false, null);
        DeliveryVerificationResult result = getService().submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Failed to submit delivery receipt: " + result.error()).isTrue();

        return holdId;
    }

    // ==================== Test Doubles ====================

    /**
     * Test implementation of EscrowService for unit testing.
     */
    static class TestEscrowService extends EscrowService {
        private boolean funded = true;
        private UUID requestId;
        private int releaseCount = 0;
        private int refundCount = 0;

        TestEscrowService() {
            super(null, null, null);
        }

        void setFunded(boolean funded) {
            this.funded = funded;
        }

        void setRequestId(UUID requestId) {
            this.requestId = requestId;
        }

        int getReleaseCount() {
            return releaseCount;
        }

        int getRefundCount() {
            return refundCount;
        }

        @Override
        public boolean isEscrowSufficientlyFunded(UUID requestId, BigDecimal requiredAmount) {
            return funded;
        }

        @Override
        public Optional<EscrowAccountDto> getEscrowByRequestId(UUID requestId) {
            UUID escrowId = UUID.randomUUID();
            return Optional.of(new EscrowAccountDto(
                    escrowId,
                    UUID.randomUUID(),
                    requestId,
                    new BigDecimal("1000.00"),
                    new BigDecimal("1000.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    com.yachaq.core.domain.EscrowAccount.EscrowStatus.LOCKED,
                    null,
                    Instant.now()
            ));
        }

        @Override
        public EscrowAccountDto releaseEscrow(UUID escrowId, BigDecimal amount, UUID dsId) {
            releaseCount++;
            return new EscrowAccountDto(
                    escrowId,
                    UUID.randomUUID(),
                    requestId != null ? requestId : UUID.randomUUID(),
                    new BigDecimal("1000.00"),
                    BigDecimal.ZERO,
                    amount,
                    BigDecimal.ZERO,
                    com.yachaq.core.domain.EscrowAccount.EscrowStatus.SETTLED,
                    null,
                    Instant.now()
            );
        }

        @Override
        public EscrowAccountDto refundEscrow(UUID escrowId, BigDecimal amount, UUID requesterId) {
            refundCount++;
            return new EscrowAccountDto(
                    escrowId,
                    requesterId,
                    requestId != null ? requestId : UUID.randomUUID(),
                    new BigDecimal("1000.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    amount,
                    com.yachaq.core.domain.EscrowAccount.EscrowStatus.REFUNDED,
                    null,
                    Instant.now()
            );
        }
    }

    /**
     * Test implementation of AuditService for unit testing.
     */
    static class TestAuditService extends AuditService {
        private final List<AuditReceipt> receipts = new ArrayList<>();

        TestAuditService() {
            super(null);
        }

        @Override
        public AuditReceipt appendReceipt(
                AuditReceipt.EventType eventType,
                UUID actorId,
                AuditReceipt.ActorType actorType,
                UUID resourceId,
                String resourceType,
                String detailsHash) {
            AuditReceipt receipt = AuditReceipt.create(
                    eventType, actorId, actorType, resourceId, resourceType, detailsHash, "TEST"
            );
            receipts.add(receipt);
            return receipt;
        }

        boolean hasReceiptOfType(AuditReceipt.EventType eventType) {
            return receipts.stream().anyMatch(r -> r.getEventType() == eventType);
        }

        void clearReceipts() {
            receipts.clear();
        }
    }
}
