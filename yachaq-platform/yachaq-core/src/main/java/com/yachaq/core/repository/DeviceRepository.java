package com.yachaq.core.repository;

import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.Device.DeviceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for device management.
 * Supports multi-device identity linking (Property 24).
 * Validates: Requirements 251.1, 251.2, 251.3
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {
    
    /**
     * Find all devices for a DS.
     */
    List<Device> findByDsId(UUID dsId);
    
    /**
     * Find device by DS ID and public key.
     */
    Optional<Device> findByDsIdAndPublicKey(UUID dsId, String publicKey);
    
    /**
     * Count all devices for a DS.
     */
    long countByDsId(UUID dsId);
    
    /**
     * Check if device exists by DS ID and public key.
     */
    boolean existsByDsIdAndPublicKey(UUID dsId, String publicKey);

    // Multi-device support queries (Property 24)

    /**
     * Find all active (non-disabled) devices for a DS.
     * Requirement 251.1: DS↔Device graph
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId AND d.disabledAt IS NULL")
    List<Device> findActiveByDsId(@Param("dsId") UUID dsId);

    /**
     * Count active devices for a DS.
     * Requirement 251.2: Device slot limits
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :dsId AND d.disabledAt IS NULL")
    long countActiveByDsId(@Param("dsId") UUID dsId);

    /**
     * Count active devices by type for a DS.
     * Requirement 251.2: Per-type device limits
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :dsId AND d.deviceType = :deviceType AND d.disabledAt IS NULL")
    long countActiveByDsIdAndDeviceType(@Param("dsId") UUID dsId, @Param("deviceType") DeviceType deviceType);

    /**
     * Count active mobile devices for a DS.
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :dsId " +
           "AND (d.deviceType = 'MOBILE_ANDROID' OR d.deviceType = 'MOBILE_IOS') " +
           "AND d.disabledAt IS NULL")
    long countActiveMobileByDsId(@Param("dsId") UUID dsId);

    /**
     * Count active desktop devices for a DS.
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :dsId " +
           "AND d.deviceType = 'DESKTOP' AND d.disabledAt IS NULL")
    long countActiveDesktopByDsId(@Param("dsId") UUID dsId);

    /**
     * Count active IoT devices for a DS.
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :dsId " +
           "AND d.deviceType = 'IOT' AND d.disabledAt IS NULL")
    long countActiveIoTByDsId(@Param("dsId") UUID dsId);

    /**
     * Find the primary device for a DS.
     * Requirement 251.1: Primary device identification
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId AND d.isPrimary = true AND d.disabledAt IS NULL")
    Optional<Device> findPrimaryByDsId(@Param("dsId") UUID dsId);

    /**
     * Check if DS has a primary device.
     */
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END FROM Device d " +
           "WHERE d.dsId = :dsId AND d.isPrimary = true AND d.disabledAt IS NULL")
    boolean hasPrimaryDevice(@Param("dsId") UUID dsId);

    /**
     * Find all verified and active devices for a DS.
     * Used for query routing.
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId " +
           "AND d.attestationStatus = 'VERIFIED' AND d.disabledAt IS NULL")
    List<Device> findVerifiedActiveByDsId(@Param("dsId") UUID dsId);

    /**
     * Find devices eligible for queries (active, verified, good trust score).
     * Property 24: Query routing to appropriate devices
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId " +
           "AND d.attestationStatus = 'VERIFIED' " +
           "AND d.disabledAt IS NULL " +
           "AND d.trustScore >= 0.3 " +
           "ORDER BY d.isPrimary DESC, d.trustScore DESC, d.lastSeen DESC")
    List<Device> findEligibleForQueries(@Param("dsId") UUID dsId);

    /**
     * Find devices that have a specific data category.
     * Used for data-location-based query routing.
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId " +
           "AND d.dataCategories LIKE %:category% " +
           "AND d.attestationStatus = 'VERIFIED' " +
           "AND d.disabledAt IS NULL")
    List<Device> findByDsIdAndDataCategory(@Param("dsId") UUID dsId, @Param("category") String category);

    /**
     * Find the replacement device for a disabled device.
     */
    @Query("SELECT d FROM Device d WHERE d.id = :replacementId AND d.disabledAt IS NULL")
    Optional<Device> findReplacementDevice(@Param("replacementId") UUID replacementId);

    /**
     * Find all disabled devices for a DS.
     */
    @Query("SELECT d FROM Device d WHERE d.dsId = :dsId AND d.disabledAt IS NOT NULL")
    List<Device> findDisabledByDsId(@Param("dsId") UUID dsId);
}
