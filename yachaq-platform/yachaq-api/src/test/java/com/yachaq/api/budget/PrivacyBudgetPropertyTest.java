package com.yachaq.api.budget;

import com.yachaq.api.budget.PrivacyBudgetService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for PrivacyBudgetService.
 * 
 * **Feature: yachaq-platform, Property 65: Privacy Budget Enforcement**
 * **Validates: Requirements 359.1, 359.4**
 */
class PrivacyBudgetPropertyTest {

    // ========================================================================
    // Property 1: Budget Allocation Creates Valid Budget
    // For any valid DS-requester pair, allocation creates a budget with correct values
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Budget allocation creates valid budget with correct values")
    void budgetAllocationCreatesValidBudget(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId,
            @ForAll @Positive @BigRange(min = "0.01", max = "10.0") BigDecimal epsilon) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        AllocationResult result = service.allocateBudget(dsId, requesterId, epsilon);
        
        assertThat(result.success())
            .as("Allocation should succeed for valid inputs")
            .isTrue();
        
        assertThat(result.budget()).isNotNull();
        assertThat(result.budget().dsId()).isEqualTo(dsId);
        assertThat(result.budget().requesterId()).isEqualTo(requesterId);
        assertThat(result.budget().allocated()).isEqualByComparingTo(epsilon);
        assertThat(result.budget().consumed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.budget().remaining()).isEqualByComparingTo(epsilon);
        assertThat(result.budget().status()).isEqualTo(BudgetStatus.ACTIVE);
    }

    // ========================================================================
    // Property 2: Duplicate Allocation Fails
    // For any DS-requester pair, second allocation must fail
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Duplicate allocation fails")
    void duplicateAllocationFails(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // First allocation
        AllocationResult first = service.allocateBudget(dsId, requesterId, BigDecimal.ONE);
        assertThat(first.success()).isTrue();
        
        // Second allocation should fail
        AllocationResult second = service.allocateBudget(dsId, requesterId, BigDecimal.ONE);
        assertThat(second.success())
            .as("Duplicate allocation must fail")
            .isFalse();
    }

    // ========================================================================
    // Property 3: Budget Consumption Decreases Remaining
    // For any valid consumption, remaining budget decreases by exact amount
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Budget consumption decreases remaining by exact amount")
    void budgetConsumptionDecreasesRemaining(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId,
            @ForAll @Positive @BigRange(min = "0.001", max = "0.05") BigDecimal epsilonCost) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate budget
        BigDecimal allocated = BigDecimal.valueOf(1.0);
        service.allocateBudget(dsId, requesterId, allocated);
        
        // Consume
        QueryConsumption consumption = new QueryConsumption(
            UUID.randomUUID().toString(),
            "SELECT",
            epsilonCost
        );
        
        ConsumptionResult result = service.consumeBudget(dsId, requesterId, consumption);
        
        assertThat(result.success()).isTrue();
        assertThat(result.budget().consumed()).isEqualByComparingTo(epsilonCost);
        assertThat(result.budget().remaining())
            .isEqualByComparingTo(allocated.subtract(epsilonCost));
    }

    // ========================================================================
    // Property 4: Budget Exhaustion Blocks Queries
    // For any exhausted budget, further queries must be blocked
    // **Property 65: Privacy Budget Enforcement**
    // **Validates: Requirements 359.1, 359.4**
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Budget exhaustion blocks queries")
    void budgetExhaustionBlocksQueries(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate small budget
        BigDecimal smallBudget = BigDecimal.valueOf(0.1);
        service.allocateBudget(dsId, requesterId, smallBudget);
        
        // Exhaust budget
        QueryConsumption consumption = new QueryConsumption(
            UUID.randomUUID().toString(),
            "SELECT",
            smallBudget
        );
        service.consumeBudget(dsId, requesterId, consumption);
        
        // Check blocked
        BlockCheckResult blockResult = service.checkBlocked(dsId, requesterId, BigDecimal.valueOf(0.01));
        
        assertThat(blockResult.blocked())
            .as("Exhausted budget must block queries")
            .isTrue();
    }

    // ========================================================================
    // Property 5: Insufficient Budget Blocks Query
    // For any query requiring more than remaining, must be blocked
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Insufficient budget blocks query")
    void insufficientBudgetBlocksQuery(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate budget
        BigDecimal allocated = BigDecimal.valueOf(0.5);
        service.allocateBudget(dsId, requesterId, allocated);
        
        // Consume part of it (within per-query limit of 0.1)
        BigDecimal consumed = BigDecimal.valueOf(0.05);
        QueryConsumption consumption = new QueryConsumption(
            UUID.randomUUID().toString(),
            "SELECT",
            consumed
        );
        service.consumeBudget(dsId, requesterId, consumption);
        
        // Try to consume more than remaining
        BigDecimal remaining = allocated.subtract(consumed);
        BigDecimal tooMuch = remaining.add(BigDecimal.valueOf(0.1));
        
        BlockCheckResult blockResult = service.checkBlocked(dsId, requesterId, tooMuch);
        
        assertThat(blockResult.blocked())
            .as("Query requiring more than remaining must be blocked")
            .isTrue();
    }

    // ========================================================================
    // Property 6: Per-Query Limit Enforced
    // For any query exceeding per-query limit, consumption must fail
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Per-query limit is enforced")
    void perQueryLimitEnforced(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate large budget
        service.allocateBudget(dsId, requesterId, BigDecimal.valueOf(10.0));
        
        // Try to consume more than per-query limit (0.1)
        QueryConsumption consumption = new QueryConsumption(
            UUID.randomUUID().toString(),
            "SELECT",
            BigDecimal.valueOf(0.2) // Exceeds 0.1 limit
        );
        
        ConsumptionResult result = service.consumeBudget(dsId, requesterId, consumption);
        
        assertThat(result.success())
            .as("Query exceeding per-query limit must fail")
            .isFalse();
    }

    // ========================================================================
    // Property 7: Budget Invariant: consumed + remaining = allocated
    // For any budget state, consumed + remaining must equal allocated
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Budget invariant: consumed + remaining = allocated")
    void budgetInvariantMaintained(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId,
            @ForAll @IntRange(min = 1, max = 10) int numConsumptions) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        BigDecimal allocated = BigDecimal.valueOf(1.0);
        service.allocateBudget(dsId, requesterId, allocated);
        
        // Perform multiple small consumptions
        for (int i = 0; i < numConsumptions; i++) {
            QueryConsumption consumption = new QueryConsumption(
                UUID.randomUUID().toString(),
                "SELECT",
                BigDecimal.valueOf(0.01)
            );
            ConsumptionResult result = service.consumeBudget(dsId, requesterId, consumption);
            
            if (result.success() && result.budget() != null) {
                BigDecimal sum = result.budget().consumed().add(result.budget().remaining());
                assertThat(sum)
                    .as("consumed + remaining must equal allocated")
                    .isEqualByComparingTo(allocated);
            }
        }
    }

    // ========================================================================
    // Property 8: Deanonymization Detection Blocks High-Risk Queries
    // For any sequence of similar queries, high risk must be detected
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Deanonymization detection blocks high-risk queries")
    void deanonymizationDetectionBlocksHighRisk(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate budget
        service.allocateBudget(dsId, requesterId, BigDecimal.valueOf(10.0));
        
        // Submit many similar queries (same prefix)
        String queryPrefix = "ABCD1234";
        for (int i = 0; i < 6; i++) {
            QueryConsumption consumption = new QueryConsumption(
                queryPrefix + UUID.randomUUID().toString().substring(0, 8),
                "SELECT",
                BigDecimal.valueOf(0.01)
            );
            service.consumeBudget(dsId, requesterId, consumption);
        }
        
        // Check deanonymization risk
        DeanonymizationCheckResult result = service.checkDeanonymizationRisk(
            dsId, requesterId, queryPrefix + "newquery"
        );
        
        assertThat(result.riskLevel())
            .as("Many similar queries should trigger high risk")
            .isEqualTo(RiskLevel.HIGH);
        
        assertThat(result.allowed())
            .as("High risk queries should be blocked")
            .isFalse();
    }

    // ========================================================================
    // Property 9: No Budget Returns Blocked
    // For any DS-requester pair without budget, queries must be blocked
    // ========================================================================
    
    @Property(tries = 50)
    @Label("No budget returns blocked")
    void noBudgetReturnsBlocked(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Don't allocate budget
        BlockCheckResult result = service.checkBlocked(dsId, requesterId, BigDecimal.valueOf(0.01));
        
        assertThat(result.blocked())
            .as("No budget must result in blocked")
            .isTrue();
    }

    // ========================================================================
    // Property 10: Budget Status Transitions Correctly
    // For any budget, status transitions from ACTIVE to EXHAUSTED when remaining <= 0
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Budget status transitions correctly")
    void budgetStatusTransitionsCorrectly(
            @ForAll("validUUIDs") UUID dsId,
            @ForAll("validUUIDs") UUID requesterId) {
        
        PrivacyBudgetService service = new PrivacyBudgetService();
        
        // Allocate exact amount
        BigDecimal exactAmount = BigDecimal.valueOf(0.05);
        service.allocateBudget(dsId, requesterId, exactAmount);
        
        // Verify initial status
        Optional<PrivacyBudget> initial = service.getBudget(dsId, requesterId);
        assertThat(initial).isPresent();
        assertThat(initial.get().status()).isEqualTo(BudgetStatus.ACTIVE);
        
        // Consume exact amount
        QueryConsumption consumption = new QueryConsumption(
            UUID.randomUUID().toString(),
            "SELECT",
            exactAmount
        );
        ConsumptionResult result = service.consumeBudget(dsId, requesterId, consumption);
        
        assertThat(result.success()).isTrue();
        assertThat(result.budget().status())
            .as("Status must be EXHAUSTED when remaining <= 0")
            .isEqualTo(BudgetStatus.EXHAUSTED);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<UUID> validUUIDs() {
        return Arbitraries.create(UUID::randomUUID);
    }
}
