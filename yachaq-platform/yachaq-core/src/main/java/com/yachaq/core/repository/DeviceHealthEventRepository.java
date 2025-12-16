package com.yachaq.core.repository;

import com.yachaq.core.domain.DeviceHealthEvent;
import com.yachaq.core.domain.DeviceHealthEvent.EventType;
import com.yachaq.core.domain.DeviceHealthEvent.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for device health events.
 * Validates: Requirements 251.4 (Device health monitoring)
 */
@Repository
public interface DeviceHealthEventRepository extends JpaRepository<DeviceHealthEvent, UUID> {

    /**
     * Find all health events for a device.
     */
    List<DeviceHealthEvent> findByDeviceId(UUID deviceId);

    /**
     * Find unresolved health events for a device.
     */
    @Query("SELECT e FROM DeviceHealthEvent e WHERE e.deviceId = :deviceId AND e.resolvedAt IS NULL")
    List<DeviceHealthEvent> findUnresolvedByDeviceId(@Param("deviceId") UUID deviceId);

    /**
     * Find critical unresolved events for a device.
     */
    @Query("SELECT e FROM DeviceHealthEvent e WHERE e.deviceId = :deviceId " +
           "AND e.severity = 'CRITICAL' AND e.resolvedAt IS NULL")
    List<DeviceHealthEvent> findCriticalUnresolvedByDeviceId(@Param("deviceId") UUID deviceId);

    /**
     * Check if device has any critical unresolved events.
     */
    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM DeviceHealthEvent e " +
           "WHERE e.deviceId = :deviceId AND e.severity = 'CRITICAL' AND e.resolvedAt IS NULL")
    boolean hasCriticalUnresolvedEvents(@Param("deviceId") UUID deviceId);

    /**
     * Find events by type for a device.
     */
    List<DeviceHealthEvent> findByDeviceIdAndEventType(UUID deviceId, EventType eventType);

    /**
     * Find events by severity for a device.
     */
    List<DeviceHealthEvent> findByDeviceIdAndSeverity(UUID deviceId, Severity severity);

    /**
     * Find recent events for a device.
     */
    @Query("SELECT e FROM DeviceHealthEvent e WHERE e.deviceId = :deviceId " +
           "AND e.detectedAt >= :since ORDER BY e.detectedAt DESC")
    List<DeviceHealthEvent> findRecentByDeviceId(
            @Param("deviceId") UUID deviceId, 
            @Param("since") Instant since);

    /**
     * Count unresolved events by severity for a device.
     */
    @Query("SELECT COUNT(e) FROM DeviceHealthEvent e WHERE e.deviceId = :deviceId " +
           "AND e.severity = :severity AND e.resolvedAt IS NULL")
    long countUnresolvedBySeverity(
            @Param("deviceId") UUID deviceId, 
            @Param("severity") Severity severity);

    /**
     * Check if device has root/jailbreak detected.
     */
    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM DeviceHealthEvent e " +
           "WHERE e.deviceId = :deviceId " +
           "AND (e.eventType = 'ROOT_DETECTED' OR e.eventType = 'JAILBREAK_DETECTED') " +
           "AND e.resolvedAt IS NULL")
    boolean hasRootOrJailbreakDetected(@Param("deviceId") UUID deviceId);
}
