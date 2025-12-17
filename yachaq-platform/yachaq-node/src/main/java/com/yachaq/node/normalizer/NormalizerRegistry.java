package com.yachaq.node.normalizer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for data normalizers with schema versioning support.
 * Requirement 308.3: Support versioned schema evolution.
 */
public class NormalizerRegistry {

    private final Map<String, DataNormalizer> normalizers;
    private final Map<String, SchemaVersion> schemaVersions;
    private final List<SchemaMigration> migrations;

    public NormalizerRegistry() {
        this.normalizers = new ConcurrentHashMap<>();
        this.schemaVersions = new ConcurrentHashMap<>();
        this.migrations = new ArrayList<>();
        
        // Register default normalizers
        registerDefaults();
    }

    private void registerDefaults() {
        register(new HealthDataNormalizer());
        register(new LocationDataNormalizer());
        register(new CommunicationDataNormalizer());
        register(new MediaDataNormalizer());
    }

    /**
     * Registers a normalizer for a source type.
     */
    public void register(DataNormalizer normalizer) {
        if (normalizer == null) {
            throw new IllegalArgumentException("Normalizer cannot be null");
        }
        normalizers.put(normalizer.getSourceType(), normalizer);
        schemaVersions.put(normalizer.getSourceType(), 
                new SchemaVersion(normalizer.getSourceType(), normalizer.getSchemaVersion()));
    }

    /**
     * Gets a normalizer for a source type.
     */
    public Optional<DataNormalizer> getNormalizer(String sourceType) {
        return Optional.ofNullable(normalizers.get(sourceType));
    }

    /**
     * Normalizes data using the appropriate normalizer.
     */
    public List<CanonicalEvent> normalize(String sourceType, Map<String, Object> rawData, String sourceId) {
        DataNormalizer normalizer = normalizers.get(sourceType);
        if (normalizer == null) {
            throw new NormalizationException("No normalizer registered for source type: " + sourceType);
        }
        return normalizer.normalize(rawData, sourceId);
    }

    /**
     * Normalizes a batch of data.
     */
    public List<CanonicalEvent> normalizeBatch(String sourceType, List<Map<String, Object>> records, String sourceId) {
        DataNormalizer normalizer = normalizers.get(sourceType);
        if (normalizer == null) {
            throw new NormalizationException("No normalizer registered for source type: " + sourceType);
        }
        return normalizer.normalizeBatch(records, sourceId);
    }

    /**
     * Gets all registered source types.
     */
    public Set<String> getRegisteredSourceTypes() {
        return Set.copyOf(normalizers.keySet());
    }

    /**
     * Gets the schema version for a source type.
     */
    public Optional<SchemaVersion> getSchemaVersion(String sourceType) {
        return Optional.ofNullable(schemaVersions.get(sourceType));
    }

    /**
     * Registers a schema migration.
     */
    public void registerMigration(SchemaMigration migration) {
        if (migration == null) {
            throw new IllegalArgumentException("Migration cannot be null");
        }
        migrations.add(migration);
        migrations.sort(Comparator.comparing(SchemaMigration::fromVersion));
    }

    /**
     * Migrates an event from one schema version to another.
     * Requirement 308.3: Support versioned schema evolution.
     */
    public CanonicalEvent migrate(CanonicalEvent event, String targetVersion) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }
        
        String currentVersion = event.schemaVersion();
        if (currentVersion.equals(targetVersion)) {
            return event;
        }

        CanonicalEvent migrated = event;
        for (SchemaMigration migration : migrations) {
            if (migration.appliesTo(migrated.schemaVersion()) && 
                compareVersions(migration.toVersion(), targetVersion) <= 0) {
                migrated = migration.migrate(migrated);
            }
        }
        
        return migrated;
    }

    /**
     * Validates data before normalization.
     */
    public DataNormalizer.ValidationResult validate(String sourceType, Map<String, Object> rawData) {
        DataNormalizer normalizer = normalizers.get(sourceType);
        if (normalizer == null) {
            return DataNormalizer.ValidationResult.failure("No normalizer for source type: " + sourceType);
        }
        return normalizer.validate(rawData);
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    // ==================== Inner Types ====================

    /**
     * Schema version information.
     */
    public record SchemaVersion(
            String sourceType,
            String version
    ) {}

    /**
     * Schema migration definition.
     */
    public interface SchemaMigration {
        String fromVersion();
        String toVersion();
        CanonicalEvent migrate(CanonicalEvent event);
        
        default boolean appliesTo(String version) {
            return fromVersion().equals(version);
        }
    }

    /**
     * Exception for normalization errors.
     */
    public static class NormalizationException extends RuntimeException {
        public NormalizationException(String message) {
            super(message);
        }

        public NormalizationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
