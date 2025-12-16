package com.yachaq.core.repository;

import com.yachaq.core.domain.SecureDeletionCertificate;
import com.yachaq.core.domain.SecureDeletionCertificate.DeletionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Secure Deletion Certificates.
 * 
 * Property 19: Secure Deletion Verification
 * Validates: Requirements 222.1, 222.4
 */
@Repository
public interface SecureDeletionCertificateRepository extends JpaRepository<SecureDeletionCertificate, UUID> {

    /**
     * Find certificates by resource type and ID.
     */
    List<SecureDeletionCertificate> findByResourceTypeAndResourceId(String resourceType, UUID resourceId);

    /**
     * Find the latest certificate for a resource.
     */
    @Query("SELECT c FROM SecureDeletionCertificate c " +
           "WHERE c.resourceType = :resourceType AND c.resourceId = :resourceId " +
           "ORDER BY c.initiatedAt DESC LIMIT 1")
    Optional<SecureDeletionCertificate> findLatestByResource(
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId);

    /**
     * Find certificates by status.
     */
    List<SecureDeletionCertificate> findByStatus(DeletionStatus status);

    /**
     * Find incomplete certificates (for retry/cleanup).
     */
    @Query("SELECT c FROM SecureDeletionCertificate c " +
           "WHERE c.status NOT IN ('COMPLETED', 'VERIFIED', 'FAILED') " +
           "AND c.initiatedAt < :cutoff")
    List<SecureDeletionCertificate> findIncompleteBefore(@Param("cutoff") Instant cutoff);

    /**
     * Find certificates by requester.
     */
    List<SecureDeletionCertificate> findByRequestedBy(UUID requestedBy);

    /**
     * Check if a resource has been deleted.
     */
    @Query("SELECT COUNT(c) > 0 FROM SecureDeletionCertificate c " +
           "WHERE c.resourceType = :resourceType AND c.resourceId = :resourceId " +
           "AND c.status IN ('COMPLETED', 'VERIFIED')")
    boolean isResourceDeleted(
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId);
}
