package com.yachaq.node.normalizer;

import com.yachaq.node.normalizer.CanonicalEvent.*;

import java.time.Instant;
import java.util.*;

/**
 * Normalizer for health data from HealthKit/Health Connect.
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public class HealthDataNormalizer extends AbstractNormalizer {

    public static final String SOURCE_TYPE = "health";

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public List<CanonicalEvent> normalize(Map<String, Object> rawData, String sourceId) {
        if (rawData == null || rawData.isEmpty()) {
            return List.of();
        }

        String recordType = getString(rawData, "type", "unknown");
        
        return switch (recordType.toLowerCase()) {
            case "steps" -> normalizeSteps(rawData, sourceId);
            case "heart_rate" -> normalizeHeartRate(rawData, sourceId);
            case "sleep" -> normalizeSleep(rawData, sourceId);
            case "workout" -> normalizeWorkout(rawData, sourceId);
            case "weight" -> normalizeWeight(rawData, sourceId);
            case "blood_pressure" -> normalizeBloodPressure(rawData, sourceId);
            default -> normalizeGenericHealth(rawData, sourceId, recordType);
        };
    }

    @Override
    protected void validateRequiredFields(Map<String, Object> rawData, List<String> errors) {
        if (!rawData.containsKey("type")) {
            errors.add("Missing required field: type");
        }
        if (!rawData.containsKey("timestamp") && !rawData.containsKey("startTime")) {
            errors.add("Missing required field: timestamp or startTime");
        }
    }

    private List<CanonicalEvent> normalizeSteps(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "startTime");
        }
        if (timestamp == null) {
            return List.of();
        }

        Long count = getLong(rawData, "count");
        if (count == null) {
            count = getLong(rawData, "value");
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("stepCount", count);
        
        Long durationSecs = getLong(rawData, "duration");
        Duration duration = durationSecs != null ? Duration.ofSeconds(durationSecs) : null;

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.ACTIVITY)
                .eventType("steps")
                .timestamp(timestamp)
                .duration(duration)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeHeartRate(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            return List.of();
        }

        Double bpm = getDouble(rawData, "bpm");
        if (bpm == null) {
            bpm = getDouble(rawData, "value");
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("bpm", bpm);
        
        String context = getString(rawData, "context");
        if (context != null) {
            attributes.put("context", context);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.HEALTH)
                .eventType("heart_rate")
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeSleep(Map<String, Object> rawData, String sourceId) {
        Instant startTime = getInstant(rawData, "startTime");
        Instant endTime = getInstant(rawData, "endTime");
        
        if (startTime == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        String stage = getString(rawData, "stage");
        if (stage != null) {
            attributes.put("sleepStage", stage);
        }
        
        Long durationSecs = null;
        if (endTime != null) {
            durationSecs = endTime.getEpochSecond() - startTime.getEpochSecond();
            attributes.put("durationMinutes", durationSecs / 60);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.HEALTH)
                .eventType("sleep")
                .timestamp(startTime)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeWorkout(Map<String, Object> rawData, String sourceId) {
        Instant startTime = getInstant(rawData, "startTime");
        if (startTime == null) {
            startTime = getInstant(rawData, "timestamp");
        }
        if (startTime == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        String workoutType = getString(rawData, "workoutType");
        if (workoutType != null) {
            attributes.put("workoutType", workoutType);
        }
        
        Double calories = getDouble(rawData, "calories");
        if (calories != null) {
            attributes.put("caloriesBurned", calories);
        }
        
        Double distance = getDouble(rawData, "distance");
        if (distance != null) {
            attributes.put("distanceMeters", distance);
        }

        Long durationSecs = getLong(rawData, "duration");
        
        // Handle location if present
        GeoLocation location = extractLocation(rawData);

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.ACTIVITY)
                .eventType("workout")
                .timestamp(startTime)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeWeight(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            return List.of();
        }

        Double weight = getDouble(rawData, "weight");
        if (weight == null) {
            weight = getDouble(rawData, "value");
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("weightKg", weight);
        
        String unit = getString(rawData, "unit");
        if (unit != null) {
            attributes.put("unit", unit);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.HEALTH)
                .eventType("weight")
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeBloodPressure(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        Long systolic = getLong(rawData, "systolic");
        Long diastolic = getLong(rawData, "diastolic");
        
        if (systolic != null) attributes.put("systolic", systolic);
        if (diastolic != null) attributes.put("diastolic", diastolic);

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.HEALTH)
                .eventType("blood_pressure")
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeGenericHealth(Map<String, Object> rawData, String sourceId, String recordType) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "startTime");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        Map<String, Object> attributes = new HashMap<>(rawData);
        attributes.remove("type");
        attributes.remove("timestamp");
        attributes.remove("startTime");

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.HEALTH)
                .eventType(recordType)
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private GeoLocation extractLocation(Map<String, Object> rawData) {
        Double lat = getDouble(rawData, "latitude");
        Double lon = getDouble(rawData, "longitude");
        
        if (lat == null || lon == null) {
            Map<String, Object> locationMap = getMap(rawData, "location");
            if (locationMap != null) {
                lat = getDouble(locationMap, "latitude");
                lon = getDouble(locationMap, "longitude");
            }
        }
        
        if (lat != null && lon != null) {
            Double accuracy = getDouble(rawData, "accuracy");
            return GeoLocation.exact(lat, lon, accuracy);
        }
        
        return null;
    }
}
