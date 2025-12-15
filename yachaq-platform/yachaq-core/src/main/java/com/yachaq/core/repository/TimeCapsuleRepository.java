package com.yachaq.core.repository;

import com.yachaq.core.domain.TimeCapsule;
import com.yachaq.core.domain.TimeCapsule.CapsuleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Time Capsules.
 * 
 * Property 13: Time Capsule TTL Enforcement
 * Validates: Requirements 206.2
 */
@Repository
public interface TimeCapsuleRepository extends JpaRepository<TimeCapsule, UUID> {

    Optional<TimeCapsule> findByNonce(String nonce);

    List<TimeCapsule> findByRequestId(UUID requestId);

    List<TimeCapsule> findByConsentContractId(UUID consentContractId);

    List<TimeCapsule> findByStatus(CapsuleStatus status);

    @Query("SELECT tc FROM TimeCapsule tc WHERE tc.ttl < :now AND tc.status NOT IN ('EXPIRED', 'DELETED')")
    List<TimeCapsule> findExpiredCapsules(@Param("now") Instant now);

    @Query("SELECT tc FROM TimeCapsule tc WHERE tc.ttl < :threshold AND tc.status = 'EXPIRED'")
    List<TimeCapsule> findCapsulesDueForDeletion(@Param("threshold") Instant threshold);

    boolean existsByNonce(String nonce);
}
