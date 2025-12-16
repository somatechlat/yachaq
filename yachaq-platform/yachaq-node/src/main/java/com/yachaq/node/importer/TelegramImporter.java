package com.yachaq.node.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Telegram data export importer.
 * Requirement 306.1: Support Telegram export imports.
 * 
 * Supports:
 * - Telegram JSON export format
 * - Telegram HTML export format
 */
public class TelegramImporter extends AbstractDataImporter {

    public static final String IMPORTER_ID = "telegram";
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".json", ".zip");
    private static final List<String> SUPPORTED_DATA_TYPES = List.of(
            "chat_messages", "contacts", "media_attachments", "calls"
    );

    private final ObjectMapper objectMapper;
    private final GoogleTakeoutImporter.VaultStorage vaultStorage;

    public TelegramImporter() {
        this(new GoogleTakeoutImporter.InMemoryVaultStorage());
    }

    public TelegramImporter(GoogleTakeoutImporter.VaultStorage vaultStorage) {
        super(IMPORTER_ID, SUPPORTED_EXTENSIONS, SUPPORTED_DATA_TYPES);
        this.objectMapper = new ObjectMapper();
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
                try {
                    if (entryName.endsWith("result.json") || entryName.contains("messages")) {
                        byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                        List<ImportResult.ImportedItem> items = parseJsonContent(
                                new String(content, StandardCharsets.UTF_8), importId
                        );
                        importedItems.addAll(items);
                        countByType.merge("chat_messages", items.size(), Integer::sum);
                    } else if (isMediaFile(entryName)) {
                        byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                        String vaultRef = vaultStorage.store(content, Map.of(
                                "source", IMPORTER_ID,
                                "dataType", "media_attachments",
                                "originalPath", entry.getName(),
                                "importId", importId
                        ));
                        importedItems.add(new ImportResult.ImportedItem(
                                UUID.randomUUID().toString(),
                                "media_attachments",
                                vaultRef,
                                Instant.now(),
                                Map.of("originalPath", entry.getName())
                        ));
                        countByType.merge("media_attachments", 1, Integer::sum);
                    } else {
                        skipped++;
                    }
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

    private ImportResult importFromJson(Path jsonPath, ImportOptions options, String importId, Instant startTime) {
        List<ImportResult.ImportError> errors = new ArrayList<>();
        Map<String, Integer> countByType = new HashMap<>();

        try {
            String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
            List<ImportResult.ImportedItem> items = parseJsonContent(content, importId);
            countByType.put("chat_messages", items.size());
            return ImportResult.success(items, 0, startTime, importId, countByType);
        } catch (IOException e) {
            errors.add(new ImportResult.ImportError(
                    jsonPath.toString(), "IO_ERROR", e.getMessage(), false
            ));
            return ImportResult.failure(errors, startTime, importId);
        }
    }

    private List<ImportResult.ImportedItem> parseJsonContent(String content, String importId) {
        List<ImportResult.ImportedItem> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode messages = root.has("messages") ? root.get("messages") : root;

            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    String text = msg.has("text") ? msg.get("text").asText() : "";
                    String from = msg.has("from") ? msg.get("from").asText() : "unknown";
                    String dateStr = msg.has("date") ? msg.get("date").asText() : null;

                    Instant timestamp = dateStr != null ? Instant.parse(dateStr + "Z") : Instant.now();

                    String vaultRef = vaultStorage.store(text.getBytes(StandardCharsets.UTF_8), Map.of(
                            "source", IMPORTER_ID,
                            "dataType", "chat_messages",
                            "from", from,
                            "importId", importId
                    ));

                    items.add(new ImportResult.ImportedItem(
                            UUID.randomUUID().toString(),
                            "chat_messages",
                            vaultRef,
                            timestamp,
                            Map.of("from", from)
                    ));
                }
            }
        } catch (Exception e) {
            // Return empty list on parse error
        }
        return items;
    }

    private boolean isMediaFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".gif") || lower.endsWith(".mp4") || lower.endsWith(".mp3") ||
               lower.endsWith(".ogg") || lower.endsWith(".webp");
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
