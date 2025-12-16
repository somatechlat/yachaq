package com.yachaq.node.importer;

import java.util.List;
import java.util.Map;

/**
 * Result of import file validation.
 * Requirement 306.2: Perform checksum verification and malware scanning.
 */
public record ValidationResult(
        boolean valid,
        ValidationStatus status,
        String checksum,
        String checksumAlgorithm,
        long fileSizeBytes,
        List<String> warnings,
        List<String> errors,
        Map<String, Object> metadata
) {
    public ValidationResult {
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
        errors = errors != null ? List.copyOf(errors) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Creates a successful validation result.
     */
    public static ValidationResult success(String checksum, String algorithm, long size) {
        return new ValidationResult(
                true, ValidationStatus.VALID, checksum, algorithm, size,
                List.of(), List.of(), Map.of()
        );
    }

    /**
     * Creates a successful validation with warnings.
     */
    public static ValidationResult successWithWarnings(String checksum, String algorithm, long size, List<String> warnings) {
        return new ValidationResult(
                true, ValidationStatus.VALID_WITH_WARNINGS, checksum, algorithm, size,
                warnings, List.of(), Map.of()
        );
    }

    /**
     * Creates a failed validation result.
     */
    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(
                false, ValidationStatus.INVALID, null, null, 0,
                List.of(), errors, Map.of()
        );
    }

    /**
     * Creates a result for corrupted file.
     */
    public static ValidationResult corrupted(String error) {
        return new ValidationResult(
                false, ValidationStatus.CORRUPTED, null, null, 0,
                List.of(), List.of(error), Map.of()
        );
    }

    /**
     * Creates a result for unsupported format.
     */
    public static ValidationResult unsupportedFormat(String format) {
        return new ValidationResult(
                false, ValidationStatus.UNSUPPORTED_FORMAT, null, null, 0,
                List.of(), List.of("Unsupported format: " + format), Map.of()
        );
    }

    /**
     * Creates a result for file too large.
     */
    public static ValidationResult tooLarge(long size, long maxSize) {
        return new ValidationResult(
                false, ValidationStatus.TOO_LARGE, null, null, size,
                List.of(), List.of("File size " + size + " exceeds maximum " + maxSize), Map.of()
        );
    }

    /**
     * Validation status.
     */
    public enum ValidationStatus {
        VALID,
        VALID_WITH_WARNINGS,
        INVALID,
        CORRUPTED,
        UNSUPPORTED_FORMAT,
        TOO_LARGE,
        MALWARE_DETECTED
    }
}
