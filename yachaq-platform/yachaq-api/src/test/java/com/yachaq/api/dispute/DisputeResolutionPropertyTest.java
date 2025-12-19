package com.yachaq.api.dispute;

import com.yachaq.api.dispute.DisputeResolutionService.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for DisputeResolutionService.
 * 
 * Validates: Requirements 351.1, 351.2, 351.3, 351.4, 351.5
 */
class DisputeResolutionPropertyTest {

    private DisputeResolutionService service;

    @BeforeEach
    void setUp() {
        // Use null for escrowService - the service handles it gracefully
        service = new DisputeResolutionService(null);
    }

    // ==================== Task 96.1: Dispute Filing Tests ====================

    @Test
    void fileDispute_createsDisputeSuccessfully() {
        // Requirement 351.1: Provide evidence-based dispute flow
        DisputeRequest request = createValidDisputeRequest();

        DisputeFilingResult result = service.fileDispute(request);

        assertThat(result.success()).isTrue();
        assertThat(result.disputeId()).isNotNull();
        assertThat(result.resolutionDeadline()).isAfter(Instant.now());
    }

    @Test
    void fileDispute_setsCorrectStatus() {
        DisputeRequest request = createValidDisputeRequest();

        DisputeFilingResult result = service.fileDispute(request);
        Optional<Dispute> dispute = service.getDispute(result.disputeId());

        assertThat(dispute).isPresent();
        assertThat(dispute.get().status()).isEqualTo(DisputeStatus.FILED);
    }

    @Test
    void fileDispute_addsInitialEvidence() {
        DisputeRequest request = new DisputeRequest(
                "contract-1",
                "capsule-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                DisputeType.DATA_QUALITY,
                "Data quality issue",
                List.of(new EvidenceSubmission(
                        UUID.randomUUID(),
                        EvidenceType.CAPSULE_HASH,
                        "Hash mismatch",
                        "abc123",
                        null
                ))
        );

        DisputeFilingResult result = service.fileDispute(request);
        List<DisputeEvidence> evidence = service.getEvidence(result.disputeId());

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).evidenceType()).isEqualTo(EvidenceType.CAPSULE_HASH);
    }

    @Test
    void fileDispute_rejectsNullRequest() {
        assertThatThrownBy(() -> service.fileDispute(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fileDispute_rejectsMissingContractId() {
        DisputeRequest request = new DisputeRequest(
                null, // missing
                "capsule-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                DisputeType.DATA_QUALITY,
                "Description",
                null
        );

        assertThatThrownBy(() -> service.fileDispute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contract ID");
    }

    @Test
    void addEvidence_addsToExistingDispute() {
        // Requirement 351.1: Evidence-based dispute flow
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        EvidenceSubmission evidence = new EvidenceSubmission(
                UUID.randomUUID(),
                EvidenceType.AUDIT_LOG,
                "Audit log showing issue",
                "hash456",
                "https://example.com/evidence"
        );

        EvidenceAddResult result = service.addEvidence(filing.disputeId(), evidence);

        assertThat(result.success()).isTrue();
        assertThat(result.evidenceId()).isNotNull();
    }

    @Test
    void addEvidence_failsForNonexistentDispute() {
        EvidenceSubmission evidence = new EvidenceSubmission(
                UUID.randomUUID(),
                EvidenceType.AUDIT_LOG,
                "Evidence",
                "hash",
                null
        );

        EvidenceAddResult result = service.addEvidence("nonexistent", evidence);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    // ==================== Task 96.2: Escrow Hold Tests ====================

    @Test
    void holdEscrow_holdsForFiledDispute() {
        // Requirement 351.3: Hold relevant escrow during dispute
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        EscrowHoldResult result = service.holdEscrow(filing.disputeId());

        assertThat(result.success()).isTrue();
        assertThat(result.heldAmount()).isNotNull();
    }

    @Test
    void holdEscrow_updatesDisputeStatus() {
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        service.holdEscrow(filing.disputeId());
        Optional<Dispute> dispute = service.getDispute(filing.disputeId());

        assertThat(dispute).isPresent();
        assertThat(dispute.get().status()).isEqualTo(DisputeStatus.ESCROW_HELD);
    }

    @Test
    void holdEscrow_failsForNonexistentDispute() {
        EscrowHoldResult result = service.holdEscrow("nonexistent");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    @Test
    void getEscrowStatus_returnsCorrectStatus() {
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);
        service.holdEscrow(filing.disputeId());

        EscrowStatus status = service.getEscrowStatus(filing.disputeId());

        assertThat(status.isHeld()).isTrue();
    }

    // ==================== Task 96.3: Dispute Resolution Tests ====================

    @Test
    void resolveDispute_resolvesSuccessfully() {
        // Requirement 351.4: Release or refund escrow per decision
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.FAVOR_DS,
                "Evidence supports DS claim",
                EscrowAction.RELEASE_TO_DS,
                BigDecimal.ZERO,
                UUID.randomUUID()
        );

        ResolutionResult result = service.resolveDispute(filing.disputeId(), decision);

        assertThat(result.success()).isTrue();
        assertThat(result.resolution()).isNotNull();
        assertThat(result.resolution().outcome()).isEqualTo(ResolutionOutcome.FAVOR_DS);
    }

    @Test
    void resolveDispute_updatesStatus() {
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.FAVOR_REQUESTER,
                "Requester claim valid",
                EscrowAction.REFUND_TO_REQUESTER,
                BigDecimal.ZERO,
                UUID.randomUUID()
        );

        service.resolveDispute(filing.disputeId(), decision);
        Optional<Dispute> dispute = service.getDispute(filing.disputeId());

        assertThat(dispute).isPresent();
        assertThat(dispute.get().status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(dispute.get().resolvedAt()).isNotNull();
    }

    @Test
    void resolveDispute_executesEscrowAction() {
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.SPLIT_DECISION,
                "Both parties share responsibility",
                EscrowAction.SPLIT,
                BigDecimal.ZERO,
                UUID.randomUUID()
        );

        ResolutionResult result = service.resolveDispute(filing.disputeId(), decision);

        assertThat(result.escrowResult()).isNotNull();
        assertThat(result.escrowResult().success()).isTrue();
    }

    @Test
    void resolveDispute_failsForAlreadyResolved() {
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = service.fileDispute(request);

        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.FAVOR_DS,
                "First resolution",
                EscrowAction.RELEASE_TO_DS,
                BigDecimal.ZERO,
                UUID.randomUUID()
        );

        service.resolveDispute(filing.disputeId(), decision);

        // Try to resolve again
        ResolutionResult result = service.resolveDispute(filing.disputeId(), decision);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("already resolved");
    }

    @Test
    void resolveDispute_failsForNonexistentDispute() {
        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.FAVOR_DS,
                "Reason",
                EscrowAction.RELEASE_TO_DS,
                BigDecimal.ZERO,
                UUID.randomUUID()
        );

        ResolutionResult result = service.resolveDispute("nonexistent", decision);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    // ==================== Query Tests ====================

    @Test
    void getDisputesForParty_returnsFiledDisputes() {
        UUID partyId = UUID.randomUUID();
        DisputeRequest request = new DisputeRequest(
                "contract-1",
                "capsule-1",
                partyId,
                UUID.randomUUID(),
                DisputeType.DATA_QUALITY,
                "Description",
                null
        );

        service.fileDispute(request);
        List<Dispute> disputes = service.getDisputesForParty(partyId);

        assertThat(disputes).hasSize(1);
        assertThat(disputes.get(0).filedBy()).isEqualTo(partyId);
    }

    @Test
    void getDisputesForParty_returnsDisputesAgainst() {
        UUID partyId = UUID.randomUUID();
        DisputeRequest request = new DisputeRequest(
                "contract-1",
                "capsule-1",
                UUID.randomUUID(),
                partyId, // filed against
                DisputeType.DATA_QUALITY,
                "Description",
                null
        );

        service.fileDispute(request);
        List<Dispute> disputes = service.getDisputesForParty(partyId);

        assertThat(disputes).hasSize(1);
        assertThat(disputes.get(0).filedAgainst()).isEqualTo(partyId);
    }

    // ==================== Property Tests ====================

    @Property
    void fileDispute_alwaysGeneratesUniqueId(@ForAll("disputeTypes") DisputeType type) {
        DisputeResolutionService svc = createService();
        
        DisputeRequest request1 = new DisputeRequest(
                "contract-1", "capsule-1",
                UUID.randomUUID(), UUID.randomUUID(),
                type, "Description 1", null
        );
        DisputeRequest request2 = new DisputeRequest(
                "contract-2", "capsule-2",
                UUID.randomUUID(), UUID.randomUUID(),
                type, "Description 2", null
        );

        DisputeFilingResult result1 = svc.fileDispute(request1);
        DisputeFilingResult result2 = svc.fileDispute(request2);

        assertThat(result1.disputeId()).isNotEqualTo(result2.disputeId());
    }

    @Property
    void resolveDispute_escrowActionMatchesOutcome(
            @ForAll("resolutionOutcomes") ResolutionOutcome outcome,
            @ForAll("escrowActions") EscrowAction action) {
        
        DisputeResolutionService svc = createService();
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = svc.fileDispute(request);

        ResolutionDecision decision = new ResolutionDecision(
                outcome, "Reason", action, BigDecimal.ZERO, UUID.randomUUID()
        );

        ResolutionResult result = svc.resolveDispute(filing.disputeId(), decision);

        assertThat(result.success()).isTrue();
        assertThat(result.resolution().escrowAction()).isEqualTo(action);
    }

    @Property
    void addEvidence_cannotAddToResolvedDispute(@ForAll("evidenceTypes") EvidenceType type) {
        DisputeResolutionService svc = createService();
        DisputeRequest request = createValidDisputeRequest();
        DisputeFilingResult filing = svc.fileDispute(request);

        // Resolve the dispute
        ResolutionDecision decision = new ResolutionDecision(
                ResolutionOutcome.DISMISSED, "Dismissed", EscrowAction.HOLD,
                BigDecimal.ZERO, UUID.randomUUID()
        );
        svc.resolveDispute(filing.disputeId(), decision);

        // Try to add evidence
        EvidenceSubmission evidence = new EvidenceSubmission(
                UUID.randomUUID(), type, "Late evidence", "hash", null
        );
        EvidenceAddResult result = svc.addEvidence(filing.disputeId(), evidence);

        assertThat(result.success()).isFalse();
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<DisputeType> disputeTypes() {
        return Arbitraries.of(DisputeType.values());
    }

    @Provide
    Arbitrary<ResolutionOutcome> resolutionOutcomes() {
        return Arbitraries.of(ResolutionOutcome.values());
    }

    @Provide
    Arbitrary<EscrowAction> escrowActions() {
        return Arbitraries.of(EscrowAction.values());
    }

    @Provide
    Arbitrary<EvidenceType> evidenceTypes() {
        return Arbitraries.of(EvidenceType.values());
    }

    // ==================== Helper Methods ====================

    private DisputeRequest createValidDisputeRequest() {
        return new DisputeRequest(
                "contract-" + UUID.randomUUID(),
                "capsule-" + UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DisputeType.DATA_QUALITY,
                "Test dispute description",
                null
        );
    }

    private DisputeResolutionService createService() {
        return new DisputeResolutionService(null);
    }
}
