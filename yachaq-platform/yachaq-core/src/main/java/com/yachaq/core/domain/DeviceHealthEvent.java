package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Device health monitoring event.
 * Tracks root/jailbreak detection, integrity failures, and other health signals.
 * 
 * Validates: Requirements 251.4 (Device health monitoring)
 * Property 24: Multi-Device Identity Linking
 */
@Entity
@Table(name = "device_health_events", indexes = {
    @Index(name = "idx_health_device", columnList = "device_id"),
    @Index(name = "idx_health_type", columnList = "event_type"),
    @Index(name = "idx_health_severity", columnList = "severity")
})
public class DeviceHealthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(columnDefinition = "TEXT")
    private String details;

    @NotNull
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    public enum EventType {
        ROOT_DETECTED,        // Android root detected
        JAILBREAK_DETECTED,   // iOS jailbreak detected
        INTEGRITY_FAILED,     // Device integrity check failed
        ATTESTATION_EXPIRED,  // Device attestation expired
        SUSPICIOUS_ACTIVITY,  // Suspicious behavior detected
        OFFLINE_EXTENDED,     // Device offline for extended period
        KEY_ROTATION,         // Device key was rotated
        DEVICE_REPLACED,      // Device was replaced
        HEALTH_CHECK_PASSED   // Periodic health check passed
    }

    public enum Severity {
        CRITICAL,  // Immediate action required (root/jailbreak)
        HIGH,      // Action within 24 hours
        MEDIUM,    // Action within 7 days
        LOW,       // Advisory only
        INFO       // Informational event
    }

    protected DeviceHealthEvent() {}

    /**
     * Creates a new device health event.
     */
    public static DeviceHealthEvent create(
            UUID deviceId,
            EventType eventType,
            Severity severity,
            String details) {
        
        if (deviceId == null) {
            throw new IllegalArgumentException("Device ID is required");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("Event type is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("Severity is required");
        }

        var event = new DeviceHealthEvent();
        event.deviceId = deviceId;
        event.eventType = eventType;
        event.severity = severity;
        event.details = details;
        event.detectedAt = Instant.now();
        return event;
    }

    /**
     * Resolves this health event.
     */
    public void resolve(String notes) {
        if (this.resolvedAt != null) {
            throw new IllegalStateException("Event is already resolved");
        }
        this.resolvedAt = Instant.now();
        this.resolutionNotes = notes;
    }

    /**
     * Checks if this event is resolved.
     */
    public boolean isResolved() {
        return this.resolvedAt != null;
    }

    /**
     * Checks if this event requires immediate action.
     */
    public boolean requiresImmediateAction() {
        return severity == Severity.CRITICAL && !isResolved();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public EventType getEventType() { return eventType; }
    public Severity getSeverity() { return severity; }
    public String getDetails() { return details; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolutionNotes() { return resolutionNotes; }
}
