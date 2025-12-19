package com.yachaq.node.onboarding;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data Sources & Connectors Manager for Provider App.
 * Manages connector enable/disable, status display, and import workflows.
 * 
 * Validates: Requirements 340.1, 340.2, 340.3, 340.4, 340.5
 */
public class ConnectorManager {

    private final Map<String, ConnectorConfig> connectors;
    private final Map<String, ConnectorStatus> connectorStatuses;
    private final List<ImportJob> importJobs;

    public ConnectorManager() {
        this.connectors = new ConcurrentHashMap<>();
        this.connectorStatuses = new ConcurrentHashMap<>();
        this.importJobs = Collections.synchronizedList(new ArrayList<>());
        initializeDefaultConnectors();
    }

    /**
     * Gets the list of all available connectors.
     * Requirement 340.1: Display per-connector enable/disable controls.
     * 
     * @return List of connector configurations
     */
    public List<ConnectorConfig> getConnectors() {
        return new ArrayList<>(connectors.values());
    }

    /**
     * Enables a connector.
     * Requirement 340.1: Per-connector enable/disable controls.
     * 
     * @param connectorId The connector ID to enable
     * @return Updated connector config
     */
    public ConnectorConfig enableConnector(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("Connector ID cannot be null or blank");
        }

        ConnectorConfig config = connectors.get(connectorId);
        if (config == null) {
            throw new IllegalArgumentException("Unknown connector: " + connectorId);
        }

        ConnectorConfig updated = new ConnectorConfig(
                config.id(),
                config.name(),
                config.description(),
                config.dataClasses(),
                true,
                config.requiresAuth(),
                config.authType()
        );
        connectors.put(connectorId, updated);

        // Initialize status
        connectorStatuses.put(connectorId, new ConnectorStatus(
                connectorId,
                HealthStatus.INITIALIZING,
                null,
                0,
                List.of(),
                Instant.now()
        ));

        return updated;
    }

    /**
     * Disables a connector.
     * Requirement 340.1: Per-connector enable/disable controls.
     * 
     * @param connectorId The connector ID to disable
     * @return Updated connector config
     */
    public ConnectorConfig disableConnector(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("Connector ID cannot be null or blank");
        }

        ConnectorConfig config = connectors.get(connectorId);
        if (config == null) {
            throw new IllegalArgumentException("Unknown connector: " + connectorId);
        }

        ConnectorConfig updated = new ConnectorConfig(
                config.id(),
                config.name(),
                config.description(),
                config.dataClasses(),
                false,
                config.requiresAuth(),
                config.authType()
        );
        connectors.put(connectorId, updated);

        // Clear status
        connectorStatuses.remove(connectorId);

        return updated;
    }

    /**
     * Gets the status of a connector.
     * Requirement 340.2: Show health, last sync, data-class warnings.
     * 
     * @param connectorId The connector ID
     * @return ConnectorStatus or null if not enabled
     */
    public ConnectorStatus getConnectorStatus(String connectorId) {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("Connector ID cannot be null or blank");
        }
        return connectorStatuses.get(connectorId);
    }

    /**
     * Updates the status of a connector after sync.
     * 
     * @param connectorId The connector ID
     * @param health The health status
     * @param lastSync The last sync time
     * @param recordCount Number of records synced
     * @param warnings Any data-class warnings
     */
    public void updateConnectorStatus(String connectorId, HealthStatus health, 
                                       Instant lastSync, int recordCount, 
                                       List<DataClassWarning> warnings) {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("Connector ID cannot be null or blank");
        }

        ConnectorStatus status = new ConnectorStatus(
                connectorId,
                health,
                lastSync,
                recordCount,
                warnings != null ? warnings : List.of(),
                Instant.now()
        );
        connectorStatuses.put(connectorId, status);
    }


    /**
     * Starts an import workflow.
     * Requirement 340.3: Provide file scan and size estimates.
     * 
     * @param request The import request
     * @return ImportJob with scan results
     */
    public ImportJob startImport(ImportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Import request cannot be null");
        }
        if (request.filePath() == null || request.filePath().isBlank()) {
            throw new IllegalArgumentException("File path cannot be null or blank");
        }

        // Scan file and estimate size
        FileScanResult scanResult = scanFile(request.filePath(), request.importType());

        ImportJob job = new ImportJob(
                UUID.randomUUID().toString(),
                request.importType(),
                request.filePath(),
                ImportStatus.SCANNING,
                scanResult,
                0,
                Instant.now(),
                null,
                null
        );

        importJobs.add(job);
        return job;
    }

    /**
     * Gets the status of an import job.
     * 
     * @param jobId The job ID
     * @return ImportJob or null if not found
     */
    public ImportJob getImportJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Job ID cannot be null or blank");
        }
        return importJobs.stream()
                .filter(j -> j.jobId().equals(jobId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Updates an import job's progress.
     * 
     * @param jobId The job ID
     * @param status The new status
     * @param progress Progress percentage (0-100)
     * @param error Error message if failed
     * @return Updated ImportJob
     */
    public ImportJob updateImportProgress(String jobId, ImportStatus status, 
                                           int progress, String error) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Job ID cannot be null or blank");
        }

        ImportJob existing = getImportJob(jobId);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }

        ImportJob updated = new ImportJob(
                existing.jobId(),
                existing.importType(),
                existing.filePath(),
                status,
                existing.scanResult(),
                progress,
                existing.startedAt(),
                status == ImportStatus.COMPLETED || status == ImportStatus.FAILED ? Instant.now() : null,
                error
        );

        importJobs.removeIf(j -> j.jobId().equals(jobId));
        importJobs.add(updated);
        return updated;
    }

    /**
     * Gets all import jobs.
     * 
     * @return List of import jobs
     */
    public List<ImportJob> getImportJobs() {
        return new ArrayList<>(importJobs);
    }

    /**
     * Gets enabled connectors count.
     */
    public int getEnabledConnectorCount() {
        return (int) connectors.values().stream()
                .filter(ConnectorConfig::enabled)
                .count();
    }

    // ==================== Private Helper Methods ====================

    private void initializeDefaultConnectors() {
        addConnector(new ConnectorConfig(
                "healthkit",
                "Apple HealthKit",
                "Health and fitness data from iOS Health app",
                List.of("health.steps", "health.heart_rate", "health.sleep", "health.workouts"),
                false,
                false,
                AuthType.FRAMEWORK
        ));

        addConnector(new ConnectorConfig(
                "health_connect",
                "Android Health Connect",
                "Health and fitness data from Android Health Connect",
                List.of("health.steps", "health.heart_rate", "health.sleep", "health.workouts"),
                false,
                false,
                AuthType.FRAMEWORK
        ));

        addConnector(new ConnectorConfig(
                "spotify",
                "Spotify",
                "Music listening history and preferences",
                List.of("media.music", "media.playlists", "media.listening_history"),
                false,
                true,
                AuthType.OAUTH2
        ));

        addConnector(new ConnectorConfig(
                "strava",
                "Strava",
                "Athletic activities and routes",
                List.of("fitness.activities", "fitness.routes", "fitness.stats"),
                false,
                true,
                AuthType.OAUTH2
        ));

        addConnector(new ConnectorConfig(
                "google_takeout",
                "Google Takeout",
                "Import data from Google Takeout export",
                List.of("location.history", "search.history", "youtube.history"),
                false,
                false,
                AuthType.FILE_IMPORT
        ));

        addConnector(new ConnectorConfig(
                "whatsapp",
                "WhatsApp Export",
                "Import chat history from WhatsApp export",
                List.of("communication.messages", "communication.media"),
                false,
                false,
                AuthType.FILE_IMPORT
        ));
    }

    private void addConnector(ConnectorConfig config) {
        connectors.put(config.id(), config);
    }

    private FileScanResult scanFile(String filePath, ImportType importType) {
        // Simulate file scanning - in production this would actually scan the file
        long estimatedSize = 1024 * 1024 * 10; // 10MB estimate
        int estimatedRecords = 1000;
        List<String> detectedDataClasses = switch (importType) {
            case GOOGLE_TAKEOUT -> List.of("location.history", "search.history");
            case WHATSAPP -> List.of("communication.messages");
            case TELEGRAM -> List.of("communication.messages", "communication.media");
            case UBER -> List.of("location.rides", "finance.transactions");
            case ICLOUD -> List.of("photos.metadata", "contacts");
            case GENERIC_ZIP -> List.of("unknown");
        };

        List<String> sensitivityWarnings = new ArrayList<>();
        if (detectedDataClasses.contains("location.history")) {
            sensitivityWarnings.add("Contains precise location data - consider coarsening");
        }
        if (detectedDataClasses.contains("communication.messages")) {
            sensitivityWarnings.add("Contains message content - will be processed locally only");
        }

        return new FileScanResult(
                filePath,
                estimatedSize,
                estimatedRecords,
                detectedDataClasses,
                sensitivityWarnings,
                true
        );
    }


    // ==================== Inner Types ====================

    /**
     * Connector configuration.
     */
    public record ConnectorConfig(
            String id,
            String name,
            String description,
            List<String> dataClasses,
            boolean enabled,
            boolean requiresAuth,
            AuthType authType
    ) {}

    /**
     * Authentication type for connectors.
     */
    public enum AuthType {
        FRAMEWORK,      // OS framework (HealthKit, Health Connect)
        OAUTH2,         // OAuth2 flow
        API_KEY,        // API key authentication
        FILE_IMPORT     // File-based import (no auth)
    }

    /**
     * Connector health status.
     */
    public enum HealthStatus {
        HEALTHY,
        DEGRADED,
        ERROR,
        INITIALIZING,
        DISCONNECTED
    }

    /**
     * Connector status with health and sync info.
     */
    public record ConnectorStatus(
            String connectorId,
            HealthStatus health,
            Instant lastSync,
            int recordCount,
            List<DataClassWarning> warnings,
            Instant updatedAt
    ) {}

    /**
     * Data class warning for sensitive data.
     */
    public record DataClassWarning(
            String dataClass,
            WarningSeverity severity,
            String message,
            String recommendation
    ) {}

    /**
     * Warning severity levels.
     */
    public enum WarningSeverity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Import request.
     */
    public record ImportRequest(
            ImportType importType,
            String filePath,
            Map<String, String> options
    ) {}

    /**
     * Import type.
     */
    public enum ImportType {
        GOOGLE_TAKEOUT,
        WHATSAPP,
        TELEGRAM,
        UBER,
        ICLOUD,
        GENERIC_ZIP
    }

    /**
     * Import job status.
     */
    public enum ImportStatus {
        SCANNING,
        PENDING_APPROVAL,
        IMPORTING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * File scan result.
     */
    public record FileScanResult(
            String filePath,
            long estimatedSizeBytes,
            int estimatedRecords,
            List<String> detectedDataClasses,
            List<String> sensitivityWarnings,
            boolean valid
    ) {
        public String formattedSize() {
            if (estimatedSizeBytes < 1024) {
                return estimatedSizeBytes + " B";
            } else if (estimatedSizeBytes < 1024 * 1024) {
                return String.format("%.1f KB", estimatedSizeBytes / 1024.0);
            } else if (estimatedSizeBytes < 1024 * 1024 * 1024) {
                return String.format("%.1f MB", estimatedSizeBytes / (1024.0 * 1024));
            } else {
                return String.format("%.1f GB", estimatedSizeBytes / (1024.0 * 1024 * 1024));
            }
        }
    }

    /**
     * Import job.
     */
    public record ImportJob(
            String jobId,
            ImportType importType,
            String filePath,
            ImportStatus status,
            FileScanResult scanResult,
            int progressPercent,
            Instant startedAt,
            Instant completedAt,
            String errorMessage
    ) {
        public boolean isComplete() {
            return status == ImportStatus.COMPLETED || status == ImportStatus.FAILED || status == ImportStatus.CANCELLED;
        }
    }
}
