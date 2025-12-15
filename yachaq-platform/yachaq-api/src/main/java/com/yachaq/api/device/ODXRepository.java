package com.yachaq.api.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for On-Device Label Index entries.
 */
@Repository
public interface ODXRepository extends JpaRepository<ODXEntry, UUID> {

    List<ODXEntry> findByDeviceId(UUID deviceId);

    List<ODXEntry> findByDsId(UUID dsId);

    Optional<ODXEntry> findByDeviceIdAndFacetKeyAndTimeBucket(
        UUID deviceId, String facetKey, String timeBucket);

    @Query("SELECT o FROM ODXEntry o WHERE o.dsId = :dsId AND o.facetKey = :facetKey")
    List<ODXEntry> findByDsIdAndFacetKey(@Param("dsId") UUID dsId, @Param("facetKey") String facetKey);

    @Query("SELECT DISTINCT o.facetKey FROM ODXEntry o WHERE o.dsId = :dsId")
    List<String> findDistinctFacetKeysByDsId(@Param("dsId") UUID dsId);

    @Query("SELECT o FROM ODXEntry o WHERE o.dsId = :dsId AND o.kMin >= :minK")
    List<ODXEntry> findByDsIdWithMinimumK(@Param("dsId") UUID dsId, @Param("minK") Integer minK);

    @Query("SELECT SUM(o.count) FROM ODXEntry o WHERE o.dsId = :dsId AND o.facetKey = :facetKey")
    Long sumCountByDsIdAndFacetKey(@Param("dsId") UUID dsId, @Param("facetKey") String facetKey);

    @Query("SELECT o FROM ODXEntry o WHERE o.deviceSignature IS NOT NULL AND o.deviceId = :deviceId")
    List<ODXEntry> findSignedEntriesByDeviceId(@Param("deviceId") UUID deviceId);

    @Query("SELECT COUNT(o) FROM ODXEntry o WHERE o.dsId = :dsId AND o.quality = :quality")
    long countByDsIdAndQuality(@Param("dsId") UUID dsId, @Param("quality") ODXEntry.Quality quality);

    @Query("SELECT o FROM ODXEntry o WHERE o.facetKey LIKE :namespace% AND o.kMin >= :minK")
    List<ODXEntry> findByNamespaceWithMinK(@Param("namespace") String namespace, @Param("minK") Integer minK);
}
