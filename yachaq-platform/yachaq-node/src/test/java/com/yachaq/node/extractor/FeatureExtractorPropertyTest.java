package com.yachaq.node.extractor;

import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.normalizer.CanonicalEvent.*;
import com.yachaq.node.extractor.ExtractedFeatures.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Feature Extractor.
 * Requirement 309.2, 309.3, 309.6: Ensure no raw content appears in ODX.
 * 
 * **Feature: yachaq-platform, Property: Feature Extractor No Leakage**
 * **Validates: Requirements 309.2, 309.3, 309.6**
 */
class FeatureExtractorPropertyTest {

    private final FeatureExtractor extractor = new FeatureExtractor();

    // ==================== Leakage Tests (52.5) ====================

    /**
     * Property: Extracted features never contain raw content from forbidden fields.
     * **Feature: yachaq-platform, Property: No Raw Content Leakage**
     * **Validates: Requirements 309.2, 309.3**
     */
    @Property(tries = 100)
    void extractedFeatures_neverContainRawContent(
            @ForAll("eventsWithForbiddenContent") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        
        // Verify no leakage
        assertThat(extractor.validateNoLeakage(features)).isTrue();
        
        // Verify forbidden fields are not in additional features
        Set<String> forbiddenFields = Set.of(
                "content", "body", "text", "message", "rawData", "payload",
                "email", "phone", "name", "address", "ssn", "password",
                "creditCard", "bankAccount", "personalId"
        );
        
        for (String key : features.additionalFeatures().keySet()) {
            assertThat(forbiddenFields).doesNotContain(key.toLowerCase());
        }
    }

    /**
     * Property: Extracted features never contain email patterns.
     * **Feature: yachaq-platform, Property: No Email Leakage**
     * **Validates: Requirements 309.2**
     */
    @Property(tries = 100)
    void extractedFeatures_neverContainEmails(
            @ForAll("eventsWithEmails") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        
        // Check all string values in additional features
        for (Object value : features.additionalFeatures().values()) {
            if (value instanceof String str) {
                assertThat(str).doesNotContainPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
            }
        }
        
        // Check tags
        for (String tag : features.clusterFeatures().tags()) {
            assertThat(tag).doesNotContain("@");
        }
    }

    /**
     * Property: Extracted features never contain phone number patterns.
     * **Feature: yachaq-platform, Property: No Phone Leakage**
     * **Validates: Requirements 309.2**
     */
    @Property(tries = 100)
    void extractedFeatures_neverContainPhoneNumbers(
            @ForAll("eventsWithPhoneNumbers") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        
        // Check all string values
        for (Object value : features.additionalFeatures().values()) {
            if (value instanceof String str) {
                // Should not contain 10+ consecutive digits
                assertThat(str).doesNotContainPattern("\\d{10,}");
            }
        }
        
        // Check tags
        for (String tag : features.clusterFeatures().tags()) {
            assertThat(tag).doesNotContainPattern("\\d{10,}");
        }
    }

    /**
     * Property: Cluster IDs are always privacy-safe identifiers, not raw content.
     * **Feature: yachaq-platform, Property: Safe Cluster IDs**
     * **Validates: Requirements 309.1, 309.3**
     */
    @Property(tries = 100)
    void clusterIds_arePrivacySafe(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        ClusterFeatures clusters = features.clusterFeatures();
        
        // Cluster IDs should be short identifiers, not raw content
        if (clusters.topicClusterId() != null) {
            assertThat(clusters.topicClusterId()).hasSizeLessThan(100);
            assertThat(clusters.topicClusterId()).startsWith("topic:");
        }
        
        if (clusters.moodClusterId() != null) {
            assertThat(clusters.moodClusterId()).hasSizeLessThan(100);
            assertThat(clusters.moodClusterId()).startsWith("mood:");
        }
        
        if (clusters.sceneClusterId() != null) {
            assertThat(clusters.sceneClusterId()).hasSizeLessThan(100);
            assertThat(clusters.sceneClusterId()).startsWith("scene:");
        }
        
        if (clusters.activityClusterId() != null) {
            assertThat(clusters.activityClusterId()).hasSizeLessThan(100);
            assertThat(clusters.activityClusterId()).startsWith("activity:");
        }
    }

    /**
     * Property: Tags are always safe and don't contain PII.
     * **Feature: yachaq-platform, Property: Safe Tags**
     * **Validates: Requirements 309.3, 309.6**
     */
    @Property(tries = 100)
    void tags_arePrivacySafe(
            @ForAll("eventsWithTags") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        
        for (String tag : features.clusterFeatures().tags()) {
            // Tags should be short
            assertThat(tag.length()).isLessThanOrEqualTo(100);
            
            // Tags should not contain email patterns
            assertThat(tag).doesNotContain("@");
            
            // Tags should not contain long number sequences
            assertThat(tag).doesNotContainPattern("\\d{4,}");
        }
    }


    // ==================== Time Bucket Tests ====================

    /**
     * Property: Time buckets are always valid.
     */
    @Property(tries = 100)
    void timeBuckets_areAlwaysValid(
            @ForAll("randomTimestamps") Instant timestamp) {
        
        TimeBucket bucket = extractor.extractTimeBucket(timestamp);
        
        assertThat(bucket.hourOfDay()).isBetween(0, 23);
        assertThat(bucket.dayOfWeek()).isBetween(1, 7);
        assertThat(bucket.weekOfYear()).isBetween(1, 53);
        assertThat(bucket.monthOfYear()).isBetween(1, 12);
        assertThat(bucket.quarter()).isBetween(1, 4);
        assertThat(bucket.timeOfDayBucket()).isNotNull();
        assertThat(bucket.dayTypeBucket()).isNotNull();
    }

    /**
     * Property: Time of day bucket matches hour.
     */
    @Property(tries = 100)
    void timeOfDayBucket_matchesHour(
            @ForAll("randomTimestamps") Instant timestamp) {
        
        TimeBucket bucket = extractor.extractTimeBucket(timestamp);
        int hour = bucket.hourOfDay();
        TimeOfDayBucket tod = bucket.timeOfDayBucket();
        
        if (hour >= 5 && hour < 8) {
            assertThat(tod).isEqualTo(TimeOfDayBucket.EARLY_MORNING);
        } else if (hour >= 8 && hour < 12) {
            assertThat(tod).isEqualTo(TimeOfDayBucket.MORNING);
        } else if (hour >= 12 && hour < 17) {
            assertThat(tod).isEqualTo(TimeOfDayBucket.AFTERNOON);
        } else if (hour >= 17 && hour < 21) {
            assertThat(tod).isEqualTo(TimeOfDayBucket.EVENING);
        } else {
            assertThat(tod).isEqualTo(TimeOfDayBucket.NIGHT);
        }
    }

    /**
     * Property: Day type bucket matches day of week.
     */
    @Property(tries = 100)
    void dayTypeBucket_matchesDayOfWeek(
            @ForAll("randomTimestamps") Instant timestamp) {
        
        TimeBucket bucket = extractor.extractTimeBucket(timestamp);
        int dow = bucket.dayOfWeek();
        DayTypeBucket dayType = bucket.dayTypeBucket();
        
        if (dow >= 6) { // Saturday=6, Sunday=7
            assertThat(dayType).isEqualTo(DayTypeBucket.WEEKEND);
        } else {
            assertThat(dayType).isEqualTo(DayTypeBucket.WEEKDAY);
        }
    }

    // ==================== Numeric Feature Tests ====================

    /**
     * Property: Duration buckets are monotonic.
     */
    @Property(tries = 100)
    void durationBuckets_areMonotonic(
            @ForAll @LongRange(min = 0, max = 100000) long seconds1,
            @ForAll @LongRange(min = 0, max = 100000) long seconds2) {
        
        DurationBucket bucket1 = extractor.categorizeDuration(seconds1);
        DurationBucket bucket2 = extractor.categorizeDuration(seconds2);
        
        if (seconds1 <= seconds2) {
            assertThat(bucket1.ordinal()).isLessThanOrEqualTo(bucket2.ordinal());
        }
    }

    /**
     * Property: Count buckets are monotonic (for non-zero counts).
     * Note: count=0 maps to NONE which has ordinal 0, but count=1 maps to SINGLE.
     */
    @Property(tries = 100)
    void countBuckets_areMonotonic(
            @ForAll @LongRange(min = 1, max = 1000) long count1,
            @ForAll @LongRange(min = 1, max = 1000) long count2) {
        
        CountBucket bucket1 = extractor.categorizeCount(count1);
        CountBucket bucket2 = extractor.categorizeCount(count2);
        
        if (count1 <= count2) {
            assertThat(bucket1.ordinal()).isLessThanOrEqualTo(bucket2.ordinal());
        }
    }

    /**
     * Property: Distance buckets are monotonic.
     */
    @Property(tries = 100)
    void distanceBuckets_areMonotonic(
            @ForAll @DoubleRange(min = 0, max = 500000) double meters1,
            @ForAll @DoubleRange(min = 0, max = 500000) double meters2) {
        
        DistanceBucket bucket1 = extractor.categorizeDistance(meters1);
        DistanceBucket bucket2 = extractor.categorizeDistance(meters2);
        
        if (meters1 <= meters2) {
            assertThat(bucket1.ordinal()).isLessThanOrEqualTo(bucket2.ordinal());
        }
    }

    // ==================== Quality Flag Tests ====================

    /**
     * Property: Quality flags are always valid.
     */
    @Property(tries = 100)
    void qualityFlags_areAlwaysValid(
            @ForAll("randomEvents") CanonicalEvent event) {
        
        ExtractedFeatures features = extractor.extract(event);
        QualityFlags flags = features.qualityFlags();
        
        assertThat(flags.dataSource()).isNotNull();
        assertThat(flags.verificationLevel()).isNotNull();
        assertThat(flags.confidenceScore()).isBetween(0.0, 1.0);
    }

    /**
     * Property: Connector sources have higher confidence than imports.
     */
    @Property(tries = 50)
    void connectorSources_haveHigherConfidence(
            @ForAll("connectorEvents") CanonicalEvent connectorEvent,
            @ForAll("importEvents") CanonicalEvent importEvent) {
        
        ExtractedFeatures connectorFeatures = extractor.extract(connectorEvent);
        ExtractedFeatures importFeatures = extractor.extract(importEvent);
        
        // Connector sources should generally have higher confidence
        assertThat(connectorFeatures.qualityFlags().confidenceScore())
                .isGreaterThanOrEqualTo(importFeatures.qualityFlags().confidenceScore());
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<CanonicalEvent> eventsWithForbiddenContent() {
        return Arbitraries.of(
                createEventWithAttribute("content", "This is raw content that should not leak"),
                createEventWithAttribute("body", "Email body with sensitive info"),
                createEventWithAttribute("message", "Private message content"),
                createEventWithAttribute("email", "user@example.com"),
                createEventWithAttribute("phone", "1234567890"),
                createEventWithAttribute("password", "secret123")
        );
    }

    @Provide
    Arbitrary<CanonicalEvent> eventsWithEmails() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(name -> name + "@example.com")
                .map(email -> createEventWithAttribute("contactEmail", email));
    }

    @Provide
    Arbitrary<CanonicalEvent> eventsWithPhoneNumbers() {
        return Arbitraries.longs()
                .between(1000000000L, 9999999999L)
                .map(String::valueOf)
                .map(phone -> createEventWithAttribute("contactPhone", phone));
    }

    @Provide
    Arbitrary<CanonicalEvent> eventsWithTags() {
        return Arbitraries.of(
                createEventWithTags(List.of("fitness", "running", "outdoor")),
                createEventWithTags(List.of("work", "meeting", "important")),
                createEventWithTags(List.of("personal", "family", "weekend")),
                createEventWithTags(List.of("user@email.com", "12345678901")), // Should be filtered
                createEventWithTags(List.of("safe-tag", "another-safe-tag"))
        );
    }

    @Provide
    Arbitrary<CanonicalEvent> randomEvents() {
        return Arbitraries.of(EventCategory.values())
                .flatMap(category -> Arbitraries.of("event1", "event2", "event3")
                        .map(eventType -> createBasicEvent(category, eventType)));
    }

    @Provide
    Arbitrary<Instant> randomTimestamps() {
        return Arbitraries.longs()
                .between(0, Instant.now().toEpochMilli())
                .map(Instant::ofEpochMilli);
    }

    @Provide
    Arbitrary<CanonicalEvent> connectorEvents() {
        return Arbitraries.of("healthkit", "spotify", "strava", "google_fit")
                .map(source -> createEventWithSource(source, DataSource.CONNECTOR));
    }

    @Provide
    Arbitrary<CanonicalEvent> importEvents() {
        return Arbitraries.of("takeout_import", "file_upload", "manual_import")
                .map(source -> createEventWithSource(source, DataSource.USER_IMPORT));
    }

    // ==================== Helper Methods ====================

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

    private CanonicalEvent createEventWithTags(List<String> tags) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(EventCategory.OTHER)
                .eventType("test_event")
                .timestamp(Instant.now())
                .attributes(Map.of("tags", tags))
                .build();
    }

    private CanonicalEvent createBasicEvent(EventCategory category, String eventType) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("test-source")
                .category(category)
                .eventType(eventType)
                .timestamp(Instant.now())
                .attributes(Map.of("count", 100L, "duration", 3600L))
                .build();
    }

    private CanonicalEvent createEventWithSource(String sourceType, DataSource expectedSource) {
        return CanonicalEvent.builder()
                .generateId()
                .sourceType(sourceType)
                .sourceId("source-" + sourceType)
                .category(EventCategory.ACTIVITY)
                .eventType("activity_event")
                .timestamp(Instant.now())
                .attributes(Map.of("count", 1000L))
                .contentHash("sha256:abc123")
                .build();
    }

    // ==================== Unit Tests ====================

    @Test
    void extract_handlesNullEvent() {
        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void extractTimeBucket_handlesNullTimestamp() {
        assertThatThrownBy(() -> extractor.extractTimeBucket(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void categorizeDuration_handlesNull() {
        assertThat(extractor.categorizeDuration(null)).isEqualTo(DurationBucket.NONE);
    }

    @Test
    void categorizeCount_handlesNull() {
        assertThat(extractor.categorizeCount(null)).isEqualTo(CountBucket.NONE);
    }

    @Test
    void categorizeDistance_handlesNull() {
        assertThat(extractor.categorizeDistance(null)).isEqualTo(DistanceBucket.NONE);
    }

    @Test
    void extractBatch_handlesEmptyList() {
        List<ExtractedFeatures> result = extractor.extractBatch(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void extractBatch_handlesNullList() {
        List<ExtractedFeatures> result = extractor.extractBatch(null);
        assertThat(result).isEmpty();
    }

    @Test
    void validateNoLeakage_detectsEmailInTags() {
        CanonicalEvent event = createEventWithTags(List.of("user@example.com"));
        ExtractedFeatures features = extractor.extract(event);
        
        // The extractor should filter out unsafe tags
        assertThat(features.clusterFeatures().tags())
                .noneMatch(tag -> tag.contains("@"));
    }

    @Test
    void qualityFlags_connectorIsVerified() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("healthkit")
                .sourceId("healthkit-1")
                .category(EventCategory.HEALTH)
                .eventType("steps")
                .timestamp(Instant.now())
                .attributes(Map.of("count", 5000L))
                .contentHash("sha256:valid")
                .build();
        
        ExtractedFeatures features = extractor.extract(event);
        
        assertThat(features.qualityFlags().dataSource()).isEqualTo(DataSource.CONNECTOR);
        assertThat(features.qualityFlags().verificationLevel()).isEqualTo(VerificationLevel.VERIFIED);
    }

    @Test
    void qualityFlags_importIsUnverified() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("takeout_import")
                .sourceId("import-1")
                .category(EventCategory.OTHER)
                .eventType("imported_data")
                .timestamp(Instant.now())
                .attributes(Map.of("data", "value"))
                .build();
        
        ExtractedFeatures features = extractor.extract(event);
        
        assertThat(features.qualityFlags().dataSource()).isEqualTo(DataSource.USER_IMPORT);
    }
}
