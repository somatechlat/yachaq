package com.yachaq.api.sdk;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.config.TestcontainersConfiguration;
import com.yachaq.api.dispute.DisputeResolutionService;
import com.yachaq.api.dispute.DisputeResolutionService.*;
import com.yachaq.api.requester.RequesterPortalService;
import com.yachaq.api.requester.RequesterPortalService.*;
import com.yachaq.api.sdk.RequesterSDK.*;
import com.yachaq.api.verification.CapsuleVerificationService;
import com.yachaq.api.verification.CapsuleVerificationService.*;
import com.yachaq.api.vetting.RequesterVettingService;
import com.yachaq.api.vetting.RequesterVettingService.*;
import com.yachaq.core.domain.RequesterTier;
import com.yachaq.core.domain.RequesterTier.Tier;
import com.yachaq.core.domain.RequesterTier.VerificationLevel;
import com.yachaq.core.repository.RequesterTierRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Production-grade integration tests for RequesterSDK.
 * 
 * Tests run against REAL infrastructure using Testcontainers:
 * - PostgreSQL 16 with Flyway migrations
 * - Real JPA repositories and transactions
 * - Real service implementations (no mocks, no test helpers)
 * 
 * Validates: Requirements 352.1, 352.2, 352.3, 352.4, 352.5
 * 
 * Resource constraints: 2GB PostgreSQL (part of 10GB cluster budget)
 */
@SpringBootTest(classes = YachaqApiApplication.class)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class RequesterSDKIntegrationTest {

    @Autowired
    private RequesterSDK sdk;

    @Autowired
    private RequesterVettingService vettingService;

    @Autowired
    private RequesterTierRepository tierRepository;

    @Autowired
    private CapsuleVerificationService verificationService;

    private UUID testRequesterId;

    @BeforeEach
    void setUp() {
        testRequesterId = UUID.randomUUID();
    }

    /**
     * Helper method to assign a tier to a requester in the database.
     * Uses real JPA repository operations.
     */
    private void assignTier(UUID requesterId, Tier tier) {
        TierCapabilities caps = vettingService.getCapabilities(tier);
        RequesterTier requesterTier = RequesterTier.create(
                requesterId,
                tier,
                VerificationLevel.KYC,
                caps.maxBudget(),
                String.join(",", caps.allowedOutputModes()),
                caps.exportAllowed()
        );
        tierRepository.save(requesterTier);
    }

    // ==================== Task 98.4: Policy Enforcement Tests ====================

    @Test
    void createRequest_enforcesTierRestrictions() {
        // Requirement 352.4: Test policy enforcement
        assignTier(testRequesterId, Tier.BASIC);

        // BASIC tier cannot use RAW_ACCESS output mode
        RequestConfig config = new RequestConfig(
                null,
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null,
                BigDecimal.valueOf(100),
                "RAW_ACCESS", // Not allowed for BASIC
                24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, config);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TIER_RESTRICTION");
        assertThat(response.errorMessage()).contains("RAW_ACCESS");
    }

    @Test
    void createRequest_enforcesBudgetLimits() {
        // Requirement 352.4: Test policy enforcement
        assignTier(testRequesterId, Tier.BASIC);

        // BASIC tier has max budget of 1000
        RequestConfig config = new RequestConfig(
                null,
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null,
                BigDecimal.valueOf(5000), // Exceeds BASIC limit
                "AGGREGATE_ONLY",
                24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, config);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TIER_RESTRICTION");
    }

    @Test
    void createRequest_enforcesCategoryRestrictions() {
        // Requirement 352.4: Test policy enforcement
        assignTier(testRequesterId, Tier.BASIC);

        // BASIC tier cannot access health data - check via restrictions API
        RequestTypeCheck check = new RequestTypeCheck(
                "AGGREGATE_ONLY",
                BigDecimal.valueOf(100),
                Set.of("health:steps"), // Not allowed for BASIC
                false
        );

        SDKResponse<RestrictionCheckResult> response = sdk.checkRestrictions(testRequesterId, check);

        assertThat(response.success()).isTrue();
        assertThat(response.data().allowed()).isFalse();
        assertThat(response.data().violations()).anyMatch(v -> v.contains("health"));
    }

    @Test
    void createRequest_allowsValidRequestForTier() {
        // Requirement 352.4: Test policy enforcement allows valid requests
        assignTier(testRequesterId, Tier.STANDARD);

        RequestConfig config = new RequestConfig(
                null,
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null,
                BigDecimal.valueOf(500),
                "AGGREGATE_ONLY",
                24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, config);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
    }

    @Test
    void createRequest_enforcesExactGeoPrecisionBlock() {
        // Requirement 352.4: Test policy enforcement
        assignTier(testRequesterId, Tier.ENTERPRISE);

        RequestConfig config = new RequestConfig(
                null,
                Set.of("location:home"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                new GeoCriteria("EXACT", List.of()), // EXACT precision not allowed
                BigDecimal.valueOf(1000),
                "AGGREGATE_ONLY",
                24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, config);

        assertThat(response.success()).isFalse();
        assertThat(response.validationErrors()).anyMatch(e -> e.contains("Exact location"));
    }

    // ==================== Task 98.4: Error Handling Tests ====================

    @Test
    void createRequest_handlesNullRequesterId() {
        // Requirement 352.5: Test error handling
        RequestConfig config = new RequestConfig(
                null, Set.of("media:music"), Set.of(), null, null,
                BigDecimal.valueOf(100), "AGGREGATE_ONLY", 24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(null, config);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.errorMessage()).contains("null");
    }

    @Test
    void createRequest_handlesNullConfig() {
        // Requirement 352.5: Test error handling
        assignTier(testRequesterId, Tier.STANDARD);

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void createRequest_handlesUnknownRequester() {
        // Requirement 352.5: Test error handling
        UUID unknownRequesterId = UUID.randomUUID();
        // Don't assign tier - requester is unknown in database

        RequestConfig config = new RequestConfig(
                null, Set.of("media:music"), Set.of(), null, null,
                BigDecimal.valueOf(100), "AGGREGATE_ONLY", 24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(unknownRequesterId, config);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("REQUESTER_NOT_FOUND");
    }

    @Test
    void createRequest_handlesInvalidLabels() {
        // Requirement 352.5: Test error handling
        assignTier(testRequesterId, Tier.STANDARD);

        // Use validateCriteria directly to test label validation
        OdxCriteria criteria = new OdxCriteria(
                Set.of("invalid_label_format"), // Invalid format - no colon separator
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        );

        SDKResponse<CriteriaValidationResult> response = sdk.validateCriteria(criteria);

        assertThat(response.success()).isTrue();
        assertThat(response.data().valid()).isFalse();
        assertThat(response.data().errors()).anyMatch(e -> e.contains("Invalid label"));
    }

    @Test
    void createRequest_handlesInvalidTimeWindow() {
        // Requirement 352.5: Test error handling
        assignTier(testRequesterId, Tier.STANDARD);

        RequestConfig config = new RequestConfig(
                null,
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now(), Instant.now().minus(7, ChronoUnit.DAYS)), // End before start
                null,
                BigDecimal.valueOf(100),
                "AGGREGATE_ONLY",
                24
        );

        SDKResponse<RequestCreationResult> response = sdk.createRequest(testRequesterId, config);

        assertThat(response.success()).isFalse();
        assertThat(response.validationErrors()).anyMatch(e -> e.contains("Time window"));
    }

    // ==================== Verification API Tests ====================

    @Test
    void verifySignature_handlesNullCapsule() {
        // Requirement 352.5: Test error handling
        SDKResponse<SignatureVerificationResult> response = sdk.verifySignature(null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("VERIFICATION_ERROR");
    }

    @Test
    void verifySignature_returnsMissingSignatureError() {
        // Requirement 352.2: Verification functions
        CapsuleData capsule = new CapsuleData(
                "capsule-1", "contract-1", "hash", null, null,
                null, null, Instant.now(), "v1.0", "AGGREGATE", 100, Map.of()
        );

        SDKResponse<SignatureVerificationResult> response = sdk.verifySignature(capsule);

        assertThat(response.success()).isTrue();
        assertThat(response.data().valid()).isFalse();
        assertThat(response.data().errors()).anyMatch(e -> e.contains("Missing"));
    }

    @Test
    void validateSchema_handlesNullInputs() {
        // Requirement 352.5: Test error handling
        CapsuleData capsule = createValidCapsule();
        CapsuleSchema schema = createValidSchema();

        SDKResponse<SchemaValidationResult> response1 = sdk.validateSchema(null, schema);
        assertThat(response1.success()).isFalse();

        SDKResponse<SchemaValidationResult> response2 = sdk.validateSchema(capsule, null);
        assertThat(response2.success()).isFalse();
    }

    @Test
    void validateSchema_detectsVersionMismatch() {
        // Requirement 352.2: Verification functions
        CapsuleData capsule = new CapsuleData(
                "capsule-1", "contract-1", "hash", "sig", "key",
                null, null, Instant.now(), "v2.0", "AGGREGATE", 100, Map.of("data", "test")
        );
        CapsuleSchema schema = new CapsuleSchema(
                "v1.0", "AGGREGATE",
                List.of(new SchemaField("data", "string", "Data")),
                List.of(), 10000
        );

        SDKResponse<SchemaValidationResult> response = sdk.validateSchema(capsule, schema);

        assertThat(response.success()).isTrue();
        assertThat(response.data().valid()).isFalse();
        assertThat(response.data().errors()).anyMatch(e -> e.contains("version mismatch"));
    }

    @Test
    void verifyHashReceipt_handlesNullInputs() {
        // Requirement 352.5: Test error handling
        CapsuleData capsule = createValidCapsule();
        HashReceipt receipt = new HashReceipt("hash", null, List.of(), null, null, Instant.now());

        SDKResponse<HashReceiptVerificationResult> response1 = sdk.verifyHashReceipt(null, receipt);
        assertThat(response1.success()).isFalse();

        SDKResponse<HashReceiptVerificationResult> response2 = sdk.verifyHashReceipt(capsule, null);
        assertThat(response2.success()).isFalse();
    }

    // ==================== Dispute API Tests ====================

    @Test
    void fileDispute_handlesNullRequest() {
        // Requirement 352.5: Test error handling
        SDKResponse<DisputeFilingResult> response = sdk.fileDispute(null);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("DISPUTE_ERROR");
    }

    @Test
    void fileDispute_createsDisputeSuccessfully() {
        // Requirement 352.4: Policy enforcement
        DisputeRequest request = new DisputeRequest(
                "contract-1", "capsule-1",
                UUID.randomUUID(), UUID.randomUUID(),
                DisputeType.DATA_QUALITY, "Quality issue", null
        );

        SDKResponse<DisputeFilingResult> response = sdk.fileDispute(request);

        assertThat(response.success()).isTrue();
        assertThat(response.data().disputeId()).isNotNull();
    }

    @Test
    void getDispute_handlesNonexistentDispute() {
        // Requirement 352.5: Test error handling
        SDKResponse<Dispute> response = sdk.getDispute("nonexistent");

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void addEvidence_handlesNonexistentDispute() {
        // Requirement 352.5: Test error handling
        EvidenceSubmission evidence = new EvidenceSubmission(
                UUID.randomUUID(), EvidenceType.AUDIT_LOG, "Evidence", "hash", null
        );

        SDKResponse<EvidenceAddResult> response = sdk.addEvidence("nonexistent", evidence);

        assertThat(response.success()).isTrue();
        assertThat(response.data().success()).isFalse();
    }

    // ==================== Tier & Status API Tests ====================

    @Test
    void getTierCapabilities_handlesUnknownRequester() {
        // Requirement 352.5: Test error handling
        UUID unknownId = UUID.randomUUID();

        SDKResponse<TierCapabilities> response = sdk.getTierCapabilities(unknownId);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void getTierCapabilities_returnsCorrectCapabilities() {
        // Requirement 352.4: Policy enforcement
        assignTier(testRequesterId, Tier.PREMIUM);

        SDKResponse<TierCapabilities> response = sdk.getTierCapabilities(testRequesterId);

        assertThat(response.success()).isTrue();
        assertThat(response.data().tier()).isEqualTo(Tier.PREMIUM);
        assertThat(response.data().exportAllowed()).isTrue();
    }

    @Test
    void checkRestrictions_enforcesPolicy() {
        // Requirement 352.4: Policy enforcement
        assignTier(testRequesterId, Tier.BASIC);

        RequestTypeCheck check = new RequestTypeCheck(
                "RAW_ACCESS",
                BigDecimal.valueOf(100),
                Set.of("health:steps"),
                false
        );

        SDKResponse<RestrictionCheckResult> response = sdk.checkRestrictions(testRequesterId, check);

        assertThat(response.success()).isTrue();
        assertThat(response.data().allowed()).isFalse();
        assertThat(response.data().violations()).isNotEmpty();
    }

    @Test
    void checkBondRequirement_calculatesCorrectly() {
        // Requirement 352.4: Policy enforcement
        assignTier(testRequesterId, Tier.BASIC);

        RequestRiskAssessment assessment = new RequestRiskAssessment(
                BigDecimal.valueOf(100000), // High budget
                true, // Sensitive data
                true, // Export
                true  // First request
        );

        SDKResponse<BondRequirement> response = sdk.checkBondRequirement(testRequesterId, assessment);

        assertThat(response.success()).isTrue();
        assertThat(response.data().required()).isTrue();
        assertThat(response.data().amount()).isGreaterThan(BigDecimal.ZERO);
    }

    // ==================== Batch Operations Tests ====================

    @Test
    void createRequestsBatch_handlesPartialFailure() {
        // Requirement 352.5: Test error handling
        assignTier(testRequesterId, Tier.BASIC);

        List<RequestConfig> configs = List.of(
                // Valid request
                new RequestConfig(null, Set.of("media:music"), Set.of(),
                        new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                        null, BigDecimal.valueOf(100), "AGGREGATE_ONLY", 24),
                // Invalid request (health not allowed for BASIC)
                new RequestConfig(null, Set.of("health:steps"), Set.of(),
                        new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                        null, BigDecimal.valueOf(100), "AGGREGATE_ONLY", 24)
        );

        SDKResponse<List<RequestCreationResult>> response = sdk.createRequestsBatch(testRequesterId, configs);

        assertThat(response.success()).isTrue();
        assertThat(response.errorCode()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(response.data()).hasSize(1); // Only valid request succeeded
        assertThat(response.validationErrors()).hasSize(1); // One failure
    }

    @Test
    void createRequestsBatch_handlesAllSuccess() {
        // Requirement 352.1: Batch request creation
        assignTier(testRequesterId, Tier.STANDARD);

        List<RequestConfig> configs = List.of(
                new RequestConfig(null, Set.of("media:music"), Set.of(),
                        new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                        null, BigDecimal.valueOf(100), "AGGREGATE_ONLY", 24),
                new RequestConfig(null, Set.of("social:interactions"), Set.of(),
                        new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                        null, BigDecimal.valueOf(200), "AGGREGATE_ONLY", 48)
        );

        SDKResponse<List<RequestCreationResult>> response = sdk.createRequestsBatch(testRequesterId, configs);

        assertThat(response.success()).isTrue();
        assertThat(response.errorCode()).isNull();
        assertThat(response.data()).hasSize(2);
    }

    // ==================== Template Tests ====================

    @Test
    void getTemplates_returnsAvailableTemplates() {
        // Requirement 352.1: Request creation API
        SDKResponse<List<RequestTemplate>> response = sdk.getTemplates(null);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotEmpty();
    }

    @Test
    void getTemplates_filtersByCategory() {
        // Requirement 352.1: Request creation API
        SDKResponse<List<RequestTemplate>> response = sdk.getTemplates("research");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).allMatch(t -> t.category().equals("research"));
    }

    // ==================== Helper Methods ====================

    private CapsuleData createValidCapsule() {
        return new CapsuleData(
                "capsule-1", "contract-1", "hash", "sig", "key",
                null, null, Instant.now(), "v1.0", "AGGREGATE", 100, Map.of("data", "test")
        );
    }

    private CapsuleSchema createValidSchema() {
        return new CapsuleSchema(
                "v1.0", "AGGREGATE",
                List.of(new SchemaField("data", "string", "Data")),
                List.of(), 10000
        );
    }
}
