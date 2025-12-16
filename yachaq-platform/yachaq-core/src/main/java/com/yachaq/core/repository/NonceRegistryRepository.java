package com.yachaq.core.repository;

import com.yachaq.core.domain.NonceRegistry;
import com.yachaq.core.domain.NonceRegistry.NonceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Nonce Registry.
 * 
 * Property 16: Capsule Replay Protection
 * Validates: Requirements 218.1, 218.2
 */
@Repository
public interface NonceRegistryRepository extends JpaRepository<NonceRegistry, String> {

    /**
     * Find nonce by value.
     */
    Optional<NonceRegistry> findByNonce(String nonce);

    /**
     * Check if nonce exists.
     */
    boolean existsByNonce(String nonce);

    /**
     * Find all nonces for a capsule.
     */
    List<NonceRegistry> findByCapsuleId(UUID capsuleId);

    /**
     * Find all active nonces.
     */
    List<NonceRegistry> findByStatus(NonceStatus status);

    /**
     * Find expired nonces that need cleanup.
     */
    @Query("SELECT n FROM NonceRegistry n WHERE n.expiresAt < :now AND n.status = 'ACTIVE'")
    List<NonceRegistry> findExpiredNonces(@Param("now") Instant now);

    /**
     * Find used nonces (for audit/logging).
     */
    @Query("SELECT n FROM NonceRegistry n WHERE n.status = 'USED' AND n.usedAt > :since")
    List<NonceRegistry> findUsedNoncesSince(@Param("since") Instant since);

    /**
     * Count replay attempts (used nonces) for a capsule.
     */
    @Query("SELECT COUNT(n) FROM NonceRegistry n WHERE n.capsuleId = :capsuleId AND n.status = 'USED'")
    long countReplayAttempts(@Param("capsuleId") UUID capsuleId);
}
