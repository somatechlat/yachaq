package com.yachaq.core.repository;

import com.yachaq.core.domain.FieldAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for FieldAccessLog entities.
 * 
 * Property 17: Field-Level Access Enforcement
 * Validates: Requirements 219.5
 */
@Repository
public interface FieldAccessLogRepository extends JpaRepository<FieldAccessLog, UUID> {

    /**
     * Find all access logs for a consent contract.
     */
    List<FieldAccessLog> findByConsentContractIdOrderByAccessedAtDesc(UUID consentContractId);

    /**
     * Find all access logs for a query plan.
     */
    List<FieldAccessLog> findByQueryPlanId(UUID queryPlanId);

    /**
     * Find all access logs by accessor within a time range.
     */
    @Query("SELECT f FROM FieldAccessLog f WHERE f.accessorId = :accessorId " +
           "AND f.accessedAt BETWEEN :start AND :end ORDER BY f.accessedAt DESC")
    List<FieldAccessLog> findByAccessorIdAndTimeRange(
            @Param("accessorId") UUID accessorId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Count access logs for a consent contract.
     */
    long countByConsentContractId(UUID consentContractId);
}
