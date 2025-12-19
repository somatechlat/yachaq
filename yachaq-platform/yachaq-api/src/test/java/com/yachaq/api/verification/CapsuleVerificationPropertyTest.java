package com.yachaq.api.verification;

import com.yachaq.api.audit.MerkleTree;
import com.yachaq.api.verification.CapsuleVerificationService.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for CapsuleVerificationService.
 * 
 * Validates: Requirements 349.1, 349.2, 349.3, 349.4
 */
class CapsuleVerificationPropertyTest {

    private CapsuleVerificationService service;

    @BeforeEach
    void setUp() {
        service = new CapsuleVerificationService();
    }

    // ==================== Task 94.1: Signature Verification Tests ====================

    @Test
    void verifySignature_failsWithMissingSignature() {
        // Requirement 349.1: Verify capsule signatures
        CapsuleData capsule = createCapsuleData(null, null, null, null);

        SignatureVerificationResult result = service.verifySignature(capsule);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Missing"));
    }

    @Test
    void verifySignature_failsWithInvalidSignature() {
        CapsuleData capsule = createCapsuleData(
                "invalid-signature",
                "invalid-public-key",
                null,
                null
        );

        SignatureVerificationResult result = service.verifySignature(capsule);

        assertThat(result.valid()).isFalse();
        assertThat(result.dsSignatureValid()).isFalse();
    }

    @Test
    void verifySignature_failsWithFutureTimestamp() {
        CapsuleData capsule = new CapsuleData(
                "capsule-1",
                "contract-1",
                "hash123",
                "sig",
                "key",
                null,
                null,
                Instant.now().plus(1, ChronoUnit.DAYS), // Future timestamp
                "v1.0",
                "AGGREGATE",
                1000,
                Map.of()
        );

        SignatureVerificationResult result = service.verifySignature(capsule);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("future"));
    }

    @Test
    void verifySignature_warnsOnOldSignature() {
        CapsuleData capsule = new CapsuleData(
                "capsule-1",
                "contract-1",
                "hash123",
                "sig",
                "key",
                null,
                null,
                Instant.now().minus(400, ChronoUnit.DAYS), // Old timestamp
                "v1.0",
                "AGGREGATE",
                1000,
                Map.of()
        );

        SignatureVerificationResult result = service.verifySignature(capsule);

        assertThat(result.warnings()).anyMatch(w -> w.contains("1 year"));
    }

    @Test
    void verifySignature_rejectsNullCapsule() {
        assertThatThrownBy(() -> service.verifySignature(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== Task 94.2: Schema Validation Tests ====================

    @Test
    void validateSchema_passesWithValidSchema() {
        // Requirement 349.1: Validate capsule against schema
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(
                "data", "test-data",
                "count", 42
        ), "v1.0");

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0",
                "AGGREGATE",
                List.of(
                        new SchemaField("data", "string", "Data field"),
                        new SchemaField("count", "integer", "Count field")
                ),
                List.of(),
                10000
        );

        SchemaValidationResult result = service.validateSchema(capsule, schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateSchema_failsWithMissingRequiredField() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(
                "data", "test-data"
                // Missing "count" field
        ), "v1.0");

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0",
                "AGGREGATE",
                List.of(
                        new SchemaField("data", "string", "Data field"),
                        new SchemaField("count", "integer", "Count field")
                ),
                List.of(),
                10000
        );

        SchemaValidationResult result = service.validateSchema(capsule, schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Missing required field"));
    }

    @Test
    void validateSchema_failsWithVersionMismatch() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of("data", "test"), "v2.0");

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0",
                "AGGREGATE",
                List.of(new SchemaField("data", "string", "Data")),
                List.of(),
                10000
        );

        SchemaValidationResult result = service.validateSchema(capsule, schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("version mismatch"));
    }

    @Test
    void validateSchema_failsWithTypeMismatch() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(
                "count", "not-a-number" // Should be integer
        ), "v1.0");

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0",
                "AGGREGATE",
                List.of(new SchemaField("count", "integer", "Count")),
                List.of(),
                10000
        );

        SchemaValidationResult result = service.validateSchema(capsule, schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Invalid type"));
    }

    @Test
    void validateSchema_failsWithOversizedPayload() {
        CapsuleData capsule = new CapsuleData(
                "capsule-1", "contract-1", "hash", "sig", "key",
                null, null, Instant.now(), "v1.0", "AGGREGATE",
                20000, // Exceeds max
                Map.of("data", "test")
        );

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0",
                "AGGREGATE",
                List.of(new SchemaField("data", "string", "Data")),
                List.of(),
                10000 // Max size
        );

        SchemaValidationResult result = service.validateSchema(capsule, schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("exceeds maximum"));
    }

    @Test
    void validateSchema_rejectsNullInputs() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        CapsuleSchema schema = new CapsuleSchema("v1.0", null, List.of(), List.of(), 0);

        assertThatThrownBy(() -> service.validateSchema(null, schema))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.validateSchema(capsule, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== Task 94.3: Hash Receipt Verification Tests ====================

    @Test
    void verifyHashReceipt_passesWithMatchingHash() {
        // Requirement 349.1: Verify hash receipts
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        String expectedHash = computeExpectedHash(capsule);

        HashReceipt receipt = new HashReceipt(
                expectedHash,
                null,
                List.of(),
                null,
                null,
                Instant.now()
        );

        HashReceiptVerificationResult result = service.verifyHashReceipt(capsule, receipt);

        assertThat(result.hashMatches()).isTrue();
    }

    @Test
    void verifyHashReceipt_failsWithMismatchedHash() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");

        HashReceipt receipt = new HashReceipt(
                "wrong-hash",
                null,
                List.of(),
                null,
                null,
                Instant.now()
        );

        HashReceiptVerificationResult result = service.verifyHashReceipt(capsule, receipt);

        assertThat(result.valid()).isFalse();
        assertThat(result.hashMatches()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("mismatch"));
    }

    @Test
    void verifyHashReceipt_failsWithFutureTimestamp() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        String expectedHash = computeExpectedHash(capsule);

        HashReceipt receipt = new HashReceipt(
                expectedHash,
                null,
                List.of(),
                null,
                null,
                Instant.now().plus(1, ChronoUnit.DAYS) // Future
        );

        HashReceiptVerificationResult result = service.verifyHashReceipt(capsule, receipt);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("future"));
    }

    @Test
    void verifyHashReceipt_verifiesMerkleProof() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        String leafHash = computeExpectedHash(capsule);

        // Create a simple Merkle tree with 2 leaves
        String siblingHash = MerkleTree.sha256("sibling-data");
        String merkleRoot = leafHash.compareTo(siblingHash) < 0 
                ? MerkleTree.sha256(leafHash + siblingHash)
                : MerkleTree.sha256(siblingHash + leafHash);

        HashReceipt receipt = new HashReceipt(
                leafHash,
                merkleRoot,
                List.of(siblingHash),
                null,
                null,
                Instant.now()
        );

        HashReceiptVerificationResult result = service.verifyHashReceipt(capsule, receipt);

        assertThat(result.merkleProofValid()).isTrue();
    }

    @Test
    void verifyHashReceipt_failsWithInvalidMerkleProof() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        String leafHash = computeExpectedHash(capsule);

        HashReceipt receipt = new HashReceipt(
                leafHash,
                "wrong-merkle-root",
                List.of("sibling-hash"),
                null,
                null,
                Instant.now()
        );

        HashReceiptVerificationResult result = service.verifyHashReceipt(capsule, receipt);

        assertThat(result.merkleProofValid()).isFalse();
    }

    @Test
    void verifyHashReceipt_rejectsNullInputs() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), "v1.0");
        HashReceipt receipt = new HashReceipt("hash", null, List.of(), null, null, Instant.now());

        assertThatThrownBy(() -> service.verifyHashReceipt(null, receipt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.verifyHashReceipt(capsule, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== Complete Verification Tests ====================

    @Test
    void verifyComplete_aggregatesAllResults() {
        CapsuleData capsule = createCapsuleDataWithFields(Map.of("data", "test"), "v1.0");
        String expectedHash = computeExpectedHash(capsule);

        CapsuleSchema schema = new CapsuleSchema(
                "v1.0", "AGGREGATE",
                List.of(new SchemaField("data", "string", "Data")),
                List.of(), 10000
        );

        HashReceipt receipt = new HashReceipt(
                expectedHash, null, List.of(), null, null, Instant.now()
        );

        CompleteVerificationResult result = service.verifyComplete(capsule, schema, receipt);

        assertThat(result.schemaResult().valid()).isTrue();
        assertThat(result.receiptResult().hashMatches()).isTrue();
        assertThat(result.verifiedAt()).isNotNull();
    }

    // ==================== Property Tests ====================

    @Property
    void validateSchema_alwaysReportsVersionMismatch(
            @ForAll("schemaVersions") String schemaVersion,
            @ForAll("schemaVersions") String capsuleVersion) {
        
        Assume.that(!schemaVersion.equals(capsuleVersion));

        CapsuleVerificationService svc = new CapsuleVerificationService();
        CapsuleData capsule = createCapsuleDataWithFields(Map.of(), capsuleVersion);
        CapsuleSchema schema = new CapsuleSchema(schemaVersion, null, List.of(), List.of(), 0);

        SchemaValidationResult result = svc.validateSchema(capsule, schema);

        assertThat(result.errors()).anyMatch(e -> e.contains("version mismatch"));
    }

    @Property
    void validateSchema_typeValidationIsConsistent(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldValues") Object value) {
        
        CapsuleVerificationService svc = new CapsuleVerificationService();
        CapsuleData capsule = createCapsuleDataWithFields(Map.of("field", value), "v1.0");
        CapsuleSchema schema = new CapsuleSchema(
                "v1.0", null,
                List.of(new SchemaField("field", fieldType, "Test")),
                List.of(), 0
        );

        SchemaValidationResult result = svc.validateSchema(capsule, schema);

        // Result should be deterministic
        SchemaValidationResult result2 = svc.validateSchema(capsule, schema);
        assertThat(result.valid()).isEqualTo(result2.valid());
    }

    @Property
    void verifyHashReceipt_hashComputationIsDeterministic(
            @ForAll("capsuleIds") String capsuleId,
            @ForAll("contractIds") String contractId) {
        
        CapsuleData capsule1 = new CapsuleData(
                capsuleId, contractId, "hash", "sig", "key",
                null, null, Instant.now(), "v1.0", "AGGREGATE", 100, Map.of()
        );
        CapsuleData capsule2 = new CapsuleData(
                capsuleId, contractId, "hash", "sig", "key",
                null, null, Instant.now(), "v1.0", "AGGREGATE", 100, Map.of()
        );

        String hash1 = computeExpectedHash(capsule1);
        String hash2 = computeExpectedHash(capsule2);

        assertThat(hash1).isEqualTo(hash2);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> schemaVersions() {
        return Arbitraries.of("v1.0", "v1.1", "v2.0", "v2.1", "v3.0");
    }

    @Provide
    Arbitrary<String> fieldTypes() {
        return Arbitraries.of("string", "integer", "number", "boolean", "array", "object");
    }

    @Provide
    Arbitrary<Object> fieldValues() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(10),
                Arbitraries.integers(),
                Arbitraries.doubles(),
                Arbitraries.of(true, false),
                Arbitraries.of(List.of("a", "b"), List.of(1, 2)),
                Arbitraries.of(Map.of("key", "value"))
        );
    }

    @Provide
    Arbitrary<String> capsuleIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(5)
                .ofMaxLength(20)
                .map(s -> "capsule-" + s);
    }

    @Provide
    Arbitrary<String> contractIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(5)
                .ofMaxLength(20)
                .map(s -> "contract-" + s);
    }

    // ==================== Helper Methods ====================

    private CapsuleData createCapsuleData(String dsSignature, String dsPublicKey,
                                          String platformSignature, String platformPublicKey) {
        return new CapsuleData(
                "capsule-1",
                "contract-1",
                "hash123",
                dsSignature,
                dsPublicKey,
                platformSignature,
                platformPublicKey,
                Instant.now(),
                "v1.0",
                "AGGREGATE",
                1000,
                Map.of()
        );
    }

    private CapsuleData createCapsuleDataWithFields(Map<String, Object> fields, String schemaVersion) {
        return new CapsuleData(
                "capsule-1",
                "contract-1",
                "hash123",
                "sig",
                "key",
                null,
                null,
                Instant.now(),
                schemaVersion,
                "AGGREGATE",
                1000,
                fields
        );
    }

    private String computeExpectedHash(CapsuleData capsule) {
        String data = String.join("|",
                capsule.capsuleId(),
                capsule.contractId(),
                String.valueOf(capsule.payloadSize()),
                capsule.schemaVersion(),
                capsule.outputMode()
        );
        return MerkleTree.sha256(data);
    }
}
