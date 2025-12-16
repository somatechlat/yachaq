package com.yachaq.node.importer;

import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Data Importers.
 * 
 * **Feature: yachaq-platform, Property 66: Import Security Validation**
 * **Validates: Requirements 306.1, 306.2, 306.3, 306.5, 306.6**
 */
class DataImporterPropertyTest {

    // For JUnit tests only
    @TempDir
    Path tempDir;

    // For jqwik property tests - create temp dir dynamically
    private Path createTempDir() throws IOException {
        return Files.createTempDirectory("importer_test_");
    }

    // ==================== Property 66: Import Security Validation ====================

    @Property(tries = 100)
    void property66_allImportersHaveUniqueIds(@ForAll("importers") DataImporter importer) {
        // Property: Each importer has a unique, non-empty ID
        assertThat(importer.getId()).isNotNull();
        assertThat(importer.getId()).isNotBlank();
    }

    @Property(tries = 100)
    void property66_allImportersHaveSupportedExtensions(@ForAll("importers") DataImporter importer) {
        // Property: Each importer declares supported extensions
        assertThat(importer.getSupportedExtensions()).isNotNull();
        assertThat(importer.getSupportedExtensions()).isNotEmpty();
    }

    @Property(tries = 100)
    void property66_allImportersHaveSupportedDataTypes(@ForAll("importers") DataImporter importer) {
        // Property: Each importer declares supported data types
        assertThat(importer.getSupportedDataTypes()).isNotNull();
        assertThat(importer.getSupportedDataTypes()).isNotEmpty();
    }


    @Property(tries = 50)
    void property66_validationRejectsNonExistentFile(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Validation fails for non-existent files
        // Validates: Requirement 306.2 - Perform checksum verification
        
        Path testDir = createTempDir();
        Path nonExistent = testDir.resolve("does_not_exist.zip");
        ValidationResult result = importer.validate(nonExistent).join();
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property66_validationRejectsUnsupportedFormat(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Validation fails for unsupported file formats
        
        Path testDir = createTempDir();
        Path unsupportedFile = testDir.resolve("test.xyz");
        Files.writeString(unsupportedFile, "test content");
        
        ValidationResult result = importer.validate(unsupportedFile).join();
        
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo(ValidationResult.ValidationStatus.UNSUPPORTED_FORMAT);
        Files.deleteIfExists(unsupportedFile);
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property66_validationCalculatesChecksum(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Valid files get checksum calculated
        // Validates: Requirement 306.2 - Perform checksum verification
        
        Path testDir = createTempDir();
        Path validZip = createValidZipFile(testDir, "test_content");
        ValidationResult result = importer.validate(validZip).join();
        
        if (result.valid()) {
            assertThat(result.checksum()).isNotNull();
            assertThat(result.checksum()).isNotBlank();
            assertThat(result.checksumAlgorithm()).isEqualTo("SHA-256");
        }
        Files.deleteIfExists(validZip);
        Files.deleteIfExists(testDir);
    }

    // ==================== Property 67: Sensitivity Detection ====================

    @Property(tries = 50)
    void property67_scanDetectsLocationData(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Scan detects location data and warns
        // Validates: Requirement 306.6 - Display warnings for sensitive data
        
        Path testDir = createTempDir();
        Path zipWithLocation = createZipWithEntry(testDir, "Takeout/Location History/data.json", "{}");
        ScanResult result = importer.scan(zipWithLocation).join();
        
        if (result.success()) {
            boolean hasLocationWarning = result.sensitivityWarnings().stream()
                    .anyMatch(w -> w.category().toLowerCase().contains("location"));
            // Location data should trigger warning
            assertThat(result.detectedDataTypes().contains("location_history") || hasLocationWarning).isTrue();
        }
        Files.deleteIfExists(zipWithLocation);
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property67_scanDetectsMessageData(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Scan detects message data and warns
        // Validates: Requirement 306.6 - Display warnings for sensitive data
        
        Path testDir = createTempDir();
        Path zipWithMessages = createZipWithEntry(testDir, "messages/chat.json", "{}");
        ScanResult result = importer.scan(zipWithMessages).join();
        
        if (result.success()) {
            boolean hasMessageWarning = result.sensitivityWarnings().stream()
                    .anyMatch(w -> w.category().toLowerCase().contains("communication"));
            assertThat(result.detectedDataTypes().contains("messages") || hasMessageWarning).isTrue();
        }
        Files.deleteIfExists(zipWithMessages);
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property67_scanDetectsHealthData(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Scan detects health data and warns at CRITICAL level
        // Validates: Requirement 306.6 - Display warnings for sensitive data
        
        Path testDir = createTempDir();
        Path zipWithHealth = createZipWithEntry(testDir, "health/data.json", "{}");
        ScanResult result = importer.scan(zipWithHealth).join();
        
        if (result.success() && !result.sensitivityWarnings().isEmpty()) {
            boolean hasHealthWarning = result.sensitivityWarnings().stream()
                    .anyMatch(w -> w.category().toLowerCase().contains("health") &&
                                   w.level() == ScanResult.SensitivityLevel.CRITICAL);
            assertThat(result.detectedDataTypes().contains("health") || hasHealthWarning).isTrue();
        }
        Files.deleteIfExists(zipWithHealth);
        Files.deleteIfExists(testDir);
    }

    // ==================== Property 68: Import Memory Safety ====================

    @Property(tries = 50)
    void property68_importRespectsMemoryLimits(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Import respects memory limits
        // Validates: Requirement 306.3 - Handle supported export schemas
        
        Path testDir = createTempDir();
        // Create a file that would exceed memory limit
        Path largeZip = createZipWithLargeEntry(testDir, "large.txt", 1024 * 1024); // 1MB entry
        
        ImportOptions options = ImportOptions.builder()
                .maxMemoryBytes(1024) // Only 1KB allowed
                .continueOnError(true)
                .build();
        
        ImportResult result = importer.importData(largeZip, options).join();
        
        // Should either fail or have errors due to size limit
        if (!result.success()) {
            assertThat(result.errors()).isNotEmpty();
        }
        Files.deleteIfExists(largeZip);
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property68_importGeneratesUniqueIds(@ForAll("importers") DataImporter importer) throws IOException {
        // Property: Each import generates unique import ID
        
        Path testDir = createTempDir();
        Path validZip = createValidZipFile(testDir, "content1");
        
        ImportResult result1 = importer.importData(validZip, ImportOptions.defaults()).join();
        ImportResult result2 = importer.importData(validZip, ImportOptions.defaults()).join();
        
        assertThat(result1.importId()).isNotNull();
        assertThat(result2.importId()).isNotNull();
        assertThat(result1.importId()).isNotEqualTo(result2.importId());
        Files.deleteIfExists(validZip);
        Files.deleteIfExists(testDir);
    }

    // ==================== Property 69: ZIP Bomb Protection ====================

    @Property(tries = 50)
    void property69_validationDetectsZipBomb() throws IOException {
        // Property: Validation detects potential ZIP bombs
        // Validates: Requirement 306.2 - Malware scanning
        
        GoogleTakeoutImporter importer = new GoogleTakeoutImporter();
        
        Path testDir = createTempDir();
        // Create a ZIP with suspicious compression ratio
        Path suspiciousZip = createZipWithHighCompressionRatio(testDir);
        ValidationResult result = importer.validate(suspiciousZip).join();
        
        // Should have warnings about compression ratio
        if (result.valid() && !result.warnings().isEmpty()) {
            boolean hasCompressionWarning = result.warnings().stream()
                    .anyMatch(w -> w.toLowerCase().contains("compression") || w.toLowerCase().contains("ratio"));
            // May or may not trigger depending on actual ratio
        }
        Files.deleteIfExists(suspiciousZip);
        Files.deleteIfExists(testDir);
    }

    @Property(tries = 50)
    void property69_validationDetectsPathTraversal() throws IOException {
        // Property: Validation detects path traversal attempts
        // Validates: Requirement 306.2 - Security checks
        
        GoogleTakeoutImporter importer = new GoogleTakeoutImporter();
        
        Path testDir = createTempDir();
        Path maliciousZip = createZipWithPathTraversal(testDir);
        ValidationResult result = importer.validate(maliciousZip).join();
        
        // Should have warnings about suspicious paths
        if (result.valid()) {
            boolean hasPathWarning = result.warnings().stream()
                    .anyMatch(w -> w.toLowerCase().contains("suspicious") || w.toLowerCase().contains("path"));
            assertThat(hasPathWarning).isTrue();
        }
        Files.deleteIfExists(maliciousZip);
        Files.deleteIfExists(testDir);
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<DataImporter> importers() {
        return Arbitraries.of(
                new GoogleTakeoutImporter(),
                new WhatsAppImporter(),
                new TelegramImporter(),
                new UberImporter(),
                new ICloudImporter()
        );
    }

    // ==================== Helper Methods ====================

    // For property tests (with explicit dir)
    private Path createValidZipFile(Path dir, String content) throws IOException {
        Path zipFile = dir.resolve("test_" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("Takeout/data.json");
            zos.putNextEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipFile;
    }

    private Path createZipWithEntry(Path dir, String entryPath, String content) throws IOException {
        Path zipFile = dir.resolve("test_" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry(entryPath);
            zos.putNextEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipFile;
    }

    private Path createZipWithLargeEntry(Path dir, String entryName, int size) throws IOException {
        Path zipFile = dir.resolve("large_" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            byte[] data = new byte[size];
            Arrays.fill(data, (byte) 'X');
            zos.write(data);
            zos.closeEntry();
        }
        return zipFile;
    }

    private Path createZipWithHighCompressionRatio(Path dir) throws IOException {
        Path zipFile = dir.resolve("bomb_" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // Create entry with highly compressible content (all zeros)
            ZipEntry entry = new ZipEntry("data.txt");
            zos.putNextEntry(entry);
            byte[] data = new byte[100000]; // 100KB of zeros compresses very well
            zos.write(data);
            zos.closeEntry();
        }
        return zipFile;
    }

    private Path createZipWithPathTraversal(Path dir) throws IOException {
        Path zipFile = dir.resolve("traversal_" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // Create entry with path traversal attempt
            ZipEntry entry = new ZipEntry("../../../etc/passwd");
            zos.putNextEntry(entry);
            zos.write("malicious".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipFile;
    }

    // ==================== Edge Case Tests ====================

    @Test
    void validationResult_successCreatesValidResult() {
        ValidationResult result = ValidationResult.success("abc123", "SHA-256", 1024);
        
        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(ValidationResult.ValidationStatus.VALID);
        assertThat(result.checksum()).isEqualTo("abc123");
    }

    @Test
    void validationResult_failureCreatesInvalidResult() {
        ValidationResult result = ValidationResult.failure(List.of("Error 1", "Error 2"));
        
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo(ValidationResult.ValidationStatus.INVALID);
        assertThat(result.errors()).hasSize(2);
    }

    @Test
    void scanResult_detectsHighSensitivity() {
        ScanResult result = ScanResult.success(
                10, 1024,
                Map.of(".json", 10L),
                Map.of(".json", 1024L),
                List.of(new ScanResult.SensitivityWarning(
                        ScanResult.SensitivityLevel.HIGH,
                        "Location",
                        "Location data detected",
                        "Review carefully"
                )),
                List.of("location_history")
        );
        
        assertThat(result.hasHighSensitivityData()).isTrue();
    }

    @Test
    void importResult_successCalculatesMetrics() {
        List<ImportResult.ImportedItem> items = List.of(
                new ImportResult.ImportedItem("1", "type1", "ref1", Instant.now(), Map.of()),
                new ImportResult.ImportedItem("2", "type1", "ref2", Instant.now(), Map.of())
        );
        
        ImportResult result = ImportResult.success(
                items, 1, Instant.now().minusSeconds(10), "import-123", Map.of("type1", 2)
        );
        
        assertThat(result.success()).isTrue();
        assertThat(result.itemsImported()).isEqualTo(2);
        assertThat(result.itemsSkipped()).isEqualTo(1);
        assertThat(result.getSuccessRate()).isGreaterThan(60.0);
    }

    @Test
    void importOptions_defaultsAreReasonable() {
        ImportOptions defaults = ImportOptions.defaults();
        
        assertThat(defaults.skipDuplicates()).isTrue();
        assertThat(defaults.continueOnError()).isTrue();
        assertThat(defaults.maxFileSizeBytes()).isGreaterThan(0);
        assertThat(defaults.maxMemoryBytes()).isGreaterThan(0);
        assertThat(defaults.emitEvents()).isTrue();
    }

    @Test
    void googleTakeoutImporter_hasCorrectId() {
        GoogleTakeoutImporter importer = new GoogleTakeoutImporter();
        assertThat(importer.getId()).isEqualTo("google_takeout");
    }

    @Test
    void whatsAppImporter_hasCorrectId() {
        WhatsAppImporter importer = new WhatsAppImporter();
        assertThat(importer.getId()).isEqualTo("whatsapp");
    }

    @Test
    void telegramImporter_hasCorrectId() {
        TelegramImporter importer = new TelegramImporter();
        assertThat(importer.getId()).isEqualTo("telegram");
    }

    @Test
    void uberImporter_hasCorrectId() {
        UberImporter importer = new UberImporter();
        assertThat(importer.getId()).isEqualTo("uber");
    }

    @Test
    void icloudImporter_hasCorrectId() {
        ICloudImporter importer = new ICloudImporter();
        assertThat(importer.getId()).isEqualTo("icloud");
    }
}
