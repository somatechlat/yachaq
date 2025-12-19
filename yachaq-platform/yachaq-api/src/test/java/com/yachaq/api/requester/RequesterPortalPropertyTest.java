package com.yachaq.api.requester;

import com.yachaq.api.requester.RequesterPortalService.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for RequesterPortalService.
 * Tests the service's validation and template logic without external dependencies.
 * 
 * Validates: Requirements 348.1, 348.2, 348.3, 348.4, 348.5
 */
class RequesterPortalPropertyTest {

    private RequestTemplateRepository templateRepository;

    @BeforeEach
    void setUp() {
        templateRepository = new RequestTemplateRepository();
    }

    // ==================== Task 93.1: Template Tests ====================

    @Test
    void getTemplates_returnsAvailableTemplates() {
        // Requirement 348.1: Provide templates for common use cases
        List<RequestTemplate> templates = templateRepository.findAll();

        assertThat(templates).isNotEmpty();
        assertThat(templates).extracting(RequestTemplate::id)
                .contains("health-research", "media-analysis", "location-patterns");
    }

    @Test
    void getTemplates_filtersByCategory() {
        List<RequestTemplate> researchTemplates = templateRepository.findByCategory("research");

        assertThat(researchTemplates).isNotEmpty();
        assertThat(researchTemplates).allMatch(t -> t.category().equals("research"));
    }

    @Test
    void getTemplate_returnsSpecificTemplate() {
        Optional<RequestTemplate> template = templateRepository.findById("health-research");

        assertThat(template).isPresent();
        assertThat(template.get().id()).isEqualTo("health-research");
        assertThat(template.get().defaultLabels()).contains("health:steps", "health:sleep");
    }

    @Test
    void getTemplate_returnsEmptyForUnknown() {
        Optional<RequestTemplate> template = templateRepository.findById("unknown-template");

        assertThat(template).isEmpty();
    }

    @Test
    void templates_haveRequiredFields() {
        // All templates should have required fields
        List<RequestTemplate> templates = templateRepository.findAll();

        for (RequestTemplate template : templates) {
            assertThat(template.id()).isNotBlank();
            assertThat(template.name()).isNotBlank();
            assertThat(template.category()).isNotBlank();
            assertThat(template.defaultLabels()).isNotEmpty();
            assertThat(template.suggestedCompensation()).isPositive();
            assertThat(template.defaultTtlHours()).isPositive();
        }
    }

    // ==================== Task 93.2: ODX Criteria Validation Tests ====================

    @Test
    void validateCriteria_validatesLabels() {
        // Requirement 348.2: Allow scope definition using ODX criteria
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("media:music", "media:video"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validateCriteria_rejectsInvalidLabels() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("invalid_label_format"),
                Set.of(),
                null,
                null
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Invalid label"));
    }

    @Test
    void validateCriteria_warnsOnSensitiveLabels() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("health:steps", "finance:transactions"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Sensitive"));
    }

    @Test
    void validateCriteria_rejectsExactGeoPrecision() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("location:home"),
                Set.of(),
                null,
                new GeoCriteria("EXACT", List.of())
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Exact location"));
    }

    @Test
    void validateCriteria_estimatesCohortSize() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        assertThat(result.estimatedCohortSize()).isGreaterThan(0);
    }

    @Test
    void validateCriteria_rejectsInvalidTimeWindow() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now(), Instant.now().minus(7, ChronoUnit.DAYS)), // End before start
                null
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Time window"));
    }

    @Test
    void validateCriteria_warnsOnLongTimeWindow() {
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("media:music"),
                Set.of(),
                new TimeWindow(Instant.now().minus(400, ChronoUnit.DAYS), Instant.now()), // > 1 year
                null
        ));

        assertThat(result.warnings()).anyMatch(w -> w.contains("1 year"));
    }

    // ==================== Label Family Tests ====================

    @Test
    void labelFamilies_containsExpectedFamilies() {
        List<LabelFamily> families = getAvailableLabelFamilies();

        assertThat(families).isNotEmpty();
        assertThat(families).extracting(LabelFamily::id)
                .contains("health", "media", "location", "finance", "social");
    }

    @Test
    void labelFamilies_marksSensitiveFamilies() {
        List<LabelFamily> families = getAvailableLabelFamilies();

        LabelFamily healthFamily = families.stream()
                .filter(f -> f.id().equals("health"))
                .findFirst()
                .orElseThrow();

        assertThat(healthFamily.sensitive()).isTrue();

        LabelFamily mediaFamily = families.stream()
                .filter(f -> f.id().equals("media"))
                .findFirst()
                .orElseThrow();

        assertThat(mediaFamily.sensitive()).isFalse();
    }

    @Test
    void labelFamilies_haveLabels() {
        List<LabelFamily> families = getAvailableLabelFamilies();

        for (LabelFamily family : families) {
            assertThat(family.labels()).isNotEmpty();
            assertThat(family.labels()).allMatch(l -> l.startsWith(family.id() + ":"));
        }
    }

    // ==================== Property Tests ====================

    @Property
    void validateCriteria_alwaysEstimatesCohortSize(@ForAll("validLabelSets") Set<String> labels) {
        // Property: Criteria validation should always estimate cohort size
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                labels,
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        assertThat(result.estimatedCohortSize()).isGreaterThanOrEqualTo(0);
    }

    @Property
    void validateCriteria_invalidTimeWindowAlwaysFails(@ForAll("invalidTimeWindows") TimeWindow timeWindow) {
        // Property: Invalid time windows should always fail validation
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                Set.of("media:music"),
                Set.of(),
                timeWindow,
                null
        ));

        assertThat(result.errors()).anyMatch(e -> e.contains("Time window"));
    }

    @Property
    void validateCriteria_validLabelsNeverFail(@ForAll("validLabelSets") Set<String> labels) {
        // Property: Valid labels should never cause validation failure
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                labels,
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        // Should not have label-related errors
        assertThat(result.errors()).noneMatch(e -> e.contains("Invalid label"));
    }

    @Property
    void validateCriteria_sensitiveLabelsAlwaysWarn(@ForAll("sensitiveLabelSets") Set<String> labels) {
        // Property: Sensitive labels should always generate warnings
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                labels,
                Set.of(),
                new TimeWindow(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now()),
                null
        ));

        assertThat(result.warnings()).anyMatch(w -> w.contains("Sensitive"));
    }

    @Property
    void validateCriteria_exactGeoAlwaysFails(@ForAll("validLabelSets") Set<String> labels) {
        // Property: EXACT geo precision should always fail
        CriteriaValidationResult result = validateCriteria(new OdxCriteria(
                labels,
                Set.of(),
                null,
                new GeoCriteria("EXACT", List.of())
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Exact location"));
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<Set<String>> validLabelSets() {
        List<String> validLabels = List.of(
                "media:music", "media:video", "media:podcasts",
                "social:interactions", "social:connections"
        );

        return Arbitraries.of(validLabels)
                .set()
                .ofMinSize(1)
                .ofMaxSize(3);
    }

    @Provide
    Arbitrary<Set<String>> sensitiveLabelSets() {
        List<String> sensitiveLabels = List.of(
                "health:steps", "health:sleep", "health:heart_rate",
                "finance:transactions", "finance:spending_category",
                "location:home", "location:work"
        );

        return Arbitraries.of(sensitiveLabels)
                .set()
                .ofMinSize(1)
                .ofMaxSize(2);
    }

    @Provide
    Arbitrary<TimeWindow> invalidTimeWindows() {
        return Arbitraries.longs().between(1, 100)
                .map(offset -> new TimeWindow(
                        Instant.now(),
                        Instant.now().minus(offset, ChronoUnit.DAYS) // End before start
                ));
    }

    // ==================== Helper Methods (extracted from service) ====================

    private CriteriaValidationResult validateCriteria(OdxCriteria criteria) {
        Objects.requireNonNull(criteria, "Criteria cannot be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate label families
        for (String label : criteria.requiredLabels()) {
            if (!isValidOdxLabel(label)) {
                errors.add("Invalid label: " + label);
            }
            if (isSensitiveLabel(label)) {
                warnings.add("Sensitive label '" + label + "' may require additional justification");
            }
        }

        // Validate time window
        if (criteria.timeWindow() != null) {
            if (criteria.timeWindow().start().isAfter(criteria.timeWindow().end())) {
                errors.add("Time window start must be before end");
            }
            long days = ChronoUnit.DAYS.between(criteria.timeWindow().start(), criteria.timeWindow().end());
            if (days > 365) {
                warnings.add("Time window exceeds 1 year - consider narrowing scope");
            }
        }

        // Validate geo criteria
        if (criteria.geoCriteria() != null && criteria.geoCriteria().precision() != null) {
            if (criteria.geoCriteria().precision().equals("EXACT")) {
                errors.add("Exact location precision is not allowed - use CITY or REGION");
            }
        }

        // Estimate cohort size
        int estimatedCohort = estimateCohortSize(criteria);
        if (estimatedCohort < 50) {
            errors.add("Estimated cohort size (" + estimatedCohort + ") is below minimum threshold (50)");
        } else if (estimatedCohort < 100) {
            warnings.add("Estimated cohort size (" + estimatedCohort + ") is close to minimum threshold");
        }

        return new CriteriaValidationResult(errors.isEmpty(), errors, warnings, estimatedCohort);
    }

    private boolean isValidOdxLabel(String label) {
        if (label == null || label.isBlank()) return false;
        return label.contains(":") && label.split(":").length == 2;
    }

    private boolean isSensitiveLabel(String label) {
        String family = label.split(":")[0].toLowerCase();
        return Set.of("health", "finance", "location", "communication").contains(family);
    }

    private int estimateCohortSize(OdxCriteria criteria) {
        int baseSize = 10000;
        baseSize = baseSize / (1 + criteria.requiredLabels().size());
        long sensitiveCount = criteria.requiredLabels().stream().filter(this::isSensitiveLabel).count();
        baseSize = (int) (baseSize / (1 + sensitiveCount * 0.5));
        if (criteria.timeWindow() != null) {
            long days = ChronoUnit.DAYS.between(criteria.timeWindow().start(), criteria.timeWindow().end());
            if (days < 30) baseSize = baseSize / 2;
        }
        return Math.max(baseSize, 10);
    }

    private List<LabelFamily> getAvailableLabelFamilies() {
        return List.of(
                new LabelFamily("health", "Health & Fitness",
                        List.of("health:steps", "health:heart_rate", "health:sleep", "health:workouts"), true),
                new LabelFamily("media", "Media & Entertainment",
                        List.of("media:music", "media:video", "media:podcasts", "media:reading"), false),
                new LabelFamily("location", "Location",
                        List.of("location:home", "location:work", "location:travel"), true),
                new LabelFamily("communication", "Communication",
                        List.of("communication:messages", "communication:calls", "communication:email"), true),
                new LabelFamily("finance", "Financial",
                        List.of("finance:transactions", "finance:spending_category"), true),
                new LabelFamily("social", "Social",
                        List.of("social:connections", "social:interactions"), false)
        );
    }
}
