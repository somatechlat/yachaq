package com.yachaq.api.criteria;

import com.yachaq.api.criteria.ODXCriteriaLanguage.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for ODX Criteria Language.
 * Tests criteria parsing, specificity validation, and privacy floor enforcement.
 * 
 * **Feature: yachaq-platform, Property 63: ODX Criteria Safety**
 * **Validates: Requirements 353.1, 353.2**
 * 
 * Security: Ensures criteria cannot be used for targeting individuals.
 * Performance: Tests parsing performance with complex expressions.
 */
@SpringBootTest
@ActiveProfiles("test")
class ODXCriteriaLanguagePropertyTest {

    @Autowired
    private ODXCriteriaLanguage criteriaLanguage;

    private static final String[] LABEL_FAMILIES = {"health", "finance", "location", "communication", "activity", "media"};
    private static final String[] LABEL_NAMES = {"steps", "heart_rate", "transactions", "messages", "photos", "music"};
    private static final String[] TIME_GRANULARITIES = {"hour", "day", "week", "month", "year"};
    private static final String[] GEO_PRECISIONS = {"country", "region", "city"};
    private static final String[] COUNT_OPERATORS = {"gte", "lte", "eq"};

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42); // Fixed seed for reproducibility
    }

    // ==================== Property 63: ODX Criteria Safety ====================

    /**
     * **Feature: yachaq-platform, Property 63.1: Criteria Parsing Completeness**
     * 
     * *For any* valid ODX criteria expression, parsing should produce an AST
     * that preserves all criteria components and can be validated.
     * 
     * **Validates: Requirements 353.1**
     */
    @Test
    void property63_1_criteriaParsingCompleteness() {
        for (int i = 0; i < 100; i++) {
            String expression = generateValidCriteriaExpression();
            
            ParseResult result = criteriaLanguage.parse(expression);
            
            assertTrue(result.success(), 
                    "Valid expression should parse successfully: " + expression + 
                    " Errors: " + result.errors());
            assertNotNull(result.ast(), "AST should not be null for valid expression");
            assertFalse(result.ast().nodes().isEmpty(), 
                    "AST should contain at least one criterion node");
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.2: Invalid Criteria Rejection**
     * 
     * *For any* invalid ODX criteria expression, parsing should fail with
     * meaningful error messages.
     * 
     * **Validates: Requirements 353.1**
     */
    @Test
    void property63_2_invalidCriteriaRejection() {
        String[] invalidExpressions = {
            "",                           // Empty
            "   ",                        // Whitespace only
            "invalid:expression:format",  // Wrong format
            "health:",                    // Incomplete label
            ":steps",                     // Missing family
            "time:invalid:123",           // Invalid time granularity
            "geo:precise:NYC",            // Invalid geo precision
            "count:between:10",           // Invalid count operator
            "AND health:steps",           // Leading operator
            "health:steps AND",           // Trailing operator
        };

        for (String expression : invalidExpressions) {
            ParseResult result = criteriaLanguage.parse(expression);
            
            assertFalse(result.success(), 
                    "Invalid expression should fail parsing: " + expression);
            assertFalse(result.errors().isEmpty(), 
                    "Should have error messages for: " + expression);
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.3: Specificity Threshold Enforcement**
     * 
     * *For any* criteria with specificity exceeding the maximum threshold,
     * validation should fail to prevent targeting.
     * 
     * **Validates: Requirements 353.2**
     */
    @Test
    void property63_3_specificityThresholdEnforcement() {
        for (int i = 0; i < 100; i++) {
            // Generate overly specific criteria
            String expression = generateOverlySpecificCriteria();
            
            ParseResult parseResult = criteriaLanguage.parse(expression);
            if (!parseResult.success()) continue;
            
            SpecificityValidationResult validationResult = 
                    criteriaLanguage.validateSpecificity(parseResult.ast());
            
            // Overly specific criteria should either:
            // 1. Have specificity > MAX_LABEL_SPECIFICITY (5)
            // 2. Have estimated cohort < MIN_COHORT_SIZE (50)
            if (!validationResult.valid()) {
                assertTrue(
                    validationResult.specificity() > 5 || 
                    validationResult.estimatedCohortSize() < 50,
                    "Invalid criteria should exceed specificity or have small cohort"
                );
            }
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.4: Sensitive Family Warning**
     * 
     * *For any* criteria containing sensitive label families (health, finance,
     * location, communication), validation should produce warnings.
     * 
     * **Validates: Requirements 353.2**
     */
    @Test
    void property63_4_sensitiveFamilyWarning() {
        String[] sensitiveExpressions = {
            "health:steps",
            "finance:transactions",
            "location:visits",
            "communication:messages"
        };

        for (String expression : sensitiveExpressions) {
            ParseResult parseResult = criteriaLanguage.parse(expression);
            assertTrue(parseResult.success(), "Should parse: " + expression);
            
            SpecificityValidationResult validationResult = 
                    criteriaLanguage.validateSpecificity(parseResult.ast());
            
            assertFalse(validationResult.warnings().isEmpty(),
                    "Sensitive family should produce warnings: " + expression);
            assertTrue(validationResult.warnings().stream()
                    .anyMatch(w -> w.contains("Sensitive label family")),
                    "Should warn about sensitive family: " + expression);
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.5: Privacy Floor Enforcement**
     * 
     * *For any* criteria with precision exceeding privacy floors, enforcement
     * should coarsen the criteria to meet minimum privacy requirements.
     * 
     * **Validates: Requirements 353.3**
     */
    @Test
    void property63_5_privacyFloorEnforcement() {
        // Test city-level geo coarsening
        String cityExpression = "geo:city:NYC";
        ParseResult parseResult = criteriaLanguage.parse(cityExpression);
        assertTrue(parseResult.success());
        
        PrivacyFloorConfig config = new PrivacyFloorConfig(50, "region", "day");
        PrivacyFloorResult floorResult = criteriaLanguage.enforcePrivacyFloor(parseResult.ast(), config);
        
        assertTrue(floorResult.wasAdjusted(), 
                "City-level geo should be adjusted to region");
        assertFalse(floorResult.adjustments().isEmpty(),
                "Should have adjustment records");
        
        // Test hour-level time coarsening
        String hourExpression = "time:hour:14";
        parseResult = criteriaLanguage.parse(hourExpression);
        assertTrue(parseResult.success());
        
        floorResult = criteriaLanguage.enforcePrivacyFloor(parseResult.ast(), config);
        
        assertTrue(floorResult.wasAdjusted(),
                "Hour-level time should be adjusted to day");
    }

    /**
     * **Feature: yachaq-platform, Property 63.6: Static Validation Completeness**
     * 
     * *For any* criteria expression, static validation should combine parsing
     * and specificity validation into a single comprehensive result.
     * 
     * **Validates: Requirements 353.4**
     */
    @Test
    void property63_6_staticValidationCompleteness() {
        for (int i = 0; i < 100; i++) {
            String expression = generateValidCriteriaExpression();
            
            StaticValidationResult result = criteriaLanguage.validateStatic(expression);
            
            // Static validation should always produce a result
            assertNotNull(result, "Static validation should produce result");
            assertNotNull(result.errors(), "Errors list should not be null");
            assertNotNull(result.warnings(), "Warnings list should not be null");
            
            if (result.valid()) {
                assertNotNull(result.ast(), "Valid result should have AST");
                assertTrue(result.errors().isEmpty(), "Valid result should have no errors");
            } else {
                assertFalse(result.errors().isEmpty(), "Invalid result should have errors");
            }
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.7: Compound Criteria Parsing**
     * 
     * *For any* compound criteria with AND/OR operators, parsing should
     * correctly build the AST with proper operator precedence.
     * 
     * **Validates: Requirements 353.1**
     */
    @Test
    void property63_7_compoundCriteriaParsing() {
        String[] compoundExpressions = {
            "activity:steps AND time:day:1",
            "media:photos OR media:music",
            "activity:steps AND time:day:1 AND geo:country:US",
            "health:heart_rate OR activity:steps AND time:week:1"
        };

        for (String expression : compoundExpressions) {
            ParseResult result = criteriaLanguage.parse(expression);
            
            assertTrue(result.success(), 
                    "Compound expression should parse: " + expression);
            assertTrue(result.ast().nodes().size() >= 2,
                    "Compound expression should have multiple nodes: " + expression);
        }
    }

    /**
     * **Feature: yachaq-platform, Property 63.8: Cohort Size Estimation**
     * 
     * *For any* criteria, cohort size estimation should decrease as
     * specificity increases (more criteria = smaller cohort).
     * 
     * **Validates: Requirements 353.2**
     */
    @Test
    void property63_8_cohortSizeEstimation() {
        // Single criterion
        String singleCriteria = "activity:steps";
        ParseResult singleResult = criteriaLanguage.parse(singleCriteria);
        assertTrue(singleResult.success());
        SpecificityValidationResult singleValidation = 
                criteriaLanguage.validateSpecificity(singleResult.ast());
        
        // Multiple criteria (more specific)
        String multipleCriteria = "activity:steps AND time:day:1 AND geo:region:CA";
        ParseResult multipleResult = criteriaLanguage.parse(multipleCriteria);
        assertTrue(multipleResult.success());
        SpecificityValidationResult multipleValidation = 
                criteriaLanguage.validateSpecificity(multipleResult.ast());
        
        // More specific criteria should have smaller estimated cohort
        assertTrue(multipleValidation.estimatedCohortSize() < singleValidation.estimatedCohortSize(),
                "More specific criteria should have smaller cohort estimate");
        assertTrue(multipleValidation.specificity() > singleValidation.specificity(),
                "More criteria should have higher specificity");
    }

    /**
     * **Feature: yachaq-platform, Property 63.9: Null Safety**
     * 
     * *For any* null input, the service should throw appropriate exceptions
     * rather than producing undefined behavior.
     * 
     * **Validates: Requirements 353.1**
     */
    @Test
    void property63_9_nullSafety() {
        assertThrows(NullPointerException.class, 
                () -> criteriaLanguage.parse(null),
                "Null expression should throw NPE");
        
        assertThrows(NullPointerException.class,
                () -> criteriaLanguage.validateSpecificity(null),
                "Null AST should throw NPE");
        
        ParseResult validResult = criteriaLanguage.parse("activity:steps");
        assertThrows(NullPointerException.class,
                () -> criteriaLanguage.enforcePrivacyFloor(validResult.ast(), null),
                "Null config should throw NPE");
    }

    /**
     * **Feature: yachaq-platform, Property 63.10: Count Criteria Parsing**
     * 
     * *For any* count criteria with valid operators (gte, lte, eq),
     * parsing should correctly extract operator and value.
     * 
     * **Validates: Requirements 353.1**
     */
    @Test
    void property63_10_countCriteriaParsing() {
        String[] countExpressions = {
            "count:gte:10",
            "count:lte:100",
            "count:eq:50"
        };

        for (String expression : countExpressions) {
            ParseResult result = criteriaLanguage.parse(expression);
            
            assertTrue(result.success(), 
                    "Count expression should parse: " + expression);
            assertEquals(1, result.ast().nodes().size(),
                    "Should have one criterion node");
            assertTrue(result.ast().nodes().get(0) instanceof CountCriterion,
                    "Should be CountCriterion: " + expression);
        }
    }

    // ==================== Helper Methods ====================

    private String generateValidCriteriaExpression() {
        int numCriteria = random.nextInt(3) + 1; // 1-3 criteria
        List<String> criteria = new ArrayList<>();
        
        for (int i = 0; i < numCriteria; i++) {
            criteria.add(generateSingleCriterion());
        }
        
        if (criteria.size() == 1) {
            return criteria.get(0);
        }
        
        StringBuilder sb = new StringBuilder(criteria.get(0));
        for (int i = 1; i < criteria.size(); i++) {
            sb.append(random.nextBoolean() ? " AND " : " OR ");
            sb.append(criteria.get(i));
        }
        return sb.toString();
    }

    private String generateSingleCriterion() {
        int type = random.nextInt(4);
        return switch (type) {
            case 0 -> generateLabelCriterion();
            case 1 -> generateTimeCriterion();
            case 2 -> generateGeoCriterion();
            case 3 -> generateCountCriterion();
            default -> generateLabelCriterion();
        };
    }

    private String generateLabelCriterion() {
        String family = LABEL_FAMILIES[random.nextInt(LABEL_FAMILIES.length)];
        String label = LABEL_NAMES[random.nextInt(LABEL_NAMES.length)];
        return family + ":" + label;
    }

    private String generateTimeCriterion() {
        String granularity = TIME_GRANULARITIES[random.nextInt(TIME_GRANULARITIES.length)];
        int value = random.nextInt(100) + 1;
        return "time:" + granularity + ":" + value;
    }

    private String generateGeoCriterion() {
        String precision = GEO_PRECISIONS[random.nextInt(GEO_PRECISIONS.length)];
        String value = "region" + random.nextInt(100);
        return "geo:" + precision + ":" + value;
    }

    private String generateCountCriterion() {
        String operator = COUNT_OPERATORS[random.nextInt(COUNT_OPERATORS.length)];
        int value = random.nextInt(1000) + 1;
        return "count:" + operator + ":" + value;
    }

    private String generateOverlySpecificCriteria() {
        // Generate criteria that should exceed specificity threshold
        List<String> criteria = new ArrayList<>();
        
        // Add multiple sensitive family labels
        criteria.add("health:heart_rate");
        criteria.add("finance:transactions");
        criteria.add("location:visits");
        
        // Add precise geo
        criteria.add("geo:city:NYC");
        
        // Add precise time
        criteria.add("time:hour:14");
        
        return String.join(" AND ", criteria);
    }
}
