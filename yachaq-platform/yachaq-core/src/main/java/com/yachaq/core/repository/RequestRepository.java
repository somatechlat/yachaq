package com.yachaq.core.repository;

import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.Request.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Request entities.
 * 
 * Validates: Requirements 5.1, 5.2
 */
@Repository
public interface RequestRepository extends JpaRepository<Request, UUID> {
    
    /**
     * Find requests by requester.
     */
    Page<Request> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId, Pageable pageable);
    
    /**
     * Find requests by status.
     */
    Page<Request> findByStatusOrderByCreatedAtDesc(RequestStatus status, Pageable pageable);
    
    /**
     * Find requests by requester and status.
     */
    List<Request> findByRequesterIdAndStatus(UUID requesterId, RequestStatus status);
    
    /**
     * Find active requests in a time range.
     */
    @Query("SELECT r FROM Request r WHERE r.status = 'ACTIVE' AND r.durationStart <= :now AND r.durationEnd >= :now")
    List<Request> findActiveRequestsInRange(@Param("now") Instant now);
    
    /**
     * Find requests pending screening.
     */
    List<Request> findByStatusOrderBySubmittedAtAsc(RequestStatus status);
    
    /**
     * Count requests by requester and status.
     */
    long countByRequesterIdAndStatus(UUID requesterId, RequestStatus status);
    
    /**
     * Find requests with escrow linked.
     */
    @Query("SELECT r FROM Request r WHERE r.escrowId IS NOT NULL AND r.status = :status")
    List<Request> findWithEscrowByStatus(@Param("status") RequestStatus status);
}
