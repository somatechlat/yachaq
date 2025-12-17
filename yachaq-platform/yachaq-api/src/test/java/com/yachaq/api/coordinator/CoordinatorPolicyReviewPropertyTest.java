package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.coordinator.CoordinatorPolicyReviewService.*;
import com.yachaq.api.coordinator.CoordinatorRequestService.PolicyDecision;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.RequestRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Coordinator Policy Review and Moderation.
 * Requirement 322: Coordinator Policy Review and Moderation.
 * 
 * Tests ODX-terms-only criteria enforcement, high-risk request blocking/downscoping,
 * policy stamp signing, and reason codes/remediation hints.
 */
class CoordinatorPolicyReviewPropertyTest {

    private TestRequestRepository requestRepository;
    private TestAuditService auditService;
    private CoordinatorPolicyReviewService service;

    @BeforeEach
    void setUp() {
        initializeService();
    }

    private void initializeService() {
        if (service == null) {
            requestRepository = new TestRequestRepository();
            auditService = new TestAuditService();
            service = new CoordinatorPolicyReviewService(requestRepository, auditService, "");
        }
    }

    private CoordinatorPolicyReviewService getService() {
        if (service == null) {
            initializeService();
        }
        return service;
    }

    private TestRequestRepository getRequestRepository() {
        if (requestRepository == null) {
            initializeService();
        }
        return requestRepository;
    }

    // ==================== ODX-Terms-Only Criteria Tests (Requirement 322.1) ====================

    @Property(tries = 100)
    @Label("ODX Validation: Valid ODX criteria pass validation")
    void validODXCriteriaPassValidation(@ForAll("validODXCriteria") Map<String, Object> criteria) {
        ODXValidationResult result = getService().validateODXCriteria(criteria);
        
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
        
        ODXValidationResult result = getService().validateODXCriteria(criteria);
        
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
        ODXValidationResult result = getService().validateODXCriteria(Map.of());
        assertThat(result.valid()).isTrue();
        
        ODXValidationResult nullResult = getService().validateODXCriteria(null);
        assertThat(nullResult.valid()).isTrue();
    }

    // ==================== High-Risk Request Tests (Requirement 322.2) ====================

    @Test
    @Label("High-Risk: Health + Location combination triggers downscope")
    void healthLocationCombinationTriggersDownscope() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> scope = Map.of(
                "domain.health", "fitness_data",
                "domain.location", "movement_patterns"
        );
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, scope, Map.of());
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
        assertThat(result.requiredSafeguards())
                .as("Health + Location should require CLEAN_ROOM_ONLY safeguard")
                .contains("CLEAN_ROOM_ONLY");
        assertThat(result.reasonCodes())
                .anyMatch(c -> c.contains("HEALTH") || c.contains("LOCATION"));
    }

    @Test
    @Label("High-Risk: Finance + Location combination triggers downscope")
    void financeLocationCombinationTriggersDownscope() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> scope = Map.of(
                "domain.finance", "spending_patterns",
                "domain.location", "store_visits"
        );
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, scope, Map.of());
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
        assertThat(result.requiredSafeguards())
                .as("Finance + Location should require safeguards")
                .containsAnyOf("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY");
    }

    @Test
    @Label("High-Risk: Minors involvement triggers manual review")
    void minorsInvolvementTriggersManualReview() {
        UUID requestId = UUID.randomUUID();
        Request request = createMockRequestWithPurpose(
                requestId, 
                Request.RequestStatus.SCREENING,
                "Research on children's fitness habits",
                Map.of("domain.fitness", "activity_data"),
                Map.of()
        );
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
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
        
        SignedPolicyStamp stamp = getService().signPolicyStamp(
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
        boolean verified = getService().verifyPolicyStamp(stamp);
        assertThat(verified)
                .as("Signed stamp should be verifiable")
                .isTrue();
    }

    @Test
    @Label("Policy Stamp: Tampered stamps fail verification")
    void tamperedStampsFailVerification() {
        UUID requestId = UUID.randomUUID();
        
        SignedPolicyStamp originalStamp = getService().signPolicyStamp(
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
        
        boolean verified = getService().verifyPolicyStamp(tamperedStamp);
        assertThat(verified)
                .as("Tampered stamp should fail verification")
                .isFalse();
    }

    @Test
    @Label("Policy Stamp: Null stamp returns false")
    void nullStampReturnsFalse() {
        boolean verified = getService().verifyPolicyStamp(null);
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
        
        ODXValidationResult result = getService().validateODXCriteria(criteria);
        
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
        UUID requestId = UUID.randomUUID();
        Map<String, Object> criteria = new HashMap<>();
        // Add more than MAX_CRITERIA_SPECIFICITY fields
        for (int i = 0; i < 10; i++) {
            criteria.put("domain.field" + i, "value" + i);
        }
        
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, Map.of(), criteria);
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
        assertThat(result.reasonCodes())
                .contains("CRITERIA_TOO_SPECIFIC");
        assertThat(result.remediationHints())
                .anyMatch(h -> h.contains("maximum"));
    }

    // ==================== Default Safeguards Tests (Requirement 322.3) ====================

    @Test
    @Label("Safeguards: Health data always requires clean room")
    void healthDataRequiresCleanRoom() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.health", "medical_data");
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, scope, Map.of());
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
        assertThat(result.requiredSafeguards())
                .contains("CLEAN_ROOM_ONLY")
                .contains("PRIVACY_FLOOR_HIGH");
    }

    @Test
    @Label("Safeguards: Location data requires coarse geo")
    void locationDataRequiresCoarseGeo() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.location", "movement_data");
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, scope, Map.of());
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
        assertThat(result.requiredSafeguards())
                .contains("COARSE_GEO");
    }

    @Test
    @Label("Safeguards: All requests get minimum safeguards")
    void allRequestsGetMinimumSafeguards() {
        UUID requestId = UUID.randomUUID();
        Map<String, Object> scope = Map.of("domain.entertainment", "viewing_habits");
        Request request = createMockRequest(requestId, Request.RequestStatus.SCREENING, scope, Map.of());
        getRequestRepository().addRequest(request);
        
        PolicyReviewResult result = getService().reviewRequest(requestId);
        
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

    private Request createMockRequest(UUID id, Request.RequestStatus status,
                                       Map<String, Object> scope, Map<String, Object> criteria) {
        return new TestRequest(id, status, "Test purpose", scope, criteria);
    }

    private Request createMockRequestWithPurpose(UUID id, Request.RequestStatus status,
                                                  String purpose, Map<String, Object> scope,
                                                  Map<String, Object> criteria) {
        return new TestRequest(id, status, purpose, scope, criteria);
    }

    // ==================== Test Doubles ====================

    static class TestRequestRepository implements RequestRepository {
        private final Map<UUID, Request> requests = new ConcurrentHashMap<>();

        @Override
        public <S extends Request> S save(S entity) {
            requests.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public Optional<Request> findById(UUID id) {
            return Optional.ofNullable(requests.get(id));
        }

        void addRequest(Request request) {
            requests.put(request.getId(), request);
        }

        // Minimal implementation for unused methods
        @Override public <S extends Request> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override public boolean existsById(UUID id) { return requests.containsKey(id); }
        @Override public List<Request> findAll() { return new ArrayList<>(requests.values()); }
        @Override public List<Request> findAllById(Iterable<UUID> ids) { return List.of(); }
        @Override public long count() { return requests.size(); }
        @Override public void deleteById(UUID id) { requests.remove(id); }
        @Override public void delete(Request entity) { requests.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) {}
        @Override public void deleteAll(Iterable<? extends Request> entities) {}
        @Override public void deleteAll() { requests.clear(); }
        @Override public void flush() {}
        @Override public <S extends Request> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends Request> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override public void deleteAllInBatch(Iterable<Request> entities) {}
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) {}
        @Override public void deleteAllInBatch() {}
        @Override public Request getOne(UUID id) { return requests.get(id); }
        @Override public Request getById(UUID id) { return requests.get(id); }
        @Override public Request getReferenceById(UUID id) { return requests.get(id); }
        @Override public <S extends Request> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return Optional.empty(); }
        @Override public <S extends Request> List<S> findAll(org.springframework.data.domain.Example<S> example) { return List.of(); }
        @Override public <S extends Request> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public <S extends Request> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public <S extends Request> long count(org.springframework.data.domain.Example<S> example) { return 0; }
        @Override public <S extends Request> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
        @Override public <S extends Request, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override public List<Request> findAll(org.springframework.data.domain.Sort sort) { return List.of(); }
        @Override public org.springframework.data.domain.Page<Request> findAll(org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public org.springframework.data.domain.Page<Request> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public org.springframework.data.domain.Page<Request> findByStatusOrderByCreatedAtDesc(Request.RequestStatus status, org.springframework.data.domain.Pageable pageable) { return org.springframework.data.domain.Page.empty(); }
        @Override public List<Request> findActiveRequestsInRange(Instant now) { return List.of(); }
    }

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
    }

    static class TestRequest extends Request {
        private final UUID id;
        private Request.RequestStatus status;
        private final String purpose;
        private final Map<String, Object> scope;
        private final Map<String, Object> criteria;

        TestRequest(UUID id, Request.RequestStatus status, String purpose,
                    Map<String, Object> scope, Map<String, Object> criteria) {
            this.id = id;
            this.status = status;
            this.purpose = purpose;
            this.scope = scope;
            this.criteria = criteria;
        }

        @Override public UUID getId() { return id; }
        @Override public Request.RequestStatus getStatus() { return status; }
        @Override public void activate() { this.status = Request.RequestStatus.ACTIVE; }
        @Override public void reject() { this.status = Request.RequestStatus.REJECTED; }
        @Override public UUID getRequesterId() { return UUID.randomUUID(); }
        @Override public String getPurpose() { return purpose; }
        @Override public Map<String, Object> getScope() { return scope; }
        @Override public Map<String, Object> getEligibilityCriteria() { return criteria; }
        @Override public BigDecimal getUnitPrice() { return BigDecimal.TEN; }
        @Override public Integer getMaxParticipants() { return 100; }
        @Override public Instant getDurationStart() { return Instant.now(); }
        @Override public Instant getDurationEnd() { return Instant.now().plus(30, ChronoUnit.DAYS); }
    }
}
