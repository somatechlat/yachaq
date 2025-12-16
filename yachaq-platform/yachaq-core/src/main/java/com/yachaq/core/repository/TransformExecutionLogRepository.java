package com.yachaq.core.repository;

import com.yachaq.core.domain.TransformExecutionLog;
import com.yachaq.core.domain.TransformExecutionLog.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for TransformExecutionLog entities.
 * 
 * Property 18: Transform Restriction Enforcement
 * Validates: Requirements 220.6
 */
@Repository
public interface TransformExecutionLogRepository extends JpaRepository<TransformExecutionLog, UUID> {

    /**
     * Find all execution logs for a consent contract.
     */
    List<TransformExecutionLog> findByConsentContractIdOrderByExecutedAtDesc(UUID consentContractId);

    /**
     * Find all execution logs for a query plan.
     */
    List<TransformExecutionLog> findByQueryPlanId(UUID queryPlanId);

    /**
     * Find all rejected executions for a consent contract.
     */
    List<TransformExecutionLog> findByConsentContractIdAndStatus(
            UUID consentContractId, 
            ExecutionStatus status);

    /**
     * Count rejected executions by executor within a time range.
     */
    @Query("SELECT COUNT(t) FROM TransformExecutionLog t WHERE t.executorId = :executorId " +
           "AND t.status = 'REJECTED' AND t.executedAt BETWEEN :start AND :end")
    long countRejectedByExecutorInTimeRange(
            @Param("executorId") UUID executorId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Find execution logs by executor within a time range.
     */
    @Query("SELECT t FROM TransformExecutionLog t WHERE t.executorId = :executorId " +
           "AND t.executedAt BETWEEN :start AND :end ORDER BY t.executedAt DESC")
    List<TransformExecutionLog> findByExecutorIdAndTimeRange(
            @Param("executorId") UUID executorId,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
