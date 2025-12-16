package com.yachaq.core.repository;

import com.yachaq.core.domain.DestroyedKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DestroyedKeyRecord entities.
 * 
 * Property 19: Secure Deletion Verification
 * Validates: Requirements 222.1, 222.2
 */
@Repository
public interface DestroyedKeyRecordRepository extends JpaRepository<DestroyedKeyRecord, UUID> {

    /**
     * Check if a key has been destroyed.
     */
    boolean existsByKeyId(String keyId);

    /**
     * Find a destroyed key record by key ID.
     */
    Optional<DestroyedKeyRecord> findByKeyId(String keyId);

    /**
     * Find destroyed keys for a resource.
     */
    List<DestroyedKeyRecord> findByAssociatedResourceTypeAndAssociatedResourceId(
            String resourceType, UUID resourceId);

    /**
     * Find destroyed keys by certificate.
     */
    List<DestroyedKeyRecord> findByCertificateId(UUID certificateId);
}
