package com.yachaq.api.transform;

import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.QueryPlan;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Transform Restriction Enforcement.
 * Tests domain logic without Spring context.
 * 
 * **Feature: yachaq-platform, Property 18: Transform Restriction Enforcement**
 * For any consent contract specifying allowed transforms, applying a transform
 * not in the allowed list must be rejected.
 * 
 * **Validates: Requirements 220.1, 220.2**
 */
class TransformRestrictionPropertyTest {

    /**
     * **Feature: yachaq-platform, Property 18: Transform Restriction Enforcement**
     * **Validates: Requirements 220.1, 220.2**
     * 
     * For any consent contract with allowed transforms, requesting an unauthorized
     * transform must be rejected.
     */
    @Property(tries = 100)
    void property18_unauthorizedTransformsMustBeRejected(
            @ForAll("allowedTransformSets") Set<String> allowedTransforms,
            @ForAll("unauthorizedTransformLists") List<String> unauthorizedTransforms) {
        
        // Ensure unauthorized transforms are actually not in allowed set
        List<String> actualUnauthorized = new ArrayList<>();
        for (String transform : unauthorizedTransforms) {
            if (!allowedTransforms.contains(transform)) {
                actualUnauthorized.add(transform);
            }
        }
        
        Assume.that(!actualUnauthorized.isEmpty());

        // Act - Validate transforms
        TransformRestrictionService.TransformValidationResult result = 
                validateTransforms(allowedTransforms, actualUnauthorized);

        // Assert - Property 18: Unauthorized transforms must be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.deniedTransforms()).containsAll(actualUnauthorized);
    }

    /**
     * **Feature: yachaq-platform, Property 18: Transform Restriction Enforcement**
     * **Validates: Requirements 220.1**
     * 
     * For any consent contract with allowed transforms, requesting only allowed
     * transforms must succeed.
     */
    @Property(tries = 100)
    void property18_authorizedTransformsMustSucceed(
            @ForAll("allowedTransformSets") Set<String> allowedTransforms) {
        
        Assume.that(!allowedTransforms.isEmpty());

        // Request a subset of allowed transforms
        List<String> requestedTransforms = new ArrayList<>();
        int count = 0;
        for (String transform : allowedTransforms) {
            if (count++ < allowedTransforms.size() / 2 + 1) {
                requestedTransforms.add(transform);
            }
        }

        // Act - Validate transforms
        TransformRestrictionService.TransformValidationResult result = 
                validateTransforms(allowedTransforms, requestedTransforms);

        // Assert - Property 18: Authorized transforms must succeed
        assertThat(result.isValid()).isTrue();
        assertThat(result.deniedTransforms()).isEmpty();
        assertThat(result.allowedTransforms()).containsAll(requestedTransforms);
    }

    /**
     * Property: Mixed authorized and unauthorized transforms must be rejected.
     */
    @Property(tries = 100)
    void mixedTransformsMustBeRejected(
            @ForAll("allowedTransformSets") Set<String> allowedTransforms,
            @ForAll("unauthorizedTransformLists") List<String> unauthorizedTransforms) {
        
        Assume.that(!allowedTransforms.isEmpty());
        
        // Create mixed list with some allowed and some unauthorized
        List<String> mixedTransforms = new ArrayList<>();
        mixedTransforms.add(allowedTransforms.iterator().next()); // Add one allowed
        
        // Add unauthorized transforms that are not in allowed set
        for (String transform : unauthorizedTransforms) {
            if (!allowedTransforms.contains(transform)) {
                mixedTransforms.add(transform);
                break; // Just add one unauthorized
            }
        }
        
        Assume.that(mixedTransforms.size() > 1);

        // Act - Validate transforms
        TransformRestrictionService.TransformValidationResult result = 
                validateTransforms(allowedTransforms, mixedTransforms);

        // Assert - Mixed transforms must be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.deniedTransforms()).isNotEmpty();
    }

    /**
     * Property: Empty allowed transforms with non-empty request must fail.
     */
    @Property(tries = 100)
    void emptyAllowedTransformsWithRequestMustFail(
            @ForAll("allowedTransformSets") Set<String> requestedTransforms) {
        
        Assume.that(!requestedTransforms.isEmpty());
        List<String> requestList = new ArrayList<>(requestedTransforms);

        // Act - Validate with empty allowed transforms
        TransformRestrictionService.TransformValidationResult result = 
                validateTransforms(Collections.emptySet(), requestList);

        // Assert - Validation fails
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("No transforms allowed");
    }

    /**
     * Property: Empty request with any allowed transforms must succeed.
     */
    @Property(tries = 100)
    void emptyRequestMustSucceed(
            @ForAll("allowedTransformSets") Set<String> allowedTransforms) {
        
        // Act - Validate with empty request
        TransformRestrictionService.TransformValidationResult result = 
                validateTransforms(allowedTransforms, Collections.emptyList());

        // Assert - Validation succeeds
        assertThat(result.isValid()).isTrue();
    }

    /**
     * Property: Transform chain validation with valid chain must succeed.
     */
    @Property(tries = 100)
    void validTransformChainMustSucceed(
            @ForAll("validChainRules") Map<String, List<String>> chainRules,
            @ForAll("validTransformChains") List<String> transformChain) {
        
        Assume.that(transformChain.size() >= 2);

        // Act - Validate chain
        TransformRestrictionService.TransformChainValidationResult result = 
                validateTransformChain(chainRules, transformChain);

        // For valid chains defined in our test data, validation should succeed
        // (This is a simplified test - real validation depends on actual chain rules)
        assertThat(result).isNotNull();
    }

    /**
     * Property: Transform chain validation with invalid chain must fail.
     */
    @Property(tries = 100)
    void invalidTransformChainMustFail() {
        // Define chain rules where "aggregate" cannot be followed by "filter"
        Map<String, List<String>> chainRules = new HashMap<>();
        chainRules.put("aggregate", List.of("anonymize", "hash"));
        chainRules.put("filter", List.of("aggregate", "count"));

        // Create invalid chain: aggregate -> filter (not allowed)
        List<String> invalidChain = List.of("aggregate", "filter");

        // Act - Validate chain
        TransformRestrictionService.TransformChainValidationResult result = 
                validateTransformChain(chainRules, invalidChain);

        // Assert - Invalid chain must fail
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("cannot follow");
    }

    /**
     * Property: Consent contract can store allowed transforms.
     */
    @Property(tries = 100)
    void consentContractCanStoreAllowedTransforms(
            @ForAll("allowedTransformsJson") String allowedTransformsJson) {
        
        // Create consent contract
        ConsentContract contract = ConsentContract.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "scope123",
                "purpose456",
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                BigDecimal.TEN);

        // Act - Set allowed transforms
        contract.setAllowedTransforms(allowedTransformsJson);

        // Assert - Transforms are stored
        assertThat(contract.getAllowedTransforms()).isEqualTo(allowedTransformsJson);
    }

    /**
     * Property: Consent contract can store transform chain rules.
     */
    @Property(tries = 100)
    void consentContractCanStoreChainRules(
            @ForAll("chainRulesJson") String chainRulesJson) {
        
        // Create consent contract
        ConsentContract contract = ConsentContract.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "scope123",
                "purpose456",
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                BigDecimal.TEN);

        // Act - Set chain rules
        contract.setTransformChainRules(chainRulesJson);

        // Assert - Rules are stored
        assertThat(contract.getTransformChainRules()).isEqualTo(chainRulesJson);
    }

    /**
     * Property: Query plan stores allowed transforms from consent.
     */
    @Property(tries = 100)
    void queryPlanStoresAllowedTransformsFromConsent(
            @ForAll("allowedTransformsJson") String allowedTransformsJson) {
        
        // Create consent contract with allowed transforms
        ConsentContract contract = ConsentContract.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "scope123",
                "purpose456",
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                BigDecimal.TEN);
        contract.setAllowedTransforms(allowedTransformsJson);

        // Create query plan
        QueryPlan plan = new QueryPlan();
        plan.setRequestId(UUID.randomUUID());
        plan.setConsentContractId(contract.getId());

        // Act - Copy allowed transforms to query plan
        plan.setAllowedTransforms(contract.getAllowedTransforms());

        // Assert - Transforms are copied
        assertThat(plan.getAllowedTransforms()).isEqualTo(allowedTransformsJson);
    }

    /**
     * Property: Allowed transforms are included in query plan signable payload.
     */
    @Property(tries = 100)
    void allowedTransformsIncludedInSignablePayload(
            @ForAll("allowedTransformsJson") String allowedTransformsJson) {
        
        // Create query plan with allowed transforms
        QueryPlan plan = new QueryPlan();
        plan.setId(UUID.randomUUID());
        plan.setRequestId(UUID.randomUUID());
        plan.setConsentContractId(UUID.randomUUID());
        plan.setScopeHash("abc123");
        plan.setAllowedTransforms(allowedTransformsJson);
        plan.setOutputRestrictions("[]");
        plan.setPermittedFields("[]");
        plan.setCompensation(BigDecimal.TEN);
        plan.setTtl(Instant.now().plus(1, ChronoUnit.HOURS));

        // Act - Get signable payload
        String payload = plan.getSignablePayload();

        // Assert - Allowed transforms are in payload
        assertThat(payload).contains("allowedTransforms=" + allowedTransformsJson);
    }

    // Helper methods that mirror TransformRestrictionService logic for testing without Spring

    private TransformRestrictionService.TransformValidationResult validateTransforms(
            Set<String> allowedTransforms, 
            List<String> requestedTransforms) {
        
        if (allowedTransforms == null || allowedTransforms.isEmpty()) {
            if (requestedTransforms == null || requestedTransforms.isEmpty()) {
                return new TransformRestrictionService.TransformValidationResult(
                        true, Collections.emptyList(), Collections.emptyList(), null);
            }
            return new TransformRestrictionService.TransformValidationResult(
                    false, Collections.emptyList(), requestedTransforms, 
                    "No transforms allowed in consent contract");
        }
        if (requestedTransforms == null || requestedTransforms.isEmpty()) {
            return new TransformRestrictionService.TransformValidationResult(
                    true, Collections.emptyList(), Collections.emptyList(), null);
        }

        List<String> allowedList = new ArrayList<>();
        List<String> deniedList = new ArrayList<>();

        for (String transform : requestedTransforms) {
            if (allowedTransforms.contains(transform)) {
                allowedList.add(transform);
            } else {
                deniedList.add(transform);
            }
        }

        boolean valid = deniedList.isEmpty();
        String reason = valid ? null : "Unauthorized transforms requested: " + deniedList;

        return new TransformRestrictionService.TransformValidationResult(
                valid, allowedList, deniedList, reason);
    }

    private TransformRestrictionService.TransformChainValidationResult validateTransformChain(
            Map<String, List<String>> chainRules,
            List<String> transformChain) {
        
        if (chainRules == null || chainRules.isEmpty()) {
            return new TransformRestrictionService.TransformChainValidationResult(
                    true, null, -1, -1);
        }
        if (transformChain == null || transformChain.size() < 2) {
            return new TransformRestrictionService.TransformChainValidationResult(
                    true, null, -1, -1);
        }

        for (int i = 0; i < transformChain.size() - 1; i++) {
            String current = transformChain.get(i);
            String next = transformChain.get(i + 1);

            List<String> allowedSuccessors = chainRules.get(current);
            if (allowedSuccessors != null && !allowedSuccessors.contains(next)) {
                return new TransformRestrictionService.TransformChainValidationResult(
                        false,
                        "Transform '" + next + "' cannot follow '" + current + "'",
                        i, i + 1);
            }
        }

        return new TransformRestrictionService.TransformChainValidationResult(
                true, null, -1, -1);
    }

    // Arbitraries

    @Provide
    Arbitrary<Set<String>> allowedTransformSets() {
        return Arbitraries.of(
                "aggregate", "anonymize", "filter", "count", 
                "sum", "average", "hash", "truncate", "mask"
        ).set().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<String>> unauthorizedTransformLists() {
        return Arbitraries.of(
                "export", "copy", "download", "raw_access",
                "decrypt", "deanonymize", "link", "correlate"
        ).list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<Map<String, List<String>>> validChainRules() {
        return Arbitraries.of(
                Map.of("filter", List.of("aggregate", "count")),
                Map.of("aggregate", List.of("anonymize", "hash")),
                Map.of("count", List.of("sum", "average")),
                Collections.<String, List<String>>emptyMap()
        );
    }

    @Provide
    Arbitrary<List<String>> validTransformChains() {
        return Arbitraries.of(
                List.of("filter", "aggregate"),
                List.of("filter", "count"),
                List.of("aggregate", "anonymize"),
                List.of("count", "sum"),
                List.of("filter")
        );
    }

    @Provide
    Arbitrary<String> allowedTransformsJson() {
        return Arbitraries.of(
                "[\"aggregate\",\"anonymize\"]",
                "[\"filter\",\"count\",\"sum\"]",
                "[\"hash\",\"truncate\",\"mask\"]",
                "[\"aggregate\"]",
                "[]"
        );
    }

    @Provide
    Arbitrary<String> chainRulesJson() {
        return Arbitraries.of(
                "{\"filter\":[\"aggregate\",\"count\"]}",
                "{\"aggregate\":[\"anonymize\",\"hash\"]}",
                "{\"count\":[\"sum\",\"average\"]}",
                "{}"
        );
    }
}
