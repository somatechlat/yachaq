package com.yachaq.core.repository;

import com.yachaq.core.domain.RequesterReputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for RequesterReputation entities.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Repository
public interface RequesterReputationRepository extends JpaRepository<RequesterReputation, UUID> {

    Optional<RequesterReputation> findByRequesterId(UUID requesterId);
    
    boolean existsByRequesterId(UUID requesterId);
    
    List<RequesterReputation> findByScoreLessThan(BigDecimal threshold);
}
