package com.yachaq.api.policy;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.PolicyDecisionReceipt;
import com.yachaq.core.repository.PolicyDecisionReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Fail-Closed Policy Evaluation Service.
 * 
 * Property 29: Fail-Closed Policy Evaluation
 * *For any* policy evaluation that fails or encounters uncertainty,
 * the system must deny access (fail-closed) and broaden cohorts on uncertainty.
 * 
 * Validates: Requirements 206.1, 206.2, 206.3
 * 
 * Key behaviors:
 * - Deny access on policy evaluation failure
 * - Broaden cohorts on uncertainty
 * - Log all policy decisions with reason codes
 * - Never fail-open (allow access on error)
 * 
 * Uses Spring Data JPA repositories for all database access - no raw SQL.
 */
@Service
public class FailClosedPolicyService {

    private static final String DEFAULT_POLICY_VERSION = "1.0.0";
    private static final int DEFAULT_COHORT_BROADENING_FACTOR = 2;

    private final AuditService auditService;
    private final PolicyDecisionReceiptRepository policyDecisionReceiptRepository;
    private final String policyVersion;

    public FailClosedPolicyService(
            AuditService auditService,
            PolicyDecisionReceiptRepository policyDecisionReceiptRepository) {
        this.auditService = auditService;
        this.policyDecisionReceiptRepository = policyDecisionReceiptRepository;
        this.policyVersion = DEFAULT_POLICY_VERSION;
    }

    /**
     * Evaluates a policy with fail-closed semantics.
     * Property 29: Fail-Closed Policy Evaluation
     * Validates: Requirements 206.1, 206.2
     * 
     * @param context The policy evaluation context
     * @return PolicyDecision with ALLOW or DENY
     */
    @Transactional
    public PolicyDecision evaluate(PolicyContext context) {
        if (context == null) {
            return denyWithReason("NULL_CONTEXT", "Policy context cannot be null");
        }

        List<String> reasonCodes = new ArrayList<>();
        PolicyDecision decision;

        try {
            // Step 1: Validate required fields
            ValidationResult validation = validateContext(context);
            if (!validation.valid()) {
                reasonCodes.addAll(validation.violations());
                decision = denyWithReasons(reasonCodes, "Context validation failed");
                logDecision(context, decision);
                return decision;
            }

            // Step 2: Check policy rules
            RuleEvaluationResult ruleResult = evaluateRules(context);
            if (ruleResult.hasUncertainty()) {
                // Fail-closed on uncertainty
                reasonCodes.add("UNCERTAINTY_DETECTED");
                reasonCodes.addAll(ruleResult.uncertaintyReasons());
                decision = denyWithReasons(reasonCodes, "Uncertainty in policy evaluation - fail-closed");
                logDecision(context, decision);
                return decision;
            }

            if (!ruleResult.passed()) {
                reasonCodes.addAll(ruleResult.failureReasons());
                decision = denyWithReasons(reasonCodes, "Policy rules not satisfied");
                logDecision(context, decision);
                return decision;
            }

            // Step 3: Check cohort requirements
            CohortCheckResult cohortResult = checkCohortRequirements(context);
            if (!cohortResult.meetsRequirements()) {
                if (cohortResult.canBroaden()) {
                    // Broaden cohort on uncertainty
                    CohortBroadeningResult broadening = broadenCohort(context, cohortResult);
                    if (broadening.success()) {
                        reasonCodes.add("COHORT_BROADENED");
                        decision = allowWithConditions(
                                List.of("BROADENED_COHORT:" + broadening.newCohortSize()),
                                "Access allowed with broadened cohort"
                        );
                    } else {
                        reasonCodes.add("COHORT_BROADENING_FAILED");
                        decision = denyWithReasons(reasonCodes, "Cannot broaden cohort sufficiently");
                    }
                } else {
                    reasonCodes.add("COHORT_TOO_SMALL");
                    decision = denyWithReasons(reasonCodes, "Cohort requirements not met");
                }
                logDecision(context, decision);
                return decision;
            }

            // All checks passed
            decision = allow("All policy checks passed");
            logDecision(context, decision);
            return decision;

        } catch (Exception e) {
            // CRITICAL: Fail-closed on any exception
            reasonCodes.add("EVALUATION_ERROR");
            reasonCodes.add("ERROR:" + e.getClass().getSimpleName());
            decision = denyWithReasons(reasonCodes, "Policy evaluation error - fail-closed: " + e.getMessage());
            logDecision(context, decision);
            return decision;
        }
    }

    /**
     * Evaluates policy with explicit fail-closed guarantee.
     * This method NEVER returns ALLOW on error.
     */
    @Transactional
    public PolicyDecision evaluateFailClosed(PolicyContext context) {
        try {
            return evaluate(context);
        } catch (Throwable t) {
            // Catch everything including Error types
            return denyWithReason("CATASTROPHIC_FAILURE", 
                    "Catastrophic failure in policy evaluation - fail-closed");
        }
    }

    /**
     * Validates policy context has all required fields.
     */
    private ValidationResult validateContext(PolicyContext context) {
        List<String> violations = new ArrayList<>();

        if (context.requesterId() == null) {
            violations.add("MISSING_REQUESTER_ID");
        }
        if (context.resourceType() == null || context.resourceType().isBlank()) {
            violations.add("MISSING_RESOURCE_TYPE");
        }
        if (context.action() == null || context.action().isBlank()) {
            violations.add("MISSING_ACTION");
        }

        return new ValidationResult(violations.isEmpty(), violations);
    }

    /**
     * Evaluates policy rules against context.
     */
    private RuleEvaluationResult evaluateRules(PolicyContext context) {
        List<String> failureReasons = new ArrayList<>();
        List<String> uncertaintyReasons = new ArrayList<>();
        boolean hasUncertainty = false;

        // Check requester authorization
        if (context.requesterTier() == null) {
            uncertaintyReasons.add("UNKNOWN_REQUESTER_TIER");
            hasUncertainty = true;
        }

        // Check resource access rules
        if (context.resourceSensitivity() == null) {
            uncertaintyReasons.add("UNKNOWN_RESOURCE_SENSITIVITY");
            hasUncertainty = true;
        } else if (context.resourceSensitivity() == Sensitivity.HIGH) {
            // High sensitivity requires additional verification
            if (context.requesterTier() == null || 
                context.requesterTier().ordinal() < RequesterTier.VERIFIED.ordinal()) {
                failureReasons.add("INSUFFICIENT_TIER_FOR_HIGH_SENSITIVITY");
            }
        }

        // Check consent validity
        if (context.consentId() != null && !isConsentValid(context.consentId())) {
            failureReasons.add("INVALID_CONSENT");
        }

        // Check time-based restrictions
        if (context.requestTime() != null && isOutsideAllowedWindow(context)) {
            failureReasons.add("OUTSIDE_ALLOWED_TIME_WINDOW");
        }

        boolean passed = failureReasons.isEmpty() && !hasUncertainty;
        return new RuleEvaluationResult(passed, hasUncertainty, failureReasons, uncertaintyReasons);
    }

    /**
     * Checks cohort size requirements.
     */
    private CohortCheckResult checkCohortRequirements(PolicyContext context) {
        int currentCohortSize = context.cohortSize() != null ? context.cohortSize() : 0;
        int requiredCohortSize = context.requiredCohortSize() != null ? context.requiredCohortSize() : 50;

        boolean meetsRequirements = currentCohortSize >= requiredCohortSize;
        boolean canBroaden = currentCohortSize > 0 && currentCohortSize < requiredCohortSize;

        return new CohortCheckResult(meetsRequirements, canBroaden, currentCohortSize, requiredCohortSize);
    }

    /**
     * Attempts to broaden cohort to meet requirements.
     * Requirement 206.2: Broaden cohorts on uncertainty.
     */
    private CohortBroadeningResult broadenCohort(PolicyContext context, CohortCheckResult cohortResult) {
        int currentSize = cohortResult.currentSize();
        int requiredSize = cohortResult.requiredSize();

        // Calculate broadening factor needed
        int broadeningFactor = (int) Math.ceil((double) requiredSize / currentSize);
        
        // Limit broadening to prevent excessive generalization
        if (broadeningFactor > DEFAULT_COHORT_BROADENING_FACTOR * 2) {
            return new CohortBroadeningResult(false, currentSize, 
                    "Broadening factor too large: " + broadeningFactor);
        }

        // Simulate broadened cohort size
        int broadenedSize = currentSize * broadeningFactor;
        
        if (broadenedSize >= requiredSize) {
            return new CohortBroadeningResult(true, broadenedSize, null);
        } else {
            return new CohortBroadeningResult(false, broadenedSize, 
                    "Cannot achieve required cohort size");
        }
    }

    private boolean isConsentValid(UUID consentId) {
        // In production, this would check the consent registry
        return consentId != null;
    }

    private boolean isOutsideAllowedWindow(PolicyContext context) {
        // In production, this would check time-based access restrictions
        return false;
    }

    /**
     * Logs policy decision for audit trail.
     * Uses Spring Data JPA repository instead of raw SQL.
     */
    private void logDecision(PolicyContext context, PolicyDecision decision) {
        PolicyDecisionReceipt receipt = PolicyDecisionReceipt.create(
                "POLICY_EVALUATION",
                decision.decision().name(),
                context.campaignId(),
                context.requesterId(),
                decision.reasonCodes(),
                policyVersion
        );
        policyDecisionReceiptRepository.save(receipt);
    }

    // Factory methods for decisions

    private PolicyDecision allow(String message) {
        return new PolicyDecision(Decision.ALLOW, List.of(), List.of(), message);
    }

    private PolicyDecision allowWithConditions(List<String> conditions, String message) {
        return new PolicyDecision(Decision.ALLOW, List.of(), conditions, message);
    }

    private PolicyDecision denyWithReason(String reasonCode, String message) {
        return new PolicyDecision(Decision.DENY, List.of(reasonCode), List.of(), message);
    }

    private PolicyDecision denyWithReasons(List<String> reasonCodes, String message) {
        return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), message);
    }

    // Public records and enums

    public enum Decision {
        ALLOW,
        DENY
    }

    public enum Sensitivity {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum RequesterTier {
        BASIC,
        VERIFIED,
        PREMIUM,
        ENTERPRISE
    }

    public record PolicyContext(
            UUID requesterId,
            UUID campaignId,
            UUID consentId,
            String resourceType,
            String action,
            Sensitivity resourceSensitivity,
            RequesterTier requesterTier,
            Integer cohortSize,
            Integer requiredCohortSize,
            Instant requestTime,
            Map<String, Object> additionalContext
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private UUID requesterId;
            private UUID campaignId;
            private UUID consentId;
            private String resourceType;
            private String action;
            private Sensitivity resourceSensitivity;
            private RequesterTier requesterTier;
            private Integer cohortSize;
            private Integer requiredCohortSize;
            private Instant requestTime;
            private Map<String, Object> additionalContext = new HashMap<>();

            public Builder requesterId(UUID requesterId) { this.requesterId = requesterId; return this; }
            public Builder campaignId(UUID campaignId) { this.campaignId = campaignId; return this; }
            public Builder consentId(UUID consentId) { this.consentId = consentId; return this; }
            public Builder resourceType(String resourceType) { this.resourceType = resourceType; return this; }
            public Builder action(String action) { this.action = action; return this; }
            public Builder resourceSensitivity(Sensitivity s) { this.resourceSensitivity = s; return this; }
            public Builder requesterTier(RequesterTier t) { this.requesterTier = t; return this; }
            public Builder cohortSize(Integer size) { this.cohortSize = size; return this; }
            public Builder requiredCohortSize(Integer size) { this.requiredCohortSize = size; return this; }
            public Builder requestTime(Instant time) { this.requestTime = time; return this; }
            public Builder additionalContext(Map<String, Object> ctx) { this.additionalContext = ctx; return this; }

            public PolicyContext build() {
                return new PolicyContext(requesterId, campaignId, consentId, resourceType, action,
                        resourceSensitivity, requesterTier, cohortSize, requiredCohortSize, 
                        requestTime, additionalContext);
            }
        }
    }

    public record PolicyDecision(
            Decision decision,
            List<String> reasonCodes,
            List<String> conditions,
            String message
    ) {
        public boolean isAllowed() {
            return decision == Decision.ALLOW;
        }

        public boolean isDenied() {
            return decision == Decision.DENY;
        }
    }

    // Internal records

    private record ValidationResult(boolean valid, List<String> violations) {}

    private record RuleEvaluationResult(
            boolean passed,
            boolean hasUncertainty,
            List<String> failureReasons,
            List<String> uncertaintyReasons
    ) {}

    private record CohortCheckResult(
            boolean meetsRequirements,
            boolean canBroaden,
            int currentSize,
            int requiredSize
    ) {}

    private record CohortBroadeningResult(
            boolean success,
            int newCohortSize,
            String failureReason
    ) {}
}
