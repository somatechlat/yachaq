package com.yachaq.api.schema;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clean-room Output Schema Library for managing and enforcing output schemas.
 * Provides schema registry, sensitivity grading, and schema enforcement.
 * 
 * Security: Schemas enforce data minimization and sensitivity controls.
 * Performance: Schema lookups are O(1) via registry.
 * 
 * Validates: Requirements 354.1, 354.2, 354.3
 */
@Service
public class CleanRoomSchemaLibrary {

    private final Map<String, OutputSchema> schemaRegistry = new ConcurrentHashMap<>();

    public CleanRoomSchemaLibrary() {
        registerDefaultSchemas();
    }

    // ==================== Task 100.1: Schema Registry ====================

    /**
     * Registers a new output schema.
     * Requirement 354.1: Register standard output schemas.
     */
    public RegistrationResult registerSchema(OutputSchema schema) {
        Objects.requireNonNull(schema, "Schema cannot be null");
        validateSchema(schema);

        if (schemaRegistry.containsKey(schema.id())) {
            return new RegistrationResult(false, "Schema already exists: " + schema.id());
        }

        schemaRegistry.put(schema.id(), schema);
        return new RegistrationResult(true, "Schema registered: " + schema.id());
    }


    /**
     * Gets a schema by ID.
     */
    public Optional<OutputSchema> getSchema(String schemaId) {
        return Optional.ofNullable(schemaRegistry.get(schemaId));
    }

    /**
     * Lists all registered schemas.
     */
    public List<OutputSchema> listSchemas() {
        return new ArrayList<>(schemaRegistry.values());
    }

    /**
     * Lists schemas by sensitivity grade.
     */
    public List<OutputSchema> listSchemasBySensitivity(SensitivityGrade grade) {
        return schemaRegistry.values().stream()
                .filter(s -> s.sensitivityGrade() == grade)
                .toList();
    }

    private void validateSchema(OutputSchema schema) {
        if (schema.id() == null || schema.id().isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        if (schema.name() == null || schema.name().isBlank()) {
            throw new IllegalArgumentException("Schema name is required");
        }
        if (schema.fields() == null || schema.fields().isEmpty()) {
            throw new IllegalArgumentException("Schema must have at least one field");
        }
    }

    private void registerDefaultSchemas() {
        // Aggregate-only schemas
        registerSchema(new OutputSchema(
                "aggregate_count",
                "Aggregate Count",
                "Simple count aggregation",
                SensitivityGrade.LOW,
                OutputMode.AGGREGATE_ONLY,
                List.of(
                        new SchemaField("count", "integer", true, false, "Total count"),
                        new SchemaField("group_key", "string", false, false, "Grouping key")
                ),
                Map.of("min_group_size", 50)
        ));

        registerSchema(new OutputSchema(
                "aggregate_stats",
                "Aggregate Statistics",
                "Statistical aggregation with mean, median, std",
                SensitivityGrade.LOW,
                OutputMode.AGGREGATE_ONLY,
                List.of(
                        new SchemaField("count", "integer", true, false, "Sample count"),
                        new SchemaField("mean", "number", true, false, "Mean value"),
                        new SchemaField("median", "number", false, false, "Median value"),
                        new SchemaField("std_dev", "number", false, false, "Standard deviation"),
                        new SchemaField("min", "number", false, false, "Minimum value"),
                        new SchemaField("max", "number", false, false, "Maximum value")
                ),
                Map.of("min_group_size", 50, "noise_epsilon", 0.1)
        ));

        // Clean-room schemas
        registerSchema(new OutputSchema(
                "clean_room_view",
                "Clean Room View",
                "View-only access in clean room environment",
                SensitivityGrade.MEDIUM,
                OutputMode.CLEAN_ROOM,
                List.of(
                        new SchemaField("data", "object", true, false, "Data payload"),
                        new SchemaField("metadata", "object", false, false, "Metadata"),
                        new SchemaField("access_log", "array", true, false, "Access audit log")
                ),
                Map.of("no_export", true, "no_copy", true, "watermark", true)
        ));

        // Export schemas (high sensitivity)
        registerSchema(new OutputSchema(
                "export_anonymized",
                "Anonymized Export",
                "Anonymized data export with k-anonymity",
                SensitivityGrade.HIGH,
                OutputMode.EXPORT,
                List.of(
                        new SchemaField("records", "array", true, false, "Anonymized records"),
                        new SchemaField("k_value", "integer", true, false, "K-anonymity value"),
                        new SchemaField("suppressed_count", "integer", true, false, "Suppressed records")
                ),
                Map.of("min_k", 50, "generalization_required", true)
        ));
    }

    // ==================== Task 100.2: Sensitivity Grading ====================

    /**
     * Assigns sensitivity grade to a schema.
     * Requirement 354.2: Assign sensitivity grades to schemas.
     */
    public SensitivityGrade calculateSensitivityGrade(OutputSchema schema) {
        int score = 0;

        // Check output mode
        score += switch (schema.outputMode()) {
            case AGGREGATE_ONLY -> 0;
            case CLEAN_ROOM -> 2;
            case EXPORT -> 4;
        };

        // Check for sensitive fields
        for (SchemaField field : schema.fields()) {
            if (field.sensitive()) {
                score += 2;
            }
            if (field.pii()) {
                score += 3;
            }
        }

        // Check constraints
        if (!schema.constraints().containsKey("min_group_size")) {
            score += 1;
        }
        if (schema.constraints().containsKey("no_export") && 
            !(Boolean) schema.constraints().get("no_export")) {
            score += 2;
        }

        // Map score to grade
        if (score <= 2) return SensitivityGrade.LOW;
        if (score <= 5) return SensitivityGrade.MEDIUM;
        if (score <= 8) return SensitivityGrade.HIGH;
        return SensitivityGrade.CRITICAL;
    }

    /**
     * Gets sensitivity requirements for a grade.
     */
    public SensitivityRequirements getRequirements(SensitivityGrade grade) {
        return switch (grade) {
            case LOW -> new SensitivityRequirements(
                    50, false, false, false, Set.of("AGGREGATE_ONLY"));
            case MEDIUM -> new SensitivityRequirements(
                    100, true, false, true, Set.of("AGGREGATE_ONLY", "CLEAN_ROOM"));
            case HIGH -> new SensitivityRequirements(
                    200, true, true, true, Set.of("AGGREGATE_ONLY", "CLEAN_ROOM", "EXPORT"));
            case CRITICAL -> new SensitivityRequirements(
                    500, true, true, true, Set.of("CLEAN_ROOM"));
        };
    }


    // ==================== Task 100.3: Schema Enforcement ====================

    /**
     * Enforces schema constraints on output data.
     * Requirement 354.3: Enforce schema constraints on outputs.
     */
    public EnforcementResult enforceSchema(String schemaId, Map<String, Object> output) {
        Objects.requireNonNull(schemaId, "Schema ID cannot be null");
        Objects.requireNonNull(output, "Output cannot be null");

        Optional<OutputSchema> schemaOpt = getSchema(schemaId);
        if (schemaOpt.isEmpty()) {
            return EnforcementResult.error("Schema not found: " + schemaId);
        }

        OutputSchema schema = schemaOpt.get();
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> sanitizedOutput = new HashMap<>();

        // Validate required fields
        for (SchemaField field : schema.fields()) {
            if (field.required() && !output.containsKey(field.name())) {
                violations.add("Missing required field: " + field.name());
            }
        }

        // Validate and sanitize each field
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();

            Optional<SchemaField> fieldOpt = schema.fields().stream()
                    .filter(f -> f.name().equals(fieldName))
                    .findFirst();

            if (fieldOpt.isEmpty()) {
                warnings.add("Unknown field removed: " + fieldName);
                continue;
            }

            SchemaField field = fieldOpt.get();

            // Type validation
            if (!isValidType(value, field.type())) {
                violations.add("Invalid type for field " + fieldName + 
                              ": expected " + field.type());
                continue;
            }

            // PII check
            if (field.pii() && containsPII(value)) {
                violations.add("PII detected in field: " + fieldName);
                continue;
            }

            sanitizedOutput.put(fieldName, value);
        }

        // Check constraints (only if no type violations so far)
        if (violations.isEmpty()) {
            for (Map.Entry<String, Object> constraint : schema.constraints().entrySet()) {
                String key = constraint.getKey();
                Object expected = constraint.getValue();

                if (key.equals("min_group_size") && sanitizedOutput.containsKey("count")) {
                    Object countValue = sanitizedOutput.get("count");
                    if (countValue instanceof Number) {
                        int count = ((Number) countValue).intValue();
                        int minSize = ((Number) expected).intValue();
                        if (count < minSize) {
                            violations.add("Group size " + count + " below minimum " + minSize);
                        }
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            return EnforcementResult.error(violations, warnings);
        }

        return EnforcementResult.success(sanitizedOutput, warnings);
    }

    private boolean isValidType(Object value, String expectedType) {
        if (value == null) return false;
        return switch (expectedType.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer", "int" -> value instanceof Integer || value instanceof Long;
            case "number", "double", "float" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array", "list" -> value instanceof List;
            case "object", "map" -> value instanceof Map;
            default -> true;
        };
    }

    private boolean containsPII(Object value) {
        if (value instanceof String str) {
            // Simple PII detection patterns
            return str.matches(".*\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b.*") || // Email
                   str.matches(".*\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b.*") || // Phone
                   str.matches(".*\\b\\d{3}[-]?\\d{2}[-]?\\d{4}\\b.*"); // SSN pattern
        }
        return false;
    }

    // ==================== Inner Types ====================

    public record OutputSchema(
            String id,
            String name,
            String description,
            SensitivityGrade sensitivityGrade,
            OutputMode outputMode,
            List<SchemaField> fields,
            Map<String, Object> constraints
    ) {}

    public record SchemaField(
            String name,
            String type,
            boolean required,
            boolean pii,
            String description
    ) {
        public boolean sensitive() {
            return pii || name.toLowerCase().contains("health") || 
                   name.toLowerCase().contains("finance");
        }
    }

    public enum SensitivityGrade { LOW, MEDIUM, HIGH, CRITICAL }
    public enum OutputMode { AGGREGATE_ONLY, CLEAN_ROOM, EXPORT }

    public record RegistrationResult(boolean success, String message) {}

    public record SensitivityRequirements(
            int minCohortSize,
            boolean auditRequired,
            boolean bondRequired,
            boolean watermarkRequired,
            Set<String> allowedOutputModes
    ) {}

    public record EnforcementResult(
            boolean success,
            Map<String, Object> sanitizedOutput,
            List<String> violations,
            List<String> warnings
    ) {
        public static EnforcementResult success(Map<String, Object> output, List<String> warnings) {
            return new EnforcementResult(true, output, List.of(), warnings);
        }
        public static EnforcementResult error(String error) {
            return new EnforcementResult(false, Map.of(), List.of(error), List.of());
        }
        public static EnforcementResult error(List<String> violations, List<String> warnings) {
            return new EnforcementResult(false, Map.of(), violations, warnings);
        }
    }
}
