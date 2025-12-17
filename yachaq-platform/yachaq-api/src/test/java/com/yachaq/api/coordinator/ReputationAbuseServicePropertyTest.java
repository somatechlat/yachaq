package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.coordinator.ReputationAbuseService.*;
import com.yachaq.core.domain.AuditReceipt;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Coordinator Reputation and Abuse Prevention Service.
 * Requirement 324: Coordinator Reputation and Abuse Prevention.
 */
class ReputationAbuseServicePropertyTest {

    private TestAuditService auditService;
    private ReputationAbuseService service;

    @BeforeEach
    void setUp() {
        initializeService();
    }

    private void initializeService() {
        if (service == null) {
            auditService = new TestAuditService();
            service = new ReputationAbuseService(auditService);
        }
    }

    private ReputationAbuseService getService() {
        if (service == null) {
            initializeService();
        }
        return service;
    }

    private TestAuditService getAuditService() {
        if (auditService == null) {
            initializeService();
        }
        return auditService;
    }


    // ==================== Requirement 324.1: Rate Limiting ====================

    @Test
    @Label("324.1: First request is always allowed")
    void firstRequestIsAlwaysAllowed() {
        UUID requesterId = UUID.randomUUID();
        RateLimitResult result = getService().checkRateLimit(requesterId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingInWindow()).isGreaterThan(0);
        assertThat(result.remainingInHour()).isGreaterThan(0);
        assertThat(result.remainingInDay()).isGreaterThan(0);
    }

    @Test
    @Label("324.1: Rate limit is enforced after threshold")
    void rateLimitIsEnforcedAfterThreshold() {
        UUID requesterId = UUID.randomUUID();

        // Make requests until limit is reached
        int allowedCount = 0;
        for (int i = 0; i < 20; i++) {
            RateLimitResult result = getService().checkRateLimit(requesterId);
            if (result.allowed()) {
                allowedCount++;
            } else {
                break;
            }
        }

        // Should have been rate limited before 20 requests
        assertThat(allowedCount).isLessThan(20);

        // Next request should be denied
        RateLimitResult result = getService().checkRateLimit(requesterId);
        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("limit");
        assertThat(result.retryAfter()).isNotNull();
    }

    @Property(tries = 50)
    @Label("324.1: Different requesters have independent rate limits")
    void differentRequestersHaveIndependentRateLimits() {
        UUID requester1 = UUID.randomUUID();
        UUID requester2 = UUID.randomUUID();

        // Exhaust requester1's limit
        for (int i = 0; i < 15; i++) {
            getService().checkRateLimit(requester1);
        }

        // Requester2 should still be allowed
        RateLimitResult result = getService().checkRateLimit(requester2);
        assertThat(result.allowed()).isTrue();
    }

    // ==================== Requirement 324.2: Reputation Scoring ====================

    @Test
    @Label("324.2: New requesters start with initial reputation")
    void newRequestersStartWithInitialReputation() {
        UUID requesterId = UUID.randomUUID();
        RequesterReputation reputation = getService().getOrCreateReputation(requesterId);

        assertThat(reputation.score()).isEqualTo(BigDecimal.valueOf(50));
        assertThat(reputation.getTier()).isEqualTo(ReputationTier.NEUTRAL);
    }

    @Test
    @Label("324.2: Dispute outcomes affect reputation")
    void disputeOutcomesAffectReputation() {
        UUID requesterId = UUID.randomUUID();

        // Lost dispute should decrease reputation
        RequesterReputation afterLoss = getService().recordDisputeOutcome(
                requesterId, DisputeOutcome.REQUESTER_LOST);
        assertThat(afterLoss.score()).isLessThan(BigDecimal.valueOf(50));

        // Won dispute should increase reputation
        RequesterReputation afterWin = getService().recordDisputeOutcome(
                requesterId, DisputeOutcome.REQUESTER_WON);
        assertThat(afterWin.score()).isGreaterThan(afterLoss.score());
    }

    @Test
    @Label("324.2: Reputation is bounded between 0 and 100")
    void reputationIsBounded() {
        UUID requesterId = UUID.randomUUID();

        // Many lost disputes should not go below 0
        for (int i = 0; i < 50; i++) {
            getService().recordDisputeOutcome(requesterId, DisputeOutcome.REQUESTER_LOST);
        }
        RequesterReputation lowRep = getService().getOrCreateReputation(requesterId);
        assertThat(lowRep.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Reset and test upper bound
        UUID requesterId2 = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            getService().recordDisputeOutcome(requesterId2, DisputeOutcome.REQUESTER_WON);
        }
        RequesterReputation highRep = getService().getOrCreateReputation(requesterId2);
        assertThat(highRep.score()).isLessThanOrEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    @Label("324.2: Reputation history is maintained")
    void reputationHistoryIsMaintained() {
        UUID requesterId = UUID.randomUUID();

        getService().recordDisputeOutcome(requesterId, DisputeOutcome.REQUESTER_WON);
        getService().recordDisputeOutcome(requesterId, DisputeOutcome.REQUESTER_LOST);
        getService().recordSuccessfulRequest(requesterId);

        RequesterReputation reputation = getService().getOrCreateReputation(requesterId);
        assertThat(reputation.history()).hasSize(3);
    }


    // ==================== Requirement 324.3: Abuse Signal Aggregation ====================

    @Test
    @Label("324.3: Abuse signals are aggregated without identity")
    void abuseSignalsAreAggregatedWithoutIdentity() {
        UUID requesterId = UUID.randomUUID();

        // Record signals from different nodes
        for (int i = 0; i < 3; i++) {
            AbuseSignal signal = new AbuseSignal(
                    requesterId,
                    "node-ephemeral-" + i,
                    AbuseSignalType.SPAM_REQUESTS,
                    Instant.now(),
                    "Test signal"
            );
            getService().recordAbuseSignal(signal);
        }

        List<AbuseSignalAggregate> signals = getService().getAbuseSignals(requesterId);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).count().get()).isEqualTo(3);
    }

    @Test
    @Label("324.3: Duplicate signals from same node are not counted")
    void duplicateSignalsFromSameNodeAreNotCounted() {
        UUID requesterId = UUID.randomUUID();
        String sameNodeId = "node-ephemeral-same";

        // Record multiple signals from same node
        for (int i = 0; i < 5; i++) {
            AbuseSignal signal = new AbuseSignal(
                    requesterId,
                    sameNodeId,
                    AbuseSignalType.TARGETING_ATTEMPT,
                    Instant.now(),
                    "Test signal " + i
            );
            getService().recordAbuseSignal(signal);
        }

        List<AbuseSignalAggregate> signals = getService().getAbuseSignals(requesterId);
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).count().get()).isEqualTo(1); // Only counted once
    }

    @Test
    @Label("324.3: Abuse signals affect reputation after threshold")
    void abuseSignalsAffectReputationAfterThreshold() {
        UUID requesterId = UUID.randomUUID();
        BigDecimal initialScore = getService().getOrCreateReputation(requesterId).score();

        // Record signals from 5 different nodes (threshold)
        for (int i = 0; i < 5; i++) {
            AbuseSignal signal = new AbuseSignal(
                    requesterId,
                    "node-ephemeral-" + i,
                    AbuseSignalType.EXCESSIVE_SCOPE,
                    Instant.now(),
                    "Test signal"
            );
            getService().recordAbuseSignal(signal);
        }

        BigDecimal finalScore = getService().getOrCreateReputation(requesterId).score();
        assertThat(finalScore).isLessThan(initialScore);
    }

    // ==================== Requirement 324.4: Sybil Detection ====================

    @Test
    @Label("324.4: Unique patterns are not flagged as sybil")
    void uniquePatternsAreNotFlaggedAsSybil() {
        UUID requesterId = UUID.randomUUID();
        RequestPattern pattern = new RequestPattern(
                Set.of("fitness", "health"),
                100,
                50,
                7,
                "morning"
        );

        SybilAnalysisResult result = getService().analyzeSybilPatterns(requesterId, pattern);
        assertThat(result.suspicious()).isFalse();
    }

    @Test
    @Label("324.4: Similar patterns from multiple requesters trigger sybil detection")
    void similarPatternsFromMultipleRequestersTriggerSybilDetection() {
        RequestPattern pattern = new RequestPattern(
                Set.of("location", "travel"),
                50,
                100,
                14,
                "evening"
        );

        // Submit same pattern from multiple requesters
        SybilAnalysisResult lastResult = null;
        for (int i = 0; i < 6; i++) {
            UUID requesterId = UUID.randomUUID();
            lastResult = getService().analyzeSybilPatterns(requesterId, pattern);
        }

        // Should be flagged after threshold
        assertThat(lastResult).isNotNull();
        assertThat(lastResult.suspicious()).isTrue();
        assertThat(lastResult.matchingRequesters()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @Label("324.4: Sybil detection uses behavioral fingerprints, not raw data")
    void sybilDetectionUsesBehavioralFingerprints() {
        UUID requesterId = UUID.randomUUID();
        RequestPattern pattern = new RequestPattern(
                Set.of("finance"),
                200,
                75,
                30,
                "afternoon"
        );

        SybilAnalysisResult result = getService().analyzeSybilPatterns(requesterId, pattern);

        // If suspicious, fingerprint should be a hash (not contain raw data)
        if (result.patternFingerprint() != null) {
            assertThat(result.patternFingerprint()).doesNotContain("finance");
            assertThat(result.patternFingerprint()).doesNotContain("afternoon");
            assertThat(result.patternFingerprint()).hasSize(64); // SHA-256 hex length
        }
    }

    // ==================== Requirement 324.5: Reputation-Based Limits ====================

    @Test
    @Label("324.5: Lower reputation results in stricter rate limits")
    void lowerReputationResultsInStricterRateLimits() {
        UUID lowRepRequester = UUID.randomUUID();
        UUID highRepRequester = UUID.randomUUID();

        // Lower the reputation of one requester
        for (int i = 0; i < 10; i++) {
            getService().recordDisputeOutcome(lowRepRequester, DisputeOutcome.REQUESTER_LOST);
        }

        // Increase reputation of another
        for (int i = 0; i < 10; i++) {
            getService().recordDisputeOutcome(highRepRequester, DisputeOutcome.REQUESTER_WON);
        }

        // Count how many requests each can make
        int lowRepAllowed = 0;
        int highRepAllowed = 0;

        for (int i = 0; i < 30; i++) {
            if (getService().checkRateLimit(lowRepRequester).allowed()) lowRepAllowed++;
            if (getService().checkRateLimit(highRepRequester).allowed()) highRepAllowed++;
        }

        // High reputation should allow more requests
        assertThat(highRepAllowed).isGreaterThan(lowRepAllowed);
    }

    @Test
    @Label("324.5: Reputation tiers are correctly assigned")
    void reputationTiersAreCorrectlyAssigned() {
        // Test each tier boundary
        UUID requester1 = UUID.randomUUID();
        // Start at 50 (NEUTRAL), add 35 to get to 85 (EXCELLENT)
        for (int i = 0; i < 18; i++) {
            getService().recordDisputeOutcome(requester1, DisputeOutcome.REQUESTER_WON);
        }
        assertThat(getService().getOrCreateReputation(requester1).getTier())
                .isEqualTo(ReputationTier.EXCELLENT);

        UUID requester2 = UUID.randomUUID();
        // Start at 50 (NEUTRAL), subtract 35 to get to 15 (RESTRICTED)
        for (int i = 0; i < 7; i++) {
            getService().recordDisputeOutcome(requester2, DisputeOutcome.REQUESTER_LOST);
        }
        assertThat(getService().getOrCreateReputation(requester2).getTier())
                .isEqualTo(ReputationTier.RESTRICTED);
    }


    // ==================== Additional Tests ====================

    @Test
    @Label("Targeting attempts severely impact reputation")
    void targetingAttemptsSeverelyImpactReputation() {
        UUID requesterId = UUID.randomUUID();
        BigDecimal initialScore = getService().getOrCreateReputation(requesterId).score();

        getService().recordTargetingAttempt(requesterId, "Attempted to target specific individual");

        BigDecimal finalScore = getService().getOrCreateReputation(requesterId).score();
        assertThat(finalScore).isLessThan(initialScore.subtract(BigDecimal.valueOf(5)));
    }

    @Test
    @Label("Successful requests slightly improve reputation")
    void successfulRequestsSlightlyImproveReputation() {
        UUID requesterId = UUID.randomUUID();
        BigDecimal initialScore = getService().getOrCreateReputation(requesterId).score();

        getService().recordSuccessfulRequest(requesterId);

        BigDecimal finalScore = getService().getOrCreateReputation(requesterId).score();
        assertThat(finalScore).isGreaterThan(initialScore);
    }

    @Test
    @Label("Settled disputes do not affect reputation")
    void settledDisputesDoNotAffectReputation() {
        UUID requesterId = UUID.randomUUID();
        BigDecimal initialScore = getService().getOrCreateReputation(requesterId).score();

        getService().recordDisputeOutcome(requesterId, DisputeOutcome.SETTLED);

        BigDecimal finalScore = getService().getOrCreateReputation(requesterId).score();
        assertThat(finalScore).isEqualTo(initialScore);
    }

    // ==================== Test Doubles ====================

    /**
     * Test implementation of AuditService for unit testing.
     */
    static class TestAuditService extends AuditService {
        private final List<AuditReceipt> receipts = new ArrayList<>();

        TestAuditService() {
            super(null);
        }

        @Override
        public AuditReceipt appendReceipt(
                AuditReceipt.EventType eventType,
                UUID actorId,
                AuditReceipt.ActorType actorType,
                UUID resourceId,
                String resourceType,
                String detailsHash) {
            AuditReceipt receipt = AuditReceipt.create(
                    eventType, actorId, actorType, resourceId, resourceType, detailsHash, "TEST"
            );
            receipts.add(receipt);
            return receipt;
        }

        boolean hasReceiptOfType(AuditReceipt.EventType eventType) {
            return receipts.stream().anyMatch(r -> r.getEventType() == eventType);
        }

        void clearReceipts() {
            receipts.clear();
        }
    }
}
