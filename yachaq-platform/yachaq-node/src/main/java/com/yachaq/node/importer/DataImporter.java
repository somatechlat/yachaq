package com.yachaq.node.importer;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for data importers that process user-provided data exports.
 * Requirement 306.1: Support Google Takeout, Telegram, WhatsApp, Uber, iCloud imports.
 * Requirement 306.5: Never upload import files to any server.
 * 
 * All processing is done locally on the device.
 */
public interface DataImporter {

    /**
     * Returns the unique identifier for this importer.
     * 
     * @return Importer ID (e.g., "google_takeout", "whatsapp", "telegram")
     */
    String getId();

    /**
     * Returns the supported file extensions for this importer.
     * 
     * @return List of supported extensions (e.g., [".zip", ".json"])
     */
    List<String> getSupportedExtensions();

    /**
     * Returns the supported data types this importer can extract.
     * 
     * @return List of data types (e.g., ["location_history", "photos", "messages"])
     */
    List<String> getSupportedDataTypes();

    /**
     * Validates an import file before processing.
     * Requirement 306.2: Perform checksum verification and malware scanning.
     * 
     * @param filePath Path to the import file
     * @return ValidationResult with status and any warnings
     */
    CompletableFuture<ValidationResult> validate(Path filePath);

    /**
     * Scans an import file to estimate its contents.
     * Requirement 306.6: Display warnings for sensitive data.
     * 
     * @param filePath Path to the import file
     * @return ScanResult with file statistics and sensitivity warnings
     */
    CompletableFuture<ScanResult> scan(Path filePath);

    /**
     * Imports data from a file.
     * Requirement 306.3: Handle supported export schemas and store in vault.
     * Requirement 306.4: Emit canonical events for normalizer.
     * 
     * @param filePath Path to the import file
     * @param options Import options
     * @return ImportResult with imported items and any errors
     */
    CompletableFuture<ImportResult> importData(Path filePath, ImportOptions options);

    /**
     * Imports data from an input stream (for in-memory processing).
     * 
     * @param inputStream Input stream of the import file
     * @param fileName Original file name for format detection
     * @param options Import options
     * @return ImportResult with imported items and any errors
     */
    CompletableFuture<ImportResult> importData(InputStream inputStream, String fileName, ImportOptions options);
}
