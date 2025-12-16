package com.yachaq.core.repository;

import com.yachaq.core.domain.DeviceDataLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for device data locations.
 * Tracks which data categories are stored on which device.
 * Validates: Requirements 224.2 (Query routing based on data location)
 */
@Repository
public interface DeviceDataLocationRepository extends JpaRepository<DeviceDataLocation, UUID> {

    /**
     * Find all data locations for a device.
     */
    List<DeviceDataLocation> findByDeviceId(UUID deviceId);

    /**
     * Find data location by device and category.
     */
    Optional<DeviceDataLocation> findByDeviceIdAndDataCategory(UUID deviceId, String dataCategory);

    /**
     * Find all devices that have a specific data category.
     */
    @Query("SELECT dl.deviceId FROM DeviceDataLocation dl WHERE dl.dataCategory = :category AND dl.recordCount > 0")
    List<UUID> findDeviceIdsWithCategory(@Param("category") String category);

    /**
     * Find all data categories for a device.
     */
    @Query("SELECT dl.dataCategory FROM DeviceDataLocation dl WHERE dl.deviceId = :deviceId AND dl.recordCount > 0")
    List<String> findCategoriesByDeviceId(@Param("deviceId") UUID deviceId);

    /**
     * Check if device has a specific data category.
     */
    @Query("SELECT CASE WHEN COUNT(dl) > 0 THEN true ELSE false END FROM DeviceDataLocation dl " +
           "WHERE dl.deviceId = :deviceId AND dl.dataCategory = :category AND dl.recordCount > 0")
    boolean hasCategory(@Param("deviceId") UUID deviceId, @Param("category") String category);

    /**
     * Get total record count for a device.
     */
    @Query("SELECT COALESCE(SUM(dl.recordCount), 0) FROM DeviceDataLocation dl WHERE dl.deviceId = :deviceId")
    long getTotalRecordCount(@Param("deviceId") UUID deviceId);

    /**
     * Get total size for a device.
     */
    @Query("SELECT COALESCE(SUM(dl.sizeBytes), 0) FROM DeviceDataLocation dl WHERE dl.deviceId = :deviceId")
    long getTotalSizeBytes(@Param("deviceId") UUID deviceId);

    /**
     * Delete all data locations for a device.
     */
    void deleteByDeviceId(UUID deviceId);
}
