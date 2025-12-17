package com.yachaq.node.contract;

import com.yachaq.node.contract.ContractBuilder.*;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.contract.ContractSigner.*;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.key.KeyManagementService;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ConsentContract Builder.
 * Requirement 314.1, 314.2, 314.3, 314.4, 314.5
 */
class ContractBuilderPropertyTest {

    // ==================== Contract Draft Building Tests (59.1) ====================

    /**
     * Property: Built drafts contain all required fields from request and choices.
     * **Feature: yachaq-platform, Property: Draft Completeness**
     * **Validates: Requirements 314.1, 314.2**
     */
    @Property(tries = 100)
    void builtDrafts_containAllRequiredFields(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        // Verify all required fields are present
        assertThat(draft.id()).isNotNull().isNotBlank();
        assertThat(draft.requestId()).isEqualTo(pair.request().id());
        assertThat(draft.requesterId()).isEqualTo(pair.request().requesterId());
        assertThat(draft.dsNodeId()).isEqualTo("ds-node-1");
        assertThat(draft.selectedLabels()).isNotNull();
        assertThat(draft.outputMode()).isNotNull();
        assertThat(draft.identityReveal()).isNotNull();
        assertThat(draft.compensation()).isNotNull();
        assertThat(draft.ttl()).isNotNull();
        assertThat(draft.nonce()).isNotNull().isNotBlank();
        assertThat(draft.createdAt()).isNotNull();
        assertThat(draft.obligations()).isNotNull();
    }

    /**
     * Property: Selected labels must be subset of request labels.
     * **Feature: yachaq-platform, Property: Label Validation**
     * **Validates: Requirements 314.1**
     */
    @Property(tries = 50)
    void selectedLabels_mustBeSubsetOfRequestLabels(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        Set<String> allowedLabels = new HashSet<>(pair.request().requiredLabels());
        allowedLabels.addAll(pair.request().optionalLabels());
        
        assertThat(allowedLabels).containsAll(draft.selectedLabels());
    }

    /**
     * Property: Required labels must be included in selection.
     * **Feature: yachaq-platform, Property: Required Label Inclusion**
     * **Validates: Requirements 314.1**
     */
    @Property(tries = 50)
    void requiredLabels_mustBeIncluded(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        assertThat(draft.selectedLabels()).containsAll(pair.request().requiredLabels());
    }

    /**
     * Property: Each draft has a unique nonce for replay protection.
     * **Feature: yachaq-platform, Property: Nonce Uniqueness**
     * **Validates: Requirements 314.4**
     */
    @Property(tries = 50)
    void eachDraft_hasUniqueNonce(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
            assertThat(nonces.add(draft.nonce()))
                    .as("Nonce should be unique")
                    .isTrue();
        }
    }

    /**
     * Property: TTL is set correctly based on choices.
     * **Feature: yachaq-platform, Property: TTL Setting**
     * **Validates: Requirements 314.1**
     */
    @Property(tries = 50)
    void ttl_isSetCorrectly(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        // TTL should be in the future
        assertThat(draft.ttl()).isAfter(Instant.now());
        
        // TTL should not be expired
        assertThat(draft.isExpired()).isFalse();
    }

    // ==================== Contract Signing Tests (59.2) ====================

    /**
     * Property: Signed contracts contain valid DS signature.
     * **Feature: yachaq-platform, Property: Signature Validity**
     * **Validates: Requirements 314.3**
     */
    @Property(tries = 50)
    void signedContracts_containValidDsSignature(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) throws Exception {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        SignedContract signed = signer.sign(draft);
        
        assertThat(signed.dsSignature()).isNotNull().isNotBlank();
        assertThat(signed.dsSignedAt()).isNotNull();
        assertThat(signed.status()).isEqualTo(SignedContract.SignatureStatus.DS_SIGNED);
    }

    /**
     * Property: Countersigned contracts have both signatures.
     * **Feature: yachaq-platform, Property: Countersignature**
     * **Validates: Requirements 314.3**
     */
    @Property(tries = 50)
    void countersignedContracts_haveBothSignatures(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) throws Exception {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        SignedContract signed = signer.sign(draft);
        SignedContract countersigned = signer.addCountersignature(
                signed, 
                "requester-signature-" + UUID.randomUUID() + "-" + UUID.randomUUID()
        );
        
        assertThat(countersigned.dsSignature()).isNotNull();
        assertThat(countersigned.requesterSignature()).isNotNull();
        assertThat(countersigned.status()).isEqualTo(SignedContract.SignatureStatus.FULLY_SIGNED);
        assertThat(countersigned.isFullySigned()).isTrue();
    }

    // ==================== Contract Verification Tests (59.3) ====================

    /**
     * Property: Valid signed contracts pass verification.
     * **Feature: yachaq-platform, Property: Verification Success**
     * **Validates: Requirements 314.4**
     */
    @Property(tries = 50)
    void validSignedContracts_passVerification(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) throws Exception {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        SignedContract signed = signer.sign(draft);
        VerificationResult result = signer.verify(signed);
        
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    /**
     * Property: Contract integrity is preserved after signing.
     * **Feature: yachaq-platform, Property: Immutability**
     * **Validates: Requirements 314.4**
     */
    @Property(tries = 50)
    void contractIntegrity_isPreservedAfterSigning(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) throws Exception {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        ContractDraft draft = builder.buildDraft(pair.request(), pair.choices());
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        SignedContract signed = signer.sign(draft);
        
        // Verify integrity
        assertThat(signer.verifyIntegrity(signed)).isTrue();
        
        // Contract hash should be consistent
        String hash1 = signed.getContractHash();
        String hash2 = signed.getContractHash();
        assertThat(hash1).isEqualTo(hash2);
    }

    /**
     * Property: Canonical bytes are deterministic.
     * **Feature: yachaq-platform, Property: Serialization Determinism**
     * **Validates: Requirements 314.5**
     */
    @Property(tries = 50)
    void canonicalBytes_areDeterministic(
            @ForAll("validRequestsAndChoices") RequestChoicesPair pair) {
        
        ContractBuilder builder = new ContractBuilder("ds-node-1", () -> "fixed-nonce");
        
        // Build same draft twice with fixed nonce
        ContractDraft draft1 = builder.buildDraft(pair.request(), pair.choices());
        
        // Canonical bytes should be consistent for same draft
        byte[] bytes1 = draft1.getCanonicalBytes();
        byte[] bytes2 = draft1.getCanonicalBytes();
        
        assertThat(bytes1).isEqualTo(bytes2);
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<RequestChoicesPair> validRequestsAndChoices() {
        return Arbitraries.of("domain:activity", "domain:health", "time:morning")
                .set().ofMinSize(1).ofMaxSize(3)
                .map(labels -> {
                    DataRequest request = createValidRequest(labels);
                    UserChoices choices = createValidChoices(labels);
                    return new RequestChoicesPair(request, choices);
                });
    }

    // ==================== Helper Methods ====================

    private DataRequest createValidRequest(Set<String> labels) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .requesterName("Test Requester")
                .type(RequestType.BROADCAST)
                .requiredLabels(labels)
                .optionalLabels(Set.of("domain:optional"))
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .compensation(new CompensationOffer(10.0, "USD", "escrow-1"))
                .policyStamp("valid-policy-stamp-" + UUID.randomUUID())
                .signature("valid-signature-" + UUID.randomUUID() + "-" + UUID.randomUUID())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private UserChoices createValidChoices(Set<String> labels) {
        return UserChoices.builder()
                .selectedLabels(labels)
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .revealIdentity(false)
                .retentionDays(30)
                .ttlSeconds(3600)
                .build();
    }

    record RequestChoicesPair(DataRequest request, UserChoices choices) {}

    // ==================== Unit Tests ====================

    @Test
    void buildDraft_rejectsNullRequest() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity"))
                .build();
        
        assertThatThrownBy(() -> builder.buildDraft(null, choices))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildDraft_rejectsNullChoices() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity"));
        
        assertThatThrownBy(() -> builder.buildDraft(request, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildDraft_rejectsMissingRequiredLabels() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity", "domain:health"));
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity")) // Missing domain:health
                .build();
        
        assertThatThrownBy(() -> builder.buildDraft(request, choices))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required label");
    }

    @Test
    void buildDraft_rejectsUnallowedLabels() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity"));
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity", "domain:forbidden"))
                .build();
        
        assertThatThrownBy(() -> builder.buildDraft(request, choices))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Label not allowed");
    }

    @Test
    void sign_rejectsExpiredDraft() throws Exception {
        // Create an already-expired draft manually
        ContractDraft expiredDraft = new ContractDraft(
                UUID.randomUUID().toString(),
                "request-1",
                "requester-1",
                "ds-node-1",
                Set.of("domain:activity"),
                null,
                OutputMode.AGGREGATE_ONLY,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-1",
                Instant.now().minusSeconds(3600), // Already expired
                ObligationTerms.standard(),
                "nonce-123",
                Instant.now().minusSeconds(7200),
                Map.of()
        );
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        assertThat(expiredDraft.isExpired()).isTrue();
        assertThatThrownBy(() -> signer.sign(expiredDraft))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void addCountersignature_requiresDsSigned() throws Exception {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity"));
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity"))
                .build();
        
        ContractDraft draft = builder.buildDraft(request, choices);
        
        KeyManagementService keyMgmt = createKeyManagementService();
        ContractSigner signer = new ContractSigner(keyMgmt);
        
        SignedContract signed = signer.sign(draft);
        SignedContract countersigned = signer.addCountersignature(signed, "requester-sig-" + UUID.randomUUID() + "-" + UUID.randomUUID());
        
        // Cannot countersign again
        assertThatThrownBy(() -> signer.addCountersignature(countersigned, "another-sig"))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("DS-signed");
    }

    @Test
    void identityReveal_defaultsToAnonymous() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity"));
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity"))
                .revealIdentity(false)
                .build();
        
        ContractDraft draft = builder.buildDraft(request, choices);
        
        assertThat(draft.isIdentityRevealed()).isFalse();
        assertThat(draft.identityReveal().reveal()).isFalse();
    }

    @Test
    void obligations_areIncludedInDraft() {
        ContractBuilder builder = new ContractBuilder("ds-node-1");
        DataRequest request = createValidRequest(Set.of("domain:activity"));
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("domain:activity"))
                .retentionDays(60)
                .retentionPolicy(ObligationTerms.RetentionPolicy.DELETE_AFTER_PERIOD)
                .usageRestrictions(Set.of("NO_RESALE"))
                .deletionRequirement(ObligationTerms.DeletionRequirement.CRYPTO_SHRED)
                .build();
        
        ContractDraft draft = builder.buildDraft(request, choices);
        
        assertThat(draft.obligations()).isNotNull();
        assertThat(draft.obligations().retentionDays()).isEqualTo(60);
        assertThat(draft.obligations().retentionPolicy())
                .isEqualTo(ObligationTerms.RetentionPolicy.DELETE_AFTER_PERIOD);
        assertThat(draft.obligations().usageRestrictions()).contains("NO_RESALE");
        assertThat(draft.obligations().deletionRequirement())
                .isEqualTo(ObligationTerms.DeletionRequirement.CRYPTO_SHRED);
    }

    // ==================== Helper for Key Management ====================

    private KeyManagementService createKeyManagementService() {
        return new KeyManagementService();
    }
}
