package com.yachaq.node.importer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Google Takeout data importer.
 * Requirement 306.1: Support Google Takeout imports.
 * 
 * Supports:
 * - Location History (Semantic Location History, Records)
 * - Google Photos metadata
 * - Calendar events
 * - Drive file metadata
 * - YouTube history
 * - Search history
 * - Chrome history
 */
public class GoogleTakeoutImporter extends AbstractDataImporter {

    public static final String IMPORTER_ID = "google_takeout";

    // Known Takeout paths
    private static final String LOCATION_HISTORY_PATH = "Takeout/Location History";
    private static final String SEMANTIC_LOCATION_PATH = "Semantic Location History";
    private static final String PHOTOS_PATH = "Takeout/Google Photos";
    private static final String CALENDAR_PATH = "Takeout/Calendar";
    private static final String DRIVE_PATH = "Takeout/Drive";
    private static final String YOUTUBE_PATH = "Takeout/YouTube";
    private static final String SEARCH_PATH = "Takeout/My Activity/Search";
    private static final String CHROME_PATH = "Takeout/Chrome";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".zip");
    private static final List<String> SUPPORTED_DATA_TYPES = List.of(
            "location_history", "semantic_location", "photos", "calendar",
            "drive", "youtube_history", "search_history", "chrome_history"
    );

    private final VaultStorage vaultStorage;

    public GoogleTakeoutImporter() {
        this(new InMemoryVaultStorage());
    }

    public GoogleTakeoutImporter(VaultStorage vaultStorage) {
        super(IMPORTER_ID, SUPPORTED_EXTENSIONS, SUPPORTED_DATA_TYPES);
        this.vaultStorage = vaultStorage;
    }

    @Override
    public CompletableFuture<ImportResult> importData(Path filePath, ImportOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            String importId = generateImportId();
            Instant startTime = Instant.now();
            List<ImportResult.ImportedItem> importedItems = new ArrayList<>();
            List<ImportResult.ImportError> errors = new ArrayList<>();
            Map<String, Integer> countByType = new HashMap<>();
            int skipped = 0;

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath))) {
                ZipEntry entry;
                int processedFiles = 0;

                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }

                    String entryPath = entry.getName();
                    String dataType = detectDataType(entryPath);

                    // Skip if data type not in filter
                    if (!options.dataTypesToImport().isEmpty() && 
                        !options.dataTypesToImport().contains(dataType)) {
                        skipped++;
                        zis.closeEntry();
                        continue;
                    }

                    // Skip unsupported files
                    if (dataType == null) {
                        skipped++;
                        zis.closeEntry();
                        continue;
                    }

                    try {
                        // Read entry content
                        byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                        
                        // Store in vault
                        String vaultRef = vaultStorage.store(content, Map.of(
                                "source", IMPORTER_ID,
                                "dataType", dataType,
                                "originalPath", entryPath,
                                "importId", importId
                        ));

                        // Create imported item
                        ImportResult.ImportedItem item = new ImportResult.ImportedItem(
                                UUID.randomUUID().toString(),
                                dataType,
                                vaultRef,
                                Instant.now(),
                                Map.of("originalPath", entryPath, "size", String.valueOf(content.length))
                        );
                        importedItems.add(item);
                        countByType.merge(dataType, 1, Integer::sum);

                        // Report progress
                        processedFiles++;
                        if (options.progressCallback() != null) {
                            options.progressCallback().onProgress(new ImportOptions.ProgressUpdate(
                                    processedFiles, processedFiles, processedFiles, -1,
                                    entryPath, -1
                            ));
                        }

                    } catch (Exception e) {
                        ImportResult.ImportError error = new ImportResult.ImportError(
                                entryPath, "PARSE_ERROR", e.getMessage(), true
                        );
                        errors.add(error);
                        
                        if (!options.continueOnError()) {
                            return ImportResult.failure(errors, startTime, importId);
                        }
                    }

                    zis.closeEntry();
                }

            } catch (IOException e) {
                errors.add(new ImportResult.ImportError(
                        filePath.toString(), "IO_ERROR", e.getMessage(), false
                ));
                return ImportResult.failure(errors, startTime, importId);
            }

            if (errors.isEmpty()) {
                return ImportResult.success(importedItems, skipped, startTime, importId, countByType);
            } else {
                return ImportResult.partial(importedItems, skipped, errors, startTime, importId, countByType);
            }
        });
    }

    /**
     * Detects data type from Takeout path.
     */
    private String detectDataType(String path) {
        if (path.contains(LOCATION_HISTORY_PATH) || path.contains("Location History")) {
            if (path.contains(SEMANTIC_LOCATION_PATH)) {
                return "semantic_location";
            }
            return "location_history";
        }
        if (path.contains(PHOTOS_PATH) || path.contains("Google Photos")) {
            return "photos";
        }
        if (path.contains(CALENDAR_PATH) || path.contains("Calendar")) {
            return "calendar";
        }
        if (path.contains(DRIVE_PATH) || path.contains("Drive")) {
            return "drive";
        }
        if (path.contains(YOUTUBE_PATH) || path.contains("YouTube")) {
            return "youtube_history";
        }
        if (path.contains(SEARCH_PATH) || path.contains("Search")) {
            return "search_history";
        }
        if (path.contains(CHROME_PATH) || path.contains("Chrome")) {
            return "chrome_history";
        }
        
        // Check file extension for generic detection
        String lowerPath = path.toLowerCase();
        if (lowerPath.endsWith(".json")) {
            return "json_data";
        }
        if (lowerPath.endsWith(".html")) {
            return "html_data";
        }
        
        return null;
    }

    /**
     * Reads ZIP entry content with memory limit.
     */
    private byte[] readZipEntry(ZipInputStream zis, long maxBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;

        while ((read = zis.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxBytes) {
                throw new IOException("Entry exceeds maximum size: " + maxBytes);
            }
            baos.write(buffer, 0, read);
        }

        return baos.toByteArray();
    }

    @Override
    protected List<String> validateFormat(Path filePath) throws IOException {
        List<String> warnings = new ArrayList<>();
        boolean foundTakeoutStructure = false;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(filePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("Takeout/")) {
                    foundTakeoutStructure = true;
                    break;
                }
                zis.closeEntry();
            }
        }

        if (!foundTakeoutStructure) {
            warnings.add("File does not appear to be a Google Takeout export (missing 'Takeout/' directory)");
        }

        return warnings;
    }

    /**
     * Interface for vault storage.
     */
    public interface VaultStorage {
        String store(byte[] data, Map<String, String> metadata);
        byte[] retrieve(String ref);
        void delete(String ref);
    }

    /**
     * In-memory vault storage for testing.
     */
    public static class InMemoryVaultStorage implements VaultStorage {
        private final Map<String, byte[]> storage = new HashMap<>();
        private final Map<String, Map<String, String>> metadata = new HashMap<>();

        @Override
        public String store(byte[] data, Map<String, String> meta) {
            String ref = "vault:" + UUID.randomUUID();
            storage.put(ref, data);
            metadata.put(ref, meta);
            return ref;
        }

        @Override
        public byte[] retrieve(String ref) {
            return storage.get(ref);
        }

        @Override
        public void delete(String ref) {
            storage.remove(ref);
            metadata.remove(ref);
        }
    }
}
