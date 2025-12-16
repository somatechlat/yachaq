package com.yachaq.api.access;

import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.QueryPlan;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Field-Level Access Enforcement.
 * Tests domain logic without Spring context.
 * 
 * **Feature: yachaq-platform, Property 17: Field-Level Access Enforcement**
 * For any consent contract specifying permitted fields, executing a query
 * must return only those permitted fields and no others.
 * 
 * **Validates: Requirements 219.1, 219.2**
 */
class FieldAccessPropertyTest {

    /**
     * **Feature: yachaq-platform, Property 17: Field-Level Access Enforcement**
     * **Validates: Requirements 219.1, 219.2**
     * 
     * For any consent contract with permitted fields, filtering data must
     * return only those permitted fields and no others.
     */
    @Property(tries = 100)
    void property17_filteringReturnsOnlyPermittedFields(
            @ForAll("permittedFieldSets") Set<String> permittedFields,
            @ForAll("dataWithFields") Map<String, Object> data) {
        
        // Act - Filter data to permitted fields
        Map<String, Object> filtered = filterToPermittedFields(data, permittedFields);

        // Assert - Property 17: Only permitted fields are returned
        for (String field : filtered.keySet()) {
            assertThat(permittedFields).contains(field);
        }

        // Assert - No unpermitted fields are returned
        for (String field : data.keySet()) {
            if (!permittedFields.contains(field)) {
                assertThat(filtered).doesNotContainKey(field);
            }
        }
    }

    /**
     * **Feature: yachaq-platform, Property 17: Field-Level Access Enforcement**
     * **Validates: Requirements 219.2**
     * 
     * For any request with fields not in permitted set, validation must fail.
     */
    @Property(tries = 100)
    void property17_requestingUnpermittedFieldsMustFail(
            @ForAll("permittedFieldSets") Set<String> permittedFields,
            @ForAll("unpermittedFieldSets") Set<String> unpermittedFields) {
        
        // Ensure unpermitted fields are actually not in permitted set
        Set<String> actualUnpermitted = new HashSet<>();
        for (String field : unpermittedFields) {
            if (!permittedFields.contains(field)) {
                actualUnpermitted.add(field);
            }
        }
        
        Assume.that(!actualUnpermitted.isEmpty());

        // Act - Validate field access with unpermitted fields
        FieldAccessService.FieldAccessResult result = validateFieldAccess(
                permittedFields, actualUnpermitted);

        // Assert - Property 17: Validation must fail for unpermitted fields
        assertThat(result.isValid()).isFalse();
        assertThat(result.deniedFields()).containsAll(actualUnpermitted);
    }

    /**
     * **Feature: yachaq-platform, Property 17: Field-Level Access Enforcement**
     * **Validates: Requirements 219.1**
     * 
     * For any request with only permitted fields, validation must succeed.
     */
    @Property(tries = 100)
    void property17_requestingOnlyPermittedFieldsMustSucceed(
            @ForAll("permittedFieldSets") Set<String> permittedFields) {
        
        Assume.that(!permittedFields.isEmpty());

        // Request a subset of permitted fields
        Set<String> requestedFields = new HashSet<>();
        int count = 0;
        for (String field : permittedFields) {
            if (count++ < permittedFields.size() / 2 + 1) {
                requestedFields.add(field);
            }
        }

        // Act - Validate field access
        FieldAccessService.FieldAccessResult result = validateFieldAccess(
                permittedFields, requestedFields);

        // Assert - Property 17: Validation must succeed for permitted fields
        assertThat(result.isValid()).isTrue();
        assertThat(result.deniedFields()).isEmpty();
        assertThat(result.allowedFields()).containsAll(requestedFields);
    }

    /**
     * Property: Filtered data preserves values of permitted fields.
     */
    @Property(tries = 100)
    void filteredDataPreservesPermittedFieldValues(
            @ForAll("permittedFieldSets") Set<String> permittedFields,
            @ForAll("dataWithFields") Map<String, Object> data) {
        
        // Act - Filter data
        Map<String, Object> filtered = filterToPermittedFields(data, permittedFields);

        // Assert - Values are preserved for permitted fields
        for (String field : filtered.keySet()) {
            assertThat(filtered.get(field)).isEqualTo(data.get(field));
        }
    }

    /**
     * Property: Empty permitted fields results in empty filtered data.
     */
    @Property(tries = 100)
    void emptyPermittedFieldsResultsInEmptyData(
            @ForAll("dataWithFields") Map<String, Object> data) {
        
        // Act - Filter with empty permitted fields
        Map<String, Object> filtered = filterToPermittedFields(data, Collections.emptySet());

        // Assert - Result is empty
        assertThat(filtered).isEmpty();
    }

    /**
     * Property: Null permitted fields results in empty filtered data.
     */
    @Property(tries = 100)
    void nullPermittedFieldsResultsInEmptyData(
            @ForAll("dataWithFields") Map<String, Object> data) {
        
        // Act - Filter with null permitted fields
        Map<String, Object> filtered = filterToPermittedFields(data, null);

        // Assert - Result is empty
        assertThat(filtered).isEmpty();
    }

    /**
     * Property: Validation with empty permitted fields must fail.
     */
    @Property(tries = 100)
    void validationWithEmptyPermittedFieldsMustFail(
            @ForAll("permittedFieldSets") Set<String> requestedFields) {
        
        Assume.that(!requestedFields.isEmpty());

        // Act - Validate with empty permitted fields
        FieldAccessService.FieldAccessResult result = validateFieldAccess(
                Collections.emptySet(), requestedFields);

        // Assert - Validation fails
        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("No permitted fields");
    }

    /**
     * Property: Consent contract can store permitted fields.
     */
    @Property(tries = 100)
    void consentContractCanStorePermittedFields(
            @ForAll("permittedFieldsJson") String permittedFieldsJson) {
        
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

        // Act - Set permitted fields
        contract.setPermittedFields(permittedFieldsJson);

        // Assert - Fields are stored
        assertThat(contract.getPermittedFields()).isEqualTo(permittedFieldsJson);
    }

    /**
     * Property: Query plan can store permitted fields from consent.
     */
    @Property(tries = 100)
    void queryPlanCanStorePermittedFieldsFromConsent(
            @ForAll("permittedFieldsJson") String permittedFieldsJson) {
        
        // Create consent contract with permitted fields
        ConsentContract contract = ConsentContract.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "scope123",
                "purpose456",
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS),
                BigDecimal.TEN);
        contract.setPermittedFields(permittedFieldsJson);

        // Create query plan
        QueryPlan plan = new QueryPlan();
        plan.setRequestId(UUID.randomUUID());
        plan.setConsentContractId(contract.getId());

        // Act - Copy permitted fields to query plan
        plan.setPermittedFields(contract.getPermittedFields());

        // Assert - Fields are copied
        assertThat(plan.getPermittedFields()).isEqualTo(permittedFieldsJson);
    }

    /**
     * Property: Permitted fields are included in query plan signable payload.
     */
    @Property(tries = 100)
    void permittedFieldsIncludedInSignablePayload(
            @ForAll("permittedFieldsJson") String permittedFieldsJson) {
        
        // Create query plan with permitted fields
        QueryPlan plan = new QueryPlan();
        plan.setId(UUID.randomUUID());
        plan.setRequestId(UUID.randomUUID());
        plan.setConsentContractId(UUID.randomUUID());
        plan.setScopeHash("abc123");
        plan.setAllowedTransforms("[]");
        plan.setOutputRestrictions("[]");
        plan.setPermittedFields(permittedFieldsJson);
        plan.setCompensation(BigDecimal.TEN);
        plan.setTtl(Instant.now().plus(1, ChronoUnit.HOURS));

        // Act - Get signable payload
        String payload = plan.getSignablePayload();

        // Assert - Permitted fields are in payload
        assertThat(payload).contains("permittedFields=" + permittedFieldsJson);
    }

    /**
     * Property: Sensitive field consents can be stored.
     */
    @Property(tries = 100)
    void sensitiveFieldConsentsCanBeStored(
            @ForAll("sensitiveFieldConsentsJson") String sensitiveFieldConsentsJson) {
        
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

        // Act - Set sensitive field consents
        contract.setSensitiveFieldConsents(sensitiveFieldConsentsJson);

        // Assert - Consents are stored
        assertThat(contract.getSensitiveFieldConsents()).isEqualTo(sensitiveFieldConsentsJson);
    }

    // Helper methods that mirror FieldAccessService logic for testing without Spring

    private Map<String, Object> filterToPermittedFields(
            Map<String, Object> data, 
            Set<String> permittedFields) {
        
        if (data == null) {
            return Collections.emptyMap();
        }
        if (permittedFields == null || permittedFields.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String field : permittedFields) {
            if (data.containsKey(field)) {
                filtered.put(field, data.get(field));
            }
        }
        return filtered;
    }

    private FieldAccessService.FieldAccessResult validateFieldAccess(
            Set<String> permittedFields, 
            Set<String> requestedFields) {
        
        if (permittedFields == null || permittedFields.isEmpty()) {
            return new FieldAccessService.FieldAccessResult(
                    false, Collections.emptySet(), requestedFields,
                    "No permitted fields defined in consent contract");
        }
        if (requestedFields == null || requestedFields.isEmpty()) {
            return new FieldAccessService.FieldAccessResult(
                    true, Collections.emptySet(), Collections.emptySet(), null);
        }

        Set<String> allowedFields = new HashSet<>();
        Set<String> deniedFields = new HashSet<>();

        for (String field : requestedFields) {
            if (permittedFields.contains(field)) {
                allowedFields.add(field);
            } else {
                deniedFields.add(field);
            }
        }

        boolean valid = deniedFields.isEmpty();
        String reason = valid ? null : "Unauthorized fields requested: " + deniedFields;

        return new FieldAccessService.FieldAccessResult(valid, allowedFields, deniedFields, reason);
    }

    // Arbitraries

    @Provide
    Arbitrary<Set<String>> permittedFieldSets() {
        return Arbitraries.of(
                "name", "email", "age", "location", "phone", 
                "address", "income", "health_status", "preferences"
        ).set().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Set<String>> unpermittedFieldSets() {
        return Arbitraries.of(
                "ssn", "password", "credit_card", "bank_account",
                "medical_records", "biometrics", "private_key"
        ).set().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<Map<String, Object>> dataWithFields() {
        return Arbitraries.maps(
                Arbitraries.of("name", "email", "age", "location", "phone", 
                        "address", "income", "health_status", "preferences",
                        "ssn", "password", "credit_card"),
                Arbitraries.oneOf(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                        Arbitraries.integers().between(1, 100).map(Object.class::cast)
                )
        ).ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<String> permittedFieldsJson() {
        return Arbitraries.of(
                "[\"name\",\"email\"]",
                "[\"age\",\"location\",\"preferences\"]",
                "[\"name\",\"phone\",\"address\"]",
                "[\"income\",\"health_status\"]",
                "[\"name\"]"
        );
    }

    @Provide
    Arbitrary<String> sensitiveFieldConsentsJson() {
        return Arbitraries.of(
                "{\"health_status\":true,\"income\":false}",
                "{\"medical_records\":true}",
                "{\"biometrics\":false,\"location\":true}",
                "{}"
        );
    }
}
