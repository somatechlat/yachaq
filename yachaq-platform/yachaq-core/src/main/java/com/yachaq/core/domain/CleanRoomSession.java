package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Clean Room Session - Tracks controlled data access sessions.
 * 
 * Validates: Requirements 221.3, 221.4
 * 
 * Enforces output restrictions in clean room environment:
 * - VIEW_ONLY: Disable download, copy, screenshot
 * - AGGREGATE_ONLY: Return only aggregated results
 * - NO_EXPORT: Block all export attempts
 */
@Entity
@Table(name = "clean_room_sessions", indexes = {
    @Index(name = "idx_clean_room_capsule", columnList = "capsule_id"),
    @Index(name = "idx_clean_room_requester", columnList = "requester_id"),
    @Index(name = "idx_clean_room_status", columnList = "status"),
    @Index(name = "idx_clean_room_expires", columnList = "expires_at")
})
public class CleanRoomSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "capsule_id", nullable = false)
    private UUID capsuleId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    /**
     * JSON array of active output restrictions.
     */
    @Column(name = "output_restrictions", nullable = false, columnDefinition = "TEXT")
    private String outputRestrictions;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "export_attempts", nullable = false)
    private int exportAttempts = 0;

    @Column(name = "copy_attempts", nullable = false)
    private int copyAttempts = 0;

    @Column(name = "screenshot_attempts", nullable = false)
    private int screenshotAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SessionStatus status;

    @Column(name = "termination_reason", columnDefinition = "TEXT")
    private String terminationReason;

    public enum SessionStatus {
        ACTIVE,
        TERMINATED,
        EXPIRED
    }

    protected CleanRoomSession() {}

    /**
     * Creates a new clean room session.
     * 
     * @param capsuleId The time capsule being accessed
     * @param requesterId The requester accessing the data
     * @param consentContractId The consent contract authorizing access
     * @param outputRestrictions JSON array of output restrictions
     * @param ttlMinutes Session time-to-live in minutes
     */
    public static CleanRoomSession create(
            UUID capsuleId,
            UUID requesterId,
            UUID consentContractId,
            String outputRestrictions,
            int ttlMinutes) {
        
        if (capsuleId == null) {
            throw new IllegalArgumentException("Capsule ID cannot be null");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }
        if (outputRestrictions == null || outputRestrictions.isBlank()) {
            throw new IllegalArgumentException("Output restrictions cannot be null or blank");
        }
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        CleanRoomSession session = new CleanRoomSession();
        session.capsuleId = capsuleId;
        session.requesterId = requesterId;
        session.consentContractId = consentContractId;
        session.outputRestrictions = outputRestrictions;
        session.startedAt = Instant.now();
        session.expiresAt = session.startedAt.plusSeconds(ttlMinutes * 60L);
        session.status = SessionStatus.ACTIVE;
        return session;
    }

    /**
     * Checks if the session is active and not expired.
     */
    public boolean isActive() {
        return status == SessionStatus.ACTIVE && !isExpired();
    }

    /**
     * Checks if the session has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Records an export attempt.
     */
    public void recordExportAttempt() {
        this.exportAttempts++;
    }

    /**
     * Records a copy attempt.
     */
    public void recordCopyAttempt() {
        this.copyAttempts++;
    }

    /**
     * Records a screenshot attempt.
     */
    public void recordScreenshotAttempt() {
        this.screenshotAttempts++;
    }

    /**
     * Terminates the session.
     */
    public void terminate(String reason) {
        if (status != SessionStatus.ACTIVE) {
            throw new IllegalStateException("Session is not active");
        }
        this.status = SessionStatus.TERMINATED;
        this.terminatedAt = Instant.now();
        this.terminationReason = reason;
    }

    /**
     * Marks the session as expired.
     */
    public void markExpired() {
        this.status = SessionStatus.EXPIRED;
        this.terminatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getCapsuleId() { return capsuleId; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getConsentContractId() { return consentContractId; }
    public String getOutputRestrictions() { return outputRestrictions; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getTerminatedAt() { return terminatedAt; }
    public int getExportAttempts() { return exportAttempts; }
    public int getCopyAttempts() { return copyAttempts; }
    public int getScreenshotAttempts() { return screenshotAttempts; }
    public SessionStatus getStatus() { return status; }
    public String getTerminationReason() { return terminationReason; }
}
