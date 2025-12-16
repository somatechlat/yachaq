package com.yachaq.node.connector;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Health status of a connector.
 */
public record HealthStatus(
        Status status,
        Instant checkedAt,
        Instant lastSuccessfulSync,
        String errorCode,
        String errorMessage,
        Map<String, Object> details
) {
    public HealthStatus {
        details = details != null ? Map.copyOf(details) : Map.of();
    }

    /**
     * Creates a healthy status.
     */
    public static HealthStatus healthy(Instant lastSync) {
        return new HealthStatus(Status.HEALTHY, Instant.now(), lastSync, null, null, Map.of());
    }

    /**
     * Creates a healthy status with details.
     */
    public static HealthStatus healthy(Instant lastSync, Map<String, Object> details) {
        return new HealthStatus(Status.HEALTHY, Instant.now(), lastSync, null, null, details);
    }

    /**
     * Creates a degraded status.
     */
    public static HealthStatus degraded(String errorCode, String errorMessage, Instant lastSync) {
        return new HealthStatus(Status.DEGRADED, Instant.now(), lastSync, errorCode, errorMessage, Map.of());
    }

    /**
     * Creates an unhealthy status.
     */
    public static HealthStatus unhealthy(String errorCode, String errorMessage) {
        return new HealthStatus(Status.UNHEALTHY, Instant.now(), null, errorCode, errorMessage, Map.of());
    }

    /**
     * Creates an unauthorized status.
     */
    public static HealthStatus unauthorized() {
        return new HealthStatus(Status.UNAUTHORIZED, Instant.now(), null, "UNAUTHORIZED", "Connector not authorized", Map.of());
    }

    /**
     * Creates an unknown status.
     */
    public static HealthStatus unknown(String reason) {
        return new HealthStatus(Status.UNKNOWN, Instant.now(), null, "UNKNOWN", reason, Map.of());
    }

    /**
     * Returns the last successful sync time if present.
     */
    public Optional<Instant> getLastSuccessfulSync() {
        return Optional.ofNullable(lastSuccessfulSync);
    }

    /**
     * Returns whether the connector is operational.
     */
    public boolean isOperational() {
        return status == Status.HEALTHY || status == Status.DEGRADED;
    }

    /**
     * Health status levels.
     */
    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNAUTHORIZED,
        UNKNOWN
    }
}
