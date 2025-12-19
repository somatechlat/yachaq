package com.yachaq.node.onboarding;

import com.yachaq.node.onboarding.ConnectorManager.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ConnectorManager.
 * 
 * Validates: Requirements 340.1, 340.2, 340.3, 340.4, 340.5
 */
class ConnectorManagerPropertyTest {

    private ConnectorManager createManager() {
        return new ConnectorManager();
    }

    // ==================== Task 82.1: Connector List View Tests ====================

    @Test
    void connectorList_containsDefaultConnectors() {
        // Requirement 340.1: Display per-connector enable/disable controls
        ConnectorManager manager = createManager();
        List<ConnectorConfig> connectors = manager.getConnectors();
        
        assertThat(connectors).isNotEmpty();
        assertThat(connectors)
                .extracting(ConnectorConfig::id)
                .contains("healthkit", "health_connect", "spotify", "strava");
    }

    @Test
    void connectorList_allHaveRequiredFields() {
        ConnectorManager manager = createManager();
        List<ConnectorConfig> connectors = manager.getConnectors();
        
        assertThat(connectors).allSatisfy(connector -> {
            assertThat(connector.id()).isNotBlank();
            assertThat(connector.name()).isNotBlank();
            assertThat(connector.description()).isNotBlank();
            assertThat(connector.dataClasses()).isNotEmpty();
            assertThat(connector.authType()).isNotNull();
        });
    }

    @Property
    void enableConnector_updatesState(@ForAll("connectorIds") String connectorId) {
        // Requirement 340.1: Per-connector enable/disable controls
        ConnectorManager manager = createManager();
        
        ConnectorConfig enabled = manager.enableConnector(connectorId);
        
        assertThat(enabled.enabled()).isTrue();
        assertThat(manager.getEnabledConnectorCount()).isGreaterThan(0);
    }

    @Property
    void disableConnector_updatesState(@ForAll("connectorIds") String connectorId) {
        // Requirement 340.1: Per-connector enable/disable controls
        ConnectorManager manager = createManager();
        
        manager.enableConnector(connectorId);
        ConnectorConfig disabled = manager.disableConnector(connectorId);
        
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void enableConnector_rejectsNullId() {
        ConnectorManager manager = createManager();
        
        assertThatThrownBy(() -> manager.enableConnector(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void enableConnector_rejectsUnknownConnector() {
        ConnectorManager manager = createManager();
        
        assertThatThrownBy(() -> manager.enableConnector("unknown_connector"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown connector");
    }

    // ==================== Task 82.2: Connector Status Display Tests ====================

    @Property
    void connectorStatus_availableAfterEnable(@ForAll("connectorIds") String connectorId) {
        // Requirement 340.2: Show health, last sync, data-class warnings
        ConnectorManager manager = createManager();
        
        manager.enableConnector(connectorId);
        ConnectorStatus status = manager.getConnectorStatus(connectorId);
        
        assertThat(status).isNotNull();
        assertThat(status.connectorId()).isEqualTo(connectorId);
        assertThat(status.health()).isNotNull();
    }

    @Property
    void connectorStatus_updatesCorrectly(@ForAll("connectorIds") String connectorId,
                                           @ForAll("healthStatuses") HealthStatus health,
                                           @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 10000) int recordCount) {
        // Requirement 340.2: Show health, last sync, data-class warnings
        ConnectorManager manager = createManager();
        manager.enableConnector(connectorId);
        
        Instant lastSync = Instant.now();
        List<DataClassWarning> warnings = List.of(
                new DataClassWarning("health.steps", WarningSeverity.INFO, "Test warning", "Test recommendation")
        );
        
        manager.updateConnectorStatus(connectorId, health, lastSync, recordCount, warnings);
        
        ConnectorStatus status = manager.getConnectorStatus(connectorId);
        assertThat(status.health()).isEqualTo(health);
        assertThat(status.lastSync()).isEqualTo(lastSync);
        assertThat(status.recordCount()).isEqualTo(recordCount);
        assertThat(status.warnings()).hasSize(1);
    }

    @Test
    void connectorStatus_nullAfterDisable() {
        ConnectorManager manager = createManager();
        
        manager.enableConnector("spotify");
        manager.disableConnector("spotify");
        
        assertThat(manager.getConnectorStatus("spotify")).isNull();
    }

    // ==================== Task 82.3: Import Workflows Tests ====================

    @Property
    void importWorkflow_scansFile(@ForAll("importTypes") ImportType importType) {
        // Requirement 340.3: Provide file scan and size estimates
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(importType, "/path/to/file.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        assertThat(job).isNotNull();
        assertThat(job.jobId()).isNotBlank();
        assertThat(job.status()).isEqualTo(ImportStatus.SCANNING);
        assertThat(job.scanResult()).isNotNull();
        assertThat(job.scanResult().estimatedSizeBytes()).isGreaterThan(0);
        assertThat(job.scanResult().estimatedRecords()).isGreaterThan(0);
    }

    @Property
    void importWorkflow_detectsDataClasses(@ForAll("importTypes") ImportType importType) {
        // Requirement 340.3: File scan detects data classes
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(importType, "/path/to/file.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        assertThat(job.scanResult().detectedDataClasses()).isNotEmpty();
    }

    @Test
    void importWorkflow_showsSensitivityWarnings() {
        // Requirement 340.3: Display sensitivity warnings
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(ImportType.GOOGLE_TAKEOUT, "/path/to/takeout.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        // Google Takeout contains location data which should trigger a warning
        assertThat(job.scanResult().sensitivityWarnings()).isNotEmpty();
    }

    @Test
    void importWorkflow_tracksProgress() {
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(ImportType.WHATSAPP, "/path/to/chat.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        // Update progress
        ImportJob updated = manager.updateImportProgress(job.jobId(), ImportStatus.IMPORTING, 50, null);
        
        assertThat(updated.status()).isEqualTo(ImportStatus.IMPORTING);
        assertThat(updated.progressPercent()).isEqualTo(50);
    }

    @Test
    void importWorkflow_handlesCompletion() {
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(ImportType.TELEGRAM, "/path/to/export.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        ImportJob completed = manager.updateImportProgress(job.jobId(), ImportStatus.COMPLETED, 100, null);
        
        assertThat(completed.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(completed.isComplete()).isTrue();
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void importWorkflow_handlesFailure() {
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(ImportType.UBER, "/path/to/data.zip", Map.of());
        ImportJob job = manager.startImport(request);
        
        ImportJob failed = manager.updateImportProgress(job.jobId(), ImportStatus.FAILED, 25, "Corrupted file");
        
        assertThat(failed.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(failed.isComplete()).isTrue();
        assertThat(failed.errorMessage()).isEqualTo("Corrupted file");
    }

    @Test
    void importWorkflow_rejectsNullRequest() {
        ConnectorManager manager = createManager();
        
        assertThatThrownBy(() -> manager.startImport(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void importWorkflow_rejectsNullFilePath() {
        ConnectorManager manager = createManager();
        
        ImportRequest request = new ImportRequest(ImportType.GENERIC_ZIP, null, Map.of());
        
        assertThatThrownBy(() -> manager.startImport(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File path cannot be null");
    }

    // ==================== File Scan Result Tests ====================

    @Test
    void fileScanResult_formatsSize() {
        FileScanResult small = new FileScanResult("/test", 500, 10, List.of(), List.of(), true);
        assertThat(small.formattedSize()).isEqualTo("500 B");
        
        FileScanResult kb = new FileScanResult("/test", 2048, 10, List.of(), List.of(), true);
        assertThat(kb.formattedSize()).isEqualTo("2.0 KB");
        
        FileScanResult mb = new FileScanResult("/test", 5 * 1024 * 1024, 10, List.of(), List.of(), true);
        assertThat(mb.formattedSize()).isEqualTo("5.0 MB");
        
        FileScanResult gb = new FileScanResult("/test", 2L * 1024 * 1024 * 1024, 10, List.of(), List.of(), true);
        assertThat(gb.formattedSize()).isEqualTo("2.0 GB");
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> connectorIds() {
        return Arbitraries.of("healthkit", "health_connect", "spotify", "strava", "google_takeout", "whatsapp");
    }

    @Provide
    Arbitrary<HealthStatus> healthStatuses() {
        return Arbitraries.of(HealthStatus.values());
    }

    @Provide
    Arbitrary<ImportType> importTypes() {
        return Arbitraries.of(ImportType.values());
    }
}
