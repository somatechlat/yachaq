package com.yachaq.api.coordinator;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.audit.AuditService;
import com.yachaq.api.config.TestcontainersConfiguration;
import com.yachaq.api.coordinator.CoordinatorRequestService.*;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.RequestRepository;
import net.jqwik.api.*;
import net.jqwik.api.Assume;
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
 * Property-based tests for Coordinator Request Management.
 * Requirement 321: Coordinator Request Management.
 * Property 62: Coordinator No Raw Ingestion.
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
class CoordinatorRequestPropertyTest {

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditReceiptRepository auditReceiptRepository;

    @Autowired
    private CoordinatorRequestService service;

    @BeforeEach
    void setUp() {
        // Clean up audit receipts for each test
        auditReceiptRepository.deleteAll();
    }

    // ==================== Property 62: Coordinator No Raw Ingestion ====================

    /**
     * Property 62: Coordinator No Raw Ingestion
     * For any coordinator endpoint, the endpoint must not accept raw user data,
     * and any attempt to submit raw data must be rejected and logged.
     * Validates: Requirements 321.5, 321.6
     */
    @Property(tries = 100)
    @Label("Property 62: Raw data in scope field is rejected")
    void rawDataInScopeIsRejected(
            @ForAll("forbiddenFieldNames") String forbiddenField,
            @ForAll @StringLength(min = 1, max = 100) String value) {

        Map<String, Object> scope = new HashMap<>();
        scope.put(forbiddenField, value);
        scope.put("valid_field", "valid_value");

        CoordinatorRequest request = createValidRequest(scope, Map.of(), Map.of());

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData())
                .as("Request with forbidden field '%s' should be detected as containing raw data", forbiddenField)
                .isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains(forbiddenField));
    }

    @Property(tries = 100)
    @Label("Property 62: GPS coordinates in values are rejected")
    void gpsCoordinatesInValuesAreRejected(
            @ForAll @DoubleRange(min = -90, max = 90) double lat,
            @ForAll @DoubleRange(min = -180, max = 180) double lon) {

        // Format as precise GPS (5+ decimal places)
        String gpsValue = String.format("%.6f, %.6f", lat, lon);

        Map<String, Object> scope = new HashMap<>();
        scope.put("location", gpsValue);

        CoordinatorRequest request = createValidRequest(scope, Map.of(), Map.of());

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData())
                .as("Request with GPS coordinates should be detected as containing raw data")
                .isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("RAW_GPS_DATA"));
    }

    @Property(tries = 50)
    @Label("Property 62: Large base64 payloads are rejected")
    void largeBase64PayloadsAreRejected(@ForAll("largeBase64") String base64Payload) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("payload", base64Payload);

        CoordinatorRequest request = createValidRequest(Map.of(), Map.of(), metadata);

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData())
                .as("Request with large base64 payload should be detected as containing raw data")
                .isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("RAW_PAYLOAD_DATA"));
    }

    @Property(tries = 100)
    @Label("Property 62: SSN patterns are rejected")
    void ssnPatternsAreRejected(
            @ForAll @IntRange(min = 100, max = 999) int area,
            @ForAll @IntRange(min = 10, max = 99) int group,
            @ForAll @IntRange(min = 1000, max = 9999) int serial) {

        String ssn = String.format("%03d-%02d-%04d", area, group, serial);

        Map<String, Object> scope = new HashMap<>();
        scope.put("identifier", ssn);

        CoordinatorRequest request = createValidRequest(scope, Map.of(), Map.of());

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData())
                .as("Request with SSN pattern should be detected as containing raw data")
                .isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("RAW_PII_DATA"));
    }

    @Property(tries = 100)
    @Label("Property 62: Raw data attempts are logged")
    void rawDataAttemptsAreLogged(@ForAll("forbiddenFieldNames") String forbiddenField) {

        Map<String, Object> scope = new HashMap<>();
        scope.put(forbiddenField, "some_value");

        CoordinatorRequest request = createValidRequest(scope, Map.of(), Map.of());

        StorageResult result = service.storeRequest(request);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(StorageStatus.RAW_DATA_REJECTED);

        // Verify audit log was created in real database
        List<AuditReceipt> receipts = auditReceiptRepository.findAll();
        assertThat(receipts)
                .as("Raw data attempt should be logged as UNAUTHORIZED_FIELD_ACCESS_ATTEMPT")
                .anyMatch(r -> r.getEventType() == AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT);
    }

    // ==================== Schema Validation Tests ====================

    @Property(tries = 100)
    @Label("Schema validation: Valid requests pass validation")
    void validRequestsPassSchemaValidation(
            @ForAll @StringLength(min = 1, max = 200) String purpose,
            @ForAll @BigRange(min = "0.01", max = "10000") BigDecimal unitPrice,
            @ForAll @IntRange(min = 1, max = 10000) int maxParticipants) {

        // Skip blank purposes (whitespace only)
        Assume.that(!purpose.isBlank());

        Map<String, Object> scope = Map.of("data_category", "fitness");
        Map<String, Object> criteria = Map.of("account_type", "DS-IND");

        CoordinatorRequest request = new CoordinatorRequest(
                UUID.randomUUID(),
                purpose,
                scope,
                criteria,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS,
                unitPrice,
                maxParticipants,
                Map.of()
        );

        SchemaValidationResult result = service.validateSchema(request);

        assertThat(result.valid())
                .as("Valid request should pass schema validation, violations: %s", result.violations())
                .isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Property(tries = 50)
    @Label("Schema validation: Invalid criteria fields are rejected")
    void invalidCriteriaFieldsAreRejected(@ForAll("invalidCriteriaFields") String invalidField) {

        Map<String, Object> criteria = new HashMap<>();
        criteria.put(invalidField, "some_value");
        criteria.put("account_type", "DS-IND"); // Valid field

        CoordinatorRequest request = createValidRequest(Map.of("data_category", "fitness"), criteria, Map.of());

        SchemaValidationResult result = service.validateSchema(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("INVALID_CRITERIA_FIELD:" + invalidField));
    }

    @Test
    @Label("Schema validation: Missing required fields are detected")
    void missingRequiredFieldsAreDetected() {
        // Missing requester ID
        CoordinatorRequest request1 = new CoordinatorRequest(
                null, "purpose", Map.of("field", "value"), Map.of(),
                Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS, BigDecimal.ONE, 10, Map.of()
        );
        assertThat(service.validateSchema(request1).violations()).contains("MISSING_REQUESTER_ID");

        // Missing purpose
        CoordinatorRequest request2 = new CoordinatorRequest(
                UUID.randomUUID(), "", Map.of("field", "value"), Map.of(),
                Instant.now(), Instant.now().plus(1, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS, BigDecimal.ONE, 10, Map.of()
        );
        assertThat(service.validateSchema(request2).violations()).contains("MISSING_PURPOSE");

        // Invalid duration (start after end)
        CoordinatorRequest request3 = new CoordinatorRequest(
                UUID.randomUUID(), "purpose", Map.of("field", "value"), Map.of(),
                Instant.now().plus(10, ChronoUnit.DAYS), Instant.now(),
                CoordinatorRequest.UnitType.DATA_ACCESS, BigDecimal.ONE, 10, Map.of()
        );
        assertThat(service.validateSchema(request3).violations()).contains("INVALID_DURATION_RANGE");
    }

    // ==================== Storage Tests ====================

    @Property(tries = 50)
    @Label("Storage: Valid requests are stored successfully")
    void validRequestsAreStoredSuccessfully(
            @ForAll @StringLength(min = 1, max = 100) String purpose,
            @ForAll @BigRange(min = "0.01", max = "1000") BigDecimal unitPrice,
            @ForAll @IntRange(min = 1, max = 1000) int maxParticipants) {

        // Skip blank purposes (whitespace only)
        Assume.that(!purpose.isBlank());

        Map<String, Object> scope = Map.of("data_category", "fitness");
        Map<String, Object> criteria = Map.of("account_type", "DS-IND");

        CoordinatorRequest request = new CoordinatorRequest(
                UUID.randomUUID(),
                purpose,
                scope,
                criteria,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS,
                unitPrice,
                maxParticipants,
                Map.of()
        );

        StorageResult result = service.storeRequest(request);

        assertThat(result.success())
                .as("Storage should succeed, violations: %s, status: %s", result.violations(), result.status())
                .isTrue();
        assertThat(result.status()).isEqualTo(StorageStatus.STORED);
        assertThat(result.violations()).isEmpty();

        // Verify request was saved in real database
        assertThat(requestRepository.count()).isGreaterThan(0);

        // Verify audit receipt was created in real database
        List<AuditReceipt> receipts = auditReceiptRepository.findAll();
        assertThat(receipts)
                .as("Storage should create REQUEST_CREATED audit receipt")
                .anyMatch(r -> r.getEventType() == AuditReceipt.EventType.REQUEST_CREATED);
    }

    @Property(tries = 50)
    @Label("Storage: Stored requests contain only allowed fields")
    void storedRequestsContainOnlyAllowedFields(
            @ForAll("forbiddenFieldNames") String forbiddenField) {

        // Create request with both valid and forbidden fields
        Map<String, Object> scope = new HashMap<>();
        scope.put("data_category", "fitness");
        scope.put(forbiddenField, "should_be_removed");

        Map<String, Object> criteria = Map.of("account_type", "DS-IND");

        CoordinatorRequest request = new CoordinatorRequest(
                UUID.randomUUID(),
                "Test purpose",
                scope,
                criteria,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS,
                BigDecimal.TEN,
                100,
                Map.of()
        );

        // This should be rejected due to raw data
        StorageResult result = service.storeRequest(request);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(StorageStatus.RAW_DATA_REJECTED);
    }

    // ==================== Policy Stamp Tests ====================

    @Test
    @Label("Policy stamp: Approved requests get valid policy stamp")
    void approvedRequestsGetValidPolicyStamp() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING);

        PolicyApproval approval = new PolicyApproval(
                PolicyDecision.APPROVED,
                Set.of("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY"),
                List.of()
        );

        PolicyStampResult result = service.attachPolicyStamp(request.getId(), approval);

        assertThat(result.success()).isTrue();
        assertThat(result.stamp()).isNotNull();
        assertThat(result.stamp().requestId()).isEqualTo(request.getId());
        assertThat(result.stamp().decision()).isEqualTo(PolicyDecision.APPROVED);
        assertThat(result.stamp().safeguards()).containsExactlyInAnyOrder("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY");
        assertThat(result.stamp().signature()).isNotBlank();
        assertThat(result.stamp().stampHash()).isNotBlank();

        // Verify request was activated in real database
        Request updatedRequest = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(updatedRequest.getStatus()).isEqualTo(Request.RequestStatus.ACTIVE);
    }

    @Test
    @Label("Policy stamp: Rejected requests get rejection stamp")
    void rejectedRequestsGetRejectionStamp() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.SCREENING);

        PolicyApproval approval = new PolicyApproval(
                PolicyDecision.REJECTED,
                Set.of(),
                List.of("COHORT_TOO_SMALL", "HIGH_RISK")
        );

        PolicyStampResult result = service.attachPolicyStamp(request.getId(), approval);

        assertThat(result.success()).isTrue();
        assertThat(result.stamp().decision()).isEqualTo(PolicyDecision.REJECTED);

        // Verify request was rejected in real database
        Request updatedRequest = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(updatedRequest.getStatus()).isEqualTo(Request.RequestStatus.REJECTED);
    }

    @Test
    @Label("Policy stamp: Non-screening requests cannot get stamp")
    void nonScreeningRequestsCannotGetStamp() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.ACTIVE);

        PolicyApproval approval = new PolicyApproval(PolicyDecision.APPROVED, Set.of(), List.of());

        PolicyStampResult result = service.attachPolicyStamp(request.getId(), approval);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("SCREENING");
    }

    // ==================== Publication Tests ====================

    @Test
    @Label("Publication: Active requests can be published via broadcast")
    void activeRequestsCanBePublishedViaBroadcast() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.ACTIVE);

        PublicationResult result = service.publishRequest(request.getId(), PublicationMode.BROADCAST);

        assertThat(result.success()).isTrue();
        assertThat(result.nodesReached()).isGreaterThan(0);

        // Verify audit receipt was created in real database
        List<AuditReceipt> receipts = auditReceiptRepository.findAll();
        assertThat(receipts)
                .as("Publication should create audit receipt")
                .anyMatch(r -> r.getEventType() == AuditReceipt.EventType.REQUEST_MATCHED);
    }

    @Test
    @Label("Publication: Non-active requests cannot be published")
    void nonActiveRequestsCannotBePublished() {
        UUID requesterId = UUID.randomUUID();
        Request request = createAndSaveRequest(requesterId, Request.RequestStatus.DRAFT);

        PublicationResult result = service.publishRequest(request.getId(), PublicationMode.BROADCAST);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ACTIVE");
    }

    // ==================== Nested Raw Data Tests ====================

    @Test
    @Label("Nested raw data: Forbidden fields in nested maps are detected")
    void forbiddenFieldsInNestedMapsAreDetected() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("raw_data", "sensitive_info");

        Map<String, Object> scope = new HashMap<>();
        scope.put("nested", nested);

        CoordinatorRequest request = createValidRequest(scope, Map.of(), Map.of());

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData()).isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("raw_data"));
    }

    @Test
    @Label("Nested raw data: GPS in nested values is detected")
    void gpsInNestedValuesIsDetected() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("coordinates", "37.774929, -122.419416");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("location_info", nested);

        CoordinatorRequest request = createValidRequest(Map.of(), Map.of(), metadata);

        RawDataCheckResult result = service.checkForRawData(request);

        assertThat(result.containsRawData()).isTrue();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("RAW_GPS_DATA"));
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<String> forbiddenFieldNames() {
        return Arbitraries.of(
                "raw_data", "rawData", "raw_payload", "rawPayload",
                "health_data", "healthData", "medical_records", "medicalRecords",
                "location_precise", "locationPrecise", "gps_coordinates", "gpsCoordinates",
                "private_labels", "privateLabels", "personal_identifiers", "personalIdentifiers",
                "biometric_data", "biometricData", "genetic_data", "geneticData",
                "node_location", "nodeLocation", "device_location", "deviceLocation",
                "health_flags", "healthFlags", "health_status", "healthStatus",
                "ssn", "social_security", "passport_number", "passportNumber",
                "credit_card", "creditCard", "bank_account", "bankAccount",
                "password", "secret_key", "secretKey", "private_key", "privateKey"
        );
    }

    @Provide
    Arbitrary<String> largeBase64() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars('+', '/')
                .ofMinLength(1000)
                .ofMaxLength(2000)
                .map(s -> s + "==");
    }

    @Provide
    Arbitrary<String> invalidCriteriaFields() {
        return Arbitraries.of(
                "node_location", "health_status", "precise_location",
                "raw_labels", "private_data", "user_identity",
                "device_fingerprint", "ip_address", "mac_address"
        );
    }

    // ==================== Helper Methods ====================

    private CoordinatorRequest createValidRequest(
            Map<String, Object> scope,
            Map<String, Object> criteria,
            Map<String, Object> metadata) {
        return new CoordinatorRequest(
                UUID.randomUUID(),
                "Test purpose",
                scope.isEmpty() ? Map.of("data_category", "fitness") : scope,
                criteria.isEmpty() ? Map.of("account_type", "DS-IND") : criteria,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                CoordinatorRequest.UnitType.DATA_ACCESS,
                BigDecimal.TEN,
                100,
                metadata
        );
    }

    /**
     * Creates and saves a real Request entity to the database.
     * Uses real JPA repository operations.
     * 
     * Request.create() signature:
     * (requesterId, purpose, scope, eligibilityCriteria, durationStart, durationEnd,
     *  unitType, unitPrice, maxParticipants, budget)
     */
    private Request createAndSaveRequest(UUID requesterId, Request.RequestStatus targetStatus) {
        Request request = Request.create(
                requesterId,
                "Test purpose",
                Map.of("category", "fitness"),
                Map.of(),
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
