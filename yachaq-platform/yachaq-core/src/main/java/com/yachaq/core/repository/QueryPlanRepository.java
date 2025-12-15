package com.yachaq.core.repository;

import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.QueryPlan.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Query Plans.
 * 
 * Property 15: Query Plan Signature Verification
 * Validates: Requirements 216.1, 216.2
 */
@Repository
public interface QueryPlanRepository extends JpaRepository<QueryPlan, UUID> {

    List<QueryPlan> findByRequestId(UUID requestId);

    List<QueryPlan> findByConsentContractId(UUID consentContractId);

    List<QueryPlan> findByStatus(PlanStatus status);

    @Query("SELECT qp FROM QueryPlan qp WHERE qp.ttl < :now AND qp.status NOT IN ('EXPIRED', 'EXECUTED', 'REJECTED')")
    List<QueryPlan> findExpiredPlans(@Param("now") Instant now);

    @Query("SELECT qp FROM QueryPlan qp WHERE qp.status = 'SIGNED' AND qp.ttl > :now")
    List<QueryPlan> findValidPlansForExecution(@Param("now") Instant now);
}
