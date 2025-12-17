package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.RequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Coordinator Request Management Service.
 * Requirement 321.1: Store only request definitions, requester identity, policy approvals, pricing.
 * Requirement 321.2: Enforce request schema validation.
 * Requirement 321.3: Attach policy stamps after review.
 * Requirement 321.4: Distribute to nodes via broadcast or topic-based delivery.
 * Requirement 321.5: Never store node locations, health flags, private labels, or raw data.
 * Requirement 321.6: Reject and log raw data ingestion attempts.
 */
@Service
public class CoordinatorRequestService {

    // Forbidden field patterns - raw data that must never be stored
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
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

    // Patterns for detecting raw data in values
    private static final Pattern GPS_PATTERN = Pattern.compile(
            "-?\\d{1,3}\\.\\d{5,}\\s*,\\s*-?\\d{1,3}\\.\\d{5,}");
    private static final Pattern BASE64_LARGE_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+/]{1000,}={0,2}$");
    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\d{3}-\\d{2}-\\d{4}");

    // Allowed ODX criteria fields
    private static final Set<String> ALLOWED_CRITERIA_FIELDS = Set.of(
            "account_type", "status", "created_after", "created_before",
            "domain", "time_bucket", "geo_bucket", "quality_tier",
            "privacy_floor", "data_category", "availability_band"
    );

    private final RequestRepository requestRepository;
    private final AuditService auditService;
    private final PolicyStampSigner policyStampSigner;

    @Value("${yachaq.coordinator.policy-version:1.0.0}")
    private String policyVersion;

    public CoordinatorRequestService(
            RequestRepository requestRepository,
            AuditService auditService,
            PolicyStampSigner policyStampSigner) {
        this.requestRepository = requestRepository;
        this.auditService = auditService;
        this.policyStampSigner = policyStampSigner;
    }

    /**
     * Stores a request after validation.
     * Requirement 321.1: Store only request definitions, requester identity, policy approvals, pricing.
     * Requirement 321.5: Never store raw data.
     * Requirement 321.6: Reject and log raw data ingestion attempts.
     */
    @Transactional
    public StorageResult storeRequest(CoordinatorRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        List<String> violations = new ArrayList<>();

        // Validate schema first
        SchemaValidationResult schemaResult = validateSchema(request);
        if (!schemaResult.valid()) {
            violations.addAll(schemaResult.violations());
        }

        // Check for forbidden raw data
        RawDataCheckResult rawDataCheck = checkForRawData(request);
        if (rawDataCheck.containsRawData()) {
            violations.addAll(rawDataCheck.violations());
            
            // Log the raw data ingestion attempt
            logRawDataAttempt(request, rawDataCheck);
            
            return new StorageResult(
                    false,
                    null,
                    violations,
                    StorageStatus.RAW_DATA_REJECTED
            );
        }

        if (!violations.isEmpty()) {
            return new StorageResult(
                    false,
                    null,
                    violations,
                    StorageStatus.VALIDATION_FAILED
            );
        }

        // Create sanitized request entity
        Request entity = createSanitizedRequest(request);
        Request saved = requestRepository.save(entity);

        // Audit the storage
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_CREATED,
                request.requesterId(),
                AuditReceipt.ActorType.REQUESTER,
                saved.getId(),
                "Request",
                computeRequestHash(saved)
        );

        return new StorageResult(
                true,
                saved.getId(),
                List.of(),
                StorageStatus.STORED
        );
    }

    /**
     * Validates request schema.
     * Requirement 321.2: Enforce request schema validation.
     */
    public SchemaValidationResult validateSchema(CoordinatorRequest request) {
        List<String> violations = new ArrayList<>();

        // Required fields
        if (request.requesterId() == null) {
            violations.add("MISSING_REQUESTER_ID");
        }
        if (request.purpose() == null || request.purpose().isBlank()) {
            violations.add("MISSING_PURPOSE");
        }
        if (request.scope() == null || request.scope().isEmpty()) {
            violations.add("MISSING_SCOPE");
        }
        if (request.eligibilityCriteria() == null) {
            violations.add("MISSING_ELIGIBILITY_CRITERIA");
        }
        if (request.unitPrice() == null || request.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            violations.add("INVALID_UNIT_PRICE");
        }
        if (request.maxParticipants() == null || request.maxParticipants() <= 0) {
            violations.add("INVALID_MAX_PARTICIPANTS");
        }
        if (request.durationStart() == null || request.durationEnd() == null) {
            violations.add("MISSING_DURATION");
        } else if (request.durationStart().isAfter(request.durationEnd())) {
            violations.add("INVALID_DURATION_RANGE");
        }

        // Validate eligibility criteria uses only ODX terms
        if (request.eligibilityCriteria() != null) {
            for (String key : request.eligibilityCriteria().keySet()) {
                if (!ALLOWED_CRITERIA_FIELDS.contains(key)) {
                    violations.add("INVALID_CRITERIA_FIELD:" + key);
                }
            }
        }

        // Validate scope contains only allowed fields
        if (request.scope() != null) {
            for (String key : request.scope().keySet()) {
                if (FORBIDDEN_FIELDS.contains(key.toLowerCase())) {
                    violations.add("FORBIDDEN_SCOPE_FIELD:" + key);
                }
            }
        }

        return new SchemaValidationResult(violations.isEmpty(), violations);
    }

    /**
     * Checks for raw data in request.
     * Requirement 321.5: Never store node locations, health flags, private labels, or raw data.
     * Requirement 321.6: Reject and log raw data ingestion attempts.
     */
    public RawDataCheckResult checkForRawData(CoordinatorRequest request) {
        List<String> violations = new ArrayList<>();
        List<String> detectedFields = new ArrayList<>();

        // Check all maps for forbidden fields
        checkMapForRawData(request.scope(), "scope", violations, detectedFields);
        checkMapForRawData(request.eligibilityCriteria(), "eligibilityCriteria", violations, detectedFields);
        checkMapForRawData(request.metadata(), "metadata", violations, detectedFields);

        // Check for raw data patterns in values
        checkValuesForRawDataPatterns(request.scope(), "scope", violations, detectedFields);
        checkValuesForRawDataPatterns(request.eligibilityCriteria(), "eligibilityCriteria", violations, detectedFields);
        checkValuesForRawDataPatterns(request.metadata(), "metadata", violations, detectedFields);

        return new RawDataCheckResult(!violations.isEmpty(), violations, detectedFields);
    }

    private void checkMapForRawData(Map<String, Object> map, String context,
                                     List<String> violations, List<String> detectedFields) {
        if (map == null) return;

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            
            // Check if key is forbidden
            if (FORBIDDEN_FIELDS.contains(key) || FORBIDDEN_FIELDS.contains(key.toLowerCase())) {
                violations.add("RAW_DATA_FIELD:" + context + "." + key);
                detectedFields.add(context + "." + key);
            }

            // Recursively check nested maps
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) entry.getValue();
                checkMapForRawData(nested, context + "." + key, violations, detectedFields);
            }
        }
    }

    private void checkValuesForRawDataPatterns(Map<String, Object> map, String context,
                                                List<String> violations, List<String> detectedFields) {
        if (map == null) return;

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String strValue) {
                // Check for GPS coordinates (precise location)
                if (GPS_PATTERN.matcher(strValue).find()) {
                    violations.add("RAW_GPS_DATA:" + context + "." + entry.getKey());
                    detectedFields.add(context + "." + entry.getKey() + " (GPS)");
                }
                // Check for large base64 (potential raw payload)
                if (BASE64_LARGE_PATTERN.matcher(strValue).matches()) {
                    violations.add("RAW_PAYLOAD_DATA:" + context + "." + entry.getKey());
                    detectedFields.add(context + "." + entry.getKey() + " (Base64 payload)");
                }
                // Check for SSN pattern
                if (SSN_PATTERN.matcher(strValue).find()) {
                    violations.add("RAW_PII_DATA:" + context + "." + entry.getKey());
                    detectedFields.add(context + "." + entry.getKey() + " (SSN)");
                }
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                checkValuesForRawDataPatterns(nested, context + "." + entry.getKey(), violations, detectedFields);
            }
        }
    }

    /**
     * Attaches policy stamp after review.
     * Requirement 321.3: Attach policy stamps after review.
     */
    @Transactional
    public PolicyStampResult attachPolicyStamp(UUID requestId, PolicyApproval approval) {
        Objects.requireNonNull(requestId, "Request ID cannot be null");
        Objects.requireNonNull(approval, "Approval cannot be null");

        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException("Request not found: " + requestId));

        if (request.getStatus() != Request.RequestStatus.SCREENING) {
            return new PolicyStampResult(
                    false,
                    null,
                    "Request must be in SCREENING status to attach policy stamp"
            );
        }

        // Generate policy stamp
        PolicyStamp stamp = policyStampSigner.sign(
                requestId,
                approval.decision(),
                approval.safeguards(),
                policyVersion,
                Instant.now()
        );

        // Update request status based on decision
        if (approval.decision() == PolicyDecision.APPROVED) {
            request.activate();
        } else if (approval.decision() == PolicyDecision.REJECTED) {
            request.reject();
        }
        requestRepository.save(request);

        // Audit the policy stamp
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), // System actor
                AuditReceipt.ActorType.SYSTEM,
                requestId,
                "PolicyStamp",
                stamp.stampHash()
        );

        return new PolicyStampResult(true, stamp, null);
    }

    /**
     * Publishes request to nodes.
     * Requirement 321.4: Distribute to nodes via broadcast or topic-based delivery.
     */
    public PublicationResult publishRequest(UUID requestId, PublicationMode mode) {
        Objects.requireNonNull(requestId, "Request ID cannot be null");
        Objects.requireNonNull(mode, "Publication mode cannot be null");

        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException("Request not found: " + requestId));

        if (request.getStatus() != Request.RequestStatus.ACTIVE) {
            return new PublicationResult(
                    false,
                    0,
                    "Request must be ACTIVE to publish"
            );
        }

        // Create publication payload (no raw data, only ODX-safe fields)
        PublicationPayload payload = createPublicationPayload(request);

        // Publish based on mode
        int nodesReached = switch (mode) {
            case BROADCAST -> publishBroadcast(payload);
            case TOPIC_BASED -> publishTopicBased(payload);
        };

        // Audit the publication (using REQUEST_MATCHED as closest existing event type)
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_MATCHED,
                request.getRequesterId(),
                AuditReceipt.ActorType.SYSTEM,
                requestId,
                "Publication",
                computePayloadHash(payload)
        );

        return new PublicationResult(true, nodesReached, null);
    }

    private Request createSanitizedRequest(CoordinatorRequest request) {
        // Only store allowed fields - no raw data
        Map<String, Object> sanitizedScope = sanitizeMap(request.scope());
        Map<String, Object> sanitizedCriteria = sanitizeMap(request.eligibilityCriteria());

        return Request.create(
                request.requesterId(),
                request.purpose(),
                sanitizedScope,
                sanitizedCriteria,
                request.durationStart(),
                request.durationEnd(),
                Request.UnitType.valueOf(request.unitType().name()),
                request.unitPrice(),
                request.maxParticipants(),
                request.unitPrice().multiply(BigDecimal.valueOf(request.maxParticipants()))
        );
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null) return Map.of();
        
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            // Skip forbidden fields
            if (FORBIDDEN_FIELDS.contains(key) || FORBIDDEN_FIELDS.contains(key.toLowerCase())) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                sanitized.put(key, sanitizeMap(nested));
            } else if (value instanceof String strValue) {
                // Skip values containing raw data patterns
                if (!GPS_PATTERN.matcher(strValue).find() &&
                    !BASE64_LARGE_PATTERN.matcher(strValue).matches() &&
                    !SSN_PATTERN.matcher(strValue).find()) {
                    sanitized.put(key, value);
                }
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private void logRawDataAttempt(CoordinatorRequest request, RawDataCheckResult check) {
        // Log raw data ingestion attempt as unauthorized access attempt
        auditService.appendReceipt(
                AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
                request.requesterId(),
                AuditReceipt.ActorType.REQUESTER,
                request.requesterId(),
                "RawDataAttempt",
                String.join(",", check.detectedFields())
        );
    }

    private PublicationPayload createPublicationPayload(Request request) {
        return new PublicationPayload(
                request.getId(),
                request.getRequesterId(),
                request.getPurpose(),
                request.getScope(),
                request.getEligibilityCriteria(),
                request.getUnitPrice(),
                request.getMaxParticipants(),
                request.getDurationStart(),
                request.getDurationEnd()
        );
    }

    private int publishBroadcast(PublicationPayload payload) {
        // Mode A: Broadcast to all online nodes
        // In production, this would use a message queue or pub/sub system
        // Returns estimated nodes reached
        return 1000; // Placeholder for actual broadcast implementation
    }

    private int publishTopicBased(PublicationPayload payload) {
        // Mode B: Publish to rotating geo topics
        // In production, this would use topic-based routing
        // Returns estimated nodes reached
        return 500; // Placeholder for actual topic-based implementation
    }

    private String computeRequestHash(Request request) {
        String data = String.join("|",
                request.getId().toString(),
                request.getRequesterId().toString(),
                request.getPurpose(),
                request.getUnitPrice().toPlainString()
        );
        return sha256(data);
    }

    private String computePayloadHash(PublicationPayload payload) {
        String data = String.join("|",
                payload.requestId().toString(),
                payload.requesterId().toString(),
                payload.purpose()
        );
        return sha256(data);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Records and Enums ====================

    /**
     * Coordinator request input (before storage).
     */
    public record CoordinatorRequest(
            UUID requesterId,
            String purpose,
            Map<String, Object> scope,
            Map<String, Object> eligibilityCriteria,
            Instant durationStart,
            Instant durationEnd,
            UnitType unitType,
            BigDecimal unitPrice,
            Integer maxParticipants,
            Map<String, Object> metadata
    ) {
        public enum UnitType {
            SURVEY, DATA_ACCESS, PARTICIPATION
        }
    }

    /**
     * Result of storing a request.
     */
    public record StorageResult(
            boolean success,
            UUID requestId,
            List<String> violations,
            StorageStatus status
    ) {}

    public enum StorageStatus {
        STORED,
        VALIDATION_FAILED,
        RAW_DATA_REJECTED
    }

    /**
     * Result of schema validation.
     */
    public record SchemaValidationResult(
            boolean valid,
            List<String> violations
    ) {}

    /**
     * Result of raw data check.
     */
    public record RawDataCheckResult(
            boolean containsRawData,
            List<String> violations,
            List<String> detectedFields
    ) {}

    /**
     * Policy approval input.
     */
    public record PolicyApproval(
            PolicyDecision decision,
            Set<String> safeguards,
            List<String> reasonCodes
    ) {}

    public enum PolicyDecision {
        APPROVED,
        REJECTED,
        MANUAL_REVIEW
    }

    /**
     * Result of attaching policy stamp.
     */
    public record PolicyStampResult(
            boolean success,
            PolicyStamp stamp,
            String error
    ) {}

    /**
     * Policy stamp attached to approved requests.
     */
    public record PolicyStamp(
            UUID requestId,
            PolicyDecision decision,
            Set<String> safeguards,
            String policyVersion,
            Instant timestamp,
            String signature,
            String stampHash
    ) {}

    /**
     * Publication mode for distributing requests.
     */
    public enum PublicationMode {
        BROADCAST,      // Mode A: All online nodes
        TOPIC_BASED     // Mode B: Rotating geo topics
    }

    /**
     * Result of publishing a request.
     */
    public record PublicationResult(
            boolean success,
            int nodesReached,
            String error
    ) {}

    /**
     * Payload sent to nodes (no raw data).
     */
    public record PublicationPayload(
            UUID requestId,
            UUID requesterId,
            String purpose,
            Map<String, Object> scope,
            Map<String, Object> eligibilityCriteria,
            BigDecimal unitPrice,
            Integer maxParticipants,
            Instant durationStart,
            Instant durationEnd
    ) {}

    /**
     * Interface for policy stamp signing.
     */
    public interface PolicyStampSigner {
        PolicyStamp sign(UUID requestId, PolicyDecision decision, Set<String> safeguards,
                        String policyVersion, Instant timestamp);
    }

    /**
     * Default policy stamp signer using SHA-256 HMAC.
     */
    @org.springframework.stereotype.Component
    public static class DefaultPolicyStampSigner implements PolicyStampSigner {
        private final byte[] secretKey;

        /**
         * Spring-managed constructor with optional key from configuration.
         */
        @org.springframework.beans.factory.annotation.Autowired
        public DefaultPolicyStampSigner(
                @org.springframework.beans.factory.annotation.Value("${yachaq.coordinator.policy-stamp-key:}") String keyBase64) {
            if (keyBase64 != null && !keyBase64.isBlank()) {
                this.secretKey = java.util.Base64.getDecoder().decode(keyBase64);
            } else {
                // Generate random key for development/testing
                this.secretKey = new byte[32];
                new SecureRandom().nextBytes(this.secretKey);
            }
        }

        /**
         * Constructor for direct instantiation with explicit key (for testing).
         */
        public DefaultPolicyStampSigner(byte[] secretKey) {
            this.secretKey = Arrays.copyOf(secretKey, secretKey.length);
        }

        @Override
        public PolicyStamp sign(UUID requestId, PolicyDecision decision, Set<String> safeguards,
                               String policyVersion, Instant timestamp) {
            String payload = String.join("|",
                    requestId.toString(),
                    decision.name(),
                    String.join(",", new TreeSet<>(safeguards)),
                    policyVersion,
                    timestamp.toString()
            );

            String signature = hmacSha256(payload);
            String stampHash = sha256(payload + "|" + signature);

            return new PolicyStamp(
                    requestId,
                    decision,
                    safeguards,
                    policyVersion,
                    timestamp,
                    signature,
                    stampHash
            );
        }

        private String hmacSha256(String data) {
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(secretKey, "HmacSHA256"));
                byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (Exception e) {
                throw new RuntimeException("HMAC-SHA256 failed", e);
            }
        }

        private String sha256(String input) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }
    }

    public static class RequestNotFoundException extends RuntimeException {
        public RequestNotFoundException(String message) {
            super(message);
        }
    }
}
