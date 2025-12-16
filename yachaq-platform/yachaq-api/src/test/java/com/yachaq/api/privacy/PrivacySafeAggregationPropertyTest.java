package com.yachaq.api.privacy;

import com.yachaq.api.privacy.PrivacySafeAggregationService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Property-based tests for Privacy-Safe Aggregation Service.
 * 
 * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
 * *For any* aggregation query, if the group size is less than 50 (k < 50), 
 * the result must be suppressed or generalized to prevent identification.
 * 
 * **Validates: Requirements 229.1, 229.3**
 */
class PrivacySafeAggregationPropertyTest {

    private static final int K_MIN = 50;

    private PrivacySafeAggregationService createService() {
        return new PrivacySafeAggregationService(null);
    }

    // ==================== Property 20: K-Anonymity Enforcement ====================

    /**
     * Property 20: Groups with size < k must be suppressed.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.1, 229.3**
     */
    @Property(tries = 100)
    void property20_smallGroupsMustBeSuppressed(
            @ForAll @IntRange(min = 1, max = 49) int groupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        // Create data with a single group smaller than k-min
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "small_group");
            record.put("value", i * 10);
            data.add(record);
        }

        AggregationResult result = service.aggregateWithKAnonymity(
                data, "group", "value", AggregationType.COUNT);

        // The small group must be suppressed
        assert result.suppressedGroups() == 1 : 
                "Small group should be suppressed. Size: " + groupSize;
        
        AggregateValue groupValue = result.results().get("small_group");
        assert groupValue != null : "Group should exist in results";
        assert groupValue.suppressed() : 
                "Group with size " + groupSize + " should be suppressed (k-min=" + K_MIN + ")";
        assert groupValue.value() == null : 
                "Suppressed group should have null value";
    }

    /**
     * Property 20: Groups with size >= k must NOT be suppressed.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.1, 229.3**
     */
    @Property(tries = 100)
    void property20_largeGroupsMustNotBeSuppressed(
            @ForAll @IntRange(min = 50, max = 200) int groupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        // Create data with a single group >= k-min
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "large_group");
            record.put("value", i * 10);
            data.add(record);
        }

        AggregationResult result = service.aggregateWithKAnonymity(
                data, "group", "value", AggregationType.COUNT);

        // The large group must NOT be suppressed
        assert result.suppressedGroups() == 0 : 
                "Large group should not be suppressed. Size: " + groupSize;
        
        AggregateValue groupValue = result.results().get("large_group");
        assert groupValue != null : "Group should exist in results";
        assert !groupValue.suppressed() : 
                "Group with size " + groupSize + " should NOT be suppressed (k-min=" + K_MIN + ")";
        assert groupValue.value() != null : 
                "Non-suppressed group should have a value";
    }

    /**
     * Property: Mixed groups - only small groups are suppressed.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.1, 229.2**
     */
    @Property(tries = 100)
    void property20_mixedGroupsCorrectlySuppressed(
            @ForAll @IntRange(min = 1, max = 49) int smallGroupSize,
            @ForAll @IntRange(min = 50, max = 150) int largeGroupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        List<Map<String, Object>> data = new ArrayList<>();
        
        // Add small group
        for (int i = 0; i < smallGroupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "small");
            record.put("value", i * 10);
            data.add(record);
        }
        
        // Add large group
        for (int i = 0; i < largeGroupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "large");
            record.put("value", i * 20);
            data.add(record);
        }

        AggregationResult result = service.aggregateWithKAnonymity(
                data, "group", "value", AggregationType.SUM);

        // Exactly one group should be suppressed
        assert result.suppressedGroups() == 1 : 
                "Exactly one group should be suppressed";
        assert result.totalGroups() == 2 : 
                "Should have 2 total groups";
        
        // Small group suppressed
        AggregateValue smallValue = result.results().get("small");
        assert smallValue.suppressed() : "Small group should be suppressed";
        
        // Large group not suppressed
        AggregateValue largeValue = result.results().get("large");
        assert !largeValue.suppressed() : "Large group should not be suppressed";
        assert largeValue.value() != null : "Large group should have computed value";
    }


    // ==================== Differential Privacy Properties ====================

    /**
     * Property: Differential privacy adds noise to results.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.3**
     */
    @Property(tries = 50)
    void differentialPrivacy_addsNoiseToResults(
            @ForAll @IntRange(min = 50, max = 100) int groupSize,
            @ForAll @DoubleRange(min = 0.1, max = 2.0) double epsilon) {
        
        PrivacySafeAggregationService service = createService();
        
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "test_group");
            record.put("value", i * 10);
            data.add(record);
        }

        PrivacyBudget budget = new PrivacyBudget(UUID.randomUUID(), 10.0);
        
        DifferentialPrivacyResult result = service.aggregateWithDifferentialPrivacy(
                data, "group", "value", AggregationType.COUNT,
                epsilon, 1.0, budget);

        assert result.isSuccess() : "DP aggregation should succeed";
        
        DPAggregateValue dpValue = result.results().get("test_group");
        assert dpValue != null : "Group should exist in results";
        assert !dpValue.suppressed() : "Large group should not be suppressed";
        assert dpValue.noiseAdded() != null : "Noise should be added";
    }

    /**
     * Property: Privacy budget is consumed correctly.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.4, 229.5**
     */
    @Property(tries = 50)
    void privacyBudget_consumedCorrectly(
            @ForAll @DoubleRange(min = 0.1, max = 1.0) double epsilon1,
            @ForAll @DoubleRange(min = 0.1, max = 1.0) double epsilon2) {
        
        double totalBudget = 5.0;
        PrivacyBudget budget = new PrivacyBudget(UUID.randomUUID(), totalBudget);

        // First consumption
        budget.consume(epsilon1);
        double expectedRemaining1 = totalBudget - epsilon1;
        assert Math.abs(budget.getRemaining() - expectedRemaining1) < 0.0001 : 
                "Budget should be correctly consumed after first query";

        // Second consumption
        budget.consume(epsilon2);
        double expectedRemaining2 = totalBudget - epsilon1 - epsilon2;
        assert Math.abs(budget.getRemaining() - expectedRemaining2) < 0.0001 : 
                "Budget should be correctly consumed after second query";
    }

    /**
     * Property: Exhausted budget blocks further queries.
     * **Feature: yachaq-platform, Property 20: Privacy-Safe Aggregation**
     * **Validates: Requirements 229.5**
     */
    @Property(tries = 50)
    void privacyBudget_exhaustedBlocksQueries(
            @ForAll @IntRange(min = 50, max = 100) int groupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "test_group");
            record.put("value", i * 10);
            data.add(record);
        }

        // Create budget that's already exhausted
        PrivacyBudget budget = new PrivacyBudget(UUID.randomUUID(), 1.0);
        budget.consume(1.0); // Exhaust the budget

        DifferentialPrivacyResult result = service.aggregateWithDifferentialPrivacy(
                data, "group", "value", AggregationType.COUNT,
                0.5, 1.0, budget);

        assert result.status() == DPStatus.BUDGET_EXHAUSTED : 
                "Query should be blocked when budget is exhausted";
        assert result.results().isEmpty() : 
                "No results should be returned when budget is exhausted";
    }

    // ==================== Aggregation Correctness Properties ====================

    /**
     * Property: COUNT aggregation returns correct count for non-suppressed groups.
     */
    @Property(tries = 100)
    void countAggregation_returnsCorrectCount(
            @ForAll @IntRange(min = 50, max = 200) int groupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "test");
            record.put("value", i);
            data.add(record);
        }

        AggregationResult result = service.aggregateWithKAnonymity(
                data, "group", "value", AggregationType.COUNT);

        AggregateValue value = result.results().get("test");
        assert !value.suppressed() : "Group should not be suppressed";
        assert value.value().intValue() == groupSize : 
                "COUNT should equal group size. Expected: " + groupSize + ", Got: " + value.value();
    }

    /**
     * Property: SUM aggregation returns correct sum for non-suppressed groups.
     */
    @Property(tries = 100)
    void sumAggregation_returnsCorrectSum(
            @ForAll @Size(min = 50, max = 100) List<@IntRange(min = 0, max = 100) Integer> values) {
        
        PrivacySafeAggregationService service = createService();
        
        List<Map<String, Object>> data = new ArrayList<>();
        int expectedSum = 0;
        for (Integer val : values) {
            Map<String, Object> record = new HashMap<>();
            record.put("group", "test");
            record.put("value", val);
            data.add(record);
            expectedSum += val;
        }

        AggregationResult result = service.aggregateWithKAnonymity(
                data, "group", "value", AggregationType.SUM);

        AggregateValue value = result.results().get("test");
        assert !value.suppressed() : "Group should not be suppressed";
        assert value.value().intValue() == expectedSum : 
                "SUM should be correct. Expected: " + expectedSum + ", Got: " + value.value();
    }

    /**
     * Property: meetsKAnonymity correctly identifies valid/invalid group sizes.
     */
    @Property(tries = 100)
    void meetsKAnonymity_correctlyIdentifiesThreshold(
            @ForAll @IntRange(min = 0, max = 200) int groupSize) {
        
        PrivacySafeAggregationService service = createService();
        
        boolean expected = groupSize >= K_MIN;
        boolean actual = service.meetsKAnonymity(groupSize);
        
        assert actual == expected : 
                "meetsKAnonymity(" + groupSize + ") should be " + expected + " but was " + actual;
    }

    /**
     * Property: Empty data returns empty result.
     */
    @Property(tries = 10)
    void emptyData_returnsEmptyResult() {
        PrivacySafeAggregationService service = createService();
        List<Map<String, Object>> emptyData = Collections.emptyList();

        AggregationResult result = service.aggregateWithKAnonymity(
                emptyData, "group", "value", AggregationType.COUNT);

        assert result.status() == AggregationStatus.EMPTY : 
                "Empty data should return EMPTY status";
        assert result.results().isEmpty() : 
                "Empty data should return empty results";
    }
}
