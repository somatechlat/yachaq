package com.yachaq.core.repository;

import com.yachaq.core.domain.ScreeningResult;
import com.yachaq.core.domain.ScreeningResult.AppealStatus;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
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
 * Repository for ScreeningResult entities.
 * 
 * Validates: Requirements 6.1, 6.2, 6.3
 */
@Repository
public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, UUID> {
    
    /**
     * Find screening result by request ID.
     */
    Optional<ScreeningResult> findByRequestId(UUID requestId);
    
    /**
     * Find screening results by decision.
     */
    Page<ScreeningResult> findByDecisionOrderByScreenedAtDesc(ScreeningDecision decision, Pageable pageable);
    
    /**
     * Find screening results with pending appeals.
     */
    List<ScreeningResult> findByAppealStatusOrderByAppealSubmittedAtAsc(AppealStatus appealStatus);
    
    /**
     * Find screening results in time range.
     */
    @Query("SELECT s FROM ScreeningResult s WHERE s.screenedAt >= :start AND s.screenedAt <= :end ORDER BY s.screenedAt DESC")
    Page<ScreeningResult> findByScreenedAtRange(
            @Param("start") Instant start,
            @Param("end") Instant end,
            Pageable pageable);
    
    /**
     * Count by decision in time range.
     */
    @Query("SELECT COUNT(s) FROM ScreeningResult s WHERE s.decision = :decision AND s.screenedAt >= :start AND s.screenedAt <= :end")
    long countByDecisionInRange(
            @Param("decision") ScreeningDecision decision,
            @Param("start") Instant start,
            @Param("end") Instant end);
    
    /**
     * Find results with cohort below threshold.
     */
    @Query("SELECT s FROM ScreeningResult s WHERE s.cohortSizeEstimate < :threshold ORDER BY s.screenedAt DESC")
    List<ScreeningResult> findBelowCohortThreshold(@Param("threshold") int threshold);
    
    /**
     * Check if request has been screened.
     */
    boolean existsByRequestId(UUID requestId);
}
