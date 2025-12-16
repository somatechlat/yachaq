package com.yachaq.node.importer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Uber data export importer.
 * Requirement 306.1: Support Uber data imports.
 * 
 * Supports: trips, rider_app_analytics, payment_methods, account_data
 */
public class UberImporter extends AbstractDataImporter {

    public static final String IMPORTER_ID = "uber";
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".zip", ".csv", ".json");
    private static final List<String> SUPPORTED_DATA_TYPES = List.of(
            "trips", "rider_analytics", "payment_methods", "account_data"
    );

    private final ObjectMapper jsonMapper;
    private final GoogleTakeoutImporter.VaultStorage vaultStorage;

    public UberImporter() {
        this(new GoogleTakeoutImporter.InMemoryVaultStorage());
    }

    public UberImporter(GoogleTakeoutImporter.VaultStorage vaultStorage) {
        super(IMPORTER_ID, SUPPORTED_EXTENSIONS, SUPPORTED_DATA_TYPES);
        this.jsonMapper = new ObjectMapper();
        this.vaultStorage = vaultStorage;
    }


    @Override
    public CompletableFuture<ImportResult> importData(Path filePath, ImportOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            String importId = generateImportId();
            Instant startTime = Instant.now();
            String fileName = filePath.getFileName().toString().toLowerCase();

            try {
                if (fileName.endsWith(".zip")) {
                    return importFromZip(filePath, options, importId, startTime);
                } else if (fileName.endsWith(".csv")) {
                    return importFromCsv(filePath, options, importId, startTime);
                } else if (fileName.endsWith(".json")) {
                    return importFromJson(filePath, options, importId, startTime);
                } else {
                    return ImportResult.failure(
                            List.of(new ImportResult.ImportError(
                                    filePath.toString(), "UNSUPPORTED_FORMAT",
                                    "Unsupported file format", false
                            )),
                            startTime, importId
                    );
                }
            } catch (Exception e) {
                return ImportResult.failure(
                        List.of(new ImportResult.ImportError(
                                filePath.toString(), "IMPORT_ERROR", e.getMessage(), false
                        )),
                        startTime, importId
                );
            }
        });
    }

    private ImportResult importFromZip(Path zipPath, ImportOptions options, String importId, Instant startTime) {
        List<ImportResult.ImportedItem> importedItems = new ArrayList<>();
        List<ImportResult.ImportError> errors = new ArrayList<>();
        Map<String, Integer> countByType = new HashMap<>();
        int skipped = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName().toLowerCase();
                String dataType = detectDataType(entryName);

                if (dataType == null) {
                    skipped++;
                    zis.closeEntry();
                    continue;
                }

                try {
                    byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                    String vaultRef = vaultStorage.store(content, Map.of(
                            "source", IMPORTER_ID,
                            "dataType", dataType,
                            "originalPath", entry.getName(),
                            "importId", importId
                    ));

                    importedItems.add(new ImportResult.ImportedItem(
                            UUID.randomUUID().toString(),
                            dataType,
                            vaultRef,
                            Instant.now(),
                            Map.of("originalPath", entry.getName())
                    ));
                    countByType.merge(dataType, 1, Integer::sum);
                } catch (Exception e) {
                    errors.add(new ImportResult.ImportError(
                            entry.getName(), "PARSE_ERROR", e.getMessage(), true
                    ));
                    if (!options.continueOnError()) {
                        return ImportResult.failure(errors, startTime, importId);
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            errors.add(new ImportResult.ImportError(
                    zipPath.toString(), "IO_ERROR", e.getMessage(), false
            ));
            return ImportResult.failure(errors, startTime, importId);
        }

        if (errors.isEmpty()) {
            return ImportResult.success(importedItems, skipped, startTime, importId, countByType);
        }
        return ImportResult.partial(importedItems, skipped, errors, startTime, importId, countByType);
    }

    private ImportResult importFromCsv(Path csvPath, ImportOptions options, String importId, Instant startTime) {
        List<ImportResult.ImportError> errors = new ArrayList<>();
        Map<String, Integer> countByType = new HashMap<>();

        try {
            byte[] content = Files.readAllBytes(csvPath);
            String dataType = detectDataType(csvPath.getFileName().toString());
            if (dataType == null) dataType = "trips";

            String vaultRef = vaultStorage.store(content, Map.of(
                    "source", IMPORTER_ID,
                    "dataType", dataType,
                    "importId", importId
            ));

            List<ImportResult.ImportedItem> items = List.of(new ImportResult.ImportedItem(
                    UUID.randomUUID().toString(),
                    dataType,
                    vaultRef,
                    Instant.now(),
                    Map.of("originalPath", csvPath.getFileName().toString())
            ));
            countByType.put(dataType, 1);
            return ImportResult.success(items, 0, startTime, importId, countByType);
        } catch (IOException e) {
            errors.add(new ImportResult.ImportError(
                    csvPath.toString(), "IO_ERROR", e.getMessage(), false
            ));
            return ImportResult.failure(errors, startTime, importId);
        }
    }

    private ImportResult importFromJson(Path jsonPath, ImportOptions options, String importId, Instant startTime) {
        List<ImportResult.ImportError> errors = new ArrayList<>();
        Map<String, Integer> countByType = new HashMap<>();

        try {
            byte[] content = Files.readAllBytes(jsonPath);
            String dataType = detectDataType(jsonPath.getFileName().toString());
            if (dataType == null) dataType = "account_data";

            String vaultRef = vaultStorage.store(content, Map.of(
                    "source", IMPORTER_ID,
                    "dataType", dataType,
                    "importId", importId
            ));

            List<ImportResult.ImportedItem> items = List.of(new ImportResult.ImportedItem(
                    UUID.randomUUID().toString(),
                    dataType,
                    vaultRef,
                    Instant.now(),
                    Map.of("originalPath", jsonPath.getFileName().toString())
            ));
            countByType.put(dataType, 1);
            return ImportResult.success(items, 0, startTime, importId, countByType);
        } catch (IOException e) {
            errors.add(new ImportResult.ImportError(
                    jsonPath.toString(), "IO_ERROR", e.getMessage(), false
            ));
            return ImportResult.failure(errors, startTime, importId);
        }
    }

    private String detectDataType(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("trip") || lower.contains("ride")) return "trips";
        if (lower.contains("analytics")) return "rider_analytics";
        if (lower.contains("payment")) return "payment_methods";
        if (lower.contains("account") || lower.contains("profile")) return "account_data";
        if (lower.endsWith(".csv") || lower.endsWith(".json")) return "trips";
        return null;
    }

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
}
