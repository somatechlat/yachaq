package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.coordinator.CoordinatorRequestService.*;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.RequestRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Coordinator Policy Review and Moderation Service.
 * Requirement 322.1: Enforce allowed criteria expressed in ODX terms only.
 * Requirement 322.2: Block or downscope high-risk requests.
 * Requirement 322.3: Apply required safeguards including privacy floors and output constraints.
 * Requirement 322.4: Sign policy_stamp with coordinator policy key.
 * Requirement 322.5: Provide reason codes and remediation hints when requests fail review.
 */
@Service
public class CoordinatorPolicyReviewService {

    // ODX-allowed criteria fields (Requirement 322.1)
    private static final Set<String> ODX_ALLOWED_CRITERIA = Set.of(
            // Domain labels
            "domain.fitness", "domain.health", "domain.location", "domain.communication",
            "domain.media", "domain.finance", "domain.social", "domain.shopping",
            "domain.travel", "domain.entertainment", "domain.productivity",
            // Time buckets
            "time.hour_of_day", "time.day_of_week", "time.week_index", "time.month",
            "time.quarter", "time.year", "time.recency_bucket",
            // Geo buckets (coarse only)
            "geo.country", "geo.region", "geo.city_bucket", "geo.timezone",
            // Quality tiers
            "quality.tier", "quality.verified_source", "quality.data_freshness",
            // Privacy floors
            "privacy.floor", "privacy.sensitivity_grade",
            // Availability bands
            "availability.band", "availability.last_active_bucket",
            // Account types
            "account.type", "account.tier", "account.status"
    );

    // High-risk combinations that require blocking or downscoping (Requirement 322.2)
    private static final List<HighRiskPattern> HIGH_RISK_PATTERNS = List.of(
            new HighRiskPattern(
                    Set.of("domain.health", "domain.location"),
                    "HEALTH_LOCATION_COMBO",
                    "Combining health and precise location data poses re-identification risk",
                    RiskAction.DOWNSCOPE,
                    Set.of("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY", "COARSE_GEO")
            ),
            new HighRiskPattern(
                    Set.of("domain.health", "geo.city_bucket"),
                    "HEALTH_CITY_COMBO",
                    "Health data with city-level location requires additional safeguards",
                    RiskAction.DOWNSCOPE,
                    Set.of("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY")
            ),
            new HighRiskPattern(
                    Set.of("domain.finance", "domain.location"),
                    "FINANCE_LOCATION_COMBO",
                    "Financial data with location poses fraud and privacy risks",
                    RiskAction.DOWNSCOPE,
                    Set.of("CLEAN_ROOM_ONLY", "AGGREGATE_ONLY")
            ),
            new HighRiskPattern(
                    Set.of("domain.communication", "domain.location"),
                    "COMMUNICATION_LOCATION_COMBO",
                    "Communication patterns with location can reveal sensitive relationships",
                    RiskAction.DOWNSCOPE,
                    Set.of("AGGREGATE_ONLY", "COARSE_TIME")
            )
    );

    // Patterns indicating minors involvement (strict blocking)
    private static final Set<String> MINORS_INDICATORS = Set.of(
            "minors", "children", "kids", "teens", "teenagers", "youth",
            "under_18", "under18", "school", "student", "pediatric"
    );

    // Minimum cohort size for any request
    private static final int MIN_COHORT_SIZE = 50;

    // Maximum criteria specificity (number of criteria fields)
    private static final int MAX_CRITERIA_SPECIFICITY = 5;

    private final RequestRepository requestRepository;
    private final AuditService auditService;
    private final byte[] coordinatorPolicyKey;

    @Value("${yachaq.coordinator.policy-version:1.0.0}")
    private String policyVersion;

    public CoordinatorPolicyReviewService(
            RequestRepository requestRepository,
            AuditService auditService,
            @Value("${yachaq.coordinator.policy-key:}") String policyKeyBase64) {
        this.requestRepository = requestRepository;
        this.auditService = auditService;
        this.coordinatorPolicyKey = initializePolicyKey(policyKeyBase64);
    }

    private byte[] initializePolicyKey(String policyKeyBase64) {
        if (policyKeyBase64 != null && !policyKeyBase64.isBlank()) {
            return Base64.getDecoder().decode(policyKeyBase64);
        }
        // Generate random key for development/testing
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Reviews a request for policy compliance.
     * Requirement 322.1: Enforce allowed criteria expressed in ODX terms only.
     * Requirement 322.2: Block or downscope high-risk requests.
     * Requirement 322.5: Provide reason codes and remediation hints.
     */
    @Transactional
    public PolicyReviewResult reviewRequest(UUID requestId) {
        Objects.requireNonNull(requestId, "Request ID cannot be null");

        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new CoordinatorRequestService.RequestNotFoundException(
                        "Request not found: " + requestId));

        if (request.getStatus() != Request.RequestStatus.SCREENING) {
            return PolicyReviewResult.failure(
                    requestId,
                    List.of("REQUEST_NOT_IN_SCREENING"),
                    List.of("Request must be in SCREENING status for policy review")
            );
        }

        List<String> reasonCodes = new ArrayList<>();
        List<String> remediationHints = new ArrayList<>();
        Set<String> requiredSafeguards = new HashSet<>();
        PolicyDecision decision = PolicyDecision.APPROVED;

        // Step 1: Validate ODX-terms-only criteria (Requirement 322.1)
        ODXValidationResult odxResult = validateODXCriteria(request.getEligibilityCriteria());
        if (!odxResult.valid()) {
            reasonCodes.addAll(odxResult.violations());
            remediationHints.addAll(odxResult.hints());
            decision = PolicyDecision.REJECTED;
        }

        // Step 2: Check for high-risk patterns (Requirement 322.2)
        HighRiskCheckResult riskResult = checkHighRiskPatterns(request);
        if (riskResult.hasRisk()) {
            reasonCodes.addAll(riskResult.reasonCodes());
            remediationHints.addAll(riskResult.hints());
            requiredSafeguards.addAll(riskResult.requiredSafeguards());
            
            if (riskResult.action() == RiskAction.BLOCK) {
                decision = PolicyDecision.REJECTED;
            } else if (decision != PolicyDecision.REJECTED) {
                decision = PolicyDecision.APPROVED; // With safeguards
            }
        }

        // Step 3: Check for minors involvement (strict blocking)
        if (involvesMinors(request)) {
            reasonCodes.add("MINORS_INVOLVEMENT_DETECTED");
            remediationHints.add("Requests involving minors require special approval workflow");
            decision = PolicyDecision.MANUAL_REVIEW;
        }

        // Step 4: Check criteria specificity
        if (request.getEligibilityCriteria() != null && 
            request.getEligibilityCriteria().size() > MAX_CRITERIA_SPECIFICITY) {
            reasonCodes.add("CRITERIA_TOO_SPECIFIC");
            remediationHints.add("Reduce eligibility criteria to maximum " + MAX_CRITERIA_SPECIFICITY + " fields");
            decision = PolicyDecision.REJECTED;
        }

        // Step 5: Apply default safeguards based on scope
        requiredSafeguards.addAll(determineDefaultSafeguards(request));

        // Audit the review
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                AuditReceipt.ActorType.SYSTEM,
                requestId,
                "PolicyReview",
                computeReviewHash(requestId, decision, reasonCodes)
        );

        return new PolicyReviewResult(
                requestId,
                decision,
                reasonCodes,
                remediationHints,
                requiredSafeguards,
                decision != PolicyDecision.REJECTED
        );
    }


    /**
     * Signs a policy stamp with the coordinator policy key.
     * Requirement 322.4: Sign policy_stamp with coordinator policy key.
     */
    public SignedPolicyStamp signPolicyStamp(UUID requestId, PolicyDecision decision,
                                              Set<String> safeguards, List<String> reasonCodes) {
        Objects.requireNonNull(requestId, "Request ID cannot be null");
        Objects.requireNonNull(decision, "Decision cannot be null");

        Instant timestamp = Instant.now();
        
        // Create payload for signing
        String payload = createSigningPayload(requestId, decision, safeguards, reasonCodes, timestamp);
        
        // Sign with coordinator policy key using HMAC-SHA256
        String signature = hmacSha256(payload, coordinatorPolicyKey);
        
        // Compute stamp hash for verification
        String stampHash = sha256(payload + "|" + signature);

        // Audit the signing
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                AuditReceipt.ActorType.SYSTEM,
                requestId,
                "PolicyStampSigned",
                stampHash
        );

        return new SignedPolicyStamp(
                requestId,
                decision,
                safeguards != null ? safeguards : Set.of(),
                reasonCodes != null ? reasonCodes : List.of(),
                policyVersion,
                timestamp,
                signature,
                stampHash
        );
    }

    /**
     * Verifies a policy stamp signature.
     */
    public boolean verifyPolicyStamp(SignedPolicyStamp stamp) {
        if (stamp == null) return false;
        
        String payload = createSigningPayload(
                stamp.requestId(),
                stamp.decision(),
                stamp.safeguards(),
                stamp.reasonCodes(),
                stamp.timestamp()
        );
        
        String expectedSignature = hmacSha256(payload, coordinatorPolicyKey);
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                stamp.signature().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Validates that eligibility criteria use only ODX terms.
     * Requirement 322.1: Enforce allowed criteria expressed in ODX terms only.
     */
    public ODXValidationResult validateODXCriteria(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return new ODXValidationResult(true, List.of(), List.of());
        }

        List<String> violations = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        for (String key : criteria.keySet()) {
            if (!isODXAllowedCriteria(key)) {
                violations.add("NON_ODX_CRITERIA:" + key);
                hints.add("Replace '" + key + "' with ODX-allowed criteria. Allowed prefixes: " +
                         "domain.*, time.*, geo.*, quality.*, privacy.*, availability.*, account.*");
            }
        }

        return new ODXValidationResult(violations.isEmpty(), violations, hints);
    }

    /**
     * Checks if a criteria key is ODX-allowed.
     */
    private boolean isODXAllowedCriteria(String key) {
        if (key == null) return false;
        
        // Check exact match
        if (ODX_ALLOWED_CRITERIA.contains(key)) {
            return true;
        }
        
        // Check prefix match for extensibility
        String[] allowedPrefixes = {"domain.", "time.", "geo.", "quality.", "privacy.", "availability.", "account."};
        for (String prefix : allowedPrefixes) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Checks for high-risk patterns in the request.
     * Requirement 322.2: Block or downscope high-risk requests.
     */
    private HighRiskCheckResult checkHighRiskPatterns(Request request) {
        Set<String> scopeLabels = extractLabels(request.getScope());
        Set<String> criteriaLabels = extractLabels(request.getEligibilityCriteria());
        Set<String> allLabels = new HashSet<>();
        allLabels.addAll(scopeLabels);
        allLabels.addAll(criteriaLabels);

        List<String> reasonCodes = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        Set<String> requiredSafeguards = new HashSet<>();
        RiskAction action = RiskAction.NONE;

        for (HighRiskPattern pattern : HIGH_RISK_PATTERNS) {
            if (matchesPattern(allLabels, pattern.triggerLabels())) {
                reasonCodes.add(pattern.code());
                hints.add(pattern.description());
                requiredSafeguards.addAll(pattern.requiredSafeguards());
                
                if (pattern.action() == RiskAction.BLOCK) {
                    action = RiskAction.BLOCK;
                } else if (action != RiskAction.BLOCK) {
                    action = pattern.action();
                }
            }
        }

        return new HighRiskCheckResult(
                !reasonCodes.isEmpty(),
                action,
                reasonCodes,
                hints,
                requiredSafeguards
        );
    }

    private Set<String> extractLabels(Map<String, Object> map) {
        Set<String> labels = new HashSet<>();
        if (map == null) return labels;
        
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            labels.add(entry.getKey());
            if (entry.getValue() instanceof String strValue) {
                labels.add(strValue);
            } else if (entry.getValue() instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof String) {
                        labels.add((String) item);
                    }
                }
            }
        }
        return labels;
    }

    private boolean matchesPattern(Set<String> labels, Set<String> triggerLabels) {
        for (String trigger : triggerLabels) {
            boolean found = false;
            for (String label : labels) {
                if (label.contains(trigger) || trigger.contains(label)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /**
     * Checks if the request involves minors.
     */
    private boolean involvesMinors(Request request) {
        String purpose = request.getPurpose();
        if (purpose != null) {
            String lowerPurpose = purpose.toLowerCase();
            for (String indicator : MINORS_INDICATORS) {
                if (lowerPurpose.contains(indicator)) {
                    return true;
                }
            }
        }

        // Check scope and criteria
        Set<String> allLabels = new HashSet<>();
        allLabels.addAll(extractLabels(request.getScope()));
        allLabels.addAll(extractLabels(request.getEligibilityCriteria()));
        
        for (String label : allLabels) {
            String lowerLabel = label.toLowerCase();
            for (String indicator : MINORS_INDICATORS) {
                if (lowerLabel.contains(indicator)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Determines default safeguards based on request scope.
     * Requirement 322.3: Apply required safeguards including privacy floors and output constraints.
     */
    private Set<String> determineDefaultSafeguards(Request request) {
        Set<String> safeguards = new HashSet<>();
        Set<String> scopeLabels = extractLabels(request.getScope());

        // Health data always requires clean room
        if (scopeLabels.stream().anyMatch(l -> l.contains("health"))) {
            safeguards.add("CLEAN_ROOM_ONLY");
            safeguards.add("PRIVACY_FLOOR_HIGH");
        }

        // Location data requires coarse geo
        if (scopeLabels.stream().anyMatch(l -> l.contains("location") || l.contains("geo"))) {
            safeguards.add("COARSE_GEO");
        }

        // Financial data requires aggregate only
        if (scopeLabels.stream().anyMatch(l -> l.contains("finance") || l.contains("financial"))) {
            safeguards.add("AGGREGATE_ONLY");
            safeguards.add("PRIVACY_FLOOR_HIGH");
        }

        // Communication data requires time bucketing
        if (scopeLabels.stream().anyMatch(l -> l.contains("communication") || l.contains("message"))) {
            safeguards.add("COARSE_TIME");
        }

        // Default minimum safeguards
        safeguards.add("K_ANONYMITY_50");
        safeguards.add("TTL_72H");

        return safeguards;
    }

    private String createSigningPayload(UUID requestId, PolicyDecision decision,
                                        Set<String> safeguards, List<String> reasonCodes,
                                        Instant timestamp) {
        return String.join("|",
                requestId.toString(),
                decision.name(),
                safeguards != null ? String.join(",", new TreeSet<>(safeguards)) : "",
                reasonCodes != null ? String.join(",", reasonCodes) : "",
                policyVersion,
                timestamp.toString()
        );
    }

    private String computeReviewHash(UUID requestId, PolicyDecision decision, List<String> reasonCodes) {
        String data = String.join("|",
                requestId.toString(),
                decision.name(),
                String.join(",", reasonCodes)
        );
        return sha256(data);
    }

    private String hmacSha256(String data, byte[] key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ==================== Records and Enums ====================

    /**
     * Result of policy review.
     */
    public record PolicyReviewResult(
            UUID requestId,
            PolicyDecision decision,
            List<String> reasonCodes,
            List<String> remediationHints,
            Set<String> requiredSafeguards,
            boolean success
    ) {
        public static PolicyReviewResult failure(UUID requestId, List<String> reasonCodes, 
                                                  List<String> hints) {
            return new PolicyReviewResult(requestId, PolicyDecision.REJECTED, 
                    reasonCodes, hints, Set.of(), false);
        }
    }

    /**
     * Result of ODX criteria validation.
     */
    public record ODXValidationResult(
            boolean valid,
            List<String> violations,
            List<String> hints
    ) {}

    /**
     * Result of high-risk pattern check.
     */
    public record HighRiskCheckResult(
            boolean hasRisk,
            RiskAction action,
            List<String> reasonCodes,
            List<String> hints,
            Set<String> requiredSafeguards
    ) {}

    /**
     * Signed policy stamp with coordinator key.
     */
    public record SignedPolicyStamp(
            UUID requestId,
            PolicyDecision decision,
            Set<String> safeguards,
            List<String> reasonCodes,
            String policyVersion,
            Instant timestamp,
            String signature,
            String stampHash
    ) {}

    /**
     * High-risk pattern definition.
     */
    public record HighRiskPattern(
            Set<String> triggerLabels,
            String code,
            String description,
            RiskAction action,
            Set<String> requiredSafeguards
    ) {}

    /**
     * Action to take for high-risk requests.
     */
    public enum RiskAction {
        NONE,       // No action needed
        DOWNSCOPE,  // Apply safeguards and continue
        BLOCK       // Reject the request
    }
}
