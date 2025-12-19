package com.yachaq.core.repository;

import com.yachaq.core.domain.PolicyDecisionReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for PolicyDecisionReceipt entities.
 * Replaces raw SQL in FailClosedPolicyService and PrivacyGovernorService.
 */
@Repository
public interface PolicyDecisionReceiptRepository extends JpaRepository<PolicyDecisionReceipt, UUID> {

    List<PolicyDecisionReceipt> findByRequesterId(UUID requesterId);
    
    List<PolicyDecisionReceipt> findByCampaignId(UUID campaignId);
    
    List<PolicyDecisionReceipt> findByDecisionType(String decisionType);
    
    List<PolicyDecisionReceipt> findByCreatedAtAfter(Instant after);
    
    List<PolicyDecisionReceipt> findByRequesterIdAndCreatedAtAfter(UUID requesterId, Instant after);
}
