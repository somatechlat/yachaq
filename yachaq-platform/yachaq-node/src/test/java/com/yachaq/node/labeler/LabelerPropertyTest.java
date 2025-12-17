package com.yachaq.node.labeler;

import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.normalizer.CanonicalEvent.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Label Engine.
 * Requirement 310.6: Test ontology regression and migration correctness.
 * 
 * **Feature: yachaq-platform, Property: Label Engine Ontology**
 * **Validates: Requirements 310.1, 310.3, 310.4, 310.5, 310.6**
 */
class LabelerPropertyTest {

    private final Labeler labeler = new Labeler();
    private final LabelOntology ontology = new LabelOntology();

    // ==================== Ontology Regression Tests (54.4) ====================

    /**
     * Property: All generated labels are valid according to the ontology.
     * **Feature: yachaq-platform, Property: Label Ontology Validity**
     * **Validates: Requirements 310.4**
     */
    @Property(tries = 100)
    void allGeneratedLabels_areValidInOntology(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        
        for (Label label : labelSet.labels()) {
            assertThat(ontology.isValidLabel(label))
                    .as("Label %s should be valid in ontology", label.toKey())
                    .isTrue();
        }
    }

    /**
     * Property: Labels always have required namespaces.
     * **Feature: yachaq-platform, Property: Required Namespaces**
     * **Validates: Requirements 310.4**
     */
    @Property(tries = 100)
    void labels_alwaysHaveRequiredNamespaces(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        
        // Every event should have domain, time, quality, and privacy labels
        assertThat(labelSet.byNamespace(LabelNamespace.DOMAIN)).isNotEmpty();
        assertThat(labelSet.byNamespace(LabelNamespace.TIME)).isNotEmpty();
        assertThat(labelSet.byNamespace(LabelNamespace.QUALITY)).isNotEmpty();
        assertThat(labelSet.byNamespace(LabelNamespace.PRIVACY)).isNotEmpty();
    }

    /**
     * Property: Time labels are consistent with timestamp.
     * **Feature: yachaq-platform, Property: Time Label Consistency**
     * **Validates: Requirements 310.1**
     */
    @Property(tries = 100)
    void timeLabels_areConsistentWithTimestamp(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ZonedDateTime zdt = event.timestamp().atZone(ZoneId.of("UTC"));
        int hour = zdt.getHour();
        int dayOfWeek = zdt.getDayOfWeek().getValue();

        // Check time period consistency
        Set<Label> periodLabels = labelSet.byCategory(LabelNamespace.TIME, "period");
        assertThat(periodLabels).isNotEmpty();
        
        Label periodLabel = periodLabels.iterator().next();
        String expectedPeriod = getExpectedPeriod(hour);
        assertThat(periodLabel.value()).isEqualTo(expectedPeriod);

        // Check day type consistency
        Set<Label> dayTypeLabels = labelSet.byCategory(LabelNamespace.TIME, "daytype");
        assertThat(dayTypeLabels).isNotEmpty();
        
        Label dayTypeLabel = dayTypeLabels.iterator().next();
        String expectedDayType = (dayOfWeek >= 6) ? "weekend" : "weekday";
        assertThat(dayTypeLabel.value()).isEqualTo(expectedDayType);
    }

    private String getExpectedPeriod(int hour) {
        if (hour >= 5 && hour < 8) return "early_morning";
        if (hour >= 8 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        return "night";
    }

    /**
     * Property: Quality labels reflect source type.
     * **Feature: yachaq-platform, Property: Quality Label Accuracy**
     * **Validates: Requirements 310.1**
     */
    @Property(tries = 100)
    void qualityLabels_reflectSourceType(
            @ForAll("connectorEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        
        // Connector events should have verified quality
        Set<Label> verificationLabels = labelSet.byCategory(LabelNamespace.QUALITY, "verification");
        assertThat(verificationLabels).isNotEmpty();
        
        Label verificationLabel = verificationLabels.iterator().next();
        assertThat(verificationLabel.value()).isEqualTo("verified");
    }

    /**
     * Property: Privacy labels are assigned based on category.
     * **Feature: yachaq-platform, Property: Privacy Label Assignment**
     * **Validates: Requirements 310.1**
     */
    @Property(tries = 50)
    void privacyLabels_areAssignedBasedOnCategory(
            @ForAll("healthEvents") CanonicalEvent healthEvent,
            @ForAll("mediaEvents") CanonicalEvent mediaEvent) {
        
        LabelSet healthLabels = labeler.label(healthEvent);
        LabelSet mediaLabels = labeler.label(mediaEvent);
        
        // Health events should have high sensitivity
        Set<Label> healthSensitivity = healthLabels.byCategory(LabelNamespace.PRIVACY, "sensitivity");
        assertThat(healthSensitivity).isNotEmpty();
        assertThat(healthSensitivity.iterator().next().value()).isEqualTo("high");
        
        // Media events should have low sensitivity
        Set<Label> mediaSensitivity = mediaLabels.byCategory(LabelNamespace.PRIVACY, "sensitivity");
        assertThat(mediaSensitivity).isNotEmpty();
        assertThat(mediaSensitivity.iterator().next().value()).isEqualTo("low");
    }

    /**
     * Property: Labels are deterministic - same input produces same output.
     * **Feature: yachaq-platform, Property: Label Determinism**
     * **Validates: Requirements 310.1**
     */
    @Property(tries = 100)
    void labeling_isDeterministic(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet1 = labeler.label(event);
        LabelSet labelSet2 = labeler.label(event);
        
        assertThat(labelSet1.getLabelKeys()).isEqualTo(labelSet2.getLabelKeys());
    }

    /**
     * Property: All labels have rule IDs for explainability.
     * **Feature: yachaq-platform, Property: Label Explainability**
     * **Validates: Requirements 310.1**
     */
    @Property(tries = 100)
    void allLabels_haveRuleIds(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        
        for (Label label : labelSet.labels()) {
            assertThat(label.ruleId())
                    .as("Label %s should have a rule ID", label.toKey())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    /**
     * Property: Label keys follow namespace:category:value format.
     * **Feature: yachaq-platform, Property: Label Key Format**
     * **Validates: Requirements 310.4**
     */
    @Property(tries = 100)
    void labelKeys_followCorrectFormat(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        
        for (String key : labelSet.getLabelKeys()) {
            String[] parts = key.split(":");
            assertThat(parts).hasSize(3);
            assertThat(parts[0]).isNotBlank(); // namespace
            assertThat(parts[1]).isNotBlank(); // category
            assertThat(parts[2]).isNotBlank(); // value
        }
    }

    // ==================== Migration Tests (54.3) ====================

    /**
     * Property: Migration preserves label count (no data loss).
     * **Feature: yachaq-platform, Property: Migration Preservation**
     * **Validates: Requirements 310.3, 310.5**
     */
    @Property(tries = 50)
    void migration_preservesLabelCount(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet original = labeler.label(event);
        
        // Migrate to same version should be identity
        LabelSet migrated = labeler.migrate(original, LabelOntology.CURRENT_VERSION);
        
        assertThat(migrated.size()).isEqualTo(original.size());
        assertThat(migrated.getLabelKeys()).isEqualTo(original.getLabelKeys());
    }

    /**
     * Property: Migration updates ontology version.
     * **Feature: yachaq-platform, Property: Migration Version Update**
     * **Validates: Requirements 310.3**
     */
    @Property(tries = 50)
    void migration_updatesOntologyVersion(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet original = labeler.label(event);
        String targetVersion = "1.0.0";
        
        LabelSet migrated = labeler.migrate(original, targetVersion);
        
        assertThat(migrated.ontologyVersion()).isEqualTo(targetVersion);
    }

    // ==================== Namespace Coverage Tests ====================

    /**
     * Property: Domain namespace has valid categories.
     */
    @Test
    void domainNamespace_hasValidCategories() {
        Set<String> categories = ontology.getCategories(LabelNamespace.DOMAIN);
        
        assertThat(categories).contains(
                "activity", "content", "communication", "health",
                "location", "media", "social", "transaction", "travel"
        );
    }

    /**
     * Property: Time namespace has valid categories.
     */
    @Test
    void timeNamespace_hasValidCategories() {
        Set<String> categories = ontology.getCategories(LabelNamespace.TIME);
        
        assertThat(categories).contains("period", "daytype", "season", "frequency");
    }

    /**
     * Property: Privacy namespace has valid categories.
     */
    @Test
    void privacyNamespace_hasValidCategories() {
        Set<String> categories = ontology.getCategories(LabelNamespace.PRIVACY);
        
        assertThat(categories).contains("sensitivity", "pii", "floor", "risk");
    }

    /**
     * Property: Time period values are valid.
     */
    @Test
    void timePeriod_hasValidValues() {
        Set<String> values = ontology.getValues(LabelNamespace.TIME, "period");
        
        assertThat(values).contains(
                "early_morning", "morning", "afternoon", "evening", "night"
        );
    }

    /**
     * Property: Privacy sensitivity values are valid.
     */
    @Test
    void privacySensitivity_hasValidValues() {
        Set<String> values = ontology.getValues(LabelNamespace.PRIVACY, "sensitivity");
        
        assertThat(values).contains("low", "medium", "high", "critical");
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<CanonicalEvent> randomEvents() {
        return Arbitraries.of(EventCategory.values())
                .flatMap(category -> Arbitraries.of("event1", "event2", "event3")
                        .flatMap(eventType -> Arbitraries.longs()
                                .between(0, Instant.now().toEpochMilli())
                                .map(millis -> createEvent(category, eventType, Instant.ofEpochMilli(millis)))));
    }

    @Provide
    Arbitrary<CanonicalEvent> connectorEvents() {
        return Arbitraries.of("healthkit", "spotify", "strava", "google_fit")
                .map(source -> createEventWithSource(source, EventCategory.ACTIVITY));
    }

    @Provide
    Arbitrary<CanonicalEvent> healthEvents() {
        return Arbitraries.of("heart_rate", "steps", "sleep", "workout")
                .map(eventType -> createEvent(EventCategory.HEALTH, eventType, Instant.now()));
    }

    @Provide
    Arbitrary<CanonicalEvent> mediaEvents() {
        return Arbitraries.of("photo", "video", "music", "podcast")
                .map(eventType -> createEvent(EventCategory.MEDIA, eventType, Instant.now()));
    }

    // ==================== Helper Methods ====================

    private CanonicalEvent createEvent(EventCategory category, String eventType, Instant timestamp) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(category)
                .eventType(eventType)
                .timestamp(timestamp)
                .attributes(Map.of("count", 100L))
                .build();
    }

    private CanonicalEvent createEventWithSource(String sourceType, EventCategory category) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType(sourceType)
                .sourceId("source-" + sourceType)
                .category(category)
                .eventType("activity_event")
                .timestamp(Instant.now())
                .attributes(Map.of("count", 1000L))
                .contentHash("sha256:abc123")
                .build();
    }

    // ==================== Unit Tests ====================

    @Test
    void label_handlesNullEvent() {
        assertThatThrownBy(() -> labeler.label(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void labelBatch_handlesEmptyList() {
        List<LabelSet> result = labeler.labelBatch(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void labelBatch_handlesNullList() {
        List<LabelSet> result = labeler.labelBatch(null);
        assertThat(result).isEmpty();
    }

    @Test
    void getOntologyVersion_returnsCurrentVersion() {
        assertThat(labeler.getOntologyVersion()).isEqualTo(LabelOntology.CURRENT_VERSION);
    }

    @Test
    void labelSet_byNamespace_filtersCorrectly() {
        CanonicalEvent event = createEvent(EventCategory.HEALTH, "steps", Instant.now());
        LabelSet labelSet = labeler.label(event);
        
        Set<Label> domainLabels = labelSet.byNamespace(LabelNamespace.DOMAIN);
        
        assertThat(domainLabels).allMatch(l -> l.namespace() == LabelNamespace.DOMAIN);
    }

    @Test
    void labelSet_byCategory_filtersCorrectly() {
        CanonicalEvent event = createEvent(EventCategory.HEALTH, "steps", Instant.now());
        LabelSet labelSet = labeler.label(event);
        
        Set<Label> periodLabels = labelSet.byCategory(LabelNamespace.TIME, "period");
        
        assertThat(periodLabels).allMatch(l -> 
                l.namespace() == LabelNamespace.TIME && l.category().equals("period"));
    }

    @Test
    void labelSet_hasLabel_detectsExistingLabel() {
        CanonicalEvent event = createEvent(EventCategory.HEALTH, "steps", Instant.now());
        LabelSet labelSet = labeler.label(event);
        
        // Health events should have high sensitivity
        assertThat(labelSet.hasLabel(LabelNamespace.PRIVACY, "sensitivity", "high")).isTrue();
    }

    @Test
    void label_withLocation_generatesGeoLabels() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(EventCategory.LOCATION)
                .eventType("location_update")
                .timestamp(Instant.now())
                .location(GeoLocation.city(40.7, -74.0))
                .attributes(Map.of())
                .build();
        
        LabelSet labelSet = labeler.label(event);
        
        assertThat(labelSet.byNamespace(LabelNamespace.GEO)).isNotEmpty();
    }

    @Test
    void label_withoutLocation_noGeoLabels() {
        CanonicalEvent event = createEvent(EventCategory.MEDIA, "photo", Instant.now());
        LabelSet labelSet = labeler.label(event);
        
        assertThat(labelSet.byNamespace(LabelNamespace.GEO)).isEmpty();
    }

    @Test
    void behaviorRule_detectsHighIntensity() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("healthkit")
                .sourceId("healthkit-1")
                .category(EventCategory.HEALTH)
                .eventType("workout")
                .timestamp(Instant.now())
                .attributes(Map.of("heartRate", 160))
                .build();
        
        LabelSet labelSet = labeler.label(event);
        
        assertThat(labelSet.hasLabel(LabelNamespace.BEHAVIOR, "pattern", "high_intensity")).isTrue();
    }

    @Test
    void behaviorRule_detectsLongDuration() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("strava")
                .sourceId("strava-1")
                .category(EventCategory.ACTIVITY)
                .eventType("run")
                .timestamp(Instant.now())
                .duration(CanonicalEvent.Duration.ofHours(2))
                .attributes(Map.of())
                .build();
        
        LabelSet labelSet = labeler.label(event);
        
        assertThat(labelSet.hasLabel(LabelNamespace.BEHAVIOR, "pattern", "long_duration")).isTrue();
    }

    @Test
    void behaviorRule_detectsNightActivity() {
        // Create event at 11 PM
        Instant nightTime = LocalDateTime.of(2024, 6, 15, 23, 0)
                .atZone(ZoneId.of("UTC"))
                .toInstant();
        
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-1")
                .category(EventCategory.ACTIVITY)
                .eventType("activity")
                .timestamp(nightTime)
                .attributes(Map.of())
                .build();
        
        LabelSet labelSet = labeler.label(event);
        
        assertThat(labelSet.hasLabel(LabelNamespace.BEHAVIOR, "pattern", "night_activity")).isTrue();
    }
}
