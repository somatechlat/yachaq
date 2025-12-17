package com.yachaq.node.normalizer;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Abstract base class for data normalizers with common utilities.
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public abstract class AbstractNormalizer implements DataNormalizer {

    protected static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    @Override
    public List<CanonicalEvent> normalizeBatch(List<Map<String, Object>> records, String sourceId) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<CanonicalEvent> events = new ArrayList<>();
        for (Map<String, Object> record : records) {
            try {
                events.addAll(normalize(record, sourceId));
            } catch (Exception e) {
                // Log and continue with next record
                // In production, this would use proper logging
            }
        }
        return events;
    }

    @Override
    public ValidationResult validate(Map<String, Object> rawData) {
        if (rawData == null || rawData.isEmpty()) {
            return ValidationResult.failure("Raw data cannot be null or empty");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateRequiredFields(rawData, errors);
        validateFieldTypes(rawData, warnings);

        if (!errors.isEmpty()) {
            return ValidationResult.failure(errors);
        }
        if (!warnings.isEmpty()) {
            return ValidationResult.withWarnings(warnings);
        }
        return ValidationResult.success();
    }

    /**
     * Validates required fields are present.
     */
    protected abstract void validateRequiredFields(Map<String, Object> rawData, List<String> errors);

    /**
     * Validates field types and formats.
     */
    protected void validateFieldTypes(Map<String, Object> rawData, List<String> warnings) {
        // Default implementation - subclasses can override
    }

    // ==================== Utility Methods ====================

    protected String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    protected String getString(Map<String, Object> data, String key, String defaultValue) {
        String value = getString(data, key);
        return value != null ? value : defaultValue;
    }

    protected Long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Double getDouble(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected Boolean getBoolean(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    protected Instant getInstant(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof Number) {
            // Assume epoch milliseconds
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        try {
            return Instant.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    protected List<Object> getList(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return null;
    }

    protected String computeContentHash(Map<String, Object> data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String content = data.toString();
            byte[] hash = digest.digest(content.getBytes());
            return bytesToHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    protected String generateEventId(String sourceType, String sourceId, Instant timestamp) {
        String input = sourceType + ":" + sourceId + ":" + timestamp.toEpochMilli();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return bytesToHex(hash).substring(0, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
