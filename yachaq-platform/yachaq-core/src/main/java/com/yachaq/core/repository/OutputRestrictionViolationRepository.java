package com.yachaq.core.repository;

import com.yachaq.core.domain.OutputRestrictionViolation;
import com.yachaq.core.domain.OutputRestrictionViolation.ViolationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for OutputRestrictionViolation entities.
 * 
 * Validates: Requirements 221.3, 221.4
 */
@Repository
public interface OutputRestrictionViolationRepository extends JpaRepository<OutputRestrictionViolation, UUID> {

    /**
     * Find all violations for a session.
     */
    List<OutputRestrictionViolation> findBySessionIdOrderByOccurredAtDesc(UUID sessionId);

    /**
     * Count violations by type for a session.
     */
    long countBySessionIdAndViolationType(UUID sessionId, ViolationType violationType);

    /**
     * Find violations within a time range.
     */
    @Query("SELECT v FROM OutputRestrictionViolation v WHERE v.occurredAt BETWEEN :start AND :end " +
           "ORDER BY v.occurredAt DESC")
    List<OutputRestrictionViolation> findByTimeRange(
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Count blocked violations for a session.
     */
    long countBySessionIdAndBlocked(UUID sessionId, boolean blocked);
}
