package com.yachaq.core.repository;

import com.yachaq.core.domain.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {
    List<PolicyRule> findByIsActiveTrueOrderBySeverityDesc();
    Optional<PolicyRule> findByRuleCode(String ruleCode);
}
