package com.yachaq.api.privacy;

import com.yachaq.core.domain.PrivacyRiskBudget;
import com.yachaq.core.domain.PrivacyRiskBudget.PRBStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.Positive;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Privacy Governor Service.
 * Property 28: PRB Allocation and Lock
 * Validates: Requirements 204.1, 204.2
 */
class PrivacyGovernorPropertyTest {

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

    @Provide
    Arbitrary<UUID> validCampaignIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Test
    void allocatePRB_withNullCampaignId_throwsException() {
        assertThatThrownBy(() -> PrivacyRiskBudget.allocate(null, BigDecimal.TEN, "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
