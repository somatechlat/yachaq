package com.yachaq.api.budget;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Privacy Budget Service for managing privacy budgets per DS-requester pair.
 * Provides budget allocation, consumption tracking, and deanonymization detection.
 * 
 * Security: Prevents repeated queries aimed at deanonymization.
 * Performance: Budget lookups are O(1).
 * 
 * Validates: Requirements 359.1, 359.2, 359.3, 359.4
 */
@Service
public class PrivacyBudgetService {

    // Budget storage: key = dsId:requesterId
    private final Map<String, PrivacyBudget> budgets = new ConcurrentHashMap<>();
    private final Map<String, List<QueryRecord>> queryHistory = new ConcurrentHashMap<>();

    // Configuration
    private static final BigDecimal DEFAULT_EPSILON = BigDecimal.valueOf(1.0);
    private static final BigDecimal MAX_EPSILON_PER_QUERY = BigDecimal.valueOf(0.1);
    private static final int QUERY_WINDOW_HOURS = 24;
    private static final int MAX_SIMILAR_QUERIES = 5;


    // ==================== Task 107.1: Budget Allocation ====================

    /**
     * Allocates privacy budget for a DS-requester pair.
     * Requirement 359.1: Allocate privacy budget per DS-requester pair.
     */
    @Transactional
    public AllocationResult allocateBudget(UUID dsId, UUID requesterId, BigDecimal epsilon) {
        Objects.requireNonNull(dsId, "DS ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");

        String key = budgetKey(dsId, requesterId);
        
        if (budgets.containsKey(key)) {
            return new AllocationResult(false, "Budget already allocated for this pair", null);
        }

        BigDecimal allocatedEpsilon = epsilon != null ? epsilon : DEFAULT_EPSILON;
        if (allocatedEpsilon.compareTo(BigDecimal.ZERO) <= 0) {
            return new AllocationResult(false, "Epsilon must be positive", null);
        }

        PrivacyBudget budget = new PrivacyBudget(
                key,
                dsId,
                requesterId,
                allocatedEpsilon,
                BigDecimal.ZERO,
                allocatedEpsilon,
                Instant.now(),
                null,
                BudgetStatus.ACTIVE
        );

        budgets.put(key, budget);
        queryHistory.put(key, new ArrayList<>());

        return new AllocationResult(true, "Budget allocated successfully", budget);
    }

    /**
     * Gets current budget for a DS-requester pair.
     */
    public Optional<PrivacyBudget> getBudget(UUID dsId, UUID requesterId) {
        return Optional.ofNullable(budgets.get(budgetKey(dsId, requesterId)));
    }

    private String budgetKey(UUID dsId, UUID requesterId) {
        return dsId.toString() + ":" + requesterId.toString();
    }

    // ==================== Task 107.2: Budget Consumption ====================

    /**
     * Consumes privacy budget for a query.
     * Requirement 359.1: Track budget consumption per query.
     */
    @Transactional
    public ConsumptionResult consumeBudget(UUID dsId, UUID requesterId, QueryConsumption consumption) {
        Objects.requireNonNull(dsId, "DS ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(consumption, "Consumption cannot be null");

        String key = budgetKey(dsId, requesterId);
        PrivacyBudget budget = budgets.get(key);

        if (budget == null) {
            return new ConsumptionResult(false, "No budget allocated", null, null);
        }

        if (budget.status() == BudgetStatus.EXHAUSTED) {
            return new ConsumptionResult(false, "Budget exhausted", budget, null);
        }

        // Check if consumption exceeds remaining budget
        BigDecimal epsilonCost = consumption.epsilonCost();
        if (epsilonCost.compareTo(budget.remaining()) > 0) {
            return new ConsumptionResult(false, 
                    "Insufficient budget: requested " + epsilonCost + ", remaining " + budget.remaining(),
                    budget, null);
        }

        // Check per-query limit
        if (epsilonCost.compareTo(MAX_EPSILON_PER_QUERY) > 0) {
            return new ConsumptionResult(false,
                    "Query exceeds per-query limit: " + epsilonCost + " > " + MAX_EPSILON_PER_QUERY,
                    budget, null);
        }

        // Record query
        QueryRecord record = new QueryRecord(
                UUID.randomUUID().toString(),
                consumption.queryHash(),
                consumption.queryType(),
                epsilonCost,
                Instant.now()
        );
        queryHistory.computeIfAbsent(key, k -> new ArrayList<>()).add(record);

        // Update budget
        BigDecimal newConsumed = budget.consumed().add(epsilonCost);
        BigDecimal newRemaining = budget.allocated().subtract(newConsumed);
        BudgetStatus newStatus = newRemaining.compareTo(BigDecimal.ZERO) <= 0 
                ? BudgetStatus.EXHAUSTED : BudgetStatus.ACTIVE;

        PrivacyBudget updated = new PrivacyBudget(
                budget.id(),
                budget.dsId(),
                budget.requesterId(),
                budget.allocated(),
                newConsumed,
                newRemaining,
                budget.createdAt(),
                Instant.now(),
                newStatus
        );

        budgets.put(key, updated);

        return new ConsumptionResult(true, "Budget consumed successfully", updated, record);
    }

    // ==================== Task 107.3: Deanonymization Detection ====================

    /**
     * Detects repeated queries aimed at deanonymization.
     * Requirement 359.3: Detect repeated queries aimed at deanonymization.
     */
    public DeanonymizationCheckResult checkDeanonymizationRisk(UUID dsId, UUID requesterId, 
                                                                String queryHash) {
        Objects.requireNonNull(dsId, "DS ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(queryHash, "Query hash cannot be null");

        String key = budgetKey(dsId, requesterId);
        List<QueryRecord> history = queryHistory.getOrDefault(key, List.of());

        // Filter to recent queries
        Instant windowStart = Instant.now().minus(QUERY_WINDOW_HOURS, ChronoUnit.HOURS);
        List<QueryRecord> recentQueries = history.stream()
                .filter(q -> q.timestamp().isAfter(windowStart))
                .toList();

        // Count similar queries
        long similarCount = recentQueries.stream()
                .filter(q -> isSimilarQuery(q.queryHash(), queryHash))
                .count();

        List<String> riskFactors = new ArrayList<>();
        RiskLevel riskLevel = RiskLevel.LOW;

        if (similarCount >= MAX_SIMILAR_QUERIES) {
            riskFactors.add("Too many similar queries: " + similarCount);
            riskLevel = RiskLevel.HIGH;
        } else if (similarCount >= MAX_SIMILAR_QUERIES / 2) {
            riskFactors.add("Elevated similar query count: " + similarCount);
            riskLevel = RiskLevel.MEDIUM;
        }

        // Check for progressive narrowing pattern
        if (detectProgressiveNarrowing(recentQueries)) {
            riskFactors.add("Progressive narrowing pattern detected");
            riskLevel = RiskLevel.HIGH;
        }

        // Check for high-frequency queries
        if (recentQueries.size() > 20) {
            riskFactors.add("High query frequency: " + recentQueries.size() + " in " + QUERY_WINDOW_HOURS + "h");
            if (riskLevel == RiskLevel.LOW) riskLevel = RiskLevel.MEDIUM;
        }

        boolean blocked = riskLevel == RiskLevel.HIGH;

        return new DeanonymizationCheckResult(
                !blocked,
                riskLevel,
                riskFactors,
                similarCount,
                recentQueries.size()
        );
    }

    private boolean isSimilarQuery(String hash1, String hash2) {
        // Simple similarity: same prefix (first 8 chars)
        if (hash1 == null || hash2 == null) return false;
        if (hash1.length() < 8 || hash2.length() < 8) return hash1.equals(hash2);
        return hash1.substring(0, 8).equals(hash2.substring(0, 8));
    }

    private boolean detectProgressiveNarrowing(List<QueryRecord> queries) {
        if (queries.size() < 3) return false;
        
        // Check if epsilon costs are increasing (more specific queries)
        BigDecimal prevCost = BigDecimal.ZERO;
        int increasingCount = 0;
        
        for (QueryRecord q : queries) {
            if (q.epsilonCost().compareTo(prevCost) > 0) {
                increasingCount++;
            }
            prevCost = q.epsilonCost();
        }
        
        return increasingCount >= queries.size() * 0.7;
    }

    // ==================== Task 107.4: Budget Exhaustion Blocking ====================

    /**
     * Checks if a query should be blocked due to budget exhaustion.
     * Requirement 359.4: Block queries when budget exhausted.
     */
    public BlockCheckResult checkBlocked(UUID dsId, UUID requesterId, BigDecimal requiredEpsilon) {
        String key = budgetKey(dsId, requesterId);
        PrivacyBudget budget = budgets.get(key);

        if (budget == null) {
            return new BlockCheckResult(true, "No budget allocated", null);
        }

        if (budget.status() == BudgetStatus.EXHAUSTED) {
            return new BlockCheckResult(true, "Budget exhausted", budget.remaining());
        }

        if (requiredEpsilon != null && requiredEpsilon.compareTo(budget.remaining()) > 0) {
            return new BlockCheckResult(true, 
                    "Insufficient budget: need " + requiredEpsilon + ", have " + budget.remaining(),
                    budget.remaining());
        }

        return new BlockCheckResult(false, "Query allowed", budget.remaining());
    }


    // ==================== Inner Types ====================

    public record PrivacyBudget(
            String id,
            UUID dsId,
            UUID requesterId,
            BigDecimal allocated,
            BigDecimal consumed,
            BigDecimal remaining,
            Instant createdAt,
            Instant lastUpdated,
            BudgetStatus status
    ) {}

    public enum BudgetStatus { ACTIVE, EXHAUSTED, SUSPENDED }

    public record AllocationResult(boolean success, String message, PrivacyBudget budget) {}

    public record QueryConsumption(
            String queryHash,
            String queryType,
            BigDecimal epsilonCost
    ) {}

    public record QueryRecord(
            String id,
            String queryHash,
            String queryType,
            BigDecimal epsilonCost,
            Instant timestamp
    ) {}

    public record ConsumptionResult(
            boolean success,
            String message,
            PrivacyBudget budget,
            QueryRecord record
    ) {}

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public record DeanonymizationCheckResult(
            boolean allowed,
            RiskLevel riskLevel,
            List<String> riskFactors,
            long similarQueryCount,
            int totalRecentQueries
    ) {}

    public record BlockCheckResult(
            boolean blocked,
            String reason,
            BigDecimal remainingBudget
    ) {}
}
