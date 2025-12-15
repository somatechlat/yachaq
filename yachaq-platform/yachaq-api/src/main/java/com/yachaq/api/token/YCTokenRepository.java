package com.yachaq.api.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for YC Token operations.
 * 
 * Requirements: 192.3 - Full auditability of all issuance and redemption
 */
@Repository
public interface YCTokenRepository extends JpaRepository<YCToken, UUID> {

    /**
     * Find all token operations for a DS.
     */
    List<YCToken> findByDsIdOrderByCreatedAtDesc(UUID dsId);

    /**
     * Find by idempotency key to prevent duplicate operations.
     */
    Optional<YCToken> findByIdempotencyKey(String idempotencyKey);

    /**
     * Check if idempotency key exists.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Calculate total YC balance for a DS.
     * Sum of all operations (issuance positive, redemption/clawback negative).
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM YCToken t WHERE t.dsId = :dsId")
    BigDecimal calculateBalance(@Param("dsId") UUID dsId);

    /**
     * Calculate total issued YC for a DS.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM YCToken t WHERE t.dsId = :dsId AND t.operationType = 'ISSUANCE'")
    BigDecimal calculateTotalIssued(@Param("dsId") UUID dsId);

    /**
     * Calculate total redeemed YC for a DS.
     */
    @Query("SELECT COALESCE(ABS(SUM(t.amount)), 0) FROM YCToken t WHERE t.dsId = :dsId AND t.operationType = 'REDEMPTION'")
    BigDecimal calculateTotalRedeemed(@Param("dsId") UUID dsId);

    /**
     * Find operations by reference (for reconciliation).
     */
    List<YCToken> findByReferenceIdAndReferenceType(UUID referenceId, String referenceType);

    /**
     * Find operations linked to an escrow (for reconciliation).
     */
    List<YCToken> findByEscrowId(UUID escrowId);
}
