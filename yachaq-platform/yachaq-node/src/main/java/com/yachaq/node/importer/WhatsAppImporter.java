package com.yachaq.node.importer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WhatsApp chat export importer.
 * Requirement 306.1: Support WhatsApp export imports.
 * 
 * Supports:
 * - WhatsApp chat export (.txt files)
 * - WhatsApp chat export with media (.zip files)
 */
public class WhatsAppImporter extends AbstractDataImporter {

    public static final String IMPORTER_ID = "whatsapp";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".txt", ".zip");
    private static final List<String> SUPPORTED_DATA_TYPES = List.of(
            "chat_messages", "media_attachments"
    );

    // WhatsApp message patterns (various formats)
    // Format: [DD/MM/YYYY, HH:MM:SS] Sender: Message
    // Format: DD/MM/YYYY, HH:MM - Sender: Message
    private static final Pattern MESSAGE_PATTERN_1 = Pattern.compile(
            "\\[(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]\\s*([^:]+):\\s*(.+)"
    );
    private static final Pattern MESSAGE_PATTERN_2 = Pattern.compile(
            "(\\d{1,2}/\\d{1,2}/\\d{2,4}),\\s*(\\d{1,2}:\\d{2})\\s*-\\s*([^:]+):\\s*(.+)"
    );

    private static final DateTimeFormatter DATE_FORMAT_1 = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final DateTimeFormatter DATE_FORMAT_2 = DateTimeFormatter.ofPattern("d/M/yy");

    private final GoogleTakeoutImporter.VaultStorage vaultStorage;

    public WhatsAppImporter() {
        this(new GoogleTakeoutImporter.InMemoryVaultStorage());
    }

    public WhatsAppImporter(GoogleTakeoutImporter.VaultStorage vaultStorage) {
        super(IMPORTER_ID, SUPPORTED_EXTENSIONS, SUPPORTED_DATA_TYPES);
        this.vaultStorage = vaultStorage;
    }

    @Override
    public CompletableFuture<ImportResult> importData(Path filePath, ImportOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            String importId = generateImportId();
            Instant startTime = Instant.now();
            String fileName = filePath.getFileName().toString().toLowerCase();

            if (fileName.endsWith(".zip")) {
                return importFromZip(filePath, options, importId, startTime);
            } else if (fileName.endsWith(".txt")) {
                return importFromText(filePath, options, importId, startTime);
            } else {
                return ImportResult.failure(
                        List.of(new ImportResult.ImportError(
                                filePath.toString(), "UNSUPPORTED_FORMAT",
                                "Unsupported file format", false
                        )),
                        startTime, importId
                );
            }
        });
    }

    /**
     * Imports from ZIP file containing chat export and media.
     */
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
                    if (entryName.endsWith(".txt") && entryName.contains("chat")) {
                        // Parse chat file
                        byte[] content = readZipEntry(zis, options.maxMemoryBytes());
                        List<ImportResult.ImportedItem> chatItems = parseChatFile(
                                new String(content, StandardCharsets.UTF_8), importId
                        );
                        importedItems.addAll(chatItems);
                        countByType.merge("chat_messages", chatItems.size(), Integer::sum);
                    } else if (isMediaFile(entryName)) {
                        // Store media file
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

    /**
     * Imports from plain text chat export.
     */
    private ImportResult importFromText(Path textPath, ImportOptions options, String importId, Instant startTime) {
        List<ImportResult.ImportError> errors = new ArrayList<>();
        Map<String, Integer> countByType = new HashMap<>();

        try {
            String content = Files.readString(textPath, StandardCharsets.UTF_8);
            List<ImportResult.ImportedItem> chatItems = parseChatFile(content, importId);
            countByType.put("chat_messages", chatItems.size());
            return ImportResult.success(chatItems, 0, startTime, importId, countByType);
        } catch (IOException e) {
            errors.add(new ImportResult.ImportError(
                    textPath.toString(), "IO_ERROR", e.getMessage(), false
            ));
            return ImportResult.failure(errors, startTime, importId);
        }
    }

    /**
     * Parses WhatsApp chat file content.
     */
    private List<ImportResult.ImportedItem> parseChatFile(String content, String importId) {
        List<ImportResult.ImportedItem> items = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder currentMessage = new StringBuilder();
        String currentSender = null;
        Instant currentTimestamp = null;

        for (String line : lines) {
            Matcher matcher1 = MESSAGE_PATTERN_1.matcher(line);
            Matcher matcher2 = MESSAGE_PATTERN_2.matcher(line);

            if (matcher1.matches() || matcher2.matches()) {
                // Save previous message if exists
                if (currentSender != null && currentMessage.length() > 0) {
                    items.add(createMessageItem(currentSender, currentMessage.toString(), currentTimestamp, importId));
                }

                // Parse new message
                Matcher matcher = matcher1.matches() ? matcher1 : matcher2;
                String dateStr = matcher.group(1);
                String timeStr = matcher.group(2);
                currentSender = matcher.group(3).trim();
                currentMessage = new StringBuilder(matcher.group(4));
                currentTimestamp = parseDateTime(dateStr, timeStr);
            } else if (currentSender != null) {
                // Continuation of previous message
                currentMessage.append("\n").append(line);
            }
        }

        // Save last message
        if (currentSender != null && currentMessage.length() > 0) {
            items.add(createMessageItem(currentSender, currentMessage.toString(), currentTimestamp, importId));
        }

        return items;
    }

    /**
     * Creates an imported item for a chat message.
     */
    private ImportResult.ImportedItem createMessageItem(String sender, String message, Instant timestamp, String importId) {
        // Store message in vault
        String vaultRef = vaultStorage.store(message.getBytes(StandardCharsets.UTF_8), Map.of(
                "source", IMPORTER_ID,
                "dataType", "chat_messages",
                "sender", sender,
                "importId", importId
        ));

        return new ImportResult.ImportedItem(
                UUID.randomUUID().toString(),
                "chat_messages",
                vaultRef,
                timestamp != null ? timestamp : Instant.now(),
                Map.of("sender", sender, "messageLength", String.valueOf(message.length()))
        );
    }

    /**
     * Parses date and time from WhatsApp format.
     */
    private Instant parseDateTime(String dateStr, String timeStr) {
        try {
            String[] dateParts = dateStr.split("/");
            int year = Integer.parseInt(dateParts[2]);
            if (year < 100) {
                year += 2000;
            }
            int month = Integer.parseInt(dateParts[1]);
            int day = Integer.parseInt(dateParts[0]);

            String[] timeParts = timeStr.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            int second = timeParts.length > 2 ? Integer.parseInt(timeParts[2]) : 0;

            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /**
     * Checks if file is a media file.
     */
    private boolean isMediaFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".gif") || lower.endsWith(".mp4") || lower.endsWith(".mp3") ||
               lower.endsWith(".opus") || lower.endsWith(".webp") || lower.endsWith(".pdf");
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
}
