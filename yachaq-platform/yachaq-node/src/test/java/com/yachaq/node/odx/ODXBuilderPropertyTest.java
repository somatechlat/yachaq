package com.yachaq.node.odx;

import com.yachaq.node.extractor.ExtractedFeatures;
import com.yachaq.node.extractor.ExtractedFeatures.*;
import com.yachaq.node.labeler.*;
import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.normalizer.CanonicalEvent.*;
import com.yachaq.node.odx.ODXEntry.*;
import com.yachaq.node.odx.ODXBuilder.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ODX Builder.
 * Requirement 311.1, 311.2: ODX Safety validation.
 * 
 * **Feature: yachaq-platform, Property: ODX Safety**
 * **Validates: Requirements 311.1, 311.2**
 */
class ODXBuilderPropertyTest {

    private final ODXBuilder builder = new ODXBuilder();
    private final Labeler labeler = new Labeler();
    private final com.yachaq.node.extractor.FeatureExtractor featureExtractor = 
            new com.yachaq.node.extractor.FeatureExtractor();

    // ==================== ODX Safety Tests (55.4) ====================

    /**
     * Property: All built ODX entries are privacy-safe.
     * **Feature: yachaq-platform, Property: ODX Safety**
     * **Validates: Requirements 311.1, 311.2**
     */
    @Property(tries = 100)
    void allBuiltEntries_arePrivacySafe(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        for (ODXEntry entry : entries) {
            assertThat(builder.validateSafety(entry))
                    .as("Entry with facet %s should be safe", entry.facetKey())
                    .isTrue();
            assertThat(entry.isPrivacySafe()).isTrue();
        }
    }

    /**
     * Property: ODX entries never contain raw payload indicators.
     * **Feature: yachaq-platform, Property: No Raw Payload**
     * **Validates: Requirements 311.2**
     */
    @Property(tries = 100)
    void odxEntries_neverContainRawPayload(
            @ForAll("eventsWithSensitiveData") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        Set<String> forbiddenPatterns = Set.of(
                "raw", "payload", "content", "text", "email", "phone",
                "address", "name", "ssn", "password", "secret", "token",
                "body", "message"
        );
        
        for (ODXEntry entry : entries) {
            String facetLower = entry.facetKey().toLowerCase();
            for (String pattern : forbiddenPatterns) {
                assertThat(facetLower)
                        .as("Facet key should not contain '%s'", pattern)
                        .doesNotContain(pattern);
            }
        }
    }

    /**
     * Property: ODX entries never contain precise GPS coordinates.
     * **Feature: yachaq-platform, Property: No Precise GPS**
     * **Validates: Requirements 311.2**
     */
    @Property(tries = 100)
    void odxEntries_neverContainPreciseGPS(
            @ForAll("eventsWithLocation") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.buildFromEvent(event, labelSet, features);
        
        for (ODXEntry entry : entries) {
            // Geo resolution must not be EXACT
            assertThat(entry.geoResolution())
                    .as("Geo resolution should not be EXACT")
                    .isNotEqualTo(GeoResolution.EXACT);
            
            // Geo bucket should not contain precise coordinates (>1 decimal)
            if (entry.geoBucket() != null) {
                assertThat(entry.geoBucket())
                        .as("Geo bucket should not contain precise coordinates")
                        .doesNotContainPattern("\\d+\\.\\d{2,}");
            }
        }
    }

    /**
     * Property: ODX entries have valid facet key format.
     * **Feature: yachaq-platform, Property: Valid Facet Format**
     * **Validates: Requirements 311.1**
     */
    @Property(tries = 100)
    void odxEntries_haveValidFacetKeyFormat(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        for (ODXEntry entry : entries) {
            // Facet key must follow namespace:category pattern
            assertThat(entry.facetKey())
                    .matches("^[a-z]+:[a-z_]+$");
        }
    }

    /**
     * Property: ODX entries have valid time bucket format.
     * **Feature: yachaq-platform, Property: Valid Time Bucket**
     * **Validates: Requirements 311.1**
     */
    @Property(tries = 100)
    void odxEntries_haveValidTimeBucketFormat(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        for (ODXEntry entry : entries) {
            // Time bucket must follow valid format
            assertThat(entry.timeBucket())
                    .matches("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)$");
        }
    }

    /**
     * Property: ODX entries have positive privacy floor.
     * **Feature: yachaq-platform, Property: Valid Privacy Floor**
     * **Validates: Requirements 311.1**
     */
    @Property(tries = 100)
    void odxEntries_havePositivePrivacyFloor(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        for (ODXEntry entry : entries) {
            assertThat(entry.privacyFloor())
                    .as("Privacy floor should be at least 1")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    /**
     * Property: High sensitivity data has higher privacy floor.
     * **Feature: yachaq-platform, Property: Sensitivity Privacy Floor**
     * **Validates: Requirements 311.1**
     */
    @Property(tries = 50)
    void highSensitivityData_hasHigherPrivacyFloor(
            @ForAll("healthEvents") CanonicalEvent healthEvent,
            @ForAll("mediaEvents") CanonicalEvent mediaEvent) {
        
        LabelSet healthLabels = labeler.label(healthEvent);
        LabelSet mediaLabels = labeler.label(mediaEvent);
        ExtractedFeatures healthFeatures = featureExtractor.extract(healthEvent);
        ExtractedFeatures mediaFeatures = featureExtractor.extract(mediaEvent);
        
        List<ODXEntry> healthEntries = builder.build(healthLabels, healthFeatures);
        List<ODXEntry> mediaEntries = builder.build(mediaLabels, mediaFeatures);
        
        // Health data should have higher privacy floor
        int maxHealthFloor = healthEntries.stream()
                .mapToInt(ODXEntry::privacyFloor)
                .max()
                .orElse(0);
        int maxMediaFloor = mediaEntries.stream()
                .mapToInt(ODXEntry::privacyFloor)
                .max()
                .orElse(0);
        
        assertThat(maxHealthFloor)
                .as("Health data should have higher privacy floor")
                .isGreaterThanOrEqualTo(maxMediaFloor);
    }

    // ==================== Upsert Tests ====================

    /**
     * Property: Upsert merges counts correctly.
     */
    @Property(tries = 50)
    void upsert_mergesCountsCorrectly(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries1 = builder.build(labelSet, features);
        List<ODXEntry> entries2 = builder.build(labelSet, features);
        
        List<ODXEntry> merged = builder.upsert(entries1, entries2);
        
        // Merged entries should have count = 2 for matching keys
        for (ODXEntry entry : merged) {
            assertThat(entry.count())
                    .as("Merged entry should have count >= 2")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * Property: Upsert preserves privacy floor (takes max).
     */
    @Property(tries = 50)
    void upsert_preservesMaxPrivacyFloor(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries1 = builder.build(labelSet, features);
        
        // Create entries with higher privacy floor
        List<ODXEntry> entries2 = entries1.stream()
                .map(e -> ODXEntry.builder()
                        .generateId()
                        .facetKey(e.facetKey())
                        .timeBucket(e.timeBucket())
                        .count(1)
                        .quality(e.quality())
                        .privacyFloor(200) // Higher floor
                        .geoResolution(e.geoResolution())
                        .timeResolution(e.timeResolution())
                        .ontologyVersion(e.ontologyVersion())
                        .build())
                .toList();
        
        List<ODXEntry> merged = builder.upsert(entries1, entries2);
        
        for (ODXEntry entry : merged) {
            assertThat(entry.privacyFloor())
                    .as("Merged entry should have max privacy floor")
                    .isGreaterThanOrEqualTo(200);
        }
    }

    // ==================== Query Tests ====================

    /**
     * Property: Query by facet pattern returns matching entries.
     */
    @Property(tries = 50)
    void query_byFacetPattern_returnsMatchingEntries(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        // Query for domain namespace
        ODXCriteria criteria = ODXCriteria.byFacet("domain:.*");
        List<ODXEntry> results = builder.query(entries, criteria);
        
        for (ODXEntry entry : results) {
            assertThat(entry.facetKey()).startsWith("domain:");
        }
    }

    /**
     * Property: Query with all criteria returns all entries.
     */
    @Property(tries = 50)
    void query_withAllCriteria_returnsAllEntries(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        LabelSet labelSet = labeler.label(event);
        ExtractedFeatures features = featureExtractor.extract(event);
        
        List<ODXEntry> entries = builder.build(labelSet, features);
        
        ODXCriteria criteria = ODXCriteria.all();
        List<ODXEntry> results = builder.query(entries, criteria);
        
        assertThat(results).hasSameSizeAs(entries);
    }

    // ==================== Validation Tests ====================

    /**
     * Property: Validation rejects entries with forbidden patterns.
     */
    @Test
    void validation_rejectsEntriesWithForbiddenPatterns() {
        // These should throw during construction
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("raw:data")
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
        
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("email:address")
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
    }

    /**
     * Property: Validation rejects entries with EXACT geo resolution.
     */
    @Test
    void validation_rejectsExactGeoResolution() {
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("domain:activity")
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.EXACT)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
    }

    /**
     * Property: Validation rejects invalid facet key format.
     */
    @Test
    void validation_rejectsInvalidFacetKeyFormat() {
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("invalid-format")
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
    }

    /**
     * Property: Validation rejects invalid time bucket format.
     */
    @Test
    void validation_rejectsInvalidTimeBucketFormat() {
        assertThatThrownBy(() -> ODXEntry.builder()
                .generateId()
                .facetKey("domain:activity")
                .timeBucket("invalid")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build())
                .isInstanceOf(ODXSafetyException.class);
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<CanonicalEvent> randomEvents() {
        return Arbitraries.of(EventCategory.values())
                .flatMap(category -> Arbitraries.of("event1", "event2", "event3")
                        .map(eventType -> createEvent(category, eventType)));
    }

    @Provide
    Arbitrary<CanonicalEvent> eventsWithSensitiveData() {
        return Arbitraries.of(
                createEventWithAttribute("content", "sensitive content"),
                createEventWithAttribute("email", "user@example.com"),
                createEventWithAttribute("phone", "1234567890"),
                createEventWithAttribute("password", "secret123")
        );
    }

    @Provide
    Arbitrary<CanonicalEvent> eventsWithLocation() {
        return Arbitraries.of(
                createEventWithLocation(GeoLocation.city(40.7, -74.0)),
                createEventWithLocation(GeoLocation.city(51.5, -0.1)),
                createEventWithLocation(new GeoLocation(35.6, 139.7, null, null, 
                        GeoLocation.GeoResolution.REGION))
        );
    }

    @Provide
    Arbitrary<CanonicalEvent> healthEvents() {
        return Arbitraries.of("heart_rate", "steps", "sleep", "workout")
                .map(eventType -> createEvent(EventCategory.HEALTH, eventType));
    }

    @Provide
    Arbitrary<CanonicalEvent> mediaEvents() {
        return Arbitraries.of("photo", "video", "music", "podcast")
                .map(eventType -> createEvent(EventCategory.MEDIA, eventType));
    }

    // ==================== Helper Methods ====================

    private CanonicalEvent createEvent(EventCategory category, String eventType) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(category)
                .eventType(eventType)
                .timestamp(Instant.now())
                .attributes(Map.of("count", 100L))
                .build();
    }

    private CanonicalEvent createEventWithAttribute(String key, Object value) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(EventCategory.OTHER)
                .eventType("test_event")
                .timestamp(Instant.now())
                .attributes(Map.of(key, value))
                .build();
    }

    private CanonicalEvent createEventWithLocation(GeoLocation location) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(EventCategory.LOCATION)
                .eventType("location_update")
                .timestamp(Instant.now())
                .location(location)
                .attributes(Map.of())
                .build();
    }

    // ==================== Unit Tests ====================

    @Test
    void build_handlesNullLabelSet() {
        ExtractedFeatures features = createMinimalFeatures();
        
        assertThatThrownBy(() -> builder.build(null, features))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void build_handlesNullFeatures() {
        LabelSet labelSet = createMinimalLabelSet();
        
        assertThatThrownBy(() -> builder.build(labelSet, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void validateBatch_returnsCorrectCounts() {
        ODXEntry safeEntry = ODXEntry.builder()
                .generateId()
                .facetKey("domain:activity")
                .timeBucket("2024-01-15")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.NONE)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build();
        
        ValidationResult result = builder.validateBatch(List.of(safeEntry));
        
        assertThat(result.safeCount()).isEqualTo(1);
        assertThat(result.unsafeCount()).isEqualTo(0);
        assertThat(result.isAllSafe()).isTrue();
    }

    @Test
    void odxEntry_compositeKey_isCorrect() {
        ODXEntry entry = ODXEntry.builder()
                .generateId()
                .facetKey("domain:activity")
                .timeBucket("2024-01-15")
                .geoBucket("40.7,-74.0")
                .count(1)
                .quality(Quality.VERIFIED)
                .privacyFloor(50)
                .geoResolution(GeoResolution.CITY)
                .timeResolution(TimeResolution.DAY)
                .ontologyVersion("1.0.0")
                .build();
        
        assertThat(entry.getCompositeKey())
                .isEqualTo("domain:activity|2024-01-15|40.7,-74.0");
    }

    private ExtractedFeatures createMinimalFeatures() {
        return ExtractedFeatures.builder()
                .eventId("test-id")
                .sourceType("test")
                .timeBucket(new TimeBucket(12, 3, 15, 4, 2, 
                        TimeOfDayBucket.AFTERNOON, DayTypeBucket.WEEKDAY))
                .numericFeatures(NumericFeatures.empty())
                .clusterFeatures(ClusterFeatures.empty())
                .qualityFlags(QualityFlags.verified())
                .build();
    }

    private LabelSet createMinimalLabelSet() {
        return LabelSet.builder()
                .eventId("test-id")
                .ontologyVersion("1.0.0")
                .addLabel(com.yachaq.node.labeler.Label.of(LabelNamespace.DOMAIN, "activity", "exercise"))
                .build();
    }
}
