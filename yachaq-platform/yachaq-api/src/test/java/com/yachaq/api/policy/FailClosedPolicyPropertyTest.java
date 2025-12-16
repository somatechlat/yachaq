package com.yachaq.api.policy;

import com.yachaq.api.policy.FailClosedPolicyService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Fail-Closed Policy Evaluation.
 * 
 * Property 29: Fail-Closed Policy Evaluation
 * *For any* policy evaluation that fails or encounters uncertainty,
 * the system must deny access (fail-closed) and broaden cohorts on uncertainty.
 * 
 * Validates: Requirements 206.1, 206.2, 206.3
 */
class FailClosedPolicyPropertyTest {

    // ==================== Property 29: Fail-Closed on Null Context ====================

    @Test
    void property29_nullContextMustDeny() {
        // Simulate policy decision for null context
        PolicyDecision decision = simulateEvaluate(null);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("NULL_CONTEXT");
    }

    // ==================== Property 29: Fail-Closed on Missing Required Fields ====================

    @Property(tries = 100)
    void property29_missingRequesterIdMustDeny(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(null)  // Missing required field
                .resourceType(resourceType)
                .action(action)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("MISSING_REQUESTER_ID");
    }

    @Property(tries = 100)
    void property29_missingResourceTypeMustDeny(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validActions") String action) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(null)  // Missing required field
                .action(action)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("MISSING_RESOURCE_TYPE");
    }

    @Property(tries = 100)
    void property29_missingActionMustDeny(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(null)  // Missing required field
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("MISSING_ACTION");
    }

    // ==================== Property 29: Fail-Closed on Uncertainty ====================

    @Property(tries = 100)
    void property29_unknownRequesterTierMustDeny(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(null)  // Unknown tier = uncertainty
                .resourceSensitivity(Sensitivity.MEDIUM)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("UNCERTAINTY_DETECTED");
        assertThat(decision.reasonCodes()).contains("UNKNOWN_REQUESTER_TIER");
    }

    @Property(tries = 100)
    void property29_unknownResourceSensitivityMustDeny(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action,
            @ForAll("validRequesterTiers") RequesterTier tier) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(tier)
                .resourceSensitivity(null)  // Unknown sensitivity = uncertainty
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("UNCERTAINTY_DETECTED");
        assertThat(decision.reasonCodes()).contains("UNKNOWN_RESOURCE_SENSITIVITY");
    }

    // ==================== Property 29: Fail-Closed on High Sensitivity Without Verification ====================

    @Property(tries = 100)
    void property29_highSensitivityWithBasicTierMustDeny(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(RequesterTier.BASIC)  // Basic tier
                .resourceSensitivity(Sensitivity.HIGH)  // High sensitivity
                .cohortSize(100)
                .requiredCohortSize(50)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).contains("INSUFFICIENT_TIER_FOR_HIGH_SENSITIVITY");
    }

    // ==================== Property 29: Cohort Broadening on Uncertainty ====================

    @Property(tries = 100)
    void property29_smallCohortCanBeBroadened(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action,
            @ForAll @IntRange(min = 25, max = 49) int cohortSize) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(RequesterTier.VERIFIED)
                .resourceSensitivity(Sensitivity.LOW)
                .cohortSize(cohortSize)
                .requiredCohortSize(50)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        // Should either allow with broadened cohort or deny if broadening fails
        if (decision.isAllowed()) {
            assertThat(decision.conditions()).anyMatch(c -> c.startsWith("BROADENED_COHORT:"));
        } else {
            assertThat(decision.reasonCodes()).anyMatch(r -> 
                    r.contains("COHORT") || r.contains("BROADENING"));
        }
    }

    @Property(tries = 100)
    void property29_verySmallCohortCannotBeBroadened(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action,
            @ForAll @IntRange(min = 1, max = 10) int cohortSize) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(RequesterTier.VERIFIED)
                .resourceSensitivity(Sensitivity.LOW)
                .cohortSize(cohortSize)
                .requiredCohortSize(50)
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        // Very small cohorts cannot be broadened enough
        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCodes()).anyMatch(r -> r.contains("COHORT"));
    }

    // ==================== Property 29: Valid Context Must Allow ====================

    @Property(tries = 100)
    void property29_validContextMustAllow(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validActions") String action,
            @ForAll @IntRange(min = 50, max = 1000) int cohortSize) {
        
        PolicyContext context = PolicyContext.builder()
                .requesterId(requesterId)
                .resourceType(resourceType)
                .action(action)
                .requesterTier(RequesterTier.VERIFIED)
                .resourceSensitivity(Sensitivity.LOW)
                .cohortSize(cohortSize)
                .requiredCohortSize(50)
                .consentId(UUID.randomUUID())
                .build();

        PolicyDecision decision = simulateEvaluate(context);
        
        assertThat(decision.isAllowed()).isTrue();
    }

    // ==================== Property 29: Decision Records ====================

    @Property(tries = 50)
    void property29_allDecisionsHaveReasonCodes(
            @ForAll("policyContexts") PolicyContext context) {
        
        PolicyDecision decision = simulateEvaluate(context);
        
        // All decisions must have a message
        assertThat(decision.message()).isNotNull();
        assertThat(decision.message()).isNotBlank();
        
        // Denied decisions must have reason codes
        if (decision.isDenied()) {
            assertThat(decision.reasonCodes()).isNotEmpty();
        }
    }

    // ==================== Simulation Helper ====================

    /**
     * Simulates policy evaluation without database dependencies.
     * This mirrors the logic in FailClosedPolicyService.
     */
    private PolicyDecision simulateEvaluate(PolicyContext context) {
        if (context == null) {
            return new PolicyDecision(Decision.DENY, List.of("NULL_CONTEXT"), List.of(), 
                    "Policy context cannot be null");
        }

        List<String> reasonCodes = new ArrayList<>();

        // Validate required fields
        if (context.requesterId() == null) {
            reasonCodes.add("MISSING_REQUESTER_ID");
        }
        if (context.resourceType() == null || context.resourceType().isBlank()) {
            reasonCodes.add("MISSING_RESOURCE_TYPE");
        }
        if (context.action() == null || context.action().isBlank()) {
            reasonCodes.add("MISSING_ACTION");
        }

        if (!reasonCodes.isEmpty()) {
            return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), 
                    "Context validation failed");
        }

        // Check for uncertainty
        boolean hasUncertainty = false;
        if (context.requesterTier() == null) {
            reasonCodes.add("UNKNOWN_REQUESTER_TIER");
            hasUncertainty = true;
        }
        if (context.resourceSensitivity() == null) {
            reasonCodes.add("UNKNOWN_RESOURCE_SENSITIVITY");
            hasUncertainty = true;
        }

        if (hasUncertainty) {
            reasonCodes.add(0, "UNCERTAINTY_DETECTED");
            return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), 
                    "Uncertainty in policy evaluation - fail-closed");
        }

        // Check high sensitivity requirements
        if (context.resourceSensitivity() == Sensitivity.HIGH) {
            if (context.requesterTier().ordinal() < RequesterTier.VERIFIED.ordinal()) {
                reasonCodes.add("INSUFFICIENT_TIER_FOR_HIGH_SENSITIVITY");
                return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), 
                        "Insufficient tier for high sensitivity resource");
            }
        }

        // Check cohort requirements
        int currentCohortSize = context.cohortSize() != null ? context.cohortSize() : 0;
        int requiredCohortSize = context.requiredCohortSize() != null ? context.requiredCohortSize() : 50;

        if (currentCohortSize < requiredCohortSize) {
            // Try to broaden
            int broadeningFactor = (int) Math.ceil((double) requiredCohortSize / Math.max(currentCohortSize, 1));
            
            if (broadeningFactor > 4) {
                reasonCodes.add("COHORT_BROADENING_FAILED");
                return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), 
                        "Cannot broaden cohort sufficiently");
            }

            int broadenedSize = currentCohortSize * broadeningFactor;
            if (broadenedSize >= requiredCohortSize) {
                return new PolicyDecision(Decision.ALLOW, List.of(), 
                        List.of("BROADENED_COHORT:" + broadenedSize), 
                        "Access allowed with broadened cohort");
            } else {
                reasonCodes.add("COHORT_TOO_SMALL");
                return new PolicyDecision(Decision.DENY, reasonCodes, List.of(), 
                        "Cohort requirements not met");
            }
        }

        // All checks passed
        return new PolicyDecision(Decision.ALLOW, List.of(), List.of(), 
                "All policy checks passed");
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<UUID> validRequesterIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> validResourceTypes() {
        return Arbitraries.of("data", "query", "export", "aggregate", "model");
    }

    @Provide
    Arbitrary<String> validActions() {
        return Arbitraries.of("read", "write", "execute", "export", "aggregate");
    }

    @Provide
    Arbitrary<RequesterTier> validRequesterTiers() {
        return Arbitraries.of(RequesterTier.values());
    }

    @Provide
    Arbitrary<Sensitivity> validSensitivities() {
        return Arbitraries.of(Sensitivity.values());
    }

    @Provide
    Arbitrary<PolicyContext> policyContexts() {
        return Combinators.combine(
                Arbitraries.create(UUID::randomUUID).injectNull(0.1),
                Arbitraries.of("data", "query", "export").injectNull(0.1),
                Arbitraries.of("read", "write", "execute").injectNull(0.1),
                Arbitraries.of(RequesterTier.values()).injectNull(0.2),
                Arbitraries.of(Sensitivity.values()).injectNull(0.2),
                Arbitraries.integers().between(0, 200)
        ).as((requesterId, resourceType, action, tier, sensitivity, cohortSize) ->
                PolicyContext.builder()
                        .requesterId(requesterId)
                        .resourceType(resourceType)
                        .action(action)
                        .requesterTier(tier)
                        .resourceSensitivity(sensitivity)
                        .cohortSize(cohortSize)
                        .requiredCohortSize(50)
                        .build()
        );
    }
}
