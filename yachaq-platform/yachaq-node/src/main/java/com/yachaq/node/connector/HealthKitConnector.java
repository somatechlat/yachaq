package com.yachaq.node.connector;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iOS HealthKit Framework Connector.
 * Requirement 305.1: Framework connectors use OS-level APIs.
 * Requirement 305.2: NO scraping, keylogging, screen reading, or bypassing.
 * Requirement 305.3: Use official OS permission flows.
 * 
 * This connector interfaces with Apple HealthKit through official APIs.
 * On actual iOS devices, this would use the HealthKit framework.
 * This implementation provides the interface contract and data structures.
 */
public class HealthKitConnector extends AbstractConnector {

    public static final String CONNECTOR_ID = "healthkit";
    
    // HealthKit data types
    public static final String TYPE_SLEEP = "sleep_analysis";
    public static final String TYPE_WORKOUT = "workout";
    public static final String TYPE_HEART_RATE = "heart_rate";
    public static final String TYPE_STEPS = "step_count";
    public static final String TYPE_ACTIVE_ENERGY = "active_energy_burned";
    public static final String TYPE_DISTANCE = "distance_walking_running";
    public static final String TYPE_WEIGHT = "body_mass";
    public static final String TYPE_HEIGHT = "height";
    public static final String TYPE_BLOOD_PRESSURE = "blood_pressure";
    public static final String TYPE_BLOOD_GLUCOSE = "blood_glucose";

    private static final ConnectorCapabilities CAPABILITIES = ConnectorCapabilities.builder()
            .dataTypes(List.of(
                    TYPE_SLEEP, TYPE_WORKOUT, TYPE_HEART_RATE, TYPE_STEPS,
                    TYPE_ACTIVE_ENERGY, TYPE_DISTANCE, TYPE_WEIGHT, TYPE_HEIGHT,
                    TYPE_BLOOD_PRESSURE, TYPE_BLOOD_GLUCOSE
            ))
            .labelFamilies(List.of("health", "activity", "biometrics"))
            .supportsIncremental(true)
            .requiresOAuth(false) // Uses OS permissions, not OAuth
            .supportedPlatforms(Set.of("ios"))
            .rateLimitPerMinute(60) // HealthKit has no strict rate limit, but we self-impose
            .maxBatchSize(1000)
            .build();

    private final HealthKitBridge bridge;
    private final Map<String, String> syncCursors;
    private Instant lastSyncTime;

    /**
     * Creates a HealthKit connector with the default bridge.
     */
    public HealthKitConnector() {
        this(new DefaultHealthKitBridge());
    }

    /**
     * Creates a HealthKit connector with a custom bridge.
     * Allows injection for testing.
     */
    public HealthKitConnector(HealthKitBridge bridge) {
        super(CONNECTOR_ID, ConnectorType.FRAMEWORK);
        this.bridge = bridge;
        this.syncCursors = new ConcurrentHashMap<>();
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected CompletableFuture<AuthResult> doAuthorize() {
        // Request HealthKit authorization through official OS flow
        return bridge.requestAuthorization(CAPABILITIES.dataTypes())
                .thenApply(result -> {
                    if (result.allGranted()) {
                        return AuthResult.success(
                                result.grantedTypes(),
                                Instant.now().plus(365, ChronoUnit.DAYS) // HealthKit auth doesn't expire
                        );
                    } else if (!result.grantedTypes().isEmpty()) {
                        return AuthResult.partial(
                                result.grantedTypes(),
                                result.deniedTypes(),
                                Instant.now().plus(365, ChronoUnit.DAYS)
                        );
                    } else {
                        return AuthResult.failure("ALL_DENIED", "User denied all HealthKit permissions");
                    }
                });
    }

    @Override
    protected CompletableFuture<SyncResult> doSync(String sinceCursor) {
        // Parse cursor to get last sync timestamp
        Instant since = parseCursor(sinceCursor);
        
        return bridge.queryHealthData(CAPABILITIES.dataTypes(), since, CAPABILITIES.maxBatchSize())
                .thenApply(data -> {
                    if (data.isEmpty()) {
                        return SyncResult.noNewData(sinceCursor);
                    }

                    List<SyncItem> items = data.stream()
                            .map(this::convertToSyncItem)
                            .toList();

                    // Create new cursor based on latest item timestamp
                    Instant latestTimestamp = items.stream()
                            .map(SyncItem::timestamp)
                            .max(Instant::compareTo)
                            .orElse(Instant.now());
                    
                    String newCursor = createCursor(latestTimestamp);
                    lastSyncTime = Instant.now();

                    boolean hasMore = items.size() >= CAPABILITIES.maxBatchSize();
                    return SyncResult.success(items, newCursor, hasMore);
                })
                .exceptionally(ex -> SyncResult.failure("HEALTHKIT_ERROR", ex.getMessage()));
    }

    @Override
    protected CompletableFuture<HealthStatus> doHealthcheck() {
        return bridge.checkAvailability()
                .thenApply(available -> {
                    if (available) {
                        return HealthStatus.healthy(lastSyncTime, Map.of(
                                "platform", "ios",
                                "framework", "HealthKit"
                        ));
                    } else {
                        return HealthStatus.unhealthy("UNAVAILABLE", "HealthKit not available on this device");
                    }
                });
    }

    @Override
    protected CompletableFuture<Void> doRevokeAuthorization() {
        // HealthKit authorization cannot be revoked programmatically
        // User must revoke in Settings app
        syncCursors.clear();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Converts HealthKit data to SyncItem.
     */
    private SyncItem convertToSyncItem(HealthKitRecord record) {
        return SyncItem.builder()
                .itemId(record.uuid())
                .recordType(record.type())
                .timestamp(record.startDate())
                .endTimestamp(record.endDate())
                .data(record.values())
                .metadata(Map.of(
                        "source", record.sourceName(),
                        "device", record.deviceName() != null ? record.deviceName() : "unknown"
                ))
                .sourceId(CONNECTOR_ID)
                .checksum(record.checksum())
                .build();
    }

    /**
     * Parses cursor to extract timestamp.
     */
    private Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(cursor);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Creates cursor from timestamp.
     */
    private String createCursor(Instant timestamp) {
        return timestamp.toString();
    }

    // ==================== Bridge Interface ====================

    /**
     * Bridge interface for HealthKit operations.
     * Allows platform-specific implementation and testing.
     */
    public interface HealthKitBridge {
        CompletableFuture<AuthorizationResult> requestAuthorization(List<String> dataTypes);
        CompletableFuture<List<HealthKitRecord>> queryHealthData(List<String> types, Instant since, int limit);
        CompletableFuture<Boolean> checkAvailability();
    }

    /**
     * Authorization result from HealthKit.
     */
    public record AuthorizationResult(
            Set<String> grantedTypes,
            Set<String> deniedTypes
    ) {
        public boolean allGranted() {
            return deniedTypes.isEmpty();
        }
    }

    /**
     * A single HealthKit record.
     */
    public record HealthKitRecord(
            String uuid,
            String type,
            Instant startDate,
            Instant endDate,
            Map<String, Object> values,
            String sourceName,
            String deviceName,
            String checksum
    ) {}

    /**
     * Default bridge implementation.
     * In production, this would call actual HealthKit APIs.
     */
    public static class DefaultHealthKitBridge implements HealthKitBridge {
        @Override
        public CompletableFuture<AuthorizationResult> requestAuthorization(List<String> dataTypes) {
            // In production: Call HKHealthStore.requestAuthorization()
            // This default returns success for testing/development
            return CompletableFuture.completedFuture(
                    new AuthorizationResult(Set.copyOf(dataTypes), Set.of())
            );
        }

        @Override
        public CompletableFuture<List<HealthKitRecord>> queryHealthData(List<String> types, Instant since, int limit) {
            // In production: Call HKHealthStore.execute(HKSampleQuery)
            // This default returns empty for testing/development
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Boolean> checkAvailability() {
            // In production: Call HKHealthStore.isHealthDataAvailable()
            return CompletableFuture.completedFuture(true);
        }
    }
}
