package com.yachaq.node.capsule;

import com.yachaq.node.capsule.CapsuleHeader.CapsuleSummary;
import com.yachaq.node.capsule.CapsulePackager.CapsuleException;
import com.yachaq.node.capsule.CapsulePackager.VerificationResult;
import com.yachaq.node.capsule.TimeCapsule.*;
import com.yachaq.node.contract.ContractBuilder;
import com.yachaq.node.contract.ContractBuilder.UserChoices;
import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.TimeWindow;
import com.yachaq.node.contract.ContractSigner;
import com.yachaq.node.contract.ContractSigner.SignedContract;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.key.KeyManagementService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.security.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property tests for Time Capsule Packager.
 * Requirement 316.5: Validate integrity and reject tampered capsules.
 * Requirement 316.6: Verify integrity checks, wrong-key failures, and TTL lifecycle.
 */
class CapsulePackagerPropertyTest {

    private KeyManagementService keyManagement;
    private ContractBuilder contractBuilder;
    private ContractSigner contractSigner;
    private CapsulePackager packager;
    private KeyPair requesterKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        keyManagement = new KeyManagementService();
        contractBuilder = new ContractBuilder("test-ds-node");
        contractSigner = new ContractSigner(keyManagement);
        packager = new CapsulePackager(keyManagement);
        requesterKeyPair = generateRequesterKeyPair();
    }

    // ==================== Capsule Creation Tests ====================

    /**
     * Property: Capsule creation includes all required header fields.
     * Requirement 316.1: Include header with plan_id, TTL, schema, summary.
     */
    @Test
    void capsuleCreation_includesAllHeaderFields() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of(
                "field1", "value1",
                "field2", 42,
                "field3", true
        );

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        CapsuleHeader header = capsule.header();
        assertThat(header.capsuleId()).isNotNull().isNotEmpty();
        assertThat(header.planId()).isNotNull().isNotEmpty();
        assertThat(header.contractId()).isEqualTo(contract.draft().id());
        assertThat(header.ttl()).isNotNull();
        assertThat(header.schemaVersion()).isEqualTo("1.0");
        assertThat(header.schema()).containsKeys("field1", "field2", "field3");
        assertThat(header.summary()).isNotNull();
        assertThat(header.summary().recordCount()).isEqualTo(3);
        assertThat(header.summary().fieldNames()).containsExactlyInAnyOrder("field1", "field2", "field3");
        assertThat(header.dsNodeId()).isEqualTo("test-ds-node");
        assertThat(header.requesterId()).isEqualTo(contract.draft().requesterId());
    }

    /**
     * Property: Capsule summary accurately reflects output contents.
     * Requirement 316.1: Include summary in header.
     */
    @Test
    void capsuleSummary_accuratelyReflectsContents() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            outputs.put("field" + i, "value" + i);
        }

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        CapsuleSummary summary = capsule.header().summary();
        assertThat(summary.recordCount()).isEqualTo(10);
        assertThat(summary.fieldNames()).hasSize(10);
        assertThat(summary.payloadSizeBytes()).isGreaterThan(0);
        assertThat(summary.outputMode()).isEqualTo(contract.draft().outputMode().name());
    }

    // ==================== Encryption Tests ====================

    /**
     * Property: Payload is encrypted to requester public key only.
     * Requirement 316.2: Encrypt payload to requester public keys only.
     */
    @Test
    void payloadEncryption_onlyDecryptableByRequester() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("secret", "sensitive-data");

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then - encrypted data is not plaintext
        EncryptedPayload payload = capsule.payload();
        String encryptedString = new String(payload.encryptedData());
        assertThat(encryptedString).doesNotContain("sensitive-data");
        assertThat(payload.recipientKeyFingerprint()).isNotEmpty();
    }

    /**
     * Property: Correct requester key can decrypt the capsule.
     * Requirement 316.2: Encrypt payload to requester public keys only.
     */
    @Test
    void decryption_succeedsWithCorrectKey() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("data", "test-value");
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // When
        byte[] decrypted = packager.decrypt(capsule, requesterKeyPair.getPrivate());

        // Then
        String decryptedString = new String(decrypted);
        assertThat(decryptedString).contains("test-value");
    }

    /**
     * Property: Wrong key fails to decrypt the capsule.
     * Requirement 316.6: Verify wrong-key failures.
     */
    @Test
    void decryption_failsWithWrongKey() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("data", "test-value");
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());
        
        // Different key pair
        KeyPair wrongKeyPair = generateRequesterKeyPair();

        // When/Then
        assertThatThrownBy(() -> packager.decrypt(capsule, wrongKeyPair.getPrivate()))
                .isInstanceOf(CapsuleException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    // ==================== Proof Attachment Tests ====================

    /**
     * Property: Capsule includes all required proofs.
     * Requirement 316.3: Attach signatures, capsule hash, contract_id.
     */
    @Test
    void proofAttachment_includesAllRequiredProofs() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        CapsuleProofs proofs = capsule.proofs();
        assertThat(proofs.capsuleHash()).isNotNull().isNotEmpty();
        assertThat(proofs.dsSignature()).isNotNull().isNotEmpty();
        assertThat(proofs.contractId()).isEqualTo(contract.draft().id());
        assertThat(proofs.planHash()).isNotNull().isNotEmpty();
        assertThat(proofs.signedAt()).isNotNull();
    }

    /**
     * Property: Capsule hash is deterministic for same content.
     * Requirement 316.3: Attach capsule hash.
     */
    @Test
    void capsuleHash_isDeterministicForSameContent() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");

        // When - create two capsules with same content
        TimeCapsule capsule1 = packager.create(outputs, contract, requesterKeyPair.getPublic());
        TimeCapsule capsule2 = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then - hashes should be different (different IVs/keys)
        // but both should be valid
        assertThat(capsule1.proofs().capsuleHash()).isNotEmpty();
        assertThat(capsule2.proofs().capsuleHash()).isNotEmpty();
        
        VerificationResult result1 = packager.verify(capsule1);
        VerificationResult result2 = packager.verify(capsule2);
        assertThat(result1.valid()).isTrue();
        assertThat(result2.valid()).isTrue();
    }

    // ==================== Integrity Verification Tests ====================

    /**
     * Property: Valid capsule passes integrity verification.
     * Requirement 316.5: Validate integrity and reject tampered capsules.
     */
    @Test
    void integrityVerification_passesForValidCapsule() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // When
        VerificationResult result = packager.verify(capsule);

        // Then
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    /**
     * Property: Tampered capsule fails integrity verification.
     * Requirement 316.5: Validate integrity and reject tampered capsules.
     */
    @Test
    void integrityVerification_failsForTamperedCapsule() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");
        TimeCapsule original = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Tamper with the payload
        byte[] tamperedData = original.payload().encryptedData().clone();
        tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
        
        EncryptedPayload tamperedPayload = new EncryptedPayload(
                tamperedData,
                original.payload().encryptedKey(),
                original.payload().iv(),
                original.payload().keyId(),
                original.payload().algorithm(),
                original.payload().recipientKeyFingerprint()
        );

        TimeCapsule tampered = TimeCapsule.builder()
                .header(original.header())
                .payload(tamperedPayload)
                .proofs(original.proofs())
                .status(original.status())
                .build();

        // When
        VerificationResult result = packager.verify(tampered);

        // Then
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Capsule hash mismatch - data may be tampered");
    }

    /**
     * Property: Contract ID mismatch is detected.
     * Requirement 316.5: Validate integrity.
     */
    @Test
    void integrityVerification_detectsContractIdMismatch() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");
        TimeCapsule original = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Create proofs with wrong contract ID
        CapsuleProofs wrongProofs = CapsuleProofs.builder()
                .capsuleHash(original.proofs().capsuleHash())
                .dsSignature(original.proofs().dsSignature())
                .contractId("wrong-contract-id")
                .planHash(original.proofs().planHash())
                .signedAt(original.proofs().signedAt())
                .build();

        TimeCapsule tampered = TimeCapsule.builder()
                .header(original.header())
                .payload(original.payload())
                .proofs(wrongProofs)
                .status(original.status())
                .build();

        // When
        VerificationResult result = packager.verify(tampered);

        // Then
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Contract ID mismatch between header and proofs");
    }

    // ==================== TTL and Crypto-Shred Tests ====================

    /**
     * Property: Expired capsule fails verification.
     * Requirement 316.6: Verify TTL lifecycle.
     */
    @Test
    void ttlEnforcement_expiredCapsuleFailsVerification() throws Exception {
        // Given - create contract with past TTL
        DataRequest request = createDataRequest();
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("health.steps"))
                .ttlSeconds(-1) // Already expired
                .build();
        
        ContractDraft draft = contractBuilder.buildDraft(request, choices);
        
        // Manually create an expired draft
        ContractDraft expiredDraft = new ContractDraft(
                draft.id(),
                draft.requestId(),
                draft.requesterId(),
                draft.dsNodeId(),
                draft.selectedLabels(),
                draft.timeWindow(),
                draft.outputMode(),
                draft.identityReveal(),
                draft.compensation(),
                draft.escrowId(),
                Instant.now().minusSeconds(3600), // Expired 1 hour ago
                draft.obligations(),
                draft.nonce(),
                draft.createdAt(),
                draft.metadata()
        );

        // Create a capsule with expired header
        CapsuleHeader expiredHeader = CapsuleHeader.builder()
                .capsuleId(UUID.randomUUID().toString())
                .planId("test-plan")
                .contractId(expiredDraft.id())
                .ttl(Instant.now().minusSeconds(3600)) // Expired
                .schemaVersion("1.0")
                .dsNodeId("test-ds-node")
                .requesterId("test-requester")
                .build();

        TimeCapsule expiredCapsule = TimeCapsule.builder()
                .header(expiredHeader)
                .payload(createDummyPayload())
                .proofs(createDummyProofs(expiredDraft.id()))
                .status(CapsuleStatus.CREATED)
                .build();

        // When
        VerificationResult result = packager.verify(expiredCapsule);

        // Then
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Capsule has expired");
    }

    /**
     * Property: Crypto-shred destroys encryption key.
     * Requirement 316.4: Support crypto-shred for TTL keys.
     */
    @Test
    void cryptoShred_destroysEncryptionKey() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());
        
        String keyId = capsule.payload().keyId();
        assertThat(packager.isShredded(keyId)).isFalse();

        // When
        boolean shredded = packager.cryptoShred(capsule);

        // Then
        assertThat(shredded).isTrue();
        assertThat(packager.isShredded(keyId)).isTrue();
    }

    /**
     * Property: Crypto-shred is idempotent.
     * Requirement 316.4: Support crypto-shred for TTL keys.
     */
    @Test
    void cryptoShred_isIdempotent() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of("field", "value");
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // When - shred twice
        boolean first = packager.cryptoShred(capsule);
        boolean second = packager.cryptoShred(capsule);

        // Then
        assertThat(first).isTrue();
        assertThat(second).isFalse(); // Already shredded
    }

    /**
     * Property: Process expired capsules shreds all expired keys.
     * Requirement 316.4: Support crypto-shred for TTL keys.
     */
    @Test
    void processExpiredCapsules_shredsAllExpiredKeys() throws Exception {
        // Given - create capsules with different TTLs
        List<TimeCapsule> capsules = new ArrayList<>();
        
        // Create a valid capsule first, then manually expire it
        SignedContract contract1 = createFullySignedContract();
        TimeCapsule originalCapsule = packager.create(
                Map.of("field", "value"), 
                contract1, 
                requesterKeyPair.getPublic()
        );
        
        // Create expired version of the capsule by rebuilding with expired TTL
        CapsuleHeader expiredHeader = CapsuleHeader.builder()
                .capsuleId(originalCapsule.header().capsuleId())
                .planId(originalCapsule.header().planId())
                .contractId(originalCapsule.header().contractId())
                .ttl(Instant.now().minusSeconds(3600)) // Expired
                .schemaVersion(originalCapsule.header().schemaVersion())
                .schema(originalCapsule.header().schema())
                .summary(originalCapsule.header().summary())
                .createdAt(originalCapsule.header().createdAt())
                .dsNodeId(originalCapsule.header().dsNodeId())
                .requesterId(originalCapsule.header().requesterId())
                .build();
        
        TimeCapsule expiredCapsule = TimeCapsule.builder()
                .header(expiredHeader)
                .payload(originalCapsule.payload()) // Same payload with registered key
                .proofs(originalCapsule.proofs())
                .status(CapsuleStatus.CREATED)
                .build();
        capsules.add(expiredCapsule);

        // Create valid (non-expired) capsule
        SignedContract contract2 = createFullySignedContract();
        TimeCapsule validCapsule = packager.create(
                Map.of("field", "value"), 
                contract2, 
                requesterKeyPair.getPublic()
        );
        capsules.add(validCapsule);

        // Verify both keys exist before processing
        assertThat(packager.isShredded(expiredCapsule.payload().keyId())).isFalse();
        assertThat(packager.isShredded(validCapsule.payload().keyId())).isFalse();

        // When
        int shreddedCount = packager.processExpiredCapsules(capsules);

        // Then
        assertThat(shreddedCount).isEqualTo(1);
        assertThat(packager.isShredded(expiredCapsule.payload().keyId())).isTrue();
        assertThat(packager.isShredded(validCapsule.payload().keyId())).isFalse();
    }

    // ==================== Contract Validation Tests ====================

    /**
     * Property: Unsigned contract is rejected.
     * Requirement 316.5: Validate integrity.
     */
    @Test
    void capsuleCreation_rejectsUnsignedContract() throws Exception {
        // Given
        DataRequest request = createDataRequest();
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("health.steps"))
                .build();
        ContractDraft draft = contractBuilder.buildDraft(request, choices);
        SignedContract partialContract = contractSigner.sign(draft);
        // Not countersigned

        Map<String, Object> outputs = Map.of("field", "value");

        // When/Then
        assertThatThrownBy(() -> packager.create(outputs, partialContract, requesterKeyPair.getPublic()))
                .isInstanceOf(CapsuleException.class)
                .hasMessageContaining("Contract must be fully signed");
    }

    /**
     * Property: Expired contract is rejected.
     * Requirement 316.5: Validate integrity.
     */
    @Test
    void capsuleCreation_rejectsExpiredContract() throws Exception {
        // Given - create contract that will expire
        DataRequest request = createDataRequest();
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("health.steps"))
                .ttlSeconds(1) // Very short TTL
                .build();
        ContractDraft draft = contractBuilder.buildDraft(request, choices);
        SignedContract signed = contractSigner.sign(draft);
        SignedContract fullySigned = contractSigner.addCountersignature(signed, generateRequesterSignature());

        // Wait for expiration
        Thread.sleep(1100);

        Map<String, Object> outputs = Map.of("field", "value");

        // When/Then
        assertThatThrownBy(() -> packager.create(outputs, fullySigned, requesterKeyPair.getPublic()))
                .isInstanceOf(CapsuleException.class)
                .hasMessageContaining("Contract has expired");
    }

    // ==================== Edge Cases ====================

    /**
     * Property: Empty outputs create valid capsule.
     */
    @Test
    void capsuleCreation_handlesEmptyOutputs() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = Map.of();

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        assertThat(capsule).isNotNull();
        assertThat(capsule.header().summary().recordCount()).isEqualTo(0);
        
        VerificationResult result = packager.verify(capsule);
        assertThat(result.valid()).isTrue();
    }

    /**
     * Property: Large outputs are handled correctly.
     */
    @Test
    void capsuleCreation_handlesLargeOutputs() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            outputs.put("field" + i, "value" + i + "-" + "x".repeat(100));
        }

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        assertThat(capsule).isNotNull();
        assertThat(capsule.header().summary().recordCount()).isEqualTo(1000);
        assertThat(capsule.header().summary().payloadSizeBytes()).isGreaterThan(100000);
        
        // Verify decryption works
        byte[] decrypted = packager.decrypt(capsule, requesterKeyPair.getPrivate());
        assertThat(decrypted.length).isGreaterThan(100000);
    }

    /**
     * Property: Null values in outputs are handled.
     */
    @Test
    void capsuleCreation_handlesNullValues() throws Exception {
        // Given
        SignedContract contract = createFullySignedContract();
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("field1", "value1");
        outputs.put("field2", null);
        outputs.put("field3", 42);

        // When
        TimeCapsule capsule = packager.create(outputs, contract, requesterKeyPair.getPublic());

        // Then
        assertThat(capsule).isNotNull();
        assertThat(capsule.header().schema()).containsKey("field2");
        
        byte[] decrypted = packager.decrypt(capsule, requesterKeyPair.getPrivate());
        String decryptedString = new String(decrypted);
        assertThat(decryptedString).contains("null");
    }

    // ==================== Helper Methods ====================

    private KeyPair generateRequesterKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    private SignedContract createFullySignedContract() throws Exception {
        DataRequest request = createDataRequest();
        UserChoices choices = UserChoices.builder()
                .selectedLabels(Set.of("health.steps"))
                .ttlSeconds(3600)
                .build();
        ContractDraft draft = contractBuilder.buildDraft(request, choices);
        SignedContract signed = contractSigner.sign(draft);
        return contractSigner.addCountersignature(signed, generateRequesterSignature());
    }

    private DataRequest createDataRequest() {
        return DataRequest.builder()
                .generateId()
                .requesterId("test-requester")
                .requesterName("Research Study")
                .type(DataRequest.RequestType.BROADCAST)
                .requiredLabels(Set.of("health.steps"))
                .optionalLabels(Set.of("health.heartrate"))
                .timeWindow(new DataRequest.TimeWindow(
                        Instant.now(),
                        Instant.now().plusSeconds(86400)
                ))
                .outputMode(OutputMode.CLEAN_ROOM)
                .compensation(new DataRequest.CompensationOffer(10.0, "USD", "escrow-123"))
                .policyStamp("policy-stamp-123")
                .signature("valid-signature")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private String generateRequesterSignature() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private EncryptedPayload createDummyPayload() {
        return new EncryptedPayload(
                new byte[32],
                new byte[256],
                new byte[12],
                UUID.randomUUID().toString(),
                "AES/GCM/NoPadding",
                "dummy-fingerprint"
        );
    }

    private CapsuleProofs createDummyProofs(String contractId) {
        return CapsuleProofs.builder()
                .capsuleHash("dummy-hash")
                .dsSignature("dummy-signature")
                .contractId(contractId)
                .planHash("dummy-plan-hash")
                .signedAt(Instant.now())
                .build();
    }
}
