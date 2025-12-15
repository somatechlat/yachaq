package com.yachaq.core.repository;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.EventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for audit receipts.
 * Append-only by design - no update or delete operations exposed.
 * 
 * Property 5: Audit Receipt Generation
 * Validates: Requirements 12.1, 126.1
 */
@Repository
public interface AuditReceiptRepository extends JpaRepository<AuditReceipt, UUID> {
    
    /**
     * Find receipts by actor (DS or Requester).
     */
    Page<AuditReceipt> findByActorIdOrderByTimestampDesc(UUID actorId, Pageable pageable);
    
    /**
     * Find receipts by resource.
     */
    List<AuditReceipt> findByResourceIdOrderByTimestampDesc(UUID resourceId);
    
    /**
     * Find receipts by event type.
     */
    Page<AuditReceipt> findByEventTypeOrderByTimestampDesc(EventType eventType, Pageable pageable);
    
    /**
     * Find receipts in time range.
     */
    @Query("SELECT r FROM AuditReceipt r WHERE r.timestamp >= :start AND r.timestamp <= :end ORDER BY r.timestamp DESC")
    Page<AuditReceipt> findByTimestampRange(
            @Param("start") Instant start,
            @Param("end") Instant end,
            Pageable pageable);
    
    /**
     * Find receipts for a specific actor and resource.
     */
    List<AuditReceipt> findByActorIdAndResourceIdOrderByTimestampDesc(UUID actorId, UUID resourceId);
    
    /**
     * Get the most recent receipt (for hash chaining).
     */
    @Query("SELECT r FROM AuditReceipt r ORDER BY r.timestamp DESC LIMIT 1")
    Optional<AuditReceipt> findMostRecent();
    
    /**
     * Get the most recent receipt hash (for hash chaining).
     */
    @Query("SELECT r.receiptHash FROM AuditReceipt r WHERE r.receiptHash IS NOT NULL ORDER BY r.timestamp DESC LIMIT 1")
    Optional<String> findMostRecentReceiptHash();

    /**
     * Find a receipt by its hash (for verifying chain links).
     */
    Optional<AuditReceipt> findByReceiptHash(String receiptHash);
    
    /**
     * Find receipts without Merkle proof (for batching).
     */
    @Query("SELECT r FROM AuditReceipt r WHERE r.merkleProof IS NULL ORDER BY r.timestamp ASC")
    List<AuditReceipt> findUnanchoredReceipts(Pageable pageable);
    
    /**
     * Count receipts by event type in time range.
     */
    @Query("SELECT COUNT(r) FROM AuditReceipt r WHERE r.eventType = :eventType AND r.timestamp >= :start AND r.timestamp <= :end")
    long countByEventTypeInRange(
            @Param("eventType") EventType eventType,
            @Param("start") Instant start,
            @Param("end") Instant end);
    
    /**
     * Find consent-related receipts for a contract.
     */
    @Query("SELECT r FROM AuditReceipt r WHERE r.resourceId = :contractId AND r.resourceType = 'ConsentContract' ORDER BY r.timestamp DESC")
    List<AuditReceipt> findByConsentContract(@Param("contractId") UUID contractId);
}
