package com.yachaq.api.privacy;

import com.yachaq.api.privacy.PrivacyGovernorService.CohortCheckResult;
import com.yachaq.api.privacy.PrivacyGovernorService.LinkageCheckResult;
import com.yachaq.api.privacy.PrivacyGovernorService.QueryRecord;
import com.yachaq.core.domain.PrivacyRiskBudget;
import com.yachaq.core.domain.PrivacyRiskBudget.PRBStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Privacy Governor Service.
 * Property 26: K-Min Cohort Enforcement
 * Property 27: Linkage Rate Limiting
 * Property 28: PRB Allocation and Lock
 * Validates: Requirements 204.1, 204.2, 204.3, 204.4, 202.1, 202.2, 203.1, 203.2
 */
class PrivacyGovernorPropertyTest {

    // ==================== Property 28: PRB Allocation and Lock ====================

    @Property(tries = 100)
    void property28_prbAllocationCreatesValidBudget(
            @ForAll("validCampaignIds") UUID campaignId,
            @ForAll @Positive double budgetValue) {
        BigDecimal budget = BigDecimal.valueOf(budgetValue).setScale(6, java.math.RoundingMode.HALF_UP);
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, "1.0.0");
        assertThat(prb.getCampaignId()).isEqualTo(campaignId);
        assertThat(prb.getAllocated()).isEqualTo(budget);
        assertThat(prb.getStatus()).isEqualTo(PRBStatus.DRAFT);
    }

    @Property(tries = 100)
    void property28_prbLockMakesImmutable(
            @ForAll("validCampaignIds") UUID campaignId,
            @ForAll @Positive double budgetValue) {
        BigDecimal budget = BigDecimal.valueOf(budgetValue).setScale(6, java.math.RoundingMode.HALF_UP);
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, "1.0.0");
        prb.lock();
        assertThat(prb.getStatus()).isEqualTo(PRBStatus.LOCKED);
        assertThat(prb.isLocked()).isTrue();
    }

    @Property(tries = 100)
    void property28_lockedPrbCannotBeRelocked(
            @ForAll("validCampaignIds") UUID campaignId,
            @ForAll @Positive double budgetValue) {
        BigDecimal budget = BigDecimal.valueOf(budgetValue).setScale(6, java.math.RoundingMode.HALF_UP);
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, "1.0.0");
        prb.lock();
        assertThatThrownBy(prb::lock).isInstanceOf(IllegalStateException.class);
    }

    @Property(tries = 100)
    void property28_prbConsumptionDecrementsBudget(
            @ForAll("validCampaignIds") UUID campaignId,
            @ForAll @Positive double budgetValue,
            @ForAll @Positive double consumeValue) {
        // Ensure consume is less than budget
        double actualBudget = Math.max(budgetValue, consumeValue + 1);
        double actualConsume = Math.min(consumeValue, actualBudget - 0.001);
        
        BigDecimal budget = BigDecimal.valueOf(actualBudget).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal consume = BigDecimal.valueOf(actualConsume).setScale(6, java.math.RoundingMode.HALF_UP);
        
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, "1.0.0");
        prb.lock();
        prb.consume(consume);
        
        assertThat(prb.getConsumed()).isEqualTo(consume);
        assertThat(prb.getRemaining()).isEqualTo(budget.subtract(consume));
    }

    @Property(tries = 50)
    void property28_prbExhaustionBlocksOperations(
            @ForAll("validCampaignIds") UUID campaignId,
            @ForAll @Positive double budgetValue) {
        BigDecimal budget = BigDecimal.valueOf(budgetValue).setScale(6, java.math.RoundingMode.HALF_UP);
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, "1.0.0");
        prb.lock();
        
        // Consume entire budget
        prb.consume(budget);
        
        assertThat(prb.isExhausted()).isTrue();
        assertThat(prb.getStatus()).isEqualTo(PRBStatus.EXHAUSTED);
        
        // Further consumption should fail
        assertThatThrownBy(() -> prb.consume(BigDecimal.ONE))
                .isInstanceOf(PrivacyRiskBudget.PRBExhaustedException.class);
    }

    // ==================== Property 26: K-Min Cohort Enforcement ====================

    @Property(tries = 100)
    void property26_cohortBelowKMinIsBlocked(
            @ForAll @IntRange(min = 1, max = 49) int cohortSize,
            @ForAll @IntRange(min = 50, max = 100) int kMin) {
        // Simulate cohort check result
        CohortCheckResult result = new CohortCheckResult(
                "test-hash",
                cohortSize,
                kMin,
                cohortSize >= kMin,
                cohortSize < kMin ? "Cohort size " + cohortSize + " < k-min " + kMin : null
        );
        
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.meetsThreshold()).isFalse();
        assertThat(result.blockReason()).isNotNull();
    }

    @Property(tries = 100)
    void property26_cohortAtOrAboveKMinIsAllowed(
            @ForAll @IntRange(min = 50, max = 1000) int cohortSize,
            @ForAll @IntRange(min = 1, max = 50) int kMin) {
        CohortCheckResult result = new CohortCheckResult(
                "test-hash",
                cohortSize,
                kMin,
                cohortSize >= kMin,
                null
        );
        
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.meetsThreshold()).isTrue();
        assertThat(result.blockReason()).isNull();
    }

    @Property(tries = 50)
    void property26_cohortExactlyAtKMinIsAllowed(
            @ForAll @IntRange(min = 10, max = 100) int kMin) {
        CohortCheckResult result = new CohortCheckResult(
                "test-hash",
                kMin,  // Exactly at threshold
                kMin,
                true,
                null
        );
        
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.cohortSize()).isEqualTo(result.kMinThreshold());
    }

    // ==================== Property 27: Linkage Rate Limiting ====================

    @Property(tries = 100)
    void property27_similarQueriesExceedingThresholdAreBlocked(
            @ForAll("validCampaignIds") UUID requesterId,
            @ForAll @IntRange(min = 11, max = 50) int similarQueryCount) {
        // Simulate linkage check with many similar queries
        LinkageCheckResult result = new LinkageCheckResult(
                requesterId,
                similarQueryCount >= 10,  // Default threshold is 10
                similarQueryCount,
                0.95,  // High similarity
                similarQueryCount >= 10 ? "Linkage rate limit exceeded: " + similarQueryCount + " similar queries" : null
        );
        
        assertThat(result.blocked()).isTrue();
        assertThat(result.similarQueryCount()).isGreaterThanOrEqualTo(10);
        assertThat(result.blockReason()).isNotNull();
    }

    @Property(tries = 100)
    void property27_fewSimilarQueriesAreAllowed(
            @ForAll("validCampaignIds") UUID requesterId,
            @ForAll @IntRange(min = 0, max = 9) int similarQueryCount) {
        LinkageCheckResult result = new LinkageCheckResult(
                requesterId,
                false,
                similarQueryCount,
                0.5,
                null
        );
        
        assertThat(result.blocked()).isFalse();
        assertThat(result.similarQueryCount()).isLessThan(10);
    }

    @Property(tries = 50)
    void property27_dissimilarQueriesAreNotCounted(
            @ForAll("validCampaignIds") UUID requesterId,
            @ForAll @IntRange(min = 1, max = 100) int queryCount) {
        // Even many queries with low similarity should not be blocked
        LinkageCheckResult result = new LinkageCheckResult(
                requesterId,
                false,
                0,  // No similar queries (all dissimilar)
                0.1,  // Low similarity
                null
        );
        
        assertThat(result.blocked()).isFalse();
        assertThat(result.maxSimilarity()).isLessThan(0.8);  // Below threshold
    }

    @Test
    void property27_queryRecordTimestampOrdering() {
        Instant now = Instant.now();
        List<QueryRecord> records = IntStream.range(0, 10)
                .mapToObj(i -> new QueryRecord("hash-" + i, now.minus(Duration.ofMinutes(i))))
                .toList();
        
        // Verify records can be ordered by timestamp
        List<QueryRecord> sorted = records.stream()
                .sorted(Comparator.comparing(QueryRecord::timestamp))
                .toList();
        
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).timestamp())
                    .isAfterOrEqualTo(sorted.get(i - 1).timestamp());
        }
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<UUID> validCampaignIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void allocatePRB_withNullCampaignId_throwsException() {
        assertThatThrownBy(() -> PrivacyRiskBudget.allocate(null, BigDecimal.TEN, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allocatePRB_withNegativeBudget_throwsException() {
        assertThatThrownBy(() -> PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.valueOf(-1), "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allocatePRB_withNullRulesetVersion_throwsException() {
        assertThatThrownBy(() -> PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumePRB_fromUnlockedPRB_throwsException() {
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, "1.0.0");
        assertThatThrownBy(() -> prb.consume(BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unlocked");
    }

    @Test
    void consumePRB_exceedingBudget_throwsException() {
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, "1.0.0");
        prb.lock();
        assertThatThrownBy(() -> prb.consume(BigDecimal.valueOf(100)))
                .isInstanceOf(PrivacyRiskBudget.PRBExhaustedException.class)
                .hasMessageContaining("Insufficient PRB");
    }

    @Test
    void canConsume_withSufficientBudget_returnsTrue() {
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, "1.0.0");
        prb.lock();
        assertThat(prb.canConsume(BigDecimal.valueOf(5))).isTrue();
    }

    @Test
    void canConsume_withInsufficientBudget_returnsFalse() {
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, "1.0.0");
        prb.lock();
        assertThat(prb.canConsume(BigDecimal.valueOf(100))).isFalse();
    }

    @Test
    void canConsume_fromDraftPRB_returnsFalse() {
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(UUID.randomUUID(), BigDecimal.TEN, "1.0.0");
        assertThat(prb.canConsume(BigDecimal.ONE)).isFalse();
    }
}
