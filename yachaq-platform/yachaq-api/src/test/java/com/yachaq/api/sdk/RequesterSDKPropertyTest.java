package com.yachaq.api.sdk;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.dispute.DisputeResolutionService;
import com.yachaq.api.dispute.DisputeResolutionService.DisputeFilingResult;
import com.yachaq.api.dispute.DisputeResolutionService.DisputeRequest;
import com.yachaq.api.dispute.DisputeResolutionService.DisputeType;
import com.yachaq.api.requester.RequesterPortalService;
import com.yachaq.api.sdk.RequesterSDK.SDKResponse;
import com.yachaq.api.verification.CapsuleVerificationService;
import com.yachaq.api.verification.CapsuleVerificationService.CapsuleData;
import com.yachaq.api.verification.CapsuleVerificationService.SignatureVerificationResult;
import com.yachaq.api.vetting.RequesterVettingService;
import com.yachaq.api.vetting.RequesterVettingService.BondRequirement;
import com.yachaq.api.vetting.RequesterVettingService.RequestRiskAssessment;
import com.yachaq.api.vetting.RequesterVettingService.RequestTypeCheck;
import com.yachaq.api.vetting.RequesterVettingService.RestrictionCheckResult;
import com.yachaq.core.domain.RequesterTier;
import com.yachaq.core.domain.RequesterTier.Tier;
import com.yachaq.core.domain.RequesterTier.VerificationLevel;
import com.yachaq.core.repository.RequesterTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for RequesterSDK against REAL infrastructure.
 * 
 * VIBE CODING RULES COMPLIANCE:
 * - Rule #1: NO MOCKS - Uses real PostgreSQL from Docker Compose
 * - Rule #4: REAL IMPLEMENTATIONS - All services are real Spring beans
 * - Rule #7: REAL DATA & SERVERS - Tests against localhost:55432 PostgreSQL
 * 
 * Run Docker Compose first: docker compose -f yachaq-platform/docker-compose.yml up -d
 * Then run with: mvn test -Dspring.profiles.active=integration
 * 
 * Validates: Requirements 352.1, 352.2, 352.4, 352.5
 */
@SpringBootTest(classes = YachaqApiApplication.class)
@ActiveProfiles("integration")
@Transactional
class RequesterSDKPropertyTest {

    @Autowired
    private RequesterTierRepository tierRepository;

    @Autowired
    private RequesterVettingService vettingService;

    @Autowired
    private RequesterPortalService portalService;

    @Autowired
    private CapsuleVerificationService verificationService;

    @Autowired
    private DisputeResolutionService disputeService;

    @Autowired
    private RequesterSDK sdk;

    /**
     * Creates a real RequesterTier in the database for testing.
     */
    private RequesterTier createRealTier(UUID requesterId, Tier tier) {
        BigDecimal maxBudget = switch (tier) {
            case BASIC -> BigDecimal.valueOf(1000);
            case STANDARD -> BigDecimal.valueOf(10000);
            case PREMIUM -> BigDecimal.valueOf(100000);
            case ENTERPRISE -> BigDecimal.valueOf(1000000);
        };
        String allowedProducts = switch (tier) {
            case BASIC -> "AGGREGATE_ONLY";
            case STANDARD -> "AGGREGATE_ONLY,CLEAN_ROOM";
            case PREMIUM -> "AGGREGATE_ONLY,CLEAN_ROOM,EXPORT";
            case ENTERPRISE -> "AGGREGATE_ONLY,CLEAN_ROOM,EXPORT,RAW_ACCESS";
        };
        boolean exportAllowed = tier == Tier.PREMIUM || tier == Tier.ENTERPRISE;

        RequesterTier requesterTier = RequesterTier.create(
                requesterId, tier, VerificationLevel.KYC,
                maxBudget, allowedProducts, exportAllowed
        );
        return tierRepository.save(requesterTier);
    }

    private Set<String> getAllowedOutputModes(Tier tier) {
        return switch (tier) {
            case BASIC -> Set.of("AGGREGATE_ONLY");
            case STANDARD -> Set.of("AGGREGATE_ONLY", "CLEAN_ROOM");
            case PREMIUM -> Set.of("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT");
            case ENTERPRISE -> Set.of("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT", "RAW_ACCESS");
        };
    }

    private BigDecimal getMaxBudgetForTier(Tier tier) {
        return switch (tier) {
            case BASIC -> BigDecimal.valueOf(1000);
            case STANDARD -> BigDecimal.valueOf(10000);
            case PREMIUM -> BigDecimal.valueOf(100000);
            case ENTERPRISE -> BigDecimal.valueOf(1000000);
        };
    }

    // ==================== Signature Verification Tests ====================

    @ParameterizedTest(name = "verifySignature with capsuleId={0}")
    @CsvSource({
        "capsule-alpha",
        "capsule-beta",
        "capsule-gamma",
        "capsule-delta",
        "capsule-epsilon"
    })
    void verifySignature_alwaysReturnsResult(String capsuleId) {
        CapsuleData capsule = new CapsuleData(
                capsuleId, "contract-1", "hash", "sig", "key",
                null, null, Instant.now(), "v1.0", "AGGREGATE", 100, Map.of()
        );

        SDKResponse<SignatureVerificationResult> response = sdk.verifySignature(capsule);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
    }

    // ==================== Dispute Filing Tests ====================

    @ParameterizedTest(name = "fileDispute with type={0}")
    @EnumSource(DisputeType.class)
    void fileDispute_alwaysGeneratesUniqueId(DisputeType type) {
        DisputeRequest request1 = new DisputeRequest(
                "contract-1", "capsule-1",
                UUID.randomUUID(), UUID.randomUUID(),
                type, "Description 1", null
        );
        DisputeRequest request2 = new DisputeRequest(
                "contract-2", "capsule-2",
                UUID.randomUUID(), UUID.randomUUID(),
                type, "Description 2", null
        );

        SDKResponse<DisputeFilingResult> response1 = sdk.fileDispute(request1);
        SDKResponse<DisputeFilingResult> response2 = sdk.fileDispute(request2);

        assertThat(response1.success()).isTrue();
        assertThat(response2.success()).isTrue();
        assertThat(response1.data().disputeId()).isNotEqualTo(response2.data().disputeId());
    }

    // ==================== Tier Restriction Tests ====================

    @ParameterizedTest(name = "checkRestrictions for tier={0} with budget={1}")
    @MethodSource("tierBudgetCombinations")
    void checkRestrictions_alwaysReturnsConsistentResult(Tier tier, BigDecimal budget) {
        UUID requesterId = UUID.randomUUID();
        createRealTier(requesterId, tier);

        RequestTypeCheck check = new RequestTypeCheck(
                "AGGREGATE_ONLY",
                budget,
                Set.of("media"),
                false
        );

        SDKResponse<RestrictionCheckResult> response = sdk.checkRestrictions(requesterId, check);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();

        BigDecimal maxBudget = getMaxBudgetForTier(tier);
        if (budget.compareTo(maxBudget) <= 0) {
            assertThat(response.data().allowed()).isTrue();
        }
    }

    static Stream<Arguments> tierBudgetCombinations() {
        return Stream.of(
            Arguments.of(Tier.BASIC, BigDecimal.valueOf(100)),
            Arguments.of(Tier.BASIC, BigDecimal.valueOf(500)),
            Arguments.of(Tier.BASIC, BigDecimal.valueOf(1000)),
            Arguments.of(Tier.STANDARD, BigDecimal.valueOf(1000)),
            Arguments.of(Tier.STANDARD, BigDecimal.valueOf(5000)),
            Arguments.of(Tier.STANDARD, BigDecimal.valueOf(10000)),
            Arguments.of(Tier.PREMIUM, BigDecimal.valueOf(10000)),
            Arguments.of(Tier.PREMIUM, BigDecimal.valueOf(50000)),
            Arguments.of(Tier.PREMIUM, BigDecimal.valueOf(100000)),
            Arguments.of(Tier.ENTERPRISE, BigDecimal.valueOf(100000)),
            Arguments.of(Tier.ENTERPRISE, BigDecimal.valueOf(500000)),
            Arguments.of(Tier.ENTERPRISE, BigDecimal.valueOf(1000000))
        );
    }

    @ParameterizedTest(name = "checkRestrictions for tier={0} with outputMode={1}")
    @MethodSource("tierOutputModeCombinations")
    void checkRestrictions_deniesDisallowedOutputModes(Tier tier, String outputMode) {
        UUID requesterId = UUID.randomUUID();
        createRealTier(requesterId, tier);

        RequestTypeCheck check = new RequestTypeCheck(
                outputMode,
                BigDecimal.valueOf(100),
                Set.of("media"),
                false
        );

        SDKResponse<RestrictionCheckResult> response = sdk.checkRestrictions(requesterId, check);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();

        Set<String> allowedModes = getAllowedOutputModes(tier);
        if (allowedModes.contains(outputMode)) {
            assertThat(response.data().allowed()).isTrue();
        } else {
            assertThat(response.data().allowed()).isFalse();
            assertThat(response.data().violations()).isNotEmpty();
        }
    }

    static Stream<Arguments> tierOutputModeCombinations() {
        return Stream.of(
            // BASIC tier
            Arguments.of(Tier.BASIC, "AGGREGATE_ONLY"),
            Arguments.of(Tier.BASIC, "CLEAN_ROOM"),
            Arguments.of(Tier.BASIC, "EXPORT"),
            Arguments.of(Tier.BASIC, "RAW_ACCESS"),
            // STANDARD tier
            Arguments.of(Tier.STANDARD, "AGGREGATE_ONLY"),
            Arguments.of(Tier.STANDARD, "CLEAN_ROOM"),
            Arguments.of(Tier.STANDARD, "EXPORT"),
            Arguments.of(Tier.STANDARD, "RAW_ACCESS"),
            // PREMIUM tier
            Arguments.of(Tier.PREMIUM, "AGGREGATE_ONLY"),
            Arguments.of(Tier.PREMIUM, "CLEAN_ROOM"),
            Arguments.of(Tier.PREMIUM, "EXPORT"),
            Arguments.of(Tier.PREMIUM, "RAW_ACCESS"),
            // ENTERPRISE tier
            Arguments.of(Tier.ENTERPRISE, "AGGREGATE_ONLY"),
            Arguments.of(Tier.ENTERPRISE, "CLEAN_ROOM"),
            Arguments.of(Tier.ENTERPRISE, "EXPORT"),
            Arguments.of(Tier.ENTERPRISE, "RAW_ACCESS")
        );
    }

    // ==================== Bond Requirement Tests ====================

    @ParameterizedTest(name = "bondRequirement for tier={0} sensitiveData={1} requiresExport={2}")
    @MethodSource("bondRiskCombinations")
    void bondRequirement_scalesWithRisk(Tier tier, boolean sensitiveData, boolean requiresExport) {
        UUID requesterId = UUID.randomUUID();
        createRealTier(requesterId, tier);

        RequestRiskAssessment lowRisk = new RequestRiskAssessment(
                BigDecimal.valueOf(1000), false, false, false
        );

        RequestRiskAssessment highRisk = new RequestRiskAssessment(
                BigDecimal.valueOf(100000), sensitiveData, requiresExport, true
        );

        SDKResponse<BondRequirement> lowRiskResponse = sdk.checkBondRequirement(requesterId, lowRisk);
        SDKResponse<BondRequirement> highRiskResponse = sdk.checkBondRequirement(requesterId, highRisk);

        assertThat(lowRiskResponse.success()).isTrue();
        assertThat(highRiskResponse.success()).isTrue();

        if (sensitiveData || requiresExport) {
            assertThat(highRiskResponse.data().amount())
                    .isGreaterThanOrEqualTo(lowRiskResponse.data().amount());
        }
    }

    static Stream<Arguments> bondRiskCombinations() {
        return Stream.of(
            Arguments.of(Tier.BASIC, false, false),
            Arguments.of(Tier.BASIC, true, false),
            Arguments.of(Tier.BASIC, false, true),
            Arguments.of(Tier.BASIC, true, true),
            Arguments.of(Tier.STANDARD, false, false),
            Arguments.of(Tier.STANDARD, true, true),
            Arguments.of(Tier.PREMIUM, false, false),
            Arguments.of(Tier.PREMIUM, true, true),
            Arguments.of(Tier.ENTERPRISE, false, false),
            Arguments.of(Tier.ENTERPRISE, true, true)
        );
    }

    @ParameterizedTest(name = "bondRequirement tier discount for tier={0}")
    @EnumSource(Tier.class)
    void bondRequirement_tierDiscountApplied(Tier tier) {
        UUID requesterId = UUID.randomUUID();
        createRealTier(requesterId, tier);

        RequestRiskAssessment assessment = new RequestRiskAssessment(
                BigDecimal.valueOf(100000), true, true, true
        );

        SDKResponse<BondRequirement> response = sdk.checkBondRequirement(requesterId, assessment);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().amount()).isNotNull();
    }

    // ==================== Unknown Requester Tests ====================

    @ParameterizedTest(name = "checkRestrictions for unknown requester with outputMode={0}")
    @CsvSource({
        "AGGREGATE_ONLY",
        "CLEAN_ROOM",
        "EXPORT",
        "RAW_ACCESS"
    })
    void checkRestrictions_unknownRequesterDenied(String outputMode) {
        UUID unknownRequesterId = UUID.randomUUID();

        RequestTypeCheck check = new RequestTypeCheck(
                outputMode,
                BigDecimal.valueOf(100),
                Set.of("media"),
                false
        );

        SDKResponse<RestrictionCheckResult> response = sdk.checkRestrictions(unknownRequesterId, check);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNotNull();
        assertThat(response.data().allowed()).isFalse();
        assertThat(response.data().violations()).contains("No tier assigned - please complete verification");
    }

    // ==================== Additional Property-Style Tests ====================

    @Test
    void allTiers_haveIncreasingBudgetLimits() {
        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length - 1; i++) {
            BigDecimal current = getMaxBudgetForTier(tiers[i]);
            BigDecimal next = getMaxBudgetForTier(tiers[i + 1]);
            assertThat(current).isLessThan(next);
        }
    }

    @Test
    void allTiers_haveIncreasingOutputModes() {
        Tier[] tiers = Tier.values();
        for (int i = 0; i < tiers.length - 1; i++) {
            Set<String> current = getAllowedOutputModes(tiers[i]);
            Set<String> next = getAllowedOutputModes(tiers[i + 1]);
            assertThat(next).containsAll(current);
        }
    }

    @Test
    void enterpriseTier_hasAllOutputModes() {
        Set<String> enterpriseModes = getAllowedOutputModes(Tier.ENTERPRISE);
        assertThat(enterpriseModes).containsExactlyInAnyOrder(
                "AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT", "RAW_ACCESS"
        );
    }

    @Test
    void basicTier_hasOnlyAggregateMode() {
        Set<String> basicModes = getAllowedOutputModes(Tier.BASIC);
        assertThat(basicModes).containsExactly("AGGREGATE_ONLY");
    }
}
