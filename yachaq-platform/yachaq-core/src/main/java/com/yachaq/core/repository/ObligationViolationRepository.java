package com.yachaq.core.repository;

import com.yachaq.core.domain.ObligationViolation;
import com.yachaq.core.domain.ObligationViolation.Severity;
import com.yachaq.core.domain.ObligationViolation.ViolationStatus;
import com.yachaq.core.domain.ObligationViolation.ViolationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for ObligationViolation entities.
 * Property 23: Consent Obligation Specification
 * Validates: Requirements 223.3, 223.4
 */
@Repository
public interface ObligationViolationRepository extends JpaRepository<ObligationViolation, UUID> {

    /**
     * Finds all violations for a consent contract.
     */
    List<ObligationViolation> findByConsentContractId(UUID consentContractId);

    /**
     * Finds all violations for an obligation.
     */
    List<ObligationViolation> findByObligationId(UUID obligationId);

    /**
     * Finds violations by status.
     */
    List<ObligationViolation> findByStatus(ViolationStatus status);

    /**
     * Finds violations by severity.
     */
    List<ObligationViolation> findBySeverity(Severity severity);

    /**
     * Finds violations by type.
     */
    List<ObligationViolation> findByViolationType(ViolationType violationType);

    /**
     * Finds unresolved violations for a contract.
     */
    @Query("SELECT v FROM ObligationViolation v " +
           "WHERE v.consentContractId = :contractId " +
           "AND v.status NOT IN ('RESOLVED', 'DISMISSED')")
    List<ObligationViolation> findUnresolvedByContractId(@Param("contractId") UUID contractId);

    /**
     * Finds critical violations that need immediate attention.
     */
    @Query("SELECT v FROM ObligationViolation v " +
           "WHERE v.severity = 'CRITICAL' " +
           "AND v.status NOT IN ('RESOLVED', 'DISMISSED') " +
           "ORDER BY v.detectedAt ASC")
    List<ObligationViolation> findCriticalUnresolved();

    /**
     * Counts violations by status.
     */
    @Query("SELECT v.status, COUNT(v) FROM ObligationViolation v " +
           "WHERE v.consentContractId = :contractId " +
           "GROUP BY v.status")
    List<Object[]> countByStatusForContract(@Param("contractId") UUID contractId);

    /**
     * Finds violations detected within a time range.
     */
    @Query("SELECT v FROM ObligationViolation v " +
           "WHERE v.detectedAt BETWEEN :start AND :end " +
           "ORDER BY v.detectedAt DESC")
    List<ObligationViolation> findByDetectedAtBetween(
            @Param("start") Instant start, 
            @Param("end") Instant end);

    /**
     * Checks if a contract has any unresolved violations.
     */
    @Query("SELECT COUNT(v) > 0 FROM ObligationViolation v " +
           "WHERE v.consentContractId = :contractId " +
           "AND v.status NOT IN ('RESOLVED', 'DISMISSED')")
    boolean hasUnresolvedViolations(@Param("contractId") UUID contractId);

    /**
     * Counts total penalties applied for a contract.
     */
    @Query("SELECT COALESCE(SUM(v.penaltyAmount), 0) FROM ObligationViolation v " +
           "WHERE v.consentContractId = :contractId " +
           "AND v.penaltyApplied = true")
    java.math.BigDecimal sumPenaltiesForContract(@Param("contractId") UUID contractId);
}
