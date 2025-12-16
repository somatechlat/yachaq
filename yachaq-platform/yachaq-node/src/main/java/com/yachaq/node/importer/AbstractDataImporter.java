package com.yachaq.node.importer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Abstract base class for data importers with common functionality.
 * Requirement 306.2: Perform checksum verification and malware scanning.
 * Requirement 306.5: Never upload import files to any server.
 */
public abstract class AbstractDataImporter implements DataImporter {

    protected static final String CHECKSUM_ALGORITHM = "SHA-256";
    protected static final long DEFAULT_MAX_FILE_SIZE = 5L * 1024 * 1024 * 1024; // 5GB
    protected static final long DEFAULT_MAX_ENTRY_SIZE = 1L * 1024 * 1024 * 1024; // 1GB per entry
    protected static final int ZIP_BOMB_RATIO_THRESHOLD = 100; // Compression ratio threshold

    private final String id;
    private final List<String> supportedExtensions;
    private final List<String> supportedDataTypes;

    protected AbstractDataImporter(String id, List<String> extensions, List<String> dataTypes) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Importer ID cannot be null or blank");
        }
        this.id = id;
        this.supportedExtensions = extensions != null ? List.copyOf(extensions) : List.of();
        this.supportedDataTypes = dataTypes != null ? List.copyOf(dataTypes) : List.of();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<String> getSupportedExtensions() {
        return supportedExtensions;
    }

    @Override
    public List<String> getSupportedDataTypes() {
        return supportedDataTypes;
    }

    @Override
    public CompletableFuture<ValidationResult> validate(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check file exists
                if (!Files.exists(filePath)) {
                    return ValidationResult.failure(List.of("File does not exist: " + filePath));
                }

                // Check file size
                long fileSize = Files.size(filePath);
                if (fileSize > DEFAULT_MAX_FILE_SIZE) {
                    return ValidationResult.tooLarge(fileSize, DEFAULT_MAX_FILE_SIZE);
                }

                // Check extension
                String fileName = filePath.getFileName().toString().toLowerCase();
                boolean validExtension = supportedExtensions.stream()
                        .anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
                if (!validExtension) {
                    return ValidationResult.unsupportedFormat(getFileExtension(fileName));
                }

                // Calculate checksum
                String checksum = calculateChecksum(filePath);

                // Check for ZIP bomb (if ZIP file)
                if (fileName.endsWith(".zip")) {
                    List<String> warnings = checkZipSafety(filePath, fileSize);
                    if (!warnings.isEmpty()) {
                        return ValidationResult.successWithWarnings(checksum, CHECKSUM_ALGORITHM, fileSize, warnings);
                    }
                }

                // Perform format-specific validation
                List<String> formatWarnings = validateFormat(filePath);
                if (!formatWarnings.isEmpty()) {
                    return ValidationResult.successWithWarnings(checksum, CHECKSUM_ALGORITHM, fileSize, formatWarnings);
                }

                return ValidationResult.success(checksum, CHECKSUM_ALGORITHM, fileSize);

            } catch (IOException e) {
                return ValidationResult.corrupted("Failed to read file: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<ScanResult> scan(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String fileName = filePath.getFileName().toString().toLowerCase();
                
                if (fileName.endsWith(".zip")) {
                    return scanZipFile(filePath);
                } else if (fileName.endsWith(".json")) {
                    return scanJsonFile(filePath);
                } else {
                    return scanGenericFile(filePath);
                }
            } catch (IOException e) {
                return ScanResult.failure("Failed to scan file: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<ImportResult> importData(InputStream inputStream, String fileName, ImportOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create temp file for processing
                Path tempFile = Files.createTempFile("import_", "_" + fileName);
                try {
                    Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return importData(tempFile, options).join();
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException e) {
                return ImportResult.failure(
                        List.of(new ImportResult.ImportError(fileName, "IO_ERROR", e.getMessage(), false)),
                        Instant.now(),
                        UUID.randomUUID().toString()
                );
            }
        });
    }

    /**
     * Calculates SHA-256 checksum of a file.
     */
    protected String calculateChecksum(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM);
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Checksum algorithm not available", e);
        }
    }

    /**
     * Checks ZIP file for potential security issues (ZIP bombs, etc.).
     */
    protected List<String> checkZipSafety(Path zipPath, long compressedSize) throws IOException {
        List<String> warnings = new ArrayList<>();
        long totalUncompressedSize = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                
                // Check for path traversal
                String entryName = entry.getName();
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    warnings.add("Suspicious path in ZIP: " + entryName);
                }

                // Track uncompressed size
                if (entry.getSize() > 0) {
                    totalUncompressedSize += entry.getSize();
                }

                // Check individual entry size
                if (entry.getSize() > DEFAULT_MAX_ENTRY_SIZE) {
                    warnings.add("Large entry detected: " + entryName + " (" + entry.getSize() + " bytes)");
                }

                zis.closeEntry();
            }
        }

        // Check compression ratio (ZIP bomb detection)
        if (compressedSize > 0 && totalUncompressedSize > 0) {
            long ratio = totalUncompressedSize / compressedSize;
            if (ratio > ZIP_BOMB_RATIO_THRESHOLD) {
                warnings.add("High compression ratio detected (" + ratio + "x). Possible ZIP bomb.");
            }
        }

        // Check entry count
        if (entryCount > 100000) {
            warnings.add("Very large number of entries: " + entryCount);
        }

        return warnings;
    }

    /**
     * Scans a ZIP file for contents.
     */
    protected ScanResult scanZipFile(Path zipPath) throws IOException {
        Map<String, Long> fileCountByType = new HashMap<>();
        Map<String, Long> sizeByType = new HashMap<>();
        List<ScanResult.SensitivityWarning> warnings = new ArrayList<>();
        Set<String> detectedTypes = new HashSet<>();
        long totalFiles = 0;
        long totalSize = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    totalFiles++;
                    long entrySize = entry.getSize() > 0 ? entry.getSize() : 0;
                    totalSize += entrySize;

                    String ext = getFileExtension(entry.getName());
                    fileCountByType.merge(ext, 1L, Long::sum);
                    sizeByType.merge(ext, entrySize, Long::sum);

                    // Detect data types and sensitivity
                    detectDataTypeAndSensitivity(entry.getName(), detectedTypes, warnings);
                }
                zis.closeEntry();
            }
        }

        return ScanResult.success(
                totalFiles, totalSize, fileCountByType, sizeByType,
                warnings, new ArrayList<>(detectedTypes)
        );
    }

    /**
     * Scans a JSON file.
     */
    protected ScanResult scanJsonFile(Path jsonPath) throws IOException {
        long size = Files.size(jsonPath);
        List<ScanResult.SensitivityWarning> warnings = new ArrayList<>();
        Set<String> detectedTypes = new HashSet<>();

        detectDataTypeAndSensitivity(jsonPath.getFileName().toString(), detectedTypes, warnings);

        return ScanResult.success(
                1, size,
                Map.of(".json", 1L),
                Map.of(".json", size),
                warnings,
                new ArrayList<>(detectedTypes)
        );
    }

    /**
     * Scans a generic file.
     */
    protected ScanResult scanGenericFile(Path filePath) throws IOException {
        long size = Files.size(filePath);
        String ext = getFileExtension(filePath.getFileName().toString());
        List<ScanResult.SensitivityWarning> warnings = new ArrayList<>();
        Set<String> detectedTypes = new HashSet<>();

        detectDataTypeAndSensitivity(filePath.getFileName().toString(), detectedTypes, warnings);

        return ScanResult.success(
                1, size,
                Map.of(ext, 1L),
                Map.of(ext, size),
                warnings,
                new ArrayList<>(detectedTypes)
        );
    }

    /**
     * Detects data type and sensitivity from file path.
     */
    protected void detectDataTypeAndSensitivity(
            String path,
            Set<String> detectedTypes,
            List<ScanResult.SensitivityWarning> warnings) {
        
        String lowerPath = path.toLowerCase();

        // Location data
        if (lowerPath.contains("location") || lowerPath.contains("gps") || lowerPath.contains("places")) {
            detectedTypes.add("location_history");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.HIGH,
                    "Location",
                    "Location history data detected",
                    "This data reveals your physical movements and frequently visited places"
            ));
        }

        // Messages
        if (lowerPath.contains("message") || lowerPath.contains("chat") || lowerPath.contains("conversation")) {
            detectedTypes.add("messages");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.CRITICAL,
                    "Communications",
                    "Private messages detected",
                    "This data contains private communications"
            ));
        }

        // Photos
        if (lowerPath.contains("photo") || lowerPath.contains("image") || 
            lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".png")) {
            detectedTypes.add("photos");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.HIGH,
                    "Photos",
                    "Photo data detected",
                    "Photos may contain faces, locations, and personal moments"
            ));
        }

        // Health data
        if (lowerPath.contains("health") || lowerPath.contains("fitness") || lowerPath.contains("medical")) {
            detectedTypes.add("health");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.CRITICAL,
                    "Health",
                    "Health data detected",
                    "Health data is highly sensitive and protected by regulations"
            ));
        }

        // Financial data
        if (lowerPath.contains("payment") || lowerPath.contains("transaction") || lowerPath.contains("purchase")) {
            detectedTypes.add("financial");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.HIGH,
                    "Financial",
                    "Financial data detected",
                    "This data reveals your spending patterns and financial activity"
            ));
        }

        // Contacts
        if (lowerPath.contains("contact") || lowerPath.contains("address")) {
            detectedTypes.add("contacts");
            warnings.add(new ScanResult.SensitivityWarning(
                    ScanResult.SensitivityLevel.MEDIUM,
                    "Contacts",
                    "Contact data detected",
                    "This data contains information about your social connections"
            ));
        }
    }

    /**
     * Gets file extension from filename.
     */
    protected String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(lastDot).toLowerCase();
        }
        return "";
    }

    /**
     * Generates a unique import ID.
     */
    protected String generateImportId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validates format-specific requirements.
     * Subclasses should override to add format-specific validation.
     */
    protected List<String> validateFormat(Path filePath) throws IOException {
        return List.of();
    }
}
