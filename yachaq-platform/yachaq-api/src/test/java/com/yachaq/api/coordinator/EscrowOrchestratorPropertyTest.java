package com.yachaq.api.coordinator;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.audit.AuditService;
import com.yachaq.api.config.TestcontainersConfiguration;
import com.yachaq.api.escrow.EscrowService;
import com.yachaq.api.coordinator.EscrowOrchestratorService.*;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.EscrowAccount;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.api.escrow.EscrowRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.spring.JqwikSpringSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * 
 * VIBE CODING RULES COMPLIANCE:
 * - Rule #1: NO MOCKS - Uses real PostgreSQL via Docker
 * - Rule #4: REAL IMPLEMENTATIONS - All services are real Spring beans
 * - Rule #7: REAL DATA & SERVERS - Tests against real Docker PostgreSQL
 */
@JqwikSpringSupport
@SpringBootTest(classes = YachaqApiApplication.class)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class EscrowOrchestratorPropertyTest {

    @Autowired
    private EscrowService escrowService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EscrowOrchestratorService service;

    @Autowired
    private AuditReceiptRepository auditReceiptRepository;

    @Autowired
    private EscrowRepository escrowRepository;

    @BeforeEach
    void setUp() {
        // Clean up for each test
        auditReceiptRepository.deleteAll();
        escrowRepository.deleteAll();
    }

    // ==================== Requirement 325.1: Escrow Hold Conditions ====================

    @Property(tries = 50)
    @Label("325.1: Escrow hold requires contract signed by both parties")
    void requirement325_1_escrowHoldRequiresContractSignedByBothParties(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        // Create a funded escrow account in the real database
        createFundedEscrowAccount(requesterId, requestId, amount);

        // Test with missing DS signature
        ContractSignatures missingDsSig = new ContractSignatures(
                null, "requester-sig", null, Instant.now());
        EscrowHoldRequest requestMissingDs = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", missingDsSig);
        
        EscrowHoldResult resultMissingDs = service.createEscrowHold(requestMissingDs);
        assertThat(resultMissingDs.success()).isFalse();
        assertThat(resultMissingDs.error()).contains("signed by both parties");

        // Test with missing requester signature
        ContractSignatures missingRequesterSig = new ContractSignatures(
                "ds-sig", null, Instant.now(), null);
        EscrowHoldRequest requestMissingRequester = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", missingRequesterSig);
        
        EscrowHoldResult resultMissingRequester = service.createEscrowHold(requestMissingRequester);
        assertThat(resultMissingRequester.success()).isFalse();

        // Test with both signatures present
        ContractSignatures validSigs = new ContractSignatures(
                "ds-sig", "requester-sig", Instant.now(), Instant.now());
        EscrowHoldRequest validRequest = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", validSigs);
        
        EscrowHoldResult validResult = service.createEscrowHold(validRequest);
        assertThat(validResult.success()).as("Should succeed with both signatures: " + validResult.error()).isTrue();
    }

    @Property(tries = 50)
    @Label("325.1: Escrow hold requires sufficient funding")
    void requirement325_1_escrowHoldRequiresSufficientFunding(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        ContractSignatures validSigs = new ContractSignatures(
                "ds-sig", "requester-sig", Instant.now(), Instant.now());

        // Test with no escrow account (insufficient funding)
        EscrowHoldRequest request = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "hash", validSigs);
        
        EscrowHoldResult result = service.createEscrowHold(request);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not sufficiently funded");
    }

    @Property(tries = 50)
    @Label("325.1: Capsule hash receipt required for release")
    void requirement325_1_capsuleHashReceiptRequiredForRelease(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Try to release without delivery receipt
        PaymentReleaseResult result = service.releasePayment(holdId);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No delivery receipt");
    }


    // ==================== Requirement 325.2: Delivery Verification ====================

    @Property(tries = 50)
    @Label("325.2: Delivery verification with integrity check")
    void requirement325_2_deliveryVerificationWithIntegrityCheck(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt with integrity verification
        IntegrityProof proof = new IntegrityProof(
                "merkle-root-hash", "signature", Instant.now());
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-abc123", "transfer-proof", "ack", true, proof);

        DeliveryVerificationResult result = service.submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Delivery receipt should be accepted: " + result.error()).isTrue();
        assertThat(result.receiptId()).isNotNull();
        assertThat(result.receipt()).isNotNull();
    }

    @Property(tries = 50)
    @Label("325.2: Delivery verification without integrity check (optional)")
    void requirement325_2_deliveryVerificationWithoutIntegrityCheck(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create a valid escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt without integrity verification (optional)
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-abc123", "transfer-proof", "ack", false, null);

        DeliveryVerificationResult result = service.submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Delivery receipt should be accepted without integrity check").isTrue();
        assertThat(result.integrityVerified()).isFalse();
    }

    // ==================== Requirement 325.3: Payment Release ====================

    @Property(tries = 50)
    @Label("325.3: Payment release per contract terms")
    void requirement325_3_paymentReleasePerContractTerms(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create a valid escrow hold with delivery receipt
        UUID holdId = createValidEscrowHoldWithDelivery(amount);

        // Release payment
        PaymentReleaseResult result = service.releasePayment(holdId);
        assertThat(result.success()).as("Payment release should succeed: " + result.error()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo(amount);
    }

    @Property(tries = 50)
    @Label("325.3: Payment release blocked during dispute")
    void requirement325_3_paymentReleaseBlockedDuringDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create a valid escrow hold with delivery receipt
        UUID holdId = createValidEscrowHoldWithDelivery(amount);

        // Open a dispute
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.DATA_QUALITY_ISSUE, List.of());
        DisputeResult disputeResult = service.openDispute(disputeRequest);
        assertThat(disputeResult.success()).as("Dispute should be opened").isTrue();

        // Try to release payment
        PaymentReleaseResult result = service.releasePayment(holdId);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("dispute is open");
    }

    // ==================== Requirement 325.4: No Raw Data ====================

    @Property(tries = 50)
    @Label("325.4: No raw data stored in escrow hold")
    void requirement325_4_noRawDataStoredInEscrowHold(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);

        // Verify hold contains only hashes, not raw data
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();
        
        // Contract hash should be a digest (SHA-256 = 64 hex chars)
        assertThat(hold.contractHashDigest()).isNotNull();
        assertThat(hold.contractHashDigest()).hasSize(64);
    }

    @Property(tries = 50)
    @Label("325.4: No raw data stored in delivery receipt")
    void requirement325_4_noRawDataStoredInDeliveryReceipt(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create escrow hold and submit delivery receipt
        UUID holdId = createValidEscrowHold(amount);
        
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "raw-capsule-hash-data", "transfer-proof", "ack", false, null);
        DeliveryVerificationResult result = service.submitDeliveryReceipt(receiptRequest);
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
        
        // Create escrow hold with delivery
        UUID holdId = createValidEscrowHoldWithDelivery(totalAmount);
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();

        // Open dispute
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.INCOMPLETE_DELIVERY, List.of("evidence-hash-1"));
        DisputeResult disputeResult = service.openDispute(disputeRequest);
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

        DisputeResolutionResult result = service.resolveDispute(resolutionRequest);
        assertThat(result.success()).as("Partial release should succeed: " + result.error()).isTrue();
        assertThat(result.releasedAmount()).isEqualByComparingTo(releaseAmount);
        assertThat(result.refundedAmount()).isEqualByComparingTo(refundAmount);
    }

    @Property(tries = 50)
    @Label("325.5: Full refund workflow")
    void requirement325_5_fullRefundWorkflow(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create escrow hold (no delivery)
        UUID holdId = createValidEscrowHold(amount);

        // Process full refund
        RefundResult result = service.processRefund(holdId, "DS failed to deliver");
        assertThat(result.success()).as("Full refund should succeed: " + result.error()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo(amount);

        // Verify hold status updated
        EscrowHold updatedHold = service.getEscrowHold(holdId).orElseThrow();
        assertThat(updatedHold.status()).isEqualTo(EscrowHoldStatus.REFUNDED);
    }

    @Property(tries = 50)
    @Label("325.5: Dispute resolution cannot exceed hold amount")
    void requirement325_5_disputeResolutionCannotExceedHoldAmount(
            @ForAll @BigRange(min = "100.00", max = "500.00") BigDecimal totalAmount) {
        
        // Create escrow hold with delivery
        UUID holdId = createValidEscrowHoldWithDelivery(totalAmount);
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();

        // Open dispute
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, hold.requesterId(), DisputeReason.CONTRACT_VIOLATION, List.of());
        DisputeResult disputeResult = service.openDispute(disputeRequest);
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

        DisputeResolutionResult result = service.resolveDispute(resolutionRequest);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceed");
    }

    @Property(tries = 50)
    @Label("325.5: Only transaction parties can open dispute")
    void requirement325_5_onlyTransactionPartiesCanOpenDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();

        // Try to open dispute as non-party
        UUID nonPartyId = UUID.randomUUID();
        DisputeRequest disputeRequest = new DisputeRequest(
                holdId, nonPartyId, DisputeReason.OTHER, List.of());

        DisputeResult result = service.openDispute(disputeRequest);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("transaction parties");

        // DS should be able to open dispute
        DisputeRequest dsDisputeRequest = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.UNAUTHORIZED_USE, List.of());
        DisputeResult dsResult = service.openDispute(dsDisputeRequest);
        assertThat(dsResult.success()).as("DS should be able to open dispute: " + dsResult.error()).isTrue();
    }

    @Property(tries = 50)
    @Label("325.5: Cannot open duplicate dispute")
    void requirement325_5_cannotOpenDuplicateDispute(
            @ForAll @BigRange(min = "1.00", max = "1000.00") BigDecimal amount) {
        
        // Create escrow hold
        UUID holdId = createValidEscrowHold(amount);
        EscrowHold hold = service.getEscrowHold(holdId).orElseThrow();

        // Open first dispute
        DisputeRequest firstDispute = new DisputeRequest(
                holdId, hold.dsId(), DisputeReason.NON_DELIVERY, List.of());
        DisputeResult firstResult = service.openDispute(firstDispute);
        assertThat(firstResult.success()).isTrue();

        // Try to open second dispute
        DisputeRequest secondDispute = new DisputeRequest(
                holdId, hold.requesterId(), DisputeReason.DATA_QUALITY_ISSUE, List.of());
        DisputeResult secondResult = service.openDispute(secondDispute);
        assertThat(secondResult.success()).isFalse();
        assertThat(secondResult.error()).contains("already exists");
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a funded escrow account in the real database.
     * EscrowAccount.create() takes (requesterId, requestId), then fund() and lock() with amounts.
     */
    private void createFundedEscrowAccount(UUID requesterId, UUID requestId, BigDecimal amount) {
        EscrowAccount escrow = EscrowAccount.create(requesterId, requestId);
        escrow.fund(amount);
        escrow.lock(amount);
        escrowRepository.save(escrow);
    }

    private UUID createValidEscrowHold(BigDecimal amount) {
        UUID contractId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID dsId = UUID.randomUUID();

        // Create funded escrow in real database
        createFundedEscrowAccount(requesterId, requestId, amount);

        ContractSignatures validSigs = new ContractSignatures(
                "ds-signature-abc", "requester-signature-xyz", Instant.now(), Instant.now());
        EscrowHoldRequest request = new EscrowHoldRequest(
                contractId, requestId, requesterId, dsId, amount, "contract-hash", validSigs);

        EscrowHoldResult result = service.createEscrowHold(request);
        assertThat(result.success()).as("Failed to create escrow hold: " + result.error()).isTrue();
        return result.holdId();
    }

    private UUID createValidEscrowHoldWithDelivery(BigDecimal amount) {
        UUID holdId = createValidEscrowHold(amount);

        // Submit delivery receipt
        DeliveryReceiptRequest receiptRequest = new DeliveryReceiptRequest(
                holdId, "capsule-hash-delivery", "transfer-proof", "ack", false, null);
        DeliveryVerificationResult result = service.submitDeliveryReceipt(receiptRequest);
        assertThat(result.success()).as("Failed to submit delivery receipt: " + result.error()).isTrue();

        return holdId;
    }
}
