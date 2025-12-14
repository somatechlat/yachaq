package com.yachaq.core.repository;

import com.yachaq.core.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for device management.
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {
    
    List<Device> findByDsId(UUID dsId);
    
    Optional<Device> findByDsIdAndPublicKey(UUID dsId, String publicKey);
    
    long countByDsId(UUID dsId);
    
    boolean existsByDsIdAndPublicKey(UUID dsId, String publicKey);
}
