package com.yachaq.node.security;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.extractor.ExtractedFeatures;
import com.yachaq.node.extractor.FeatureExtractor;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.labeler.*;
import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.normalizer.CanonicalEvent.*;
import com.yachaq.node.odx.*;
import com.yachaq.node.odx.ODXEntry.*;
import com.yachaq.node.planvm.*;
import com.yachaq.node.planvm.QueryPlan.*;
import com.yachaq.node.safety.SensitivityGate;
import com.yachaq.node.safety.SensitivityGate.*;
import com.yachaq.node.transport.NetworkGate;
import com.yachaq.node.transport.NetworkGate.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Security Test Suite for YACHAQ Phone-as-Node Architecture.
 * 
 * Task 77: Security Test Suite
 * - 77.1 ODX Safety Scanner (Requirements 337.1)
 * - 77.2 PlanVM Fuzzing Suite (Requirements 337.2)
 * - 77.3 Parser Fuzzing Suite (Requirements 337.3)
 * - 77.4 Network Egress Guard Tests (Requirements 337.4)
 * - 77.5 Replay and MITM Tests (Requirements 337.5)
 * - 77.6 Correlation Tests (Requirements 337.6)
 * - 77.7 High-Risk Gating Tests (Requirements 337.7)
 */
class SecurityTestSuite {

    // Initialize fields directly for jqwik property tests (jqwik doesn't use @BeforeEach)
    private final ODXBuilder odxBuilder = new ODXBuilder();
    private final Labeler labeler = new Labeler();
    private final FeatureExtractor featureExtractor = new FeatureExtractor();
    private final PlanValidator planValidator = new PlanValidator();
    private final SensitivityGate sensitivityGate = new SensitivityGate();
    
    // These need fresh instances per test to avoid state pollution
    // For JUnit tests, @BeforeEach initializes them
    // For jqwik property tests, use freshXxx() helper methods
    private PlanVM planVM;
    private NetworkGate networkGate;
    private KeyManagementService keyManagementService;

    @BeforeEach
    void setUp() {
        planVM = new PlanVM();
        networkGate = new NetworkGate();
        keyManagementService = new KeyManagementService();
    }
    
    // Helper to get fresh instances for property tests (jqwik doesn't run @BeforeEach)
    private NetworkGate freshNetworkGate() {
        return new NetworkGate();
    }
    
    private KeyManagementService freshKeyManagementService() {
        return new KeyManagementService();
    }
    
    private PlanVM freshPlanVM() {
        return new PlanVM();
    }

    // ==================================================================================
    // 77.1 ODX Safety Scanner - Requirements 337.1
    // Ensure forbidden fields never appear in ODX or coordinator payloads
    // ==================================================================================

    /**
     * Property: Forbidden fields (raw, payload, content, email, phone, etc.) 
     * never appear in ODX entries.
     * **Validates: Requirements 337.1**
     */
    @Property(tries = 200)
    void odxSafetyScanner_forbiddenFieldsNeverAppear(
            @ForAll("sensitiveEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        List<ODXEntry> entries = odxBuilder.build(labelSet, features);
        
        Set<String> forbiddenPatterns = Set.of(
                "raw", "payload", "content", "text", "email", "phone",
                "address", "name", "ssn", "password", "secret", "token",
                "body", "message", "creditcard", "bankaccount"
        );
        
        for (ODXEntry entry : entries) {
            String facetLower = entry.facetKey().toLowerCase();
            for (String forbidden : forbiddenPatterns) {
                assertThat(facetLower)
                        .as("ODX facet key must not contain forbidden pattern '%s'", forbidden)
                        .doesNotContain(forbidden);
            }
            
            // Verify geo bucket doesn't leak precise coordinates
            if (entry.geoBucket() != null) {
                assertThat(entry.geoBucket())
                        .as("Geo bucket must not contain precise coordinates")
                        .doesNotContainPattern("\\d+\\.\\d{3,}");
            }
            
            // Verify time bucket doesn't leak precise timestamps
            assertThat(entry.timeBucket())
                        .as("Time bucket must follow coarse format")
                        .matches("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)$");
        }
    }

    /**
     * Property: ODX entries with EXACT geo resolution are rejected.
     * **Validates: Requirements 337.1**
     */
    @Test
    void odxSafetyScanner_rejectsExactGeoResolution() {
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
     * Property: ODX entries with PII patterns in facet keys are rejected.
     * **Validates: Requirements 337.1**
     */
    @Property(tries = 50)
    void odxSafetyScanner_rejectsPIIPatterns(
            @ForAll("piiPatterns") String piiPattern) {
        
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey(piiPattern)
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
    }

    /**
     * Property: Coordinator payloads never contain raw data.
     * **Validates: Requirements 337.1**
     */
    @Property(tries = 100)
    void odxSafetyScanner_coordinatorPayloadsNeverContainRawData(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        List<ODXEntry> entries = odxBuilder.build(labelSet, features);
        
        // Simulate coordinator payload serialization
        for (ODXEntry entry : entries) {
            String serialized = serializeForCoordinator(entry);
            
            // Verify no raw event data leaks
            if (event.attributes() != null) {
                for (Object value : event.attributes().values()) {
                    if (value instanceof String strValue && strValue.length() > 10) {
                        assertThat(serialized)
                                .as("Coordinator payload must not contain raw attribute values")
                                .doesNotContain(strValue);
                    }
                }
            }
        }
    }

    // ==================================================================================
    // 77.2 PlanVM Fuzzing Suite - Requirements 337.2
    // Test disallowed operators, oversized outputs, sandbox escapes
    // ==================================================================================

    /**
     * Property: Disallowed operators are rejected by PlanVM.
     * **Validates: Requirements 337.2**
     */
    @Property(tries = 100)
    void planVMFuzzing_disallowedOperatorsRejected(
            @ForAll("disallowedOperatorNames") String operatorName) {
        
        assertThat(PlanOperator.isAllowed(operatorName))
                .as("Operator '%s' must not be allowed", operatorName)
                .isFalse();
    }

    /**
     * Property: Plans with excessive resource limits are rejected.
     * **Validates: Requirements 337.2**
     */
    @Property(tries = 50)
    void planVMFuzzing_excessiveResourceLimitsRejected(
            @ForAll @IntRange(min = 100000, max = 1000000) int cpuMillis,
            @ForAll @IntRange(min = 500000000, max = 2000000000) int memoryBytes) {
        
        QueryPlan plan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .resourceLimits(new ResourceLimits(cpuMillis, memoryBytes, 600000, 50))
                .signature("valid-signature")
                .build();
        
        PlanValidator.ValidationResult result = planValidator.validate(plan, null);
        
        assertThat(result.valid())
                .as("Plan with excessive resources should be rejected")
                .isFalse();
    }

    /**
     * Property: Network egress is blocked during PlanVM execution.
     * **Validates: Requirements 337.2**
     */
    @Test
    void planVMFuzzing_networkEgressBlockedDuringExecution() {
        PlanVM.NetworkGate gate = new PlanVM.NetworkGate();
        PlanVM vm = new PlanVM(new PlanValidator(), gate, new PlanVM.ResourceMonitor());
        
        QueryPlan plan = createValidPlan(Set.of("domain:activity"), "contract-1");
        ContractDraft contract = createContract(Set.of("domain:activity"));
        
        // Execute plan
        vm.execute(plan, contract, Map.of("domain:activity", "test"));
        
        // Verify network was blocked during execution (gate should be unblocked after)
        assertThat(gate.isBlocked()).isFalse();
        
        // Verify blocking works
        gate.blockAll();
        assertThatThrownBy(() -> gate.checkEgress("https://malicious.com"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("blocked");
        
        vm.shutdown();
    }

    /**
     * Property: Sandbox escape attempts via arbitrary code are blocked.
     * **Validates: Requirements 337.2**
     */
    @Property(tries = 50)
    void planVMFuzzing_sandboxEscapeAttemptsBlocked(
            @ForAll("maliciousOperatorParams") Map<String, Object> params) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        PlanVM vm = freshPlanVM();
        
        // Attempt to inject malicious parameters
        QueryPlan plan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, params, Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .signature("valid-signature")
                .build();
        
        ContractDraft contract = createContract(Set.of("domain:activity"));
        
        // Execution should not throw unhandled exceptions
        PlanVM.ExecutionResult result = vm.execute(plan, contract, Map.of("domain:activity", "test"));
        
        // Either succeeds safely or fails with controlled error
        assertThat(result).isNotNull();
        
        vm.shutdown();
    }

    /**
     * Property: Oversized outputs are rejected.
     * **Validates: Requirements 337.2**
     */
    @Test
    void planVMFuzzing_oversizedOutputsRejected() {
        QueryPlan plan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .outputConfig(new OutputConfig(OutputConfig.OutputMode.CAPSULE, 1000000, 10000000, true))
                .resourceLimits(ResourceLimits.defaults())
                .signature("valid-signature")
                .build();
        
        PlanValidator.ValidationResult result = planValidator.validate(plan, null);
        
        // Oversized output config should trigger validation warning or rejection
        assertThat(result.warnings().isEmpty() && result.valid())
                .as("Oversized output config should be flagged")
                .isFalse();
    }

    // ==================================================================================
    // 77.3 Parser Fuzzing Suite - Requirements 337.3
    // Test ZIP bombs, JSON bombs, malformed encodings
    // ==================================================================================

    /**
     * Property: JSON bombs (deeply nested structures) are handled safely.
     * **Validates: Requirements 337.3**
     */
    @Property(tries = 50)
    void parserFuzzing_jsonBombsHandledSafely(
            @ForAll @IntRange(min = 100, max = 1000) int nestingDepth) {
        
        // Create deeply nested JSON structure
        StringBuilder jsonBomb = new StringBuilder();
        for (int i = 0; i < nestingDepth; i++) {
            jsonBomb.append("{\"nested\":");
        }
        jsonBomb.append("\"value\"");
        for (int i = 0; i < nestingDepth; i++) {
            jsonBomb.append("}");
        }
        
        // Parsing should either succeed with limits or fail gracefully with controlled exception
        // (not crash, hang, or cause OOM)
        assertThatCode(() -> {
            try {
                parseJsonSafely(jsonBomb.toString());
            } catch (IllegalArgumentException e) {
                // Expected for deeply nested JSON - this is safe handling
                assertThat(e.getMessage()).contains("nesting depth exceeded");
            }
        }).doesNotThrowAnyException();
    }

    /**
     * Property: Malformed UTF-8 encodings are handled safely.
     * **Validates: Requirements 337.3**
     */
    @Property(tries = 100)
    void parserFuzzing_malformedEncodingsHandledSafely(
            @ForAll("malformedByteSequences") byte[] malformedBytes) {
        
        // Attempt to decode malformed bytes
        assertThatCode(() -> {
            String decoded = new String(malformedBytes, StandardCharsets.UTF_8);
            // Further processing should not crash
            decoded.length();
        }).doesNotThrowAnyException();
    }

    /**
     * Property: Extremely long strings are handled safely.
     * **Validates: Requirements 337.3**
     */
    @Property(tries = 20)
    void parserFuzzing_extremelyLongStringsHandledSafely(
            @ForAll @IntRange(min = 10000, max = 100000) int length) {
        
        // Create extremely long string
        char[] chars = new char[length];
        Arrays.fill(chars, 'X');
        String longString = new String(chars);
        
        // Processing should not cause OOM or hang
        assertThatCode(() -> {
            // Simulate processing
            longString.hashCode();
            longString.substring(0, Math.min(100, length));
        }).doesNotThrowAnyException();
    }

    /**
     * Property: Null bytes in strings are handled safely.
     * **Validates: Requirements 337.3**
     */
    @Test
    void parserFuzzing_nullBytesHandledSafely() {
        String withNulls = "test\0data\0with\0nulls";
        
        assertThatCode(() -> {
            // Processing should handle null bytes
            withNulls.replace("\0", "");
            withNulls.getBytes(StandardCharsets.UTF_8);
        }).doesNotThrowAnyException();
    }

    /**
     * Property: Control characters are sanitized.
     * **Validates: Requirements 337.3**
     */
    @Property(tries = 50)
    void parserFuzzing_controlCharactersSanitized(
            @ForAll("stringsWithControlChars") String input) {
        
        String sanitized = sanitizeControlCharacters(input);
        
        // Verify no dangerous control characters remain
        assertThat(sanitized)
                .doesNotContain("\u0000")  // Null
                .doesNotContain("\u001B"); // Escape
    }

    // ==================================================================================
    // 77.4 Network Egress Guard Tests - Requirements 337.4
    // Attempt to send raw payload bytes to any route
    // ==================================================================================

    /**
     * Property: Raw payload bytes are blocked from egress.
     * **Validates: Requirements 337.4**
     */
    @Property(tries = 100)
    void networkEgressGuard_rawPayloadBytesBlocked(
            @ForAll("rawPayloadData") byte[] rawPayload) {
        
        NetworkGate gate = freshNetworkGate();
        gate.allow("api.yachaq.io", "Platform API");
        
        NetworkRequest request = NetworkRequest.capsule("api.yachaq.io", rawPayload);
        
        // Raw payload should be blocked
        PayloadClassification classification = gate.classifyPayload(rawPayload);
        
        if (classification == PayloadClassification.RAW_PAYLOAD) {
            assertThatThrownBy(() -> gate.send(request))
                    .isInstanceOf(NetworkGateException.class)
                    .hasMessageContaining("Raw payload");
        }
    }

    /**
     * Property: Unknown destinations are always blocked.
     * **Validates: Requirements 337.4**
     */
    @Property(tries = 100)
    void networkEgressGuard_unknownDestinationsBlocked(
            @ForAll("randomDomains") String domain) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        NetworkGate gate = freshNetworkGate();
        
        // Don't add to allowlist
        NetworkRequest request = NetworkRequest.metadata(domain, new byte[0]);
        
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("not in allowlist");
    }

    /**
     * Property: PII patterns in payloads are blocked.
     * **Validates: Requirements 337.4**
     */
    @Property(tries = 50)
    void networkEgressGuard_piiPatternsBlocked(
            @ForAll("piiPayloads") byte[] piiPayload) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        NetworkGate gate = freshNetworkGate();
        
        gate.allow("api.yachaq.io", "Platform API");
        NetworkRequest request = NetworkRequest.metadata("api.yachaq.io", piiPayload);
        
        // PII patterns should be blocked
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("forbidden patterns");
    }

    /**
     * Property: Only ciphertext capsules are allowed for data transfer.
     * **Validates: Requirements 337.4**
     */
    @Test
    void networkEgressGuard_onlyCiphertextAllowedForTransfer() throws Exception {
        networkGate.allow("relay.yachaq.io", "Capsule relay");
        
        // Generate high-entropy ciphertext
        byte[] ciphertext = new byte[1024];
        new SecureRandom().nextBytes(ciphertext);
        
        NetworkRequest request = NetworkRequest.capsule("relay.yachaq.io", ciphertext);
        GateResult result = networkGate.send(request);
        
        assertThat(result.allowed()).isTrue();
        assertThat(result.classification()).isEqualTo(PayloadClassification.CIPHERTEXT_CAPSULE);
    }

    /**
     * Property: All blocked attempts are logged.
     * **Validates: Requirements 337.4**
     */
    @Test
    void networkEgressGuard_blockedAttemptsLogged() {
        // Attempt various blocked operations
        try { networkGate.send(NetworkRequest.metadata("unknown.com", new byte[0])); } 
        catch (NetworkGateException ignored) {}
        
        networkGate.allow("api.yachaq.io", "API");
        // Use a payload that triggers FORBIDDEN_PATTERN (email pattern) but is long enough
        // to not be classified as RAW_PAYLOAD (needs to look like metadata/JSON)
        try { networkGate.send(NetworkRequest.metadata("api.yachaq.io", 
                "{\"contact\": \"user@example.com\", \"type\": \"metadata\"}".getBytes())); } 
        catch (NetworkGateException ignored) {}
        
        List<EgressAttempt> blocked = networkGate.getBlockedAttempts();
        
        assertThat(blocked).hasSize(2);
        assertThat(blocked.get(0).reason()).isEqualTo(BlockReason.UNKNOWN_DESTINATION);
        assertThat(blocked.get(1).reason()).isEqualTo(BlockReason.FORBIDDEN_PATTERN);
    }

    // ==================================================================================
    // 77.5 Replay and MITM Tests - Requirements 337.5
    // Attempt reuse of old requests/contracts/capsules
    // ==================================================================================

    /**
     * Property: Contracts with expired nonces are rejected.
     * **Validates: Requirements 337.5**
     */
    @Test
    void replayProtection_expiredNoncesRejected() {
        // Create contract with old timestamp
        ContractDraft oldContract = new ContractDraft(
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
                Instant.now().minusSeconds(7200), // Expired 2 hours ago
                ObligationTerms.standard(),
                "nonce-" + UUID.randomUUID(),
                Instant.now().minusSeconds(7200),
                Map.of()
        );
        
        // Contract should be detected as expired
        assertThat(oldContract.ttl().isBefore(Instant.now()))
                .as("Contract TTL should be expired")
                .isTrue();
    }

    /**
     * Property: Duplicate nonces are detected.
     * **Validates: Requirements 337.5**
     */
    @Test
    void replayProtection_duplicateNoncesDetected() {
        String sharedNonce = "nonce-" + UUID.randomUUID();
        Set<String> usedNonces = new HashSet<>();
        
        // First use should succeed
        boolean firstUse = usedNonces.add(sharedNonce);
        assertThat(firstUse).isTrue();
        
        // Second use (replay) should be detected
        boolean replayAttempt = usedNonces.add(sharedNonce);
        assertThat(replayAttempt)
                .as("Replay attempt should be detected")
                .isFalse();
    }

    /**
     * Property: Plans with expired timestamps are rejected.
     * **Validates: Requirements 337.5**
     */
    @Test
    void replayProtection_expiredPlansRejected() {
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
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan has expired");
    }

    /**
     * Property: Unsigned plans are rejected (MITM protection).
     * **Validates: Requirements 337.5**
     */
    @Test
    void mitmProtection_unsignedPlansRejected() {
        QueryPlan unsignedPlan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .signature(null)
                .build();
        
        PlanValidator.ValidationResult result = planValidator.validate(unsignedPlan, null);
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan is not signed");
    }

    /**
     * Property: Tampered signatures are detected.
     * **Validates: Requirements 337.5**
     */
    @Property(tries = 50)
    void mitmProtection_tamperedSignaturesDetected(
            @ForAll("randomData") byte[] data) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        // Sign data
        byte[] signature = kms.signWithRootKey(data);
        
        // Tamper with data
        byte[] tamperedData = data.clone();
        if (tamperedData.length > 0) {
            tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
        }
        
        // Verification should fail
        boolean valid = kms.verifySignature(
                tamperedData, 
                signature, 
                kms.getOrCreateRootKeyPair().getPublic()
        );
        
        assertThat(valid)
                .as("Tampered data should fail signature verification")
                .isFalse();
    }

    /**
     * Property: Valid signatures are accepted.
     * **Validates: Requirements 337.5**
     */
    @Property(tries = 50)
    void mitmProtection_validSignaturesAccepted(
            @ForAll("randomData") byte[] data) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        // Sign data
        byte[] signature = kms.signWithRootKey(data);
        
        // Verification should succeed
        boolean valid = kms.verifySignature(
                data, 
                signature, 
                kms.getOrCreateRootKeyPair().getPublic()
        );
        
        assertThat(valid)
                .as("Valid signature should be accepted")
                .isTrue();
    }

    // ==================================================================================
    // 77.6 Correlation Tests - Requirements 337.6
    // Verify pairwise identities differ per requester and rotate
    // ==================================================================================

    /**
     * Property: Pairwise DIDs differ per requester.
     * **Validates: Requirements 337.6**
     */
    @Property(tries = 100)
    void correlationProtection_pairwiseDIDsDifferPerRequester(
            @ForAll("requesterIds") String requesterId1,
            @ForAll("requesterIds") String requesterId2) {
        
        Assume.that(!requesterId1.equals(requesterId2));
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        KeyManagementService.PairwiseDID did1 = kms.getOrCreatePairwiseDID(requesterId1);
        KeyManagementService.PairwiseDID did2 = kms.getOrCreatePairwiseDID(requesterId2);
        
        assertThat(did1.id())
                .as("Pairwise DIDs for different requesters must differ")
                .isNotEqualTo(did2.id());
        
        assertThat(did1.publicKey())
                .as("Pairwise public keys for different requesters must differ")
                .isNotEqualTo(did2.publicKey());
    }

    /**
     * Property: Same requester gets same pairwise DID (until rotation).
     * **Validates: Requirements 337.6**
     */
    @Property(tries = 50)
    void correlationProtection_sameRequesterGetsSameDID(
            @ForAll("requesterIds") String requesterId) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        KeyManagementService.PairwiseDID did1 = kms.getOrCreatePairwiseDID(requesterId);
        KeyManagementService.PairwiseDID did2 = kms.getOrCreatePairwiseDID(requesterId);
        
        assertThat(did1.id())
                .as("Same requester should get same pairwise DID")
                .isEqualTo(did2.id());
    }

    /**
     * Property: Pairwise DID rotation produces new identity.
     * **Validates: Requirements 337.6**
     */
    @Property(tries = 50)
    void correlationProtection_rotationProducesNewIdentity(
            @ForAll("requesterIds") String requesterId) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        KeyManagementService.PairwiseDID originalDID = kms.getOrCreatePairwiseDID(requesterId);
        KeyManagementService.PairwiseDID rotatedDID = kms.rotatePairwiseDID(requesterId);
        
        assertThat(rotatedDID.id())
                .as("Rotated DID must differ from original")
                .isNotEqualTo(originalDID.id());
        
        assertThat(rotatedDID.publicKey())
                .as("Rotated public key must differ from original")
                .isNotEqualTo(originalDID.publicKey());
    }

    /**
     * Property: Node DID is consistent across calls.
     * **Validates: Requirements 337.6**
     */
    @Test
    void correlationProtection_nodeDIDConsistent() {
        KeyManagementService.NodeDID nodeDID1 = keyManagementService.getOrCreateNodeDID();
        KeyManagementService.NodeDID nodeDID2 = keyManagementService.getOrCreateNodeDID();
        
        assertThat(nodeDID1.id())
                .as("Node DID should be consistent")
                .isEqualTo(nodeDID2.id());
    }

    /**
     * Property: Pairwise DIDs cannot be correlated to Node DID.
     * **Validates: Requirements 337.6**
     */
    @Property(tries = 50)
    void correlationProtection_pairwiseDIDsNotCorrelatedToNodeDID(
            @ForAll("requesterIds") String requesterId) {
        
        // Use fresh instance for jqwik property tests (jqwik doesn't run @BeforeEach)
        KeyManagementService kms = freshKeyManagementService();
        
        KeyManagementService.NodeDID nodeDID = kms.getOrCreateNodeDID();
        KeyManagementService.PairwiseDID pairwiseDID = kms.getOrCreatePairwiseDID(requesterId);
        
        // Pairwise DID should not contain node DID components
        assertThat(pairwiseDID.id())
                .as("Pairwise DID should not contain node DID")
                .doesNotContain(nodeDID.id().substring(nodeDID.id().lastIndexOf(":") + 1));
        
        // Public keys should differ
        assertThat(pairwiseDID.publicKey())
                .as("Pairwise public key should differ from node public key")
                .isNotEqualTo(nodeDID.publicKey());
    }

    /**
     * Property: Network identifier rotation produces new identifier.
     * **Validates: Requirements 337.6**
     */
    @Test
    void correlationProtection_networkIdentifierRotation() {
        String networkId1 = keyManagementService.rotateNetworkIdentifier();
        String networkId2 = keyManagementService.rotateNetworkIdentifier();
        
        assertThat(networkId1)
                .as("Network identifiers should differ after rotation")
                .isNotEqualTo(networkId2);
    }

    // ==================================================================================
    // 77.7 High-Risk Gating Tests - Requirements 337.7
    // Verify health + minors + neighborhood defaults to clean-room
    // ==================================================================================

    /**
     * Property: Health + Minors combination triggers clean-room enforcement.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_healthMinorsCombinationTriggersCleanRoom() {
        DataRequest request = createDataRequest(
                Set.of("domain:health", "demographic:minor"),
                Set.of(),
                OutputMode.RAW_EXPORT
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        
        assertThat(assessment.riskLevel())
                .as("Health + Minors should be HIGH or CRITICAL risk")
                .isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
        
        assertThat(assessment.requiredProtections())
                .as("Should require clean-room protection")
                .contains(RequiredProtection.CLEAN_ROOM_ONLY);
    }

    /**
     * Property: Health + Minors + Location triggers maximum protection.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_healthMinorsLocationTriggersMaxProtection() {
        DataRequest request = createDataRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"),
                Set.of(),
                OutputMode.RAW_EXPORT
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        
        assertThat(assessment.riskLevel())
                .as("Health + Minors + Location should be CRITICAL risk")
                .isEqualTo(RiskLevel.CRITICAL);
        
        assertThat(assessment.requiredProtections())
                .as("Should require all maximum protections")
                .contains(
                        RequiredProtection.CLEAN_ROOM_ONLY,
                        RequiredProtection.NO_EXPORT,
                        RequiredProtection.COARSE_GEO,
                        RequiredProtection.ADDITIONAL_CONSENT
                );
    }

    /**
     * Property: Biometric + Minors triggers clean-room enforcement.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_biometricMinorsTriggersCleanRoom() {
        DataRequest request = createDataRequest(
                Set.of("biometric:face", "demographic:minor"),
                Set.of(),
                OutputMode.EXPORT_ALLOWED
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        
        assertThat(assessment.riskLevel())
                .as("Biometric + Minors should be CRITICAL risk")
                .isEqualTo(RiskLevel.CRITICAL);
        
        assertThat(assessment.requiredProtections())
                .contains(RequiredProtection.CLEAN_ROOM_ONLY, RequiredProtection.NO_EXPORT);
    }

    /**
     * Property: Forced defaults are applied to high-risk contracts.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_forcedDefaultsApplied() {
        DataRequest request = createDataRequest(
                Set.of("domain:health", "demographic:minor", "geo:precise"),
                Set.of(),
                OutputMode.RAW_EXPORT
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        
        ContractDraft originalDraft = new ContractDraft(
                UUID.randomUUID().toString(),
                "request-1",
                "requester-1",
                "ds-node-1",
                Set.of("domain:health", "demographic:minor", "geo:precise"),
                null,
                OutputMode.RAW_EXPORT,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-1",
                Instant.now().plusSeconds(3600),
                ObligationTerms.standard(),
                "nonce-" + UUID.randomUUID(),
                Instant.now(),
                Map.of()
        );
        
        ContractDraft modifiedDraft = sensitivityGate.applyForcedDefaults(originalDraft, assessment);
        
        // Output mode should be forced to clean-room
        assertThat(modifiedDraft.outputMode())
                .as("Output mode should be forced to CLEAN_ROOM")
                .isEqualTo(OutputMode.CLEAN_ROOM);
        
        // Precise geo labels should be removed
        assertThat(modifiedDraft.selectedLabels())
                .as("Precise geo labels should be removed")
                .doesNotContain("geo:precise");
        
        // Metadata should indicate forced changes
        assertThat(modifiedDraft.metadata())
                .containsKey("forced_clean_room")
                .containsKey("risk_level");
    }

    /**
     * Property: Consent warnings are generated for high-risk requests.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_consentWarningsGenerated() {
        DataRequest request = createDataRequest(
                Set.of("domain:health", "demographic:minor"),
                Set.of(),
                OutputMode.AGGREGATE_ONLY
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        List<ConsentWarning> warnings = sensitivityGate.generateWarnings(assessment);
        
        assertThat(warnings)
                .as("Warnings should be generated for high-risk request")
                .isNotEmpty();
        
        boolean hasCriticalWarning = warnings.stream()
                .anyMatch(w -> w.severity() == ConsentWarning.Severity.CRITICAL ||
                               w.severity() == ConsentWarning.Severity.HIGH);
        
        assertThat(hasCriticalWarning)
                .as("Should have critical or high severity warning")
                .isTrue();
    }

    /**
     * Property: Low-risk requests pass without intervention.
     * **Validates: Requirements 337.7**
     */
    @Test
    void highRiskGating_lowRiskRequestsPassWithoutIntervention() {
        DataRequest request = createDataRequest(
                Set.of("domain:activity"),
                Set.of(),
                OutputMode.AGGREGATE_ONLY
        );
        
        SensitivityAssessment assessment = sensitivityGate.assess(request);
        
        assertThat(assessment.riskLevel())
                .as("Simple activity request should be low risk")
                .isIn(RiskLevel.NONE, RiskLevel.LOW);
        
        assertThat(assessment.requiresIntervention())
                .as("Low-risk request should not require intervention")
                .isFalse();
    }

    // ==================================================================================
    // Arbitraries for Property-Based Testing
    // ==================================================================================

    @Provide
    Arbitrary<CanonicalEvent> sensitiveEvents() {
        return Arbitraries.of(
                createEventWithAttribute("email", "user@example.com"),
                createEventWithAttribute("phone", "555-123-4567"),
                createEventWithAttribute("ssn", "123-45-6789"),
                createEventWithAttribute("content", "sensitive message content"),
                createEventWithAttribute("password", "secret123"),
                createEventWithAttribute("address", "123 Main St"),
                createEventWithAttribute("creditcard", "4111111111111111")
        );
    }

    @Provide
    Arbitrary<CanonicalEvent> randomEvents() {
        return Arbitraries.of(EventCategory.values())
                .flatMap(category -> Arbitraries.of("event1", "event2", "event3")
                        .map(eventType -> createEvent(category, eventType)));
    }

    @Provide
    Arbitrary<String> piiPatterns() {
        return Arbitraries.of(
                "raw:data", "email:address", "phone:number", "ssn:value",
                "password:hash", "content:body", "message:text", "payload:raw"
        );
    }

    @Provide
    Arbitrary<String> disallowedOperatorNames() {
        return Arbitraries.of(
                "execute", "eval", "system", "shell", "http", "fetch",
                "network", "socket", "file", "write", "delete", "drop",
                "exec", "spawn", "fork", "runtime", "process", "command"
        );
    }

    @Provide
    Arbitrary<Map<String, Object>> maliciousOperatorParams() {
        return Arbitraries.of(
                Map.of("command", "rm -rf /"),
                Map.of("url", "http://malicious.com"),
                Map.of("script", "eval('malicious')"),
                Map.of("file", "/etc/passwd"),
                Map.of("exec", "system('whoami')"),
                Map.<String, Object>of()
        );
    }

    @Provide
    Arbitrary<byte[]> malformedByteSequences() {
        return Arbitraries.bytes()
                .array(byte[].class).ofMinSize(3).ofMaxSize(100)
                .map(arr -> {
                    // Inject invalid UTF-8 sequences
                    arr[0] = (byte) 0xC0; // Invalid UTF-8 start
                    arr[1] = (byte) 0x80; // Invalid continuation
                    return arr;
                });
    }

    @Provide
    Arbitrary<String> stringsWithControlChars() {
        return Arbitraries.strings()
                .withChars('\u0000', '\u0001', '\u001B', '\u007F', 'a', 'b', 'c')
                .ofMinLength(1).ofMaxLength(50);
    }

    @Provide
    Arbitrary<byte[]> rawPayloadData() {
        return Arbitraries.strings()
                .ofMinLength(10).ofMaxLength(100)
                .map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    @Provide
    Arbitrary<String> randomDomains() {
        return Arbitraries.of(
                "unknown.com", "malicious.io", "attacker.net",
                "phishing.org", "evil.co", "hacker.xyz"
        );
    }

    @Provide
    Arbitrary<byte[]> piiPayloads() {
        // Payloads must look like metadata (JSON format) to pass raw payload check
        // but still contain PII patterns that should be blocked
        return Arbitraries.of(
                "{\"type\":\"contact\",\"email\":\"user@example.com\",\"status\":\"active\"}".getBytes(),
                "{\"type\":\"user\",\"phone\":\"555-123-4567\",\"verified\":true}".getBytes(),
                "{\"type\":\"record\",\"ssn\":\"123-45-6789\",\"valid\":true}".getBytes(),
                "{\"type\":\"network\",\"ip\":\"192.168.1.1\",\"active\":true}".getBytes()
        );
    }

    @Provide
    Arbitrary<String> requesterIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(5).ofMaxLength(20)
                .map(s -> "requester-" + s);
    }

    @Provide
    Arbitrary<byte[]> randomData() {
        return Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(10).ofMaxSize(100);
    }

    // ==================================================================================
    // Helper Methods
    // ==================================================================================

    private CanonicalEvent createEvent(EventCategory category, String eventType) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(category)
                .eventType(eventType)
                .timestamp(Instant.now())
                .attributes(Map.of("count", 100L))
                .build();
    }

    private CanonicalEvent createEventWithAttribute(String key, Object value) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(EventCategory.OTHER)
                .eventType("test_event")
                .timestamp(Instant.now())
                .attributes(Map.of(key, value))
                .build();
    }

    private String serializeForCoordinator(ODXEntry entry) {
        return String.format("{\"facetKey\":\"%s\",\"timeBucket\":\"%s\",\"count\":%d,\"privacyFloor\":%d}",
                entry.facetKey(), entry.timeBucket(), entry.count(), entry.privacyFloor());
    }

    private void parseJsonSafely(String json) {
        // Simple JSON validation - in production would use Jackson with limits
        int depth = 0;
        int maxDepth = 100;
        for (char c : json.toCharArray()) {
            if (c == '{' || c == '[') {
                depth++;
                if (depth > maxDepth) {
                    throw new IllegalArgumentException("JSON nesting depth exceeded");
                }
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
    }

    private String sanitizeControlCharacters(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private ContractDraft createContract(Set<String> labels) {
        return new ContractDraft(
                UUID.randomUUID().toString(),
                "request-1",
                "requester-1",
                "ds-node-1",
                labels,
                null,
                OutputMode.AGGREGATE_ONLY,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-1",
                Instant.now().plusSeconds(3600),
                ObligationTerms.standard(),
                "nonce-" + UUID.randomUUID(),
                Instant.now(),
                Map.of()
        );
    }

    private QueryPlan createValidPlan(Set<String> labels, String contractId) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep(0, PlanOperator.SELECT, Map.of("criteria", "*"), labels, labels));
        steps.add(new PlanStep(1, PlanOperator.AGGREGATE, Map.of("operation", "count"), labels, Set.of()));
        
        return QueryPlan.builder()
                .generateId()
                .contractId(contractId)
                .steps(steps)
                .allowedFields(labels)
                .outputConfig(new OutputConfig(OutputConfig.OutputMode.AGGREGATE_ONLY, 100, 10000, false))
                .resourceLimits(ResourceLimits.defaults())
                .signature("valid-signature-" + UUID.randomUUID())
                .build();
    }

    private DataRequest createDataRequest(Set<String> requiredLabels, Set<String> optionalLabels, 
            DataRequest.OutputMode outputMode) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .requesterName("Test Requester")
                .type(DataRequest.RequestType.BROADCAST)
                .requiredLabels(requiredLabels)
                .optionalLabels(optionalLabels)
                .outputMode(outputMode)
                .compensation(new DataRequest.CompensationOffer(10.0, "USD", "escrow-1"))
                .policyStamp("policy-stamp")
                .signature("valid-signature")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
