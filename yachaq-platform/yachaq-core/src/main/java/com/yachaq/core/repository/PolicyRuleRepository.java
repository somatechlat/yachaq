package com.yachaq.core.repository;

import com.yachaq.core.domain.PolicyRule;
import com.yachaq.core.domain.PolicyRule.RuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for PolicyRule entities.
 * 
 * Validates: Requirements 6.1, 6.2
 */
@Repository
public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {
    
    /**
     * Find all active rules.
     */
    List<PolicyRule> findByIsActiveTrueOrderBySeverityDesc();
    
    /**
     * Find active rules by type.
     */
    List<PolicyRule> findByIsActiveTrueAndRuleTypeOrderBySeverityDesc(RuleType ruleType);
    
    /**
     * Find active rules by category.
     */
    List<PolicyRule> findByIsActiveTrueAndRuleCategoryOrderBySeverityDesc(String ruleCategory);
    
    /**
     * Find rule by code.
     */
    Optional<PolicyRule> findByRuleCode(String ruleCode);
    
    /**
     * Find all blocking rules.
     */
    @Query("SELECT r FROM PolicyRule r WHERE r.isActive = true AND r.ruleType = 'BLOCKING' ORDER BY r.severity DESC")
    List<PolicyRule> findActiveBlockingRules();
    
    /**
     * Count active rules by category.
     */
    long countByIsActiveTrueAndRuleCategory(String ruleCategory);
}
