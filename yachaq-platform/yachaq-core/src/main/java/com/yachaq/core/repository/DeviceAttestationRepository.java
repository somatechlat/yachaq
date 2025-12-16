package com.yachaq.core.repository;

import com.yachaq.core.domain.DeviceAttestation;
import com.yachaq.core.domain.DeviceAttestation.AttestationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for device attestation records.
 * 
 * Validates: Requirements 217.1, 217.2
 */
@Repository
public interface DeviceAttestationRepository extends JpaRepository<DeviceAttestation, UUID> {

    /**
     * Find all attestations for a device.
     */
    List<DeviceAttestation> findByDeviceId(UUID deviceId);

    /**
     * Find the latest valid attestation for a device.
     */
    @Query("SELECT a FROM DeviceAttestation a WHERE a.deviceId = :deviceId " +
           "AND a.status = 'VERIFIED' AND a.expiresAt > :now " +
           "ORDER BY a.verifiedAt DESC LIMIT 1")
    Optional<DeviceAttestation> findLatestValidAttestation(
            @Param("deviceId") UUID deviceId,
            @Param("now") Instant now);

    /**
     * Find attestation by nonce (for replay protection).
     */
    Optional<DeviceAttestation> findByNonce(String nonce);

    /**
     * Check if nonce has been used.
     */
    boolean existsByNonce(String nonce);

    /**
     * Find all expired attestations that need cleanup.
     */
    @Query("SELECT a FROM DeviceAttestation a WHERE a.expiresAt < :now " +
           "AND a.status = 'VERIFIED'")
    List<DeviceAttestation> findExpiredAttestations(@Param("now") Instant now);

    /**
     * Count attestations by status for a device.
     */
    long countByDeviceIdAndStatus(UUID deviceId, AttestationStatus status);

    /**
     * Find devices needing re-attestation (expired or about to expire).
     */
    @Query("SELECT DISTINCT a.deviceId FROM DeviceAttestation a " +
           "WHERE a.status = 'VERIFIED' AND a.expiresAt < :threshold")
    List<UUID> findDevicesNeedingReAttestation(@Param("threshold") Instant threshold);
}
