package com.yachaq.core.repository;

import com.yachaq.core.domain.ConsentObligation;
import com.yachaq.core.domain.ConsentObligation.ObligationStatus;
import com.yachaq.core.domain.ConsentObligation.ObligationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ConsentObligation entities.
 * Property 23: Consent Obligation Specification
 * Validates: Requirements 223.1, 223.2
 */
@Repository
public interface ConsentObligationRepository extends JpaRepository<ConsentObligation, UUID> {

    /**
     * Finds all obligations for a consent contract.
     */
    List<ConsentObligation> findByConsentContractId(UUID consentContractId);

    /**
     * Finds all active obligations for a consent contract.
     */
    List<ConsentObligation> findByConsentContractIdAndStatus(UUID consentContractId, ObligationStatus status);

    /**
     * Finds all obligations of a specific type for a consent contract.
     */
    List<ConsentObligation> findByConsentContractIdAndObligationType(UUID consentContractId, ObligationType obligationType);

    /**
     * Checks if a consent contract has all required obligation types.
     */
    @Query("SELECT COUNT(DISTINCT o.obligationType) FROM ConsentObligation o " +
           "WHERE o.consentContractId = :contractId " +
           "AND o.obligationType IN ('RETENTION_LIMIT', 'USAGE_RESTRICTION', 'DELETION_REQUIREMENT')")
    long countRequiredObligationTypes(@Param("contractId") UUID contractId);

    /**
     * Checks if a consent contract has a retention limit obligation.
     */
    @Query("SELECT COUNT(o) > 0 FROM ConsentObligation o " +
           "WHERE o.consentContractId = :contractId " +
           "AND o.obligationType = 'RETENTION_LIMIT'")
    boolean hasRetentionLimit(@Param("contractId") UUID contractId);

    /**
     * Checks if a consent contract has a usage restriction obligation.
     */
    @Query("SELECT COUNT(o) > 0 FROM ConsentObligation o " +
           "WHERE o.consentContractId = :contractId " +
           "AND o.obligationType = 'USAGE_RESTRICTION'")
    boolean hasUsageRestriction(@Param("contractId") UUID contractId);

    /**
     * Checks if a consent contract has a deletion requirement obligation.
     */
    @Query("SELECT COUNT(o) > 0 FROM ConsentObligation o " +
           "WHERE o.consentContractId = :contractId " +
           "AND o.obligationType = 'DELETION_REQUIREMENT'")
    boolean hasDeletionRequirement(@Param("contractId") UUID contractId);

    /**
     * Finds all violated obligations.
     */
    List<ConsentObligation> findByStatus(ObligationStatus status);

    /**
     * Counts obligations by status for a contract.
     */
    @Query("SELECT o.status, COUNT(o) FROM ConsentObligation o " +
           "WHERE o.consentContractId = :contractId " +
           "GROUP BY o.status")
    List<Object[]> countByStatusForContract(@Param("contractId") UUID contractId);
}
