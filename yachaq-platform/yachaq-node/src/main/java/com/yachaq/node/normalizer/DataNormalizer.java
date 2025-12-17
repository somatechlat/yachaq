package com.yachaq.node.normalizer;

import java.util.List;
import java.util.Map;

/**
 * Interface for data normalizers that convert source-specific data to canonical events.
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public interface DataNormalizer {

    /**
     * Gets the source type this normalizer handles.
     */
    String getSourceType();

    /**
     * Normalizes raw data into canonical events.
     * 
     * @param rawData The raw data from the source
     * @param sourceId Identifier for the specific source instance
     * @return List of normalized canonical events
     */
    List<CanonicalEvent> normalize(Map<String, Object> rawData, String sourceId);

    /**
     * Normalizes a batch of raw data records.
     * 
     * @param records List of raw data records
     * @param sourceId Identifier for the specific source instance
     * @return List of normalized canonical events
     */
    List<CanonicalEvent> normalizeBatch(List<Map<String, Object>> records, String sourceId);

    /**
     * Gets the schema version this normalizer produces.
     */
    default String getSchemaVersion() {
        return CanonicalEvent.CURRENT_SCHEMA_VERSION;
    }

    /**
     * Validates that raw data can be normalized.
     * 
     * @param rawData The raw data to validate
     * @return Validation result
     */
    ValidationResult validate(Map<String, Object> rawData);

    /**
     * Result of validation.
     */
    record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {
        public static ValidationResult success() {
            return new ValidationResult(true, List.of(), List.of());
        }

        public static ValidationResult failure(String error) {
            return new ValidationResult(false, List.of(error), List.of());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors, List.of());
        }

        public static ValidationResult withWarnings(List<String> warnings) {
            return new ValidationResult(true, List.of(), warnings);
        }
    }
}
