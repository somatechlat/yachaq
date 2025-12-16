package com.yachaq.node.importer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * iCloud archive importer.
 * Requirement 306.1: Support iCloud archive imports.
 * 
 * Supports: photos, notes, documents, contacts, calendars
 */
public class ICloudImporter extends AbstractDataImporter {

    public static final String IMPORTER_ID = "icloud";
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".zip");
    private static final List<String> SUPPORTED_DATA_TYPES = List.of(
            "photos", "notes", "documents", "contacts", "calendars"
    );

    private final GoogleTakeoutImporter.VaultStorage vaultStorage;

    public ICloudImporter() {
        this(new GoogleTakeoutImporter.InMemoryVaultStorage());
    }

    public ICloudImporter(GoogleTakeoutImporter.VaultStorage vaultStorage) {
        super(IMPORTER_ID, SUPPORTED_EXTENSIONS, SUPPORTED_DATA_TYPES);
        this.vaultStorage = vaultStorage;
    }


    @Override
    public CompletableFuture<ImportResult> importData(Path filePath, ImportOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            String importId = generateImportId();
            Instant startTime = Instant.now();

            if (!filePath.getFileName().toString().toLowerCase().endsWith(".zip")) {
                return ImportResult.failure(
                        List.of(new ImportResult.ImportError(
                                filePath.toString(), "UNSUPPORTED_FORMAT",
                                "iCloud import requires ZIP archive", false
                        )),
                        startTime, importId
                );
            }

            return importFromZip(filePath, options, importId, startTime);
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

                String entryName = entry.getName();
                String dataType = detectDataType(entryName);

                if (dataType == null) {
                    skipped++;
                    zis.closeEntry();
                    continue;
                }

                if (!options.dataTypesToImport().isEmpty() && 
                    !options.dataTypesToImport().contains(dataType)) {
                    skipped++;
                    zis.closeEntry();
                    continue;
                }

                try {
                    byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                    String vaultRef = vaultStorage.store(content, Map.of(
                            "source", IMPORTER_ID,
                            "dataType", dataType,
                            "originalPath", entryName,
                            "importId", importId
                    ));

                    importedItems.add(new ImportResult.ImportedItem(
                            UUID.randomUUID().toString(),
                            dataType,
                            vaultRef,
                            Instant.now(),
                            Map.of("originalPath", entryName, "size", String.valueOf(content.length))
                    ));
                    countByType.merge(dataType, 1, Integer::sum);
                } catch (Exception e) {
                    errors.add(new ImportResult.ImportError(
                            entryName, "PARSE_ERROR", e.getMessage(), true
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

    private String detectDataType(String path) {
        String lower = path.toLowerCase();
        
        // Photos
        if (lower.contains("photo") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".heic") || lower.endsWith(".mov")) {
            return "photos";
        }
        
        // Notes
        if (lower.contains("note") || lower.endsWith(".txt") || lower.endsWith(".rtf")) {
            return "notes";
        }
        
        // Documents
        if (lower.contains("document") || lower.contains("icloud drive") ||
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
            lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".pages") ||
            lower.endsWith(".numbers") || lower.endsWith(".key")) {
            return "documents";
        }
        
        // Contacts
        if (lower.contains("contact") || lower.endsWith(".vcf") || lower.endsWith(".vcard")) {
            return "contacts";
        }
        
        // Calendars
        if (lower.contains("calendar") || lower.endsWith(".ics")) {
            return "calendars";
        }
        
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
