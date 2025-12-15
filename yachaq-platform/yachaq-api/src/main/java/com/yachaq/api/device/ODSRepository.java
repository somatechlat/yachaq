package com.yachaq.api.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for On-Device Data Store entries.
 */
@Repository
public interface ODSRepository extends JpaRepository<ODSEntry, UUID> {

    List<ODSEntry> findByDeviceIdAndDeletedAtIsNull(UUID deviceId);

    List<ODSEntry> findByDsIdAndDeletedAtIsNull(UUID dsId);

    List<ODSEntry> findByDsIdAndCategoryAndDeletedAtIsNull(UUID dsId, String category);

    @Query("SELECT o FROM ODSEntry o WHERE o.dsId = :dsId AND o.storageLocation = 'DEVICE' AND o.deletedAt IS NULL")
    List<ODSEntry> findOnDeviceEntriesByDsId(@Param("dsId") UUID dsId);

    @Query("SELECT o FROM ODSEntry o WHERE o.dsId = :dsId AND o.storageLocation = 'CLOUD_WITH_CONSENT' AND o.deletedAt IS NULL")
    List<ODSEntry> findCloudEntriesByDsId(@Param("dsId") UUID dsId);

    @Query("SELECT COUNT(o) FROM ODSEntry o WHERE o.dsId = :dsId AND o.storageLocation = 'DEVICE' AND o.deletedAt IS NULL")
    long countOnDeviceEntries(@Param("dsId") UUID dsId);

    @Query("SELECT COUNT(o) FROM ODSEntry o WHERE o.dsId = :dsId AND o.storageLocation = 'CLOUD_WITH_CONSENT' AND o.deletedAt IS NULL")
    long countCloudEntries(@Param("dsId") UUID dsId);

    @Query("SELECT DISTINCT o.category FROM ODSEntry o WHERE o.dsId = :dsId AND o.deletedAt IS NULL")
    List<String> findCategoriesByDsId(@Param("dsId") UUID dsId);

    List<ODSEntry> findByExpiresAtBeforeAndDeletedAtIsNull(Instant now);

    @Query("SELECT o FROM ODSEntry o WHERE o.deviceId = :deviceId AND o.category = :category AND o.deletedAt IS NULL ORDER BY o.timestamp DESC")
    List<ODSEntry> findByDeviceAndCategory(@Param("deviceId") UUID deviceId, @Param("category") String category);
}
