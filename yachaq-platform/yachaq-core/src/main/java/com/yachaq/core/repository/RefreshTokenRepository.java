package com.yachaq.core.repository;

import com.yachaq.core.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for refresh token management.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    
    List<RefreshToken> findByDsIdAndRevokedAtIsNull(UUID dsId);
    
    List<RefreshToken> findByDeviceIdAndRevokedAtIsNull(UUID deviceId);
    
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.dsId = :dsId AND t.revokedAt IS NULL")
    int revokeAllByDsId(UUID dsId, Instant now);
    
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.deviceId = :deviceId AND t.revokedAt IS NULL")
    int revokeAllByDeviceId(UUID deviceId, Instant now);
}
