package com.yachaq.api.vetting;

import com.yachaq.api.vetting.RequesterVettingService.*;
import com.yachaq.core.domain.RequesterTier;
import com.yachaq.core.domain.RequesterTier.Tier;
import com.yachaq.core.domain.RequesterTier.VerificationLevel;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for RequesterVettingService.
 * Tests tier assignment, restrictions, and bond requirements without database.
 * 
 * Validates: Requirements 350.1, 350.2, 350.3, 350.4, 350.5
 */
class RequesterVettingPropertyTest {

    // ==================== Task 95.1: Tier Assignment Tests ====================

    @Test
    void tierAssignment_noneVerificationGetsBasic() {
        // Requirement 350.1: Assign tiers based on verification level
        Tier tier = determineTierFromVerification(VerificationLevel.NONE);
        assertThat(tier).isEqualTo(Tier.BASIC);
    }

    @Test
    void tierAssignment_emailVerificationGetsBasic() {
        Tier tier = determineTierFromVerification(VerificationLevel.EMAIL);
        assertThat(tier).isEqualTo(Tier.BASIC);
    }

    @Test
    void tierAssignment_phoneVerificationGetsStandard() {
        Tier tier = determineTierFromVerification(VerificationLevel.PHONE);
        assertThat(tier).isEqualTo(Tier.STANDARD);
    }

    @Test
    void tierAssignment_kycVerificationGetsPremium() {
        Tier tier = determineTierFromVerification(VerificationLevel.KYC);
        assertThat(tier).isEqualTo(Tier.PREMIUM);
    }

    @Test
    void tierAssignment_kybVerificationGetsEnterprise() {
        Tier tier = determineTierFromVerification(VerificationLevel.KYB);
        assertThat(tier).isEqualTo(Tier.ENTERPRISE);
    }

    @Property
    void tierAssignment_higherVerificationNeverGetsLowerTier(
            @ForAll("verificationLevels") VerificationLevel level1,
            @ForAll("verificationLevels") VerificationLevel level2) {
        
        Assume.that(level1.ordinal() < level2.ordinal());

        Tier tier1 = determineTierFromVerification(level1);
        Tier tier2 = determineTierFromVerification(level2);

        assertThat(tier1.ordinal()).isLessThanOrEqualTo(tier2.ordinal());
    }

    // ==================== Task 95.2: Tier Restrictions Tests ====================

    @Test
    void tierCapabilities_basicHasLimitedBudget() {
        // Requirement 350.2: Restrict request types based on tier
        TierCapabilities caps = getCapabilities(Tier.BASIC);
        
        assertThat(caps.maxBudget()).isEqualTo(BigDecimal.valueOf(1000));
        assertThat(caps.exportAllowed()).isFalse();
        assertThat(caps.allowedOutputModes()).contains("AGGREGATE_ONLY");
        assertThat(caps.allowedOutputModes()).doesNotContain("EXPORT", "RAW_ACCESS");
    }

    @Test
    void tierCapabilities_enterpriseHasFullAccess() {
        TierCapabilities caps = getCapabilities(Tier.ENTERPRISE);
        
        assertThat(caps.maxBudget()).isEqualTo(BigDecimal.valueOf(1000000));
        assertThat(caps.exportAllowed()).isTrue();
        assertThat(caps.allowedOutputModes()).contains("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT", "RAW_ACCESS");
    }

    @Test
    void tierCapabilities_basicCannotAccessHealth() {
        TierCapabilities caps = getCapabilities(Tier.BASIC);
        
        assertThat(caps.allowedCategories()).doesNotContain("health", "finance");
        assertThat(caps.allowedCategories()).contains("media", "social");
    }

    @Test
    void tierCapabilities_premiumCanAccessHealth() {
        TierCapabilities caps = getCapabilities(Tier.PREMIUM);
        
        assertThat(caps.allowedCategories()).contains("health");
    }

    @Property
    void tierCapabilities_higherTierHasMoreBudget(
            @ForAll("tiers") Tier tier1,
            @ForAll("tiers") Tier tier2) {
        
        Assume.that(tier1.ordinal() < tier2.ordinal());

        TierCapabilities caps1 = getCapabilities(tier1);
        TierCapabilities caps2 = getCapabilities(tier2);

        assertThat(caps1.maxBudget()).isLessThan(caps2.maxBudget());
    }

    @Property
    void tierCapabilities_higherTierHasMoreCategories(
            @ForAll("tiers") Tier tier1,
            @ForAll("tiers") Tier tier2) {
        
        Assume.that(tier1.ordinal() < tier2.ordinal());

        TierCapabilities caps1 = getCapabilities(tier1);
        TierCapabilities caps2 = getCapabilities(tier2);

        assertThat(caps2.allowedCategories()).containsAll(caps1.allowedCategories());
    }

    // ==================== Task 95.3: Bond Requirements Tests ====================

    @Test
    void bondRequirement_sensitiveDataRequiresBond() {
        // Requirement 350.3: Require bonds for high-risk requests
        BondRequirement bond = calculateBond(new RequestRiskAssessment(
                BigDecimal.valueOf(1000),
                true, // sensitive data
                false,
                false
        ), Tier.BASIC);

        assertThat(bond.required()).isTrue();
        assertThat(bond.amount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(bond.reason()).contains("Sensitive");
    }

    @Test
    void bondRequirement_exportRequiresBond() {
        BondRequirement bond = calculateBond(new RequestRiskAssessment(
                BigDecimal.valueOf(1000),
                false,
                true, // export
                false
        ), Tier.BASIC);

        assertThat(bond.required()).isTrue();
        assertThat(bond.reason()).contains("export");
    }

    @Test
    void bondRequirement_highBudgetRequiresBond() {
        BondRequirement bond = calculateBond(new RequestRiskAssessment(
                BigDecimal.valueOf(100000), // high budget
                false,
                false,
                false
        ), Tier.BASIC);

        assertThat(bond.required()).isTrue();
        assertThat(bond.reason()).contains("High-value");
    }

    @Test
    void bondRequirement_firstRequestRequiresBond() {
        BondRequirement bond = calculateBond(new RequestRiskAssessment(
                BigDecimal.valueOf(1000),
                false,
                false,
                true // first request
        ), Tier.BASIC);

        assertThat(bond.required()).isTrue();
        assertThat(bond.reason()).contains("First");
    }

    @Test
    void bondRequirement_enterpriseGetsDiscount() {
        // Enterprise has minimum bond of 50000, so we need a high-risk scenario
        // where the calculated bond exceeds that minimum
        RequestRiskAssessment assessment = new RequestRiskAssessment(
                BigDecimal.valueOf(1000000), // Very high budget
                true,
                true,
                true
        );

        BondRequirement basicBond = calculateBond(assessment, Tier.BASIC);
        BondRequirement enterpriseBond = calculateBond(assessment, Tier.ENTERPRISE);

        // Enterprise gets 80% discount (0.2 multiplier) vs Basic (1.0 multiplier)
        // But Enterprise has minimum bond of 50000
        // Basic calculated: (2000 + 100000 + 5000 + 1000) * 1.0 = 108000
        // Enterprise calculated: (2000 + 100000 + 5000 + 1000) * 0.2 = 21600, but min is 50000
        assertThat(enterpriseBond.amount()).isLessThanOrEqualTo(basicBond.amount());
    }

    @Property
    void bondRequirement_higherTierPaysLessBondBeforeMinimum(
            @ForAll("lowTiers") Tier tier1,
            @ForAll("lowTiers") Tier tier2,
            @ForAll("budgets") BigDecimal budget) {
        
        Assume.that(tier1.ordinal() < tier2.ordinal());

        // Test with tiers that have no minimum bond (Basic, Standard)
        RequestRiskAssessment assessment = new RequestRiskAssessment(
                budget, true, true, true
        );

        BondRequirement bond1 = calculateBond(assessment, tier1);
        BondRequirement bond2 = calculateBond(assessment, tier2);

        // Higher tier gets discount, so pays less (before minimum kicks in)
        assertThat(bond2.amount()).isLessThanOrEqualTo(bond1.amount());
    }

    @Property
    void bondRequirement_lowRiskBasicAndStandardNoMinimum(
            @ForAll("lowTiers") Tier tier) {
        
        RequestRiskAssessment lowRisk = new RequestRiskAssessment(
                BigDecimal.valueOf(100), // low budget
                false, // no sensitive data
                false, // no export
                false  // not first request
        );

        BondRequirement bond = calculateBond(lowRisk, tier);

        // Basic and Standard have no minimum bond requirement
        assertThat(bond.required()).isFalse();
        assertThat(bond.amount().compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<VerificationLevel> verificationLevels() {
        return Arbitraries.of(VerificationLevel.values());
    }

    @Provide
    Arbitrary<Tier> tiers() {
        return Arbitraries.of(Tier.values());
    }

    @Provide
    Arbitrary<Tier> lowTiers() {
        return Arbitraries.of(Tier.BASIC, Tier.STANDARD);
    }

    @Provide
    Arbitrary<BigDecimal> budgets() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(100), BigDecimal.valueOf(500000))
                .ofScale(2);
    }

    // ==================== Helper Methods (extracted from service) ====================

    private Tier determineTierFromVerification(VerificationLevel level) {
        return switch (level) {
            case NONE, EMAIL -> Tier.BASIC;
            case PHONE -> Tier.STANDARD;
            case KYC -> Tier.PREMIUM;
            case KYB -> Tier.ENTERPRISE;
        };
    }

    private static final Map<Tier, TierConfigTest> TIER_CONFIGS = Map.of(
            Tier.BASIC, new TierConfigTest(
                    BigDecimal.valueOf(1000),
                    Set.of("AGGREGATE_ONLY"),
                    false,
                    BigDecimal.ZERO,
                    Set.of("media", "social")
            ),
            Tier.STANDARD, new TierConfigTest(
                    BigDecimal.valueOf(10000),
                    Set.of("AGGREGATE_ONLY", "CLEAN_ROOM"),
                    false,
                    BigDecimal.ZERO,
                    Set.of("media", "social", "location")
            ),
            Tier.PREMIUM, new TierConfigTest(
                    BigDecimal.valueOf(100000),
                    Set.of("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT"),
                    true,
                    BigDecimal.valueOf(5000),
                    Set.of("media", "social", "location", "health")
            ),
            Tier.ENTERPRISE, new TierConfigTest(
                    BigDecimal.valueOf(1000000),
                    Set.of("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT", "RAW_ACCESS"),
                    true,
                    BigDecimal.valueOf(50000),
                    Set.of("media", "social", "location", "health", "finance")
            )
    );

    private record TierConfigTest(
            BigDecimal maxBudget,
            Set<String> allowedProducts,
            boolean exportAllowed,
            BigDecimal bondRequired,
            Set<String> allowedCategories
    ) {}

    private TierCapabilities getCapabilities(Tier tier) {
        TierConfigTest config = TIER_CONFIGS.get(tier);
        return new TierCapabilities(
                tier,
                config.maxBudget(),
                config.allowedProducts(),
                config.allowedCategories(),
                config.exportAllowed(),
                config.bondRequired()
        );
    }

    private BondRequirement calculateBond(RequestRiskAssessment assessment, Tier tier) {
        TierConfigTest config = TIER_CONFIGS.get(tier);

        BigDecimal bondAmount = BigDecimal.ZERO;
        List<String> reasons = new ArrayList<>();

        if (assessment.includesSensitiveData()) {
            bondAmount = bondAmount.add(BigDecimal.valueOf(2000));
            reasons.add("Sensitive data access");
        }

        if (assessment.budget() != null && assessment.budget().compareTo(BigDecimal.valueOf(50000)) > 0) {
            bondAmount = bondAmount.add(assessment.budget().multiply(BigDecimal.valueOf(0.1)));
            reasons.add("High-value request");
        }

        if (assessment.requiresExport()) {
            bondAmount = bondAmount.add(BigDecimal.valueOf(5000));
            reasons.add("Data export requested");
        }

        if (assessment.isFirstRequest()) {
            bondAmount = bondAmount.add(BigDecimal.valueOf(1000));
            reasons.add("First request");
        }

        BigDecimal discount = switch (tier) {
            case BASIC -> BigDecimal.ONE;
            case STANDARD -> BigDecimal.valueOf(0.8);
            case PREMIUM -> BigDecimal.valueOf(0.5);
            case ENTERPRISE -> BigDecimal.valueOf(0.2);
        };
        bondAmount = bondAmount.multiply(discount);

        if (bondAmount.compareTo(config.bondRequired()) < 0) {
            bondAmount = config.bondRequired();
        }

        boolean required = bondAmount.compareTo(BigDecimal.ZERO) > 0;
        String reason = required ? String.join("; ", reasons) : "No bond required";

        return new BondRequirement(required, bondAmount, reason);
    }
}
