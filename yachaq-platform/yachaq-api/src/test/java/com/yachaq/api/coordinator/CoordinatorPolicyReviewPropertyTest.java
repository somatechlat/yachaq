package com.yachaq.api.coordinator;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.audit.AuditService;
import com.yachaq.api.config.TestcontainersConfiguration;
import com.yachaq.api.coordinator.CoordinatorPolicyReviewService.*;
import com.yachaq.api.coordinator.CoordinatorRequestService.PolicyDecision;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.RequestRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Coordinator Policy Review and Moderation.
 * Requirement 322: Coordinator Policy Review and Moderation.
 * 
 * Tests ODX-terms-only criteria enforcement, high-risk request blocking/downscoping,
 * policy stamp signing, and reason codes/remediation hints.
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
class CoordinatorPolicyReviewPropertyTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditReceiptRepository auditReceiptRepository;

    @Autowired
    private CoordinatorPolicyReviewService service;

    @BeforeEach
    void setUp() {
        // Clean up for each test
        auditReceiptRepository.deleteAll();
        requestRepository.deleteAll();
    }

    // ==================== ODX-Terms-Only Criteria Tests (Requirement 322.1) ====================

    @Property(tries = 100)
    @Label("ODX Validation: Valid ODX criteria pass validation")
    void validODXCriteriaPassValidation(@ForAll("validODXCriteria") Map<String, Object> criteria) {
        ODXValidationResult result = service.validateODXCriteria(criteria);
        
        assertThat(result.valid())
                .as("Valid ODX criteria should pass validation")
                .isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Property(tries = 100)
    @Label("ODX Validation: Non-ODX criteria are rejected")
    void nonODXCriteriaAreRejected(@ForAll("invalidODXCriteria") String invalidKey) {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put(invalidKey, "some_value");
        
        ODXValidationResult result = service.validateODXCriteria(criteria);
        
        assertThat(result.valid())
                .as("Non-ODX criteria '%s' should be rejected", invalidKey)
                .isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("NON_ODX_CRITERIA"));
        assertThat(result.hints())
                .anyMatch(h -> h.contains("ODX-allowed"));
    }

    @Test
    @Label("ODX Validation: Empty criteria pass validation")
    void emptyCriteriaPassValidation() {
        ODXValidationResult result = service.validateODXCriteria(Map.of());
        assertThat(result.valid()).isTrue();
        
        ODXValidationResult nullResult = service.validateODXCriteria(null);
        assertThat(nullResult.valid()).isTrue();
    }

    // ==================== High-Risk Request Tests (Requirement 322.2) ====================

    @Test
    @Label("High-Risk: Health + Location combination triggers downscope")
    void healthLocationCombinationTriggersDownscope() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> scope = Map.of(
                "domain.health", "fitness_data",
                "domain.location", "movement_patterns"
        );
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING, 
                "Test purpose", scope, Map.of());
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.requiredSafeguards())
                .as("Health + Location should require CLEAN_ROOM_ONLY safeguard")
                .contains("CLEAN_ROOM_ONLY");
        assertThat(result.reasonCodes())
                .anyMatch(c -> c.contains("HEALTH") || c.contains("LOCATION"));
    }

    @Test
    @Label("High-Risk: Finance + Location combination triggers downscope")
    void financeLocationCombinationTriggersDownscope() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> scope = Map.of(
                "domain.finance", "spending_patterns",
                "domain.location", "store_visits"
        );
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING,
                "Test purpose", scope, Map.of());
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.requiredSafeguards())
                .as("Finance + Location should require safeguards")
                .containsAnyOf("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY");
    }

    @Test
    @Label("High-Risk: Minors involvement triggers manual review")
    void minorsInvolvementTriggersManualReview() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(
                requesterId, 
                Request.RequestStatus.SCREENING,
                "Research on children's fitness habits",
                Map.of("domain.fitness", "activity_data"),
                Map.of()
        );
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.decision())
                .as("Minors involvement should trigger MANUAL_REVIEW")
                .isEqualTo(PolicyDecision.MANUAL_REVIEW);
        assertThat(result.reasonCodes())
                .contains("MINORS_INVOLVEMENT_DETECTED");
    }

    // ==================== Policy Stamp Signing Tests (Requirement 322.4) ====================

    @Property(tries = 50)
    @Label("Policy Stamp: Signed stamps can be verified")
    void signedStampsCanBeVerified(
            @ForAll @StringLength(min = 1, max = 50) String safeguard) {
        
        UUID requestId = UUID.randomUUID();
        Set<String> safeguards = Set.of(safeguard, "DEFAULT_SAFEGUARD");
        List<String> reasonCodes = List.of("APPROVED");
        
        SignedPolicyStamp stamp = service.signPolicyStamp(
                requestId, 
                PolicyDecision.APPROVED, 
                safeguards, 
                reasonCodes
        );
        
        assertThat(stamp).isNotNull();
        assertThat(stamp.requestId()).isEqualTo(requestId);
        assertThat(stamp.decision()).isEqualTo(PolicyDecision.APPROVED);
        assertThat(stamp.signature()).isNotBlank();
        assertThat(stamp.stampHash()).isNotBlank();
        
        // Verify the stamp
        boolean verified = service.verifyPolicyStamp(stamp);
        assertThat(verified)
                .as("Signed stamp should be verifiable")
                .isTrue();
    }

    @Test
    @Label("Policy Stamp: Tampered stamps fail verification")
    void tamperedStampsFailVerification() {
        UUID requestId = UUID.randomUUID();
        
        SignedPolicyStamp originalStamp = service.signPolicyStamp(
                requestId,
                PolicyDecision.APPROVED,
                Set.of("CLEAN_ROOM_ONLY"),
                List.of()
        );
        
        // Create tampered stamp with different decision
        SignedPolicyStamp tamperedStamp = new SignedPolicyStamp(
                requestId,
                PolicyDecision.REJECTED, // Changed!
                originalStamp.safeguards(),
                originalStamp.reasonCodes(),
                originalStamp.policyVersion(),
                originalStamp.timestamp(),
                originalStamp.signature(), // Same signature
                originalStamp.stampHash()
        );
        
        boolean verified = service.verifyPolicyStamp(tamperedStamp);
        assertThat(verified)
                .as("Tampered stamp should fail verification")
                .isFalse();
    }

    @Test
    @Label("Policy Stamp: Null stamp returns false")
    void nullStampReturnsFalse() {
        boolean verified = service.verifyPolicyStamp(null);
        assertThat(verified).isFalse();
    }

    // ==================== Reason Codes and Remediation Tests (Requirement 322.5) ====================

    @Test
    @Label("Remediation: Non-ODX criteria provides helpful hints")
    void nonODXCriteriaProvidesHelpfulHints() {
        Map<String, Object> criteria = Map.of(
                "raw_user_data", "sensitive_info",
                "private_field", "secret"
        );
        
        ODXValidationResult result = service.validateODXCriteria(criteria);
        
        assertThat(result.hints())
                .as("Should provide remediation hints")
                .isNotEmpty();
        assertThat(result.hints().get(0))
                .contains("ODX-allowed")
                .contains("domain.*");
    }

    @Test
    @Label("Remediation: Too specific criteria provides hint")
    void tooSpecificCriteriaProvidesHint() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> criteria = new HashMap<>();
        // Add more than MAX_CRITERIA_SPECIFICITY fields
        for (int i = 0; i < 10; i++) {
            criteria.put("domain.field" + i, "value" + i);
        }
        
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING,
                "Test purpose", Map.of(), criteria);
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.reasonCodes())
                .contains("CRITERIA_TOO_SPECIFIC");
        assertThat(result.remediationHints())
                .anyMatch(h -> h.contains("maximum"));
    }

    // ==================== Default Safeguards Tests (Requirement 322.3) ====================

    @Test
    @Label("Safeguards: Health data always requires clean room")
    void healthDataRequiresCleanRoom() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.health", "medical_data");
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING,
                "Test purpose", scope, Map.of());
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.requiredSafeguards())
                .contains("CLEAN_ROOM_ONLY")
                .contains("PRIVACY_FLOOR_HIGH");
    }

    @Test
    @Label("Safeguards: Location data requires coarse geo")
    void locationDataRequiresCoarseGeo() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.location", "movement_data");
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING,
                "Test purpose", scope, Map.of());
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.requiredSafeguards())
                .contains("COARSE_GEO");
    }

    @Test
    @Label("Safeguards: All requests get minimum safeguards")
    void allRequestsGetMinimumSafeguards() {
        UUID requesterId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.entertainment", "viewing_habits");
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING,
                "Test purpose", scope, Map.of());
        
        PolicyReviewResult result = service.reviewRequest(request.getId());
        
        assertThat(result.requiredSafeguards())
                .contains("K_ANONYMITY_50")
                .contains("TTL_72H");
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<Map<String, Object>> validODXCriteria() {
        return Arbitraries.of(
                "domain.fitness", "domain.health", "domain.location",
                "time.hour_of_day", "time.day_of_week",
                "geo.country", "geo.region",
                "quality.tier", "privacy.floor",
                "account.type", "account.tier"
        ).list().ofMinSize(1).ofMaxSize(3)
                .map(keys -> {
                    Map<String, Object> map = new HashMap<>();
                    for (String key : keys) {
                        map.put(key, "test_value");
                    }
                    return map;
                });
    }

    @Provide
    Arbitrary<String> invalidODXCriteria() {
        return Arbitraries.of(
                "raw_data", "user_identity", "device_fingerprint",
                "ip_address", "mac_address", "ssn", "credit_card",
                "password", "secret_key", "private_key",
                "node_location", "health_flags", "precise_location"
        );
    }

    // ==================== Helper Methods ====================

    /**
     * Creates and saves a real Request entity to the database.
     * Uses real JPA repository operations.
     * 
     * Request.create() signature:
     * (requesterId, purpose, scope, eligibilityCriteria, durationStart, durationEnd,
     *  unitType, unitPrice, maxParticipants, budget)
     */
    private Request createAndSaveRequest(UUID requesterId, Request.RequestStatus targetStatus,
                                          String purpose, Map<String, Object> scope,
                                          Map<String, Object> criteria) {
        Request request = Request.create(
                requesterId,
                purpose,
                scope.isEmpty() ? Map.of("category", "fitness") : scope,
                criteria,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                Request.UnitType.DATA_ACCESS,
                BigDecimal.TEN,
                100,
                BigDecimal.valueOf(1000)
        );
        // Save first to get ID, then transition to target status
        Request saved = requestRepository.save(request);
        
        // Transition through states to reach target status
        if (targetStatus == Request.RequestStatus.SCREENING || 
            targetStatus == Request.RequestStatus.ACTIVE ||
            targetStatus == Request.RequestStatus.REJECTED) {
            saved.submitForScreening();
        }
        if (targetStatus == Request.RequestStatus.ACTIVE) {
            saved.activate();
        }
        if (targetStatus == Request.RequestStatus.REJECTED) {
            saved.reject();
        }
        
        return requestRepository.save(saved);
    }
}
