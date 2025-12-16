package com.yachaq.core.repository;

import com.yachaq.core.domain.CleanRoomSession;
import com.yachaq.core.domain.CleanRoomSession.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CleanRoomSession entities.
 * 
 * Validates: Requirements 221.3, 221.4
 */
@Repository
public interface CleanRoomSessionRepository extends JpaRepository<CleanRoomSession, UUID> {

    /**
     * Find active session for a capsule.
     */
    Optional<CleanRoomSession> findByCapsuleIdAndStatus(UUID capsuleId, SessionStatus status);

    /**
     * Find all sessions for a requester.
     */
    List<CleanRoomSession> findByRequesterIdOrderByStartedAtDesc(UUID requesterId);

    /**
     * Find expired sessions that need cleanup.
     */
    @Query("SELECT s FROM CleanRoomSession s WHERE s.status = 'ACTIVE' AND s.expiresAt < :now")
    List<CleanRoomSession> findExpiredSessions(@Param("now") Instant now);

    /**
     * Count active sessions for a requester.
     */
    long countByRequesterIdAndStatus(UUID requesterId, SessionStatus status);

    /**
     * Find sessions by consent contract.
     */
    List<CleanRoomSession> findByConsentContractId(UUID consentContractId);
}
