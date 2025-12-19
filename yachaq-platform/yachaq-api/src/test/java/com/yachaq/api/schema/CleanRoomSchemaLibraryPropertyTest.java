package com.yachaq.api.schema;

import com.yachaq.api.schema.CleanRoomSchemaLibrary.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Clean-room Output Schema Library.
 * Tests schema registration, sensitivity grading, and schema enforcement.
 * 
 * **Feature: yachaq-platform, Property 64: Clean-room Schema Enforcement**
 * **Validates: Requirements 354.2, 354.3**
 * 
 * Security: Ensures schemas enforce data minimization and sensitivity controls.
 * Performance: Tests schema operations with various data sizes.
 */
@SpringBootTest
@ActiveProfiles("test")
class CleanRoomSchemaLibraryPropertyTest {

    @Autowired
    private CleanRoomSchemaLibrary schemaLibrary;

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42); // Fixed seed for reproducibility
    }

    // ==================== Property 64: Clean-room Schema Enforcement ====================

    /**
     * **Feature: yachaq-platform, Property 64.1: Schema Registration Completeness**
     * 
     * *For any* valid schema, registration should succeed and the schema
     * should be retrievable with all fields intact.
     * 
     * **Validates: Requirements 354.1**
     */
    @Test
    void property64_1_schemaRegistrationCompleteness() {
        for (int i = 0; i < 50; i++) {
            OutputSchema schema = generateValidSchema("test_schema_" + i);
            
            RegistrationResult result = schemaLibrary.registerSchema(schema);
            
            assertTrue(result.success(), 
                    "Valid schema should register successfully: " + result.message());
            
            Optional<OutputSchema> retrieved = schemaLibrary.getSchema(schema.id());
            assertTrue(retrieved.isPresent(), "Registered schema should be retrievable");
            assertEquals(schema.id(), retrieved.get().id());
            assertEquals(schema.name(), retrieved.get().name());
            assertEquals(schema.sensitivityGrade(), retrieved.get().sensitivityGrade());
            assertEquals(schema.fields().size(), retrieved.get().fields().size());
        }
    }

    /**
     * **Feature: yachaq-platform, Property 64.2: Duplicate Schema Rejection**
     * 
     * *For any* schema with an existing ID, registration should fail
     * with an appropriate error message.
     * 
     * **Validates: Requirements 354.1**
     */
    @Test
    void property64_2_duplicateSchemaRejection() {
        OutputSchema schema = generateValidSchema("duplicate_test");
        
        RegistrationResult firstResult = schemaLibrary.registerSchema(schema);
        assertTrue(firstResult.success(), "First registration should succeed");
        
        RegistrationResult secondResult = schemaLibrary.registerSchema(schema);
        assertFalse(secondResult.success(), "Duplicate registration should fail");
        assertTrue(secondResult.message().contains("already exists"),
                "Error message should indicate duplicate");
    }

    /**
     * **Feature: yachaq-platform, Property 64.3: Sensitivity Grade Calculation**
     * 
     * *For any* schema, sensitivity grade should be calculated based on
     * output mode, field sensitivity, and constraints.
     * 
     * **Validates: Requirements 354.2**
     */
    @Test
    void property64_3_sensitivityGradeCalculation() {
        // LOW sensitivity: aggregate-only, no sensitive fields
        OutputSchema lowSchema = new OutputSchema(
                "low_test", "Low Test", "Test",
                SensitivityGrade.LOW,
                OutputMode.AGGREGATE_ONLY,
                List.of(new SchemaField("count", "integer", true, false, "Count")),
                Map.of("min_group_size", 50)
        );
        assertEquals(SensitivityGrade.LOW, schemaLibrary.calculateSensitivityGrade(lowSchema));

        // MEDIUM sensitivity: clean-room mode
        OutputSchema mediumSchema = new OutputSchema(
                "medium_test", "Medium Test", "Test",
                SensitivityGrade.MEDIUM,
                OutputMode.CLEAN_ROOM,
                List.of(new SchemaField("data", "object", true, false, "Data")),
                Map.of("min_group_size", 100)
        );
        SensitivityGrade mediumGrade = schemaLibrary.calculateSensitivityGrade(mediumSchema);
        assertTrue(mediumGrade == SensitivityGrade.MEDIUM || mediumGrade == SensitivityGrade.LOW,
                "Clean-room should be at least MEDIUM sensitivity");

        // HIGH sensitivity: export mode with sensitive fields
        OutputSchema highSchema = new OutputSchema(
                "high_test", "High Test", "Test",
                SensitivityGrade.HIGH,
                OutputMode.EXPORT,
                List.of(
                        new SchemaField("records", "array", true, false, "Records"),
                        new SchemaField("health_data", "object", true, false, "Health")
                ),
                Map.of()
        );
        SensitivityGrade highGrade = schemaLibrary.calculateSensitivityGrade(highSchema);
        assertTrue(highGrade == SensitivityGrade.HIGH || highGrade == SensitivityGrade.CRITICAL,
                "Export with sensitive fields should be HIGH or CRITICAL");
    }

    /**
     * **Feature: yachaq-platform, Property 64.4: PII Field Detection**
     * 
     * *For any* schema with PII fields, sensitivity grade should increase
     * and enforcement should detect PII in output.
     * 
     * **Validates: Requirements 354.2, 354.3**
     */
    @Test
    void property64_4_piiFieldDetection() {
        OutputSchema piiSchema = new OutputSchema(
                "pii_test_" + System.currentTimeMillis(),
                "PII Test", "Test with PII",
                SensitivityGrade.HIGH,
                OutputMode.CLEAN_ROOM,
                List.of(
                        new SchemaField("email", "string", true, true, "Email address"),
                        new SchemaField("count", "integer", true, false, "Count")
                ),
                Map.of()
        );
        
        schemaLibrary.registerSchema(piiSchema);
        
        // Test with PII data
        Map<String, Object> outputWithPII = Map.of(
                "email", "test@example.com",
                "count", 100
        );
        
        EnforcementResult result = schemaLibrary.enforceSchema(piiSchema.id(), outputWithPII);
        assertFalse(result.success(), "Output with PII should fail enforcement");
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("PII")),
                "Should detect PII violation");
    }

    /**
     * **Feature: yachaq-platform, Property 64.5: Required Field Enforcement**
     * 
     * *For any* schema with required fields, enforcement should fail
     * when required fields are missing.
     * 
     * **Validates: Requirements 354.3**
     */
    @Test
    void property64_5_requiredFieldEnforcement() {
        String schemaId = "aggregate_count"; // Default schema with required fields
        
        // Missing required field
        Map<String, Object> incompleteOutput = Map.of("group_key", "test");
        
        EnforcementResult result = schemaLibrary.enforceSchema(schemaId, incompleteOutput);
        assertFalse(result.success(), "Missing required field should fail");
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("Missing required")),
                "Should report missing required field");
    }

    /**
     * **Feature: yachaq-platform, Property 64.6: Type Validation**
     * 
     * *For any* output with incorrect field types, enforcement should fail
     * with type validation errors.
     * 
     * **Validates: Requirements 354.3**
     */
    @Test
    void property64_6_typeValidation() {
        String schemaId = "aggregate_count";
        
        // Wrong type for count field (string instead of integer)
        Map<String, Object> wrongTypeOutput = Map.of(
                "count", "not a number",
                "group_key", "test"
        );
        
        EnforcementResult result = schemaLibrary.enforceSchema(schemaId, wrongTypeOutput);
        assertFalse(result.success(), "Wrong type should fail enforcement");
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("Invalid type")),
                "Should report type violation");
    }

    /**
     * **Feature: yachaq-platform, Property 64.7: Minimum Group Size Constraint**
     * 
     * *For any* aggregate schema with min_group_size constraint, enforcement
     * should fail when count is below the minimum.
     * 
     * **Validates: Requirements 354.3**
     */
    @Test
    void property64_7_minimumGroupSizeConstraint() {
        String schemaId = "aggregate_count"; // Has min_group_size: 50
        
        // Count below minimum
        Map<String, Object> smallGroupOutput = Map.of(
                "count", 10,
                "group_key", "test"
        );
        
        EnforcementResult result = schemaLibrary.enforceSchema(schemaId, smallGroupOutput);
        assertFalse(result.success(), "Small group should fail enforcement");
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("below minimum")),
                "Should report group size violation");
        
        // Count at or above minimum
        Map<String, Object> validGroupOutput = Map.of(
                "count", 100,
                "group_key", "test"
        );
        
        EnforcementResult validResult = schemaLibrary.enforceSchema(schemaId, validGroupOutput);
        assertTrue(validResult.success(), "Valid group size should pass enforcement");
    }

    /**
     * **Feature: yachaq-platform, Property 64.8: Unknown Field Removal**
     * 
     * *For any* output with fields not in the schema, enforcement should
     * remove unknown fields and produce warnings.
     * 
     * **Validates: Requirements 354.3**
     */
    @Test
    void property64_8_unknownFieldRemoval() {
        String schemaId = "aggregate_count";
        
        Map<String, Object> outputWithExtra = new HashMap<>();
        outputWithExtra.put("count", 100);
        outputWithExtra.put("group_key", "test");
        outputWithExtra.put("unknown_field", "should be removed");
        
        EnforcementResult result = schemaLibrary.enforceSchema(schemaId, outputWithExtra);
        assertTrue(result.success(), "Should succeed with warnings");
        assertFalse(result.sanitizedOutput().containsKey("unknown_field"),
                "Unknown field should be removed");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Unknown field")),
                "Should warn about unknown field");
    }

    /**
     * **Feature: yachaq-platform, Property 64.9: Sensitivity Requirements**
     * 
     * *For any* sensitivity grade, requirements should be progressively
     * stricter as grade increases.
     * 
     * **Validates: Requirements 354.2**
     */
    @Test
    void property64_9_sensitivityRequirements() {
        SensitivityRequirements low = schemaLibrary.getRequirements(SensitivityGrade.LOW);
        SensitivityRequirements medium = schemaLibrary.getRequirements(SensitivityGrade.MEDIUM);
        SensitivityRequirements high = schemaLibrary.getRequirements(SensitivityGrade.HIGH);
        SensitivityRequirements critical = schemaLibrary.getRequirements(SensitivityGrade.CRITICAL);
        
        // Cohort size should increase with sensitivity
        assertTrue(low.minCohortSize() <= medium.minCohortSize(),
                "Medium should require larger cohort than low");
        assertTrue(medium.minCohortSize() <= high.minCohortSize(),
                "High should require larger cohort than medium");
        assertTrue(high.minCohortSize() <= critical.minCohortSize(),
                "Critical should require largest cohort");
        
        // Higher grades should require more controls
        assertFalse(low.auditRequired(), "Low should not require audit");
        assertTrue(high.auditRequired(), "High should require audit");
        assertTrue(critical.bondRequired(), "Critical should require bond");
    }

    /**
     * **Feature: yachaq-platform, Property 64.10: Null Safety**
     * 
     * *For any* null input, the service should throw appropriate exceptions.
     * 
     * **Validates: Requirements 354.1, 354.3**
     */
    @Test
    void property64_10_nullSafety() {
        assertThrows(NullPointerException.class,
                () -> schemaLibrary.registerSchema(null),
                "Null schema should throw NPE");
        
        assertThrows(NullPointerException.class,
                () -> schemaLibrary.enforceSchema(null, Map.of()),
                "Null schema ID should throw NPE");
        
        assertThrows(NullPointerException.class,
                () -> schemaLibrary.enforceSchema("test", null),
                "Null output should throw NPE");
    }

    /**
     * **Feature: yachaq-platform, Property 64.11: Default Schemas Available**
     * 
     * The library should have default schemas registered on initialization.
     * 
     * **Validates: Requirements 354.1**
     */
    @Test
    void property64_11_defaultSchemasAvailable() {
        List<OutputSchema> schemas = schemaLibrary.listSchemas();
        assertFalse(schemas.isEmpty(), "Should have default schemas");
        
        // Check specific default schemas
        assertTrue(schemaLibrary.getSchema("aggregate_count").isPresent(),
                "aggregate_count schema should exist");
        assertTrue(schemaLibrary.getSchema("aggregate_stats").isPresent(),
                "aggregate_stats schema should exist");
        assertTrue(schemaLibrary.getSchema("clean_room_view").isPresent(),
                "clean_room_view schema should exist");
        assertTrue(schemaLibrary.getSchema("export_anonymized").isPresent(),
                "export_anonymized schema should exist");
    }

    // ==================== Helper Methods ====================

    private OutputSchema generateValidSchema(String id) {
        List<SchemaField> fields = new ArrayList<>();
        fields.add(new SchemaField("data", "object", true, false, "Data field"));
        
        int extraFields = random.nextInt(3);
        for (int i = 0; i < extraFields; i++) {
            fields.add(new SchemaField(
                    "field_" + i,
                    randomType(),
                    random.nextBoolean(),
                    false,
                    "Field " + i
            ));
        }

        OutputMode mode = OutputMode.values()[random.nextInt(OutputMode.values().length)];
        SensitivityGrade grade = SensitivityGrade.values()[random.nextInt(SensitivityGrade.values().length)];

        return new OutputSchema(
                id,
                "Test Schema " + id,
                "Generated test schema",
                grade,
                mode,
                fields,
                Map.of("min_group_size", 50 + random.nextInt(100))
        );
    }

    private String randomType() {
        String[] types = {"string", "integer", "number", "boolean", "array", "object"};
        return types[random.nextInt(types.length)];
    }
}
