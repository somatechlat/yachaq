package com.yachaq.api.privacy;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.*;

/**
 * Privacy-Safe Aggregation Service - Enforces k-anonymity and differential privacy.
 * 
 * Property 20: Privacy-Safe Aggregation
 * *For any* aggregation query, if the group size is less than 50 (k < 50), 
 * the result must be suppressed or generalized to prevent identification.
 * 
 * Validates: Requirements 229.1, 229.2, 229.3, 229.4, 229.5
 * 
 * Key features:
 * - k-anonymity enforcement (k ≥ 50 for aggregates)
 * - Differential privacy with Laplace noise
 * - Privacy budget tracking and enforcement
 * - Group suppression and generalization
 */
@Service
public class PrivacySafeAggregationService {

    private static final int DEFAULT_K_MIN = 50;
    private static final double DEFAULT_EPSILON = 1.0;
    private static final double DEFAULT_MAX_PRIVACY_BUDGET = 10.0;

    private final AuditService auditService;
    private final SecureRandom secureRandom;
    private final int kMin;
    private final double defaultEpsilon;
    private final double maxPrivacyBudget;

    public PrivacySafeAggregationService(AuditService auditService) {
        this(auditService, DEFAULT_K_MIN, DEFAULT_EPSILON, DEFAULT_MAX_PRIVACY_BUDGET);
    }

    public PrivacySafeAggregationService(
            AuditService auditService,
            @Value("${yachaq.privacy.k-min:50}") int kMin,
            @Value("${yachaq.privacy.default-epsilon:1.0}") double defaultEpsilon,
            @Value("${yachaq.privacy.max-privacy-budget:10.0}") double maxPrivacyBudget) {
        this.auditService = auditService;
        this.secureRandom = new SecureRandom();
        this.kMin = kMin > 0 ? kMin : DEFAULT_K_MIN;
        this.defaultEpsilon = defaultEpsilon > 0 ? defaultEpsilon : DEFAULT_EPSILON;
        this.maxPrivacyBudget = maxPrivacyBudget > 0 ? maxPrivacyBudget : DEFAULT_MAX_PRIVACY_BUDGET;
    }


    /**
     * Aggregates data with k-anonymity enforcement.
     * Requirement 229.1: Enforce minimum group size (k ≥ 50) for aggregates.
     * Requirement 229.2: Suppress or generalize small groups.
     * 
     * @param data List of data records to aggregate
     * @param groupByField Field to group by
     * @param aggregateField Field to aggregate
     * @param aggregationType Type of aggregation (COUNT, SUM, AVG, etc.)
     * @return Aggregation result with k-anonymity enforced
     */
    @Transactional
    public AggregationResult aggregateWithKAnonymity(
            List<Map<String, Object>> data,
            String groupByField,
            String aggregateField,
            AggregationType aggregationType) {
        
        if (data == null || data.isEmpty()) {
            return new AggregationResult(
                    Collections.emptyMap(),
                    0,
                    0,
                    kMin,
                    AggregationStatus.EMPTY,
                    "No data to aggregate");
        }

        // Group data by the specified field
        Map<Object, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> record : data) {
            Object groupKey = record.get(groupByField);
            if (groupKey == null) {
                groupKey = "__NULL__";
            }
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(record);
        }

        // Apply k-anonymity: suppress groups with fewer than k members
        Map<Object, AggregateValue> results = new LinkedHashMap<>();
        int suppressedGroups = 0;
        int totalGroups = groups.size();

        for (Map.Entry<Object, List<Map<String, Object>>> entry : groups.entrySet()) {
            Object groupKey = entry.getKey();
            List<Map<String, Object>> groupData = entry.getValue();
            int groupSize = groupData.size();

            if (groupSize < kMin) {
                // Suppress this group - k-anonymity violation
                suppressedGroups++;
                results.put(groupKey, new AggregateValue(
                        null,
                        groupSize,
                        true,
                        "Group suppressed: size " + groupSize + " < k-min " + kMin));
            } else {
                // Compute aggregate for this group
                BigDecimal aggregateValue = computeAggregate(groupData, aggregateField, aggregationType);
                results.put(groupKey, new AggregateValue(
                        aggregateValue,
                        groupSize,
                        false,
                        null));
            }
        }

        AggregationStatus status = suppressedGroups == 0 
                ? AggregationStatus.COMPLETE 
                : (suppressedGroups == totalGroups 
                        ? AggregationStatus.ALL_SUPPRESSED 
                        : AggregationStatus.PARTIAL);

        return new AggregationResult(
                results,
                totalGroups,
                suppressedGroups,
                kMin,
                status,
                null);
    }

    /**
     * Aggregates data with differential privacy.
     * Requirement 229.3: Calibrate noise to output sensitivity.
     * Requirement 229.4: Track and enforce privacy budget limits.
     * 
     * @param data List of data records to aggregate
     * @param groupByField Field to group by
     * @param aggregateField Field to aggregate
     * @param aggregationType Type of aggregation
     * @param epsilon Privacy parameter (smaller = more privacy)
     * @param sensitivity Query sensitivity (max change from one record)
     * @param privacyBudget Current privacy budget state
     * @return Differentially private aggregation result
     */
    @Transactional
    public DifferentialPrivacyResult aggregateWithDifferentialPrivacy(
            List<Map<String, Object>> data,
            String groupByField,
            String aggregateField,
            AggregationType aggregationType,
            double epsilon,
            double sensitivity,
            PrivacyBudget privacyBudget) {
        
        // Check privacy budget
        if (privacyBudget != null && !privacyBudget.canConsume(epsilon)) {
            return new DifferentialPrivacyResult(
                    Collections.emptyMap(),
                    epsilon,
                    sensitivity,
                    privacyBudget.getRemaining(),
                    DPStatus.BUDGET_EXHAUSTED,
                    "Privacy budget exhausted. Remaining: " + privacyBudget.getRemaining());
        }

        // First apply k-anonymity
        AggregationResult kAnonResult = aggregateWithKAnonymity(
                data, groupByField, aggregateField, aggregationType);

        // Add Laplace noise to non-suppressed results
        Map<Object, DPAggregateValue> dpResults = new LinkedHashMap<>();
        
        for (Map.Entry<Object, AggregateValue> entry : kAnonResult.results().entrySet()) {
            Object groupKey = entry.getKey();
            AggregateValue value = entry.getValue();

            if (value.suppressed()) {
                dpResults.put(groupKey, new DPAggregateValue(
                        null,
                        null,
                        value.groupSize(),
                        true,
                        value.suppressionReason()));
            } else {
                // Add Laplace noise
                double noise = generateLaplaceNoise(sensitivity, epsilon);
                BigDecimal noisyValue = value.value().add(BigDecimal.valueOf(noise))
                        .setScale(2, RoundingMode.HALF_UP);
                
                dpResults.put(groupKey, new DPAggregateValue(
                        noisyValue,
                        noise,
                        value.groupSize(),
                        false,
                        null));
            }
        }

        // Consume privacy budget
        double remaining = maxPrivacyBudget;
        if (privacyBudget != null) {
            privacyBudget.consume(epsilon);
            remaining = privacyBudget.getRemaining();
        }

        return new DifferentialPrivacyResult(
                dpResults,
                epsilon,
                sensitivity,
                remaining,
                DPStatus.SUCCESS,
                null);
    }


    /**
     * Checks if a group size meets k-anonymity threshold.
     * Property 20: If group size < 50, result must be suppressed.
     * 
     * @param groupSize Size of the group
     * @return true if group meets k-anonymity threshold
     */
    public boolean meetsKAnonymity(int groupSize) {
        return groupSize >= kMin;
    }

    /**
     * Checks if a group size meets k-anonymity threshold with custom k.
     * 
     * @param groupSize Size of the group
     * @param k Custom k-anonymity threshold
     * @return true if group meets k-anonymity threshold
     */
    public boolean meetsKAnonymity(int groupSize, int k) {
        return groupSize >= k;
    }

    /**
     * Gets the current k-min threshold.
     */
    public int getKMin() {
        return kMin;
    }

    /**
     * Generalizes a value to a broader category for privacy protection.
     * Used when suppression is not desired but k-anonymity must be maintained.
     * 
     * @param value The value to generalize
     * @param generalizationLevel Level of generalization (higher = more general)
     * @return Generalized value
     */
    public Object generalizeValue(Object value, int generalizationLevel) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return generalizeNumeric((Number) value, generalizationLevel);
        } else if (value instanceof String) {
            return generalizeString((String) value, generalizationLevel);
        }

        return "*"; // Default generalization
    }

    private Object generalizeNumeric(Number value, int level) {
        double d = value.doubleValue();
        // Round to nearest power of 10 based on level
        double factor = Math.pow(10, level);
        return Math.round(d / factor) * factor;
    }

    private String generalizeString(String value, int level) {
        if (value.isEmpty()) {
            return "*";
        }
        // Truncate string based on level
        int keepChars = Math.max(1, value.length() - level);
        return value.substring(0, keepChars) + "*".repeat(level);
    }

    /**
     * Generates Laplace noise for differential privacy.
     * The Laplace mechanism adds noise drawn from Laplace(0, sensitivity/epsilon).
     * 
     * @param sensitivity Query sensitivity
     * @param epsilon Privacy parameter
     * @return Random noise value
     */
    private double generateLaplaceNoise(double sensitivity, double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("Epsilon must be positive");
        }
        
        double scale = sensitivity / epsilon;
        
        // Generate Laplace noise using inverse CDF method
        double u = secureRandom.nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }

    /**
     * Computes aggregate value for a group.
     */
    private BigDecimal computeAggregate(
            List<Map<String, Object>> groupData,
            String aggregateField,
            AggregationType aggregationType) {
        
        if (aggregationType == AggregationType.COUNT) {
            return BigDecimal.valueOf(groupData.size());
        }

        List<BigDecimal> values = new ArrayList<>();
        for (Map<String, Object> record : groupData) {
            Object val = record.get(aggregateField);
            if (val instanceof Number) {
                values.add(BigDecimal.valueOf(((Number) val).doubleValue()));
            }
        }

        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return switch (aggregationType) {
            case SUM -> values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
            case MIN -> values.stream()
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            case MAX -> values.stream()
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            case COUNT -> BigDecimal.valueOf(groupData.size());
        };
    }

    // ==================== Enums and Records ====================

    public enum AggregationType {
        COUNT, SUM, AVG, MIN, MAX
    }

    public enum AggregationStatus {
        COMPLETE,       // All groups met k-anonymity
        PARTIAL,        // Some groups suppressed
        ALL_SUPPRESSED, // All groups suppressed
        EMPTY           // No data to aggregate
    }

    public enum DPStatus {
        SUCCESS,
        BUDGET_EXHAUSTED,
        INVALID_PARAMETERS,
        ERROR
    }

    public record AggregateValue(
            BigDecimal value,
            int groupSize,
            boolean suppressed,
            String suppressionReason) {}

    public record AggregationResult(
            Map<Object, AggregateValue> results,
            int totalGroups,
            int suppressedGroups,
            int kMinUsed,
            AggregationStatus status,
            String errorMessage) {
        
        public boolean hasSuppressions() {
            return suppressedGroups > 0;
        }
        
        public boolean isComplete() {
            return status == AggregationStatus.COMPLETE;
        }
    }

    public record DPAggregateValue(
            BigDecimal noisyValue,
            Double noiseAdded,
            int groupSize,
            boolean suppressed,
            String suppressionReason) {}

    public record DifferentialPrivacyResult(
            Map<Object, DPAggregateValue> results,
            double epsilonUsed,
            double sensitivity,
            double remainingBudget,
            DPStatus status,
            String errorMessage) {
        
        public boolean isSuccess() {
            return status == DPStatus.SUCCESS;
        }
    }


    /**
     * Privacy Budget tracker for differential privacy.
     * Requirement 229.5: Track and enforce privacy budget limits.
     */
    public static class PrivacyBudget {
        private final UUID queryId;
        private final double totalBudget;
        private double consumed;
        private final List<BudgetConsumption> history;

        public PrivacyBudget(UUID queryId, double totalBudget) {
            this.queryId = queryId;
            this.totalBudget = totalBudget;
            this.consumed = 0.0;
            this.history = new ArrayList<>();
        }

        public boolean canConsume(double epsilon) {
            return (consumed + epsilon) <= totalBudget;
        }

        public void consume(double epsilon) {
            if (!canConsume(epsilon)) {
                throw new PrivacyBudgetExhaustedException(
                        "Cannot consume " + epsilon + ". Remaining: " + getRemaining());
            }
            consumed += epsilon;
            history.add(new BudgetConsumption(epsilon, java.time.Instant.now()));
        }

        public double getRemaining() {
            return totalBudget - consumed;
        }

        public double getConsumed() {
            return consumed;
        }

        public double getTotalBudget() {
            return totalBudget;
        }

        public UUID getQueryId() {
            return queryId;
        }

        public List<BudgetConsumption> getHistory() {
            return Collections.unmodifiableList(history);
        }

        public boolean isExhausted() {
            return consumed >= totalBudget;
        }

        public record BudgetConsumption(double epsilon, java.time.Instant timestamp) {}
    }

    public static class PrivacyBudgetExhaustedException extends RuntimeException {
        public PrivacyBudgetExhaustedException(String message) {
            super(message);
        }
    }

    public static class KAnonymityViolationException extends RuntimeException {
        private final int groupSize;
        private final int kMin;

        public KAnonymityViolationException(int groupSize, int kMin) {
            super("K-anonymity violation: group size " + groupSize + " < k-min " + kMin);
            this.groupSize = groupSize;
            this.kMin = kMin;
        }

        public int getGroupSize() { return groupSize; }
        public int getKMin() { return kMin; }
    }
}
