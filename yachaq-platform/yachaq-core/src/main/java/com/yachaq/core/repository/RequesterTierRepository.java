package com.yachaq.core.repository;

import com.yachaq.core.domain.RequesterTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for RequesterTier entities.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Repository
public interface RequesterTierRepository extends JpaRepository<RequesterTier, UUID> {

    Optional<RequesterTier> findByRequesterId(UUID requesterId);
    
    boolean existsByRequesterId(UUID requesterId);
}
