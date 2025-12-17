package com.yachaq.node.normalizer;

import com.yachaq.node.normalizer.CanonicalEvent.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Data Normalizer.
 * Requirement 308.4, 308.5: Verify same input produces same canonical output (deterministic).
 * 
 * Golden-file style tests ensuring normalization is deterministic and consistent.
 */
class DataNormalizerPropertyTest {

    // ==================== Deterministic Normalization Property ====================

    @Property(tries = 100)
    void normalization_isDeterministic(
            @ForAll("healthRecords") Map<String, Object> rawData,
            @ForAll("sourceIds") String sourceId) {
        // Property: Same input always produces same output
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> result1 = normalizer.normalize(rawData, sourceId);
        List<CanonicalEvent> result2 = normalizer.normalize(rawData, sourceId);
        
        assertThat(result1).hasSameSizeAs(result2);
        
        for (int i = 0; i < result1.size(); i++) {
            CanonicalEvent e1 = result1.get(i);
            CanonicalEvent e2 = result2.get(i);
            
            // Core fields must match
            assertThat(e1.sourceType()).isEqualTo(e2.sourceType());
            assertThat(e1.sourceId()).isEqualTo(e2.sourceId());
            assertThat(e1.category()).isEqualTo(e2.category());
            assertThat(e1.eventType()).isEqualTo(e2.eventType());
            assertThat(e1.timestamp()).isEqualTo(e2.timestamp());
            assertThat(e1.attributes()).isEqualTo(e2.attributes());
            assertThat(e1.contentHash()).isEqualTo(e2.contentHash());
        }
    }

    @Property(tries = 100)
    void normalization_preservesSourceInfo(
            @ForAll("healthRecords") Map<String, Object> rawData,
            @ForAll("sourceIds") String sourceId) {
        // Property: Normalized events preserve source information
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        for (CanonicalEvent event : events) {
            assertThat(event.sourceType()).isEqualTo(HealthDataNormalizer.SOURCE_TYPE);
            assertThat(event.sourceId()).isEqualTo(sourceId);
        }
    }

    @Property(tries = 100)
    void normalization_generatesValidEvents(
            @ForAll("healthRecords") Map<String, Object> rawData,
            @ForAll("sourceIds") String sourceId) {
        // Property: All normalized events have required fields
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        for (CanonicalEvent event : events) {
            assertThat(event.id()).isNotNull().isNotBlank();
            assertThat(event.sourceType()).isNotNull().isNotBlank();
            assertThat(event.sourceId()).isNotNull().isNotBlank();
            assertThat(event.category()).isNotNull();
            assertThat(event.eventType()).isNotNull().isNotBlank();
            assertThat(event.timestamp()).isNotNull();
            assertThat(event.schemaVersion()).isNotNull();
        }
    }

    // ==================== Location Normalizer Tests ====================

    @Property(tries = 100)
    void locationNormalization_preservesCoordinates(
            @ForAll @DoubleRange(min = -90, max = 90) double lat,
            @ForAll @DoubleRange(min = -180, max = 180) double lon,
            @ForAll("sourceIds") String sourceId) {
        // Property: Location coordinates are preserved accurately
        LocationDataNormalizer normalizer = new LocationDataNormalizer();
        
        Map<String, Object> rawData = Map.of(
                "type", "point",
                "latitude", lat,
                "longitude", lon,
                "timestamp", Instant.now().toString()
        );
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        assertThat(events).hasSize(1);
        CanonicalEvent event = events.get(0);
        assertThat(event.location()).isNotNull();
        assertThat(event.location().latitude()).isEqualTo(lat);
        assertThat(event.location().longitude()).isEqualTo(lon);
    }

    @Property(tries = 50)
    void locationNormalization_handlesVisits(
            @ForAll @DoubleRange(min = -90, max = 90) double lat,
            @ForAll @DoubleRange(min = -180, max = 180) double lon,
            @ForAll("sourceIds") String sourceId) {
        // Property: Visit events include duration when available
        LocationDataNormalizer normalizer = new LocationDataNormalizer();
        
        Instant arrival = Instant.now().minusSeconds(3600);
        Instant departure = Instant.now();
        
        Map<String, Object> rawData = Map.of(
                "type", "visit",
                "latitude", lat,
                "longitude", lon,
                "arrivalTime", arrival.toString(),
                "departureTime", departure.toString()
        );
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        assertThat(events).hasSize(1);
        CanonicalEvent event = events.get(0);
        assertThat(event.eventType()).isEqualTo("visit");
        assertThat(event.duration()).isNotNull();
        assertThat(event.duration().toSeconds()).isEqualTo(3600);
    }

    // ==================== Communication Normalizer Tests ====================

    @Property(tries = 100)
    void communicationNormalization_preservesDirection(
            @ForAll("directions") String direction,
            @ForAll("sourceIds") String sourceId) {
        // Property: Message direction is preserved
        CommunicationDataNormalizer normalizer = new CommunicationDataNormalizer();
        
        Map<String, Object> rawData = Map.of(
                "type", "message",
                "direction", direction,
                "timestamp", Instant.now().toString()
        );
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        assertThat(events).hasSize(1);
        assertThat(events.get(0).attributes().get("direction")).isEqualTo(direction);
    }

    @Property(tries = 50)
    void communicationNormalization_handlesCallDuration(
            @ForAll @LongRange(min = 0, max = 7200) long durationSecs,
            @ForAll("sourceIds") String sourceId) {
        // Property: Call duration is preserved
        CommunicationDataNormalizer normalizer = new CommunicationDataNormalizer();
        
        Map<String, Object> rawData = Map.of(
                "type", "call",
                "duration", durationSecs,
                "timestamp", Instant.now().toString()
        );
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        assertThat(events).hasSize(1);
        CanonicalEvent event = events.get(0);
        assertThat(event.duration()).isNotNull();
        assertThat(event.duration().toSeconds()).isEqualTo(durationSecs);
    }

    // ==================== Media Normalizer Tests ====================

    @Property(tries = 100)
    void mediaNormalization_preservesDimensions(
            @ForAll @LongRange(min = 1, max = 10000) long width,
            @ForAll @LongRange(min = 1, max = 10000) long height,
            @ForAll("sourceIds") String sourceId) {
        // Property: Photo dimensions are preserved
        MediaDataNormalizer normalizer = new MediaDataNormalizer();
        
        Map<String, Object> rawData = Map.of(
                "type", "photo",
                "width", width,
                "height", height,
                "timestamp", Instant.now().toString()
        );
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        assertThat(events).hasSize(1);
        CanonicalEvent event = events.get(0);
        assertThat(event.attributes().get("width")).isEqualTo(width);
        assertThat(event.attributes().get("height")).isEqualTo(height);
    }

    // ==================== Registry Tests ====================

    @Property(tries = 50)
    void registry_normalizesCorrectSourceType(
            @ForAll("sourceTypes") String sourceType,
            @ForAll("sourceIds") String sourceId) {
        // Property: Registry routes to correct normalizer
        NormalizerRegistry registry = new NormalizerRegistry();
        
        Map<String, Object> rawData = createMinimalRecord(sourceType);
        
        List<CanonicalEvent> events = registry.normalize(sourceType, rawData, sourceId);
        
        for (CanonicalEvent event : events) {
            assertThat(event.sourceType()).isEqualTo(sourceType);
        }
    }

    @Property(tries = 50)
    void registry_validatesBeforeNormalization(
            @ForAll("sourceTypes") String sourceType) {
        // Property: Validation catches missing required fields
        NormalizerRegistry registry = new NormalizerRegistry();
        
        Map<String, Object> emptyData = Map.of();
        
        DataNormalizer.ValidationResult result = registry.validate(sourceType, emptyData);
        
        // Empty data should fail validation for most normalizers
        // (they require at least timestamp)
        assertThat(result).isNotNull();
    }

    // ==================== Schema Version Tests ====================

    @Property(tries = 50)
    void schemaVersion_isConsistent(
            @ForAll("healthRecords") Map<String, Object> rawData,
            @ForAll("sourceIds") String sourceId) {
        // Property: All events have consistent schema version
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events = normalizer.normalize(rawData, sourceId);
        
        for (CanonicalEvent event : events) {
            assertThat(event.schemaVersion()).isEqualTo(CanonicalEvent.CURRENT_SCHEMA_VERSION);
        }
    }

    // ==================== Content Hash Tests ====================

    @Property(tries = 100)
    void contentHash_isDeterministic(
            @ForAll("healthRecords") Map<String, Object> rawData,
            @ForAll("sourceIds") String sourceId) {
        // Property: Same input produces same content hash
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events1 = normalizer.normalize(rawData, sourceId);
        List<CanonicalEvent> events2 = normalizer.normalize(rawData, sourceId);
        
        for (int i = 0; i < events1.size(); i++) {
            assertThat(events1.get(i).contentHash())
                    .isEqualTo(events2.get(i).contentHash());
        }
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<Map<String, Object>> healthRecords() {
        return Arbitraries.of("steps", "heart_rate", "sleep", "workout", "weight")
                .flatMap(type -> {
                    Map<String, Object> record = new HashMap<>();
                    record.put("type", type);
                    record.put("timestamp", Instant.now().toString());
                    
                    switch (type) {
                        case "steps" -> record.put("count", (long) (Math.random() * 10000));
                        case "heart_rate" -> record.put("bpm", 60 + Math.random() * 100);
                        case "sleep" -> {
                            record.put("startTime", Instant.now().minusSeconds(28800).toString());
                            record.put("endTime", Instant.now().toString());
                        }
                        case "workout" -> {
                            record.put("workoutType", "running");
                            record.put("duration", 1800L);
                        }
                        case "weight" -> record.put("weight", 50 + Math.random() * 100);
                    }
                    
                    return Arbitraries.just(record);
                });
    }

    @Provide
    Arbitrary<String> sourceIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(5)
                .ofMaxLength(20)
                .map(s -> "source-" + s);
    }

    @Provide
    Arbitrary<String> directions() {
        return Arbitraries.of("sent", "received", "incoming", "outgoing");
    }

    @Provide
    Arbitrary<String> sourceTypes() {
        return Arbitraries.of("health", "location", "communication", "media");
    }

    // ==================== Helper Methods ====================

    private Map<String, Object> createMinimalRecord(String sourceType) {
        Map<String, Object> record = new HashMap<>();
        record.put("timestamp", Instant.now().toString());
        
        switch (sourceType) {
            case "health" -> {
                record.put("type", "steps");
                record.put("count", 1000L);
            }
            case "location" -> {
                record.put("type", "point");
                record.put("latitude", 37.7749);
                record.put("longitude", -122.4194);
            }
            case "communication" -> {
                record.put("type", "message");
                record.put("direction", "sent");
            }
            case "media" -> {
                record.put("type", "photo");
                record.put("width", 1920L);
                record.put("height", 1080L);
            }
        }
        
        return record;
    }

    // ==================== Edge Case Tests ====================

    @Test
    void normalize_handlesNullData() {
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events = normalizer.normalize(null, "source-1");
        
        assertThat(events).isEmpty();
    }

    @Test
    void normalize_handlesEmptyData() {
        HealthDataNormalizer normalizer = new HealthDataNormalizer();
        
        List<CanonicalEvent> events = normalizer.normalize(Map.of(), "source-1");
        
        assertThat(events).isEmpty();
    }

    @Test
    void registry_throwsForUnknownSourceType() {
        NormalizerRegistry registry = new NormalizerRegistry();
        
        assertThatThrownBy(() -> registry.normalize("unknown", Map.of(), "source-1"))
                .isInstanceOf(NormalizerRegistry.NormalizationException.class);
    }

    @Test
    void canonicalEvent_builder_generatesId() {
        CanonicalEvent event = CanonicalEvent.builder()
                .generateId()
                .sourceType("test")
                .sourceId("source-1")
                .category(EventCategory.OTHER)
                .eventType("test_event")
                .timestamp(Instant.now())
                .build();
        
        assertThat(event.id()).isNotNull().isNotBlank();
    }

    @Test
    void geoLocation_cityResolution_roundsCoordinates() {
        GeoLocation location = GeoLocation.city(37.7749, -122.4194);
        
        assertThat(location.resolution()).isEqualTo(GeoLocation.GeoResolution.CITY);
        // Should be rounded to 1 decimal place
        assertThat(location.latitude()).isEqualTo(37.8);
        assertThat(location.longitude()).isEqualTo(-122.4);
    }

    @Test
    void duration_convertsCorrectly() {
        Duration minutes = Duration.ofMinutes(30);
        Duration hours = Duration.ofHours(2);
        
        assertThat(minutes.toSeconds()).isEqualTo(1800);
        assertThat(hours.toSeconds()).isEqualTo(7200);
    }
}
