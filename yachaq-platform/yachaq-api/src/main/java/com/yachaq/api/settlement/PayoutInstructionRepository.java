package com.yachaq.api.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Payout Instructions.
 */
@Repository
public interface PayoutInstructionRepository extends JpaRepository<PayoutInstruction, UUID> {
    
    List<PayoutInstruction> findByDsId(UUID dsId);
    
    List<PayoutInstruction> findByStatus(PayoutService.PayoutStatus status);
    
    @Query("SELECT COUNT(p) FROM PayoutInstruction p WHERE p.dsId = :dsId AND p.createdAt > :since")
    long countRecentPayouts(@Param("dsId") UUID dsId, @Param("since") Instant since);
    
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PayoutInstruction p WHERE p.dsId = :dsId AND p.createdAt > :since AND p.status IN ('PENDING', 'PROCESSING', 'COMPLETED')")
    BigDecimal sumRecentPayouts(@Param("dsId") UUID dsId, @Param("since") Instant since);

    List<PayoutInstruction> findByDsIdOrderByCreatedAtDesc(UUID dsId);
}
