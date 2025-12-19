package com.yachaq.api.criteria;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ODX Criteria Language Service for parsing and validating ODX criteria expressions.
 * Provides criteria parsing, specificity validation, and privacy floor enforcement.
 * 
 * Security: Restricts overly specific criteria to prevent targeting.
 * Performance: Criteria parsing is O(n) where n is expression length.
 * 
 * Validates: Requirements 353.1, 353.2, 353.3, 353.4
 */
@Service
public class ODXCriteriaLanguage {

    // Grammar patterns for ODX criteria
    private static final Pattern LABEL_PATTERN = Pattern.compile("^([a-z]+):([a-z_]+)$");
    private static final Pattern TIME_BUCKET_PATTERN = Pattern.compile("^time:(hour|day|week|month|year):(\\d+)$");
    private static final Pattern GEO_BUCKET_PATTERN = Pattern.compile("^geo:(country|region|city):(\\w+)$");
    private static final Pattern COUNT_PATTERN = Pattern.compile("^count:(gte|lte|eq):(\\d+)$");

    // Privacy floor thresholds
    private static final int MIN_COHORT_SIZE = 50;
    private static final int MAX_LABEL_SPECIFICITY = 5;
    private static final Set<String> SENSITIVE_FAMILIES = Set.of("health", "finance", "location", "communication");

    // ==================== Task 99.1: Criteria Parser ====================

    /**
     * Parses an ODX criteria expression.
     * Requirement 353.1: Parse ODX criteria expressions.
     */
    public ParseResult parse(String expression) {
        Objects.requireNonNull(expression, "Expression cannot be null");

        if (expression.isBlank()) {
            return ParseResult.error("Empty expression");
        }

        List<CriterionNode> nodes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Split by AND/OR operators
        String[] parts = expression.split("\\s+(AND|OR)\\s+");
        List<String> operators = extractOperators(expression);

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            CriterionNode node = parseCriterion(part);
            
            if (node == null) {
                errors.add("Invalid criterion: " + part);
            } else {
                nodes.add(node);
            }
        }

        if (!errors.isEmpty()) {
            return ParseResult.error(errors);
        }

        CriteriaAST ast = buildAST(nodes, operators);
        return ParseResult.success(ast, warnings);
    }

    /**
     * Parses a single criterion.
     */
    private CriterionNode parseCriterion(String criterion) {
        criterion = criterion.trim();
        
        // Remove parentheses if present
        if (criterion.startsWith("(") && criterion.endsWith(")")) {
            criterion = criterion.substring(1, criterion.length() - 1).trim();
        }

        // Try label pattern
        Matcher labelMatcher = LABEL_PATTERN.matcher(criterion);
        if (labelMatcher.matches()) {
            return new LabelCriterion(labelMatcher.group(1), labelMatcher.group(2));
        }

        // Try time bucket pattern
        Matcher timeMatcher = TIME_BUCKET_PATTERN.matcher(criterion);
        if (timeMatcher.matches()) {
            return new TimeBucketCriterion(timeMatcher.group(1), Integer.parseInt(timeMatcher.group(2)));
        }

        // Try geo bucket pattern
        Matcher geoMatcher = GEO_BUCKET_PATTERN.matcher(criterion);
        if (geoMatcher.matches()) {
            return new GeoBucketCriterion(geoMatcher.group(1), geoMatcher.group(2));
        }

        // Try count pattern
        Matcher countMatcher = COUNT_PATTERN.matcher(criterion);
        if (countMatcher.matches()) {
            return new CountCriterion(countMatcher.group(1), Integer.parseInt(countMatcher.group(2)));
        }

        return null;
    }

    private List<String> extractOperators(String expression) {
        List<String> operators = new ArrayList<>();
        Pattern opPattern = Pattern.compile("\\s+(AND|OR)\\s+");
        Matcher matcher = opPattern.matcher(expression);
        while (matcher.find()) {
            operators.add(matcher.group(1));
        }
        return operators;
    }

    private CriteriaAST buildAST(List<CriterionNode> nodes, List<String> operators) {
        if (nodes.isEmpty()) {
            return new CriteriaAST(null, List.of(), 0);
        }

        // Build tree from nodes and operators
        CriterionNode root = nodes.get(0);
        for (int i = 0; i < operators.size() && i + 1 < nodes.size(); i++) {
            String op = operators.get(i);
            CriterionNode right = nodes.get(i + 1);
            root = new BinaryOperator(op, root, right);
        }

        return new CriteriaAST(root, nodes, calculateSpecificity(nodes));
    }

    private int calculateSpecificity(List<CriterionNode> nodes) {
        int specificity = 0;
        for (CriterionNode node : nodes) {
            if (node instanceof LabelCriterion lc) {
                specificity += SENSITIVE_FAMILIES.contains(lc.family()) ? 2 : 1;
            } else if (node instanceof GeoBucketCriterion gc) {
                specificity += gc.precision().equals("city") ? 3 : 1;
            } else if (node instanceof TimeBucketCriterion tc) {
                specificity += tc.granularity().equals("hour") ? 2 : 1;
            }
        }
        return specificity;
    }


    // ==================== Task 99.2: Specificity Validation ====================

    /**
     * Validates criteria specificity.
     * Requirement 353.2: Restrict overly specific criteria.
     */
    public SpecificityValidationResult validateSpecificity(CriteriaAST ast) {
        Objects.requireNonNull(ast, "AST cannot be null");

        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check overall specificity
        if (ast.specificity() > MAX_LABEL_SPECIFICITY) {
            violations.add("Criteria too specific: specificity " + ast.specificity() + 
                          " exceeds maximum " + MAX_LABEL_SPECIFICITY);
        }

        // Check individual criteria
        for (CriterionNode node : ast.nodes()) {
            if (node instanceof GeoBucketCriterion gc) {
                if (gc.precision().equals("city")) {
                    warnings.add("City-level geo precision may reduce cohort size significantly");
                }
            }
            if (node instanceof TimeBucketCriterion tc) {
                if (tc.granularity().equals("hour")) {
                    warnings.add("Hour-level time precision may reduce cohort size significantly");
                }
            }
            if (node instanceof LabelCriterion lc) {
                if (SENSITIVE_FAMILIES.contains(lc.family())) {
                    warnings.add("Sensitive label family '" + lc.family() + "' requires additional justification");
                }
            }
        }

        // Estimate cohort size
        int estimatedCohort = estimateCohortSize(ast);
        if (estimatedCohort < MIN_COHORT_SIZE) {
            violations.add("Estimated cohort size (" + estimatedCohort + 
                          ") below minimum threshold (" + MIN_COHORT_SIZE + ")");
        }

        return new SpecificityValidationResult(
                violations.isEmpty(),
                ast.specificity(),
                estimatedCohort,
                violations,
                warnings
        );
    }

    private int estimateCohortSize(CriteriaAST ast) {
        int baseSize = 10000;
        for (CriterionNode node : ast.nodes()) {
            if (node instanceof LabelCriterion lc) {
                baseSize = (int) (baseSize * (SENSITIVE_FAMILIES.contains(lc.family()) ? 0.3 : 0.7));
            } else if (node instanceof GeoBucketCriterion gc) {
                baseSize = (int) (baseSize * (gc.precision().equals("city") ? 0.1 : 0.5));
            } else if (node instanceof TimeBucketCriterion tc) {
                baseSize = (int) (baseSize * (tc.granularity().equals("hour") ? 0.2 : 0.8));
            }
        }
        return Math.max(baseSize, 1);
    }

    // ==================== Task 99.3: Privacy Floor Enforcement ====================

    /**
     * Enforces privacy floors on criteria.
     * Requirement 353.3: Include privacy floors in criteria.
     */
    public PrivacyFloorResult enforcePrivacyFloor(CriteriaAST ast, PrivacyFloorConfig config) {
        Objects.requireNonNull(ast, "AST cannot be null");
        Objects.requireNonNull(config, "Config cannot be null");

        List<CriterionNode> adjustedNodes = new ArrayList<>();
        List<String> adjustments = new ArrayList<>();

        for (CriterionNode node : ast.nodes()) {
            CriterionNode adjusted = applyPrivacyFloor(node, config);
            if (!adjusted.equals(node)) {
                adjustments.add("Adjusted " + node + " to " + adjusted);
            }
            adjustedNodes.add(adjusted);
        }

        CriteriaAST adjustedAST = new CriteriaAST(
                ast.root(),
                adjustedNodes,
                calculateSpecificity(adjustedNodes)
        );

        return new PrivacyFloorResult(
                adjustedAST,
                !adjustments.isEmpty(),
                adjustments,
                config.minimumCohortSize()
        );
    }

    private CriterionNode applyPrivacyFloor(CriterionNode node, PrivacyFloorConfig config) {
        if (node instanceof GeoBucketCriterion gc) {
            // Coarsen geo precision if needed
            if (gc.precision().equals("city") && config.minGeoPrecision().equals("region")) {
                return new GeoBucketCriterion("region", gc.value());
            }
        }
        if (node instanceof TimeBucketCriterion tc) {
            // Coarsen time precision if needed
            if (tc.granularity().equals("hour") && config.minTimeGranularity().equals("day")) {
                return new TimeBucketCriterion("day", tc.value());
            }
        }
        return node;
    }

    // ==================== Task 99.4: Static Validation ====================

    /**
     * Performs static validation on criteria.
     * Requirement 353.4: Make criteria statically checkable.
     */
    public StaticValidationResult validateStatic(String expression) {
        ParseResult parseResult = parse(expression);
        if (!parseResult.success()) {
            return new StaticValidationResult(false, null, parseResult.errors(), List.of());
        }

        SpecificityValidationResult specificityResult = validateSpecificity(parseResult.ast());
        
        List<String> allErrors = new ArrayList<>(specificityResult.violations());
        List<String> allWarnings = new ArrayList<>(parseResult.warnings());
        allWarnings.addAll(specificityResult.warnings());

        return new StaticValidationResult(
                allErrors.isEmpty(),
                parseResult.ast(),
                allErrors,
                allWarnings
        );
    }


    // ==================== Inner Types ====================

    public sealed interface CriterionNode permits LabelCriterion, TimeBucketCriterion, 
            GeoBucketCriterion, CountCriterion, BinaryOperator {}

    public record LabelCriterion(String family, String label) implements CriterionNode {}
    public record TimeBucketCriterion(String granularity, int value) implements CriterionNode {}
    public record GeoBucketCriterion(String precision, String value) implements CriterionNode {}
    public record CountCriterion(String operator, int value) implements CriterionNode {}
    public record BinaryOperator(String op, CriterionNode left, CriterionNode right) implements CriterionNode {}

    public record CriteriaAST(CriterionNode root, List<CriterionNode> nodes, int specificity) {}

    public record ParseResult(boolean success, CriteriaAST ast, List<String> errors, List<String> warnings) {
        public static ParseResult success(CriteriaAST ast, List<String> warnings) {
            return new ParseResult(true, ast, List.of(), warnings);
        }
        public static ParseResult error(String error) {
            return new ParseResult(false, null, List.of(error), List.of());
        }
        public static ParseResult error(List<String> errors) {
            return new ParseResult(false, null, errors, List.of());
        }
    }

    public record SpecificityValidationResult(
            boolean valid,
            int specificity,
            int estimatedCohortSize,
            List<String> violations,
            List<String> warnings
    ) {}

    public record PrivacyFloorConfig(
            int minimumCohortSize,
            String minGeoPrecision,
            String minTimeGranularity
    ) {
        public static PrivacyFloorConfig defaults() {
            return new PrivacyFloorConfig(50, "region", "day");
        }
    }

    public record PrivacyFloorResult(
            CriteriaAST adjustedAST,
            boolean wasAdjusted,
            List<String> adjustments,
            int minimumCohortSize
    ) {}

    public record StaticValidationResult(
            boolean valid,
            CriteriaAST ast,
            List<String> errors,
            List<String> warnings
    ) {}
}
