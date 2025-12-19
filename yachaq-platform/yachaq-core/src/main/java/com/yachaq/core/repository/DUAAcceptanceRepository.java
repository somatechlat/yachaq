package com.yachaq.core.repository;

import com.yachaq.core.domain.DUAAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DUAAcceptance entities.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Repository
public interface DUAAcceptanceRepository extends JpaRepository<DUAAcceptance, UUID> {

    List<DUAAcceptance> findByRequesterIdOrderByAcceptedAtDesc(UUID requesterId);
    
    @Query("SELECT d FROM DUAAcceptance d WHERE d.requesterId = :requesterId ORDER BY d.acceptedAt DESC LIMIT 1")
    Optional<DUAAcceptance> findLatestByRequesterId(@Param("requesterId") UUID requesterId);
    
    boolean existsByRequesterIdAndDuaVersion(UUID requesterId, String duaVersion);
}
