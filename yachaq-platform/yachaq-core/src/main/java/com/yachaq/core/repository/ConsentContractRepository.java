package com.yachaq.core.repository;

import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.ConsentContract.ConsentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for consent contract management.
 * Supports consent lifecycle operations with audit trail.
 * 
 * Validates: Requirements 3.1, 3.2, 3.4
 */
@Repository
public interface ConsentContractRepository extends JpaRepository<ConsentContract, UUID> {
    
    /**
     * Find all contracts for a Data Sovereign.
     */
    List<ConsentContract> findByDsId(UUID dsId);
    
    /**
     * Find all contracts for a Data Sovereign with specific status.
     */
    List<ConsentContract> findByDsIdAndStatus(UUID dsId, ConsentStatus status);
    
    /**
     * Find all active contracts for a Data Sovereign.
     */
    @Query("SELECT c FROM ConsentContract c WHERE c.dsId = :dsId AND c.status = 'ACTIVE' AND c.durationEnd > :now")
    List<ConsentContract> findActiveByDsId(@Param("dsId") UUID dsId, @Param("now") Instant now);
    
    /**
     * Find all contracts for a requester.
     */
    List<ConsentContract> findByRequesterId(UUID requesterId);
    
    /**
     * Find all contracts for a specific request.
     */
    List<ConsentContract> findByRequestId(UUID requestId);
    
    /**
     * Find active contract between DS and requester for a specific request.
     */
    @Query("SELECT c FROM ConsentContract c WHERE c.dsId = :dsId AND c.requesterId = :requesterId " +
           "AND c.requestId = :requestId AND c.status = 'ACTIVE' AND c.durationEnd > :now")
    Optional<ConsentContract> findActiveContract(
            @Param("dsId") UUID dsId,
            @Param("requesterId") UUID requesterId,
            @Param("requestId") UUID requestId,
            @Param("now") Instant now);
    
    /**
     * Check if an active consent exists for the given scope.
     */
    @Query("SELECT COUNT(c) > 0 FROM ConsentContract c WHERE c.dsId = :dsId AND c.requesterId = :requesterId " +
           "AND c.scopeHash = :scopeHash AND c.status = 'ACTIVE' AND c.durationEnd > :now")
    boolean existsActiveConsentForScope(
            @Param("dsId") UUID dsId,
            @Param("requesterId") UUID requesterId,
            @Param("scopeHash") String scopeHash,
            @Param("now") Instant now);
    
    /**
     * Find all expired contracts that need status update.
     */
    @Query("SELECT c FROM ConsentContract c WHERE c.status = 'ACTIVE' AND c.durationEnd <= :now")
    List<ConsentContract> findExpiredContracts(@Param("now") Instant now);
    
    /**
     * Bulk update expired contracts to EXPIRED status.
     * Returns count of updated records.
     */
    @Modifying
    @Query("UPDATE ConsentContract c SET c.status = 'EXPIRED' WHERE c.status = 'ACTIVE' AND c.durationEnd <= :now")
    int markExpiredContracts(@Param("now") Instant now);
    
    /**
     * Count active contracts for a DS.
     */
    @Query("SELECT COUNT(c) FROM ConsentContract c WHERE c.dsId = :dsId AND c.status = 'ACTIVE' AND c.durationEnd > :now")
    long countActiveByDsId(@Param("dsId") UUID dsId, @Param("now") Instant now);
    
    /**
     * Count active contracts for a request.
     */
    @Query("SELECT COUNT(c) FROM ConsentContract c WHERE c.requestId = :requestId AND c.status = 'ACTIVE' AND c.durationEnd > :now")
    long countActiveByRequestId(@Param("requestId") UUID requestId, @Param("now") Instant now);
}
