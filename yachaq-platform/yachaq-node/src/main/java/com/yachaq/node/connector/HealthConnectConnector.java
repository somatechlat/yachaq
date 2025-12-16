package com.yachaq.node.connector;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android Health Connect Framework Connector.
 * Requirement 305.1: Framework connectors use OS-level APIs.
 * Requirement 305.2: NO scraping, keylogging, screen reading, or bypassing.
 * Requirement 305.3: Use official OS permission flows.
 * 
 * This connector interfaces with Android Health Connect through official APIs.
 * On actual Android devices, this would use the Health Connect SDK.
 * This implementation provides the interface contract and data structures.
 */
public class HealthConnectConnector extends AbstractConnector {

    public static final String CONNECTOR_ID = "health_connect";
    
    // Health Connect record types
    public static final String TYPE_SLEEP_SESSION = "SleepSessionRecord";
    public static final String TYPE_EXERCISE_SESSION = "ExerciseSessionRecord";
    public static final String TYPE_HEART_RATE = "HeartRateRecord";
    public static final String TYPE_STEPS = "StepsRecord";
    public static final String TYPE_ACTIVE_CALORIES = "ActiveCaloriesBurnedRecord";
    public static final String TYPE_DISTANCE = "DistanceRecord";
    public static final String TYPE_WEIGHT = "WeightRecord";
    public static final String TYPE_HEIGHT = "HeightRecord";
    public static final String TYPE_BLOOD_PRESSURE = "BloodPressureRecord";
    public static final String TYPE_BLOOD_GLUCOSE = "BloodGlucoseRecord";
    public static final String TYPE_NUTRITION = "NutritionRecord";
    public static final String TYPE_HYDRATION = "HydrationRecord";

    private static final ConnectorCapabilities CAPABILITIES = ConnectorCapabilities.builder()
            .dataTypes(List.of(
                    TYPE_SLEEP_SESSION, TYPE_EXERCISE_SESSION, TYPE_HEART_RATE, TYPE_STEPS,
                    TYPE_ACTIVE_CALORIES, TYPE_DISTANCE, TYPE_WEIGHT, TYPE_HEIGHT,
                    TYPE_BLOOD_PRESSURE, TYPE_BLOOD_GLUCOSE, TYPE_NUTRITION, TYPE_HYDRATION
            ))
            .labelFamilies(List.of("health", "activity", "biometrics", "nutrition"))
            .supportsIncremental(true)
            .requiresOAuth(false) // Uses OS permissions, not OAuth
            .supportedPlatforms(Set.of("android"))
            .rateLimitPerMinute(120) // Health Connect has generous limits
            .maxBatchSize(1000)
            .build();

    private final HealthConnectBridge bridge;
    private final Map<String, String> changeTokens;
    private Instant lastSyncTime;

    /**
     * Creates a Health Connect connector with the default bridge.
     */
    public HealthConnectConnector() {
        this(new DefaultHealthConnectBridge());
    }

    /**
     * Creates a Health Connect connector with a custom bridge.
     * Allows injection for testing.
     */
    public HealthConnectConnector(HealthConnectBridge bridge) {
        super(CONNECTOR_ID, ConnectorType.FRAMEWORK);
        this.bridge = bridge;
        this.changeTokens = new ConcurrentHashMap<>();
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected CompletableFuture<AuthResult> doAuthorize() {
        // Request Health Connect permissions through official OS flow
        return bridge.requestPermissions(CAPABILITIES.dataTypes())
                .thenApply(result -> {
                    if (result.allGranted()) {
                        return AuthResult.success(
                                result.grantedPermissions(),
                                Instant.now().plus(365, ChronoUnit.DAYS)
                        );
                    } else if (!result.grantedPermissions().isEmpty()) {
                        return AuthResult.partial(
                                result.grantedPermissions(),
                                result.deniedPermissions(),
                                Instant.now().plus(365, ChronoUnit.DAYS)
                        );
                    } else {
                        return AuthResult.failure("ALL_DENIED", "User denied all Health Connect permissions");
                    }
                });
    }

    @Override
    protected CompletableFuture<SyncResult> doSync(String sinceCursor) {
        // Health Connect supports change tokens for efficient incremental sync
        String changeToken = sinceCursor;
        
        return bridge.getChanges(changeToken, CAPABILITIES.dataTypes(), CAPABILITIES.maxBatchSize())
                .thenApply(changes -> {
                    if (changes.records().isEmpty() && !changes.hasMorePages()) {
                        return SyncResult.noNewData(changes.nextToken());
                    }

                    List<SyncItem> items = changes.records().stream()
                            .map(this::convertToSyncItem)
                            .toList();

                    lastSyncTime = Instant.now();
                    
                    return SyncResult.success(items, changes.nextToken(), changes.hasMorePages());
                })
                .exceptionally(ex -> {
                    // Handle token expiration - need full resync
                    if (ex.getMessage() != null && ex.getMessage().contains("TOKEN_EXPIRED")) {
                        return SyncResult.failure("TOKEN_EXPIRED", "Change token expired, full resync required");
                    }
                    return SyncResult.failure("HEALTH_CONNECT_ERROR", ex.getMessage());
                });
    }

    @Override
    protected CompletableFuture<HealthStatus> doHealthcheck() {
        return bridge.checkSdkStatus()
                .thenApply(status -> {
                    return switch (status) {
                        case AVAILABLE -> HealthStatus.healthy(lastSyncTime, Map.of(
                                "platform", "android",
                                "sdk", "Health Connect"
                        ));
                        case NOT_INSTALLED -> HealthStatus.unhealthy(
                                "NOT_INSTALLED", "Health Connect app not installed"
                        );
                        case NOT_SUPPORTED -> HealthStatus.unhealthy(
                                "NOT_SUPPORTED", "Health Connect not supported on this device"
                        );
                        case UPDATE_REQUIRED -> HealthStatus.degraded(
                                "UPDATE_REQUIRED", "Health Connect update required", lastSyncTime
                        );
                    };
                });
    }

    @Override
    protected CompletableFuture<Void> doRevokeAuthorization() {
        return bridge.revokeAllPermissions()
                .thenRun(() -> changeTokens.clear());
    }

    /**
     * Converts Health Connect record to SyncItem.
     */
    private SyncItem convertToSyncItem(HealthConnectRecord record) {
        return SyncItem.builder()
                .itemId(record.id())
                .recordType(record.recordType())
                .timestamp(record.startTime())
                .endTimestamp(record.endTime())
                .data(record.values())
                .metadata(Map.of(
                        "source", record.dataOrigin(),
                        "clientRecordId", record.clientRecordId() != null ? record.clientRecordId() : ""
                ))
                .sourceId(CONNECTOR_ID)
                .checksum(record.checksum())
                .build();
    }

    // ==================== Bridge Interface ====================

    /**
     * Bridge interface for Health Connect operations.
     * Allows platform-specific implementation and testing.
     */
    public interface HealthConnectBridge {
        CompletableFuture<PermissionResult> requestPermissions(List<String> recordTypes);
        CompletableFuture<ChangesResponse> getChanges(String token, List<String> recordTypes, int limit);
        CompletableFuture<SdkStatus> checkSdkStatus();
        CompletableFuture<Void> revokeAllPermissions();
    }

    /**
     * Permission request result.
     */
    public record PermissionResult(
            Set<String> grantedPermissions,
            Set<String> deniedPermissions
    ) {
        public boolean allGranted() {
            return deniedPermissions.isEmpty();
        }
    }

    /**
     * Changes response from Health Connect.
     */
    public record ChangesResponse(
            List<HealthConnectRecord> records,
            String nextToken,
            boolean hasMorePages
    ) {}

    /**
     * A single Health Connect record.
     */
    public record HealthConnectRecord(
            String id,
            String recordType,
            Instant startTime,
            Instant endTime,
            Map<String, Object> values,
            String dataOrigin,
            String clientRecordId,
            String checksum
    ) {}

    /**
     * Health Connect SDK availability status.
     */
    public enum SdkStatus {
        AVAILABLE,
        NOT_INSTALLED,
        NOT_SUPPORTED,
        UPDATE_REQUIRED
    }

    /**
     * Default bridge implementation.
     * In production, this would call actual Health Connect APIs.
     */
    public static class DefaultHealthConnectBridge implements HealthConnectBridge {
        @Override
        public CompletableFuture<PermissionResult> requestPermissions(List<String> recordTypes) {
            // In production: Call PermissionController.requestPermissions()
            return CompletableFuture.completedFuture(
                    new PermissionResult(Set.copyOf(recordTypes), Set.of())
            );
        }

        @Override
        public CompletableFuture<ChangesResponse> getChanges(String token, List<String> recordTypes, int limit) {
            // In production: Call HealthConnectClient.getChanges()
            return CompletableFuture.completedFuture(
                    new ChangesResponse(List.of(), token != null ? token : "initial_token", false)
            );
        }

        @Override
        public CompletableFuture<SdkStatus> checkSdkStatus() {
            // In production: Call HealthConnectClient.getSdkStatus()
            return CompletableFuture.completedFuture(SdkStatus.AVAILABLE);
        }

        @Override
        public CompletableFuture<Void> revokeAllPermissions() {
            // In production: Call PermissionController.revokeAllPermissions()
            return CompletableFuture.completedFuture(null);
        }
    }
}
