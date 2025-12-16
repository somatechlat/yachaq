package com.yachaq.node.importer;

import java.util.List;
import java.util.Map;

/**
 * Result of scanning an import file for contents and sensitivity.
 * Requirement 306.6: Display warnings for sensitive data.
 */
public record ScanResult(
        boolean success,
        long totalFiles,
        long totalSizeBytes,
        Map<String, Long> fileCountByType,
        Map<String, Long> sizeByType,
        List<SensitivityWarning> sensitivityWarnings,
        List<String> detectedDataTypes,
        String estimatedImportTime,
        String errorMessage
) {
    public ScanResult {
        fileCountByType = fileCountByType != null ? Map.copyOf(fileCountByType) : Map.of();
        sizeByType = sizeByType != null ? Map.copyOf(sizeByType) : Map.of();
        sensitivityWarnings = sensitivityWarnings != null ? List.copyOf(sensitivityWarnings) : List.of();
        detectedDataTypes = detectedDataTypes != null ? List.copyOf(detectedDataTypes) : List.of();
    }

    /**
     * Creates a successful scan result.
     */
    public static ScanResult success(
            long totalFiles,
            long totalSize,
            Map<String, Long> fileCountByType,
            Map<String, Long> sizeByType,
            List<SensitivityWarning> warnings,
            List<String> dataTypes) {
        
        String estimatedTime = estimateImportTime(totalSize);
        return new ScanResult(
                true, totalFiles, totalSize, fileCountByType, sizeByType,
                warnings, dataTypes, estimatedTime, null
        );
    }

    /**
     * Creates a failed scan result.
     */
    public static ScanResult failure(String error) {
        return new ScanResult(
                false, 0, 0, Map.of(), Map.of(),
                List.of(), List.of(), null, error
        );
    }

    /**
     * Returns whether the import contains high-sensitivity data.
     */
    public boolean hasHighSensitivityData() {
        return sensitivityWarnings.stream()
                .anyMatch(w -> w.level() == SensitivityLevel.HIGH || w.level() == SensitivityLevel.CRITICAL);
    }

    /**
     * Estimates import time based on file size.
     */
    private static String estimateImportTime(long sizeBytes) {
        // Rough estimate: 10MB/second processing speed
        long seconds = sizeBytes / (10 * 1024 * 1024);
        if (seconds < 60) {
            return "Less than 1 minute";
        } else if (seconds < 3600) {
            return (seconds / 60) + " minutes";
        } else {
            return (seconds / 3600) + " hours";
        }
    }

    /**
     * Sensitivity warning for import data.
     */
    public record SensitivityWarning(
            SensitivityLevel level,
            String category,
            String description,
            String recommendation
    ) {}

    /**
     * Sensitivity levels.
     */
    public enum SensitivityLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
