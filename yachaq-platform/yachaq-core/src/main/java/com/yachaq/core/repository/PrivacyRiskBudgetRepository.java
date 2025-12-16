package com.yachaq.core.repository;

import com.yachaq.core.domain.PrivacyRiskBudget;
import com.yachaq.core.domain.PrivacyRiskBudget.PRBStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Privacy Risk Budget (PRB) entities.
 * 
 * Validates: Requirements 204.1, 204.2, 204.3, 204.4
 */
@Repository
public interface PrivacyRiskBudgetRepository extends JpaRepository<PrivacyRiskBudget, UUID> {

    /**
     * Finds PRB by campaign ID.
     */
    Optional<PrivacyRiskBudget> findByCampaignId(UUID campaignId);

    /**
     * Checks if a PRB exists for a campaign.
     */
    boolean existsByCampaignId(UUID campaignId);

    /**
     * Finds all PRBs by status.
     */
    List<PrivacyRiskBudget> findByStatus(PRBStatus status);

    /**
     * Finds all locked PRBs with remaining budget above threshold.
     */
    @Query("SELECT p FROM PrivacyRiskBudget p WHERE p.status = 'LOCKED' AND p.remaining >= :threshold")
    List<PrivacyRiskBudget> findLockedWithRemainingAbove(@Param("threshold") BigDecimal threshold);

    /**
     * Finds all exhausted PRBs for cleanup/archival.
     */
    @Query("SELECT p FROM PrivacyRiskBudget p WHERE p.status = 'EXHAUSTED'")
    List<PrivacyRiskBudget> findExhausted();

    /**
     * Finds PRBs by ruleset version.
     */
    List<PrivacyRiskBudget> findByRulesetVersion(String rulesetVersion);

    /**
     * Counts PRBs by status.
     */
    long countByStatus(PRBStatus status);
}
