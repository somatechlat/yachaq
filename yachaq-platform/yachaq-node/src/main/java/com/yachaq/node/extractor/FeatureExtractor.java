package com.yachaq.node.extractor;

import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.extractor.ExtractedFeatures.*;

import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Feature Extractor for extracting privacy-safe features from canonical events.
 * Requirement 309.1: Extract time buckets, numeric features, cluster IDs.
 * Requirement 309.2, 309.3: Ensure no raw content appears in extracted features.
 * 
 * This service transforms canonical events into privacy-safe feature vectors
 * suitable for ODX (On-Device Label Index) storage.
 */
public class FeatureExtractor {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "content", "body", "text", "message", "rawData", "payload",
            "email", "phone", "name", "address", "ssn", "password",
            "creditCard", "bankAccount", "personalId"
    );

    private final ZoneId zoneId;
    private final ClusterIdGenerator clusterIdGenerator;

    public FeatureExtractor() {
        this(DEFAULT_ZONE, new DefaultClusterIdGenerator());
    }

    public FeatureExtractor(ZoneId zoneId) {
        this(zoneId, new DefaultClusterIdGenerator());
    }

    public FeatureExtractor(ZoneId zoneId, ClusterIdGenerator clusterIdGenerator) {
        this.zoneId = Objects.requireNonNull(zoneId, "Zone ID cannot be null");
        this.clusterIdGenerator = Objects.requireNonNull(clusterIdGenerator, "Cluster ID generator cannot be null");
    }

    /**
     * Extracts privacy-safe features from a canonical event.
     * 
     * @param event The canonical event to extract features from
     * @return Extracted features suitable for ODX
     */
    public ExtractedFeatures extract(CanonicalEvent event) {
        Objects.requireNonNull(event, "Event cannot be null");

        return ExtractedFeatures.builder()
                .eventId(event.id())
                .sourceType(event.sourceType())
                .timeBucket(extractTimeBucket(event.timestamp()))
                .numericFeatures(extractNumericFeatures(event))
                .clusterFeatures(extractClusterFeatures(event))
                .qualityFlags(extractQualityFlags(event))
                .additionalFeatures(extractAdditionalFeatures(event))
                .build();
    }

    /**
     * Extracts features from a batch of events.
     */
    public List<ExtractedFeatures> extractBatch(List<CanonicalEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .map(this::extract)
                .toList();
    }

    // ==================== Time Bucket Extraction (52.1) ====================

    /**
     * Extracts time bucket features from a timestamp.
     * Requirement 309.1: Extract hour-of-day, day-of-week, week index.
     */
    public TimeBucket extractTimeBucket(Instant timestamp) {
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");

        ZonedDateTime zdt = timestamp.atZone(zoneId);
        
        int hourOfDay = zdt.getHour();
        int dayOfWeek = zdt.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
        int weekOfYear = zdt.get(WeekFields.ISO.weekOfWeekBasedYear());
        int monthOfYear = zdt.getMonthValue();
        int quarter = (monthOfYear - 1) / 3 + 1;

        TimeOfDayBucket timeOfDayBucket = categorizeTimeOfDay(hourOfDay);
        DayTypeBucket dayTypeBucket = categorizeDayType(dayOfWeek);

        return new TimeBucket(
                hourOfDay,
                dayOfWeek,
                weekOfYear,
                monthOfYear,
                quarter,
                timeOfDayBucket,
                dayTypeBucket
        );
    }

    private TimeOfDayBucket categorizeTimeOfDay(int hour) {
        if (hour >= 5 && hour < 8) return TimeOfDayBucket.EARLY_MORNING;
        if (hour >= 8 && hour < 12) return TimeOfDayBucket.MORNING;
        if (hour >= 12 && hour < 17) return TimeOfDayBucket.AFTERNOON;
        if (hour >= 17 && hour < 21) return TimeOfDayBucket.EVENING;
        return TimeOfDayBucket.NIGHT;
    }

    private DayTypeBucket categorizeDayType(int dayOfWeek) {
        return (dayOfWeek >= 6) ? DayTypeBucket.WEEKEND : DayTypeBucket.WEEKDAY;
    }


    // ==================== Numeric Feature Extraction (52.2) ====================

    /**
     * Extracts numeric features from an event.
     * Requirement 309.1: Extract durations, counts, distance buckets.
     */
    public NumericFeatures extractNumericFeatures(CanonicalEvent event) {
        Long durationSeconds = extractDurationSeconds(event);
        Long count = extractCount(event);
        Double distanceMeters = extractDistanceMeters(event);

        return new NumericFeatures(
                categorizeDuration(durationSeconds),
                categorizeCount(count),
                categorizeDistance(distanceMeters),
                durationSeconds,
                count,
                distanceMeters
        );
    }

    private Long extractDurationSeconds(CanonicalEvent event) {
        if (event.duration() != null) {
            return event.duration().toSeconds();
        }
        
        // Try to extract from attributes
        Map<String, Object> attrs = event.attributes();
        if (attrs.containsKey("duration")) {
            Object val = attrs.get("duration");
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        }
        if (attrs.containsKey("durationSeconds")) {
            Object val = attrs.get("durationSeconds");
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
        }
        return null;
    }

    private Long extractCount(CanonicalEvent event) {
        Map<String, Object> attrs = event.attributes();
        
        // Check common count fields
        for (String field : List.of("count", "steps", "quantity", "amount", "total")) {
            if (attrs.containsKey(field)) {
                Object val = attrs.get(field);
                if (val instanceof Number) {
                    return ((Number) val).longValue();
                }
            }
        }
        return null;
    }

    private Double extractDistanceMeters(CanonicalEvent event) {
        Map<String, Object> attrs = event.attributes();
        
        // Check distance fields
        if (attrs.containsKey("distance")) {
            Object val = attrs.get("distance");
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
        }
        if (attrs.containsKey("distanceMeters")) {
            Object val = attrs.get("distanceMeters");
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
        }
        if (attrs.containsKey("distanceKm")) {
            Object val = attrs.get("distanceKm");
            if (val instanceof Number) {
                return ((Number) val).doubleValue() * 1000;
            }
        }
        return null;
    }

    /**
     * Categorizes duration into privacy-safe buckets.
     */
    public DurationBucket categorizeDuration(Long seconds) {
        if (seconds == null) return DurationBucket.NONE;
        if (seconds < 60) return DurationBucket.INSTANT;
        if (seconds < 300) return DurationBucket.VERY_SHORT;
        if (seconds < 900) return DurationBucket.SHORT;
        if (seconds < 1800) return DurationBucket.MEDIUM;
        if (seconds < 3600) return DurationBucket.LONG;
        if (seconds < 7200) return DurationBucket.VERY_LONG;
        return DurationBucket.EXTENDED;
    }

    /**
     * Categorizes count into privacy-safe buckets.
     */
    public CountBucket categorizeCount(Long count) {
        if (count == null || count <= 0) return CountBucket.NONE;
        if (count == 1) return CountBucket.SINGLE;
        if (count <= 5) return CountBucket.FEW;
        if (count <= 10) return CountBucket.SEVERAL;
        if (count <= 50) return CountBucket.MANY;
        if (count <= 100) return CountBucket.VERY_MANY;
        return CountBucket.NUMEROUS;
    }

    /**
     * Categorizes distance into privacy-safe buckets.
     */
    public DistanceBucket categorizeDistance(Double meters) {
        if (meters == null) return DistanceBucket.NONE;
        if (meters < 100) return DistanceBucket.NEARBY;
        if (meters < 1000) return DistanceBucket.SHORT;
        if (meters < 5000) return DistanceBucket.MEDIUM;
        if (meters < 20000) return DistanceBucket.LONG;
        if (meters < 100000) return DistanceBucket.VERY_LONG;
        return DistanceBucket.DISTANT;
    }

    // ==================== Cluster ID Extraction (52.3) ====================

    /**
     * Extracts cluster features from an event.
     * Requirement 309.1: Extract topic/mood/scene cluster IDs (not raw content).
     */
    public ClusterFeatures extractClusterFeatures(CanonicalEvent event) {
        String topicClusterId = clusterIdGenerator.generateTopicClusterId(event);
        String moodClusterId = clusterIdGenerator.generateMoodClusterId(event);
        String sceneClusterId = clusterIdGenerator.generateSceneClusterId(event);
        String activityClusterId = clusterIdGenerator.generateActivityClusterId(event);
        Set<String> tags = extractSafeTags(event);

        return new ClusterFeatures(
                topicClusterId,
                moodClusterId,
                sceneClusterId,
                activityClusterId,
                tags
        );
    }

    private Set<String> extractSafeTags(CanonicalEvent event) {
        Set<String> tags = new HashSet<>();
        
        // Add category-based tag
        if (event.category() != null) {
            tags.add("category:" + event.category().name().toLowerCase());
        }
        
        // Add event type tag
        if (event.eventType() != null) {
            tags.add("type:" + event.eventType().toLowerCase());
        }
        
        // Add source type tag
        if (event.sourceType() != null) {
            tags.add("source:" + event.sourceType().toLowerCase());
        }
        
        // Extract safe tags from attributes
        Map<String, Object> attrs = event.attributes();
        if (attrs.containsKey("tags") && attrs.get("tags") instanceof Collection<?> tagList) {
            for (Object tag : tagList) {
                if (tag instanceof String tagStr && isSafeTag(tagStr)) {
                    tags.add("user:" + tagStr.toLowerCase());
                }
            }
        }
        
        return tags;
    }

    private boolean isSafeTag(String tag) {
        // Tags must be short and not contain PII patterns
        if (tag == null || tag.length() > 50) return false;
        String lower = tag.toLowerCase();
        return !lower.contains("@") && 
               !lower.matches(".*\\d{4,}.*") && // No long numbers
               !FORBIDDEN_FIELDS.stream().anyMatch(lower::contains);
    }


    // ==================== Quality Flag Extraction (52.4) ====================

    /**
     * Extracts quality flags from an event.
     * Requirement 309.5: Distinguish verified sources from user imports.
     */
    public QualityFlags extractQualityFlags(CanonicalEvent event) {
        DataSource dataSource = determineDataSource(event);
        VerificationLevel verificationLevel = determineVerificationLevel(event, dataSource);
        boolean isComplete = checkCompleteness(event);
        boolean hasTimestamp = event.timestamp() != null;
        boolean hasLocation = event.location() != null && 
                event.location().resolution() != CanonicalEvent.GeoLocation.GeoResolution.NONE;
        double confidenceScore = calculateConfidenceScore(event, dataSource, isComplete);

        return new QualityFlags(
                dataSource,
                verificationLevel,
                isComplete,
                hasTimestamp,
                hasLocation,
                confidenceScore
        );
    }

    private DataSource determineDataSource(CanonicalEvent event) {
        Map<String, String> metadata = event.metadata();
        
        if (metadata.containsKey("source")) {
            String source = metadata.get("source").toLowerCase();
            if (source.contains("import") || source.contains("upload")) {
                return DataSource.USER_IMPORT;
            }
            if (source.contains("manual") || source.contains("entry")) {
                return DataSource.MANUAL_ENTRY;
            }
            if (source.contains("derived") || source.contains("computed")) {
                return DataSource.DERIVED;
            }
        }
        
        // Check source type for known connectors
        String sourceType = event.sourceType();
        if (sourceType != null) {
            String lower = sourceType.toLowerCase();
            if (lower.contains("healthkit") || lower.contains("health_connect") ||
                lower.contains("spotify") || lower.contains("strava") ||
                lower.contains("google") || lower.contains("apple")) {
                return DataSource.CONNECTOR;
            }
            if (lower.contains("import") || lower.contains("takeout")) {
                return DataSource.USER_IMPORT;
            }
        }
        
        return DataSource.UNKNOWN;
    }

    private VerificationLevel determineVerificationLevel(CanonicalEvent event, DataSource dataSource) {
        // Connectors with OAuth are verified
        if (dataSource == DataSource.CONNECTOR) {
            return VerificationLevel.VERIFIED;
        }
        
        // User imports have some verification (file integrity)
        if (dataSource == DataSource.USER_IMPORT) {
            // Check if content hash exists
            if (event.contentHash() != null && !event.contentHash().isEmpty()) {
                return VerificationLevel.PARTIALLY_VERIFIED;
            }
            return VerificationLevel.UNVERIFIED;
        }
        
        // Manual entries are unverified
        if (dataSource == DataSource.MANUAL_ENTRY) {
            return VerificationLevel.UNVERIFIED;
        }
        
        // Derived data inherits verification from source
        if (dataSource == DataSource.DERIVED) {
            return VerificationLevel.PARTIALLY_VERIFIED;
        }
        
        return VerificationLevel.UNVERIFIED;
    }

    private boolean checkCompleteness(CanonicalEvent event) {
        // Check required fields
        if (event.id() == null || event.sourceType() == null || 
            event.eventType() == null || event.timestamp() == null) {
            return false;
        }
        
        // Check for meaningful content
        Map<String, Object> attrs = event.attributes();
        return attrs != null && !attrs.isEmpty();
    }

    private double calculateConfidenceScore(CanonicalEvent event, DataSource dataSource, boolean isComplete) {
        double score = 0.0;
        
        // Base score from data source
        score += switch (dataSource) {
            case CONNECTOR -> 0.4;
            case USER_IMPORT -> 0.2;
            case DERIVED -> 0.3;
            case MANUAL_ENTRY -> 0.1;
            case UNKNOWN -> 0.0;
        };
        
        // Completeness bonus
        if (isComplete) score += 0.2;
        
        // Timestamp bonus
        if (event.timestamp() != null) score += 0.1;
        
        // Content hash bonus (integrity)
        if (event.contentHash() != null && !event.contentHash().isEmpty()) {
            score += 0.2;
        }
        
        // Schema version bonus
        if (CanonicalEvent.CURRENT_SCHEMA_VERSION.equals(event.schemaVersion())) {
            score += 0.1;
        }
        
        return Math.min(1.0, score);
    }

    // ==================== Additional Features ====================

    private Map<String, Object> extractAdditionalFeatures(CanonicalEvent event) {
        Map<String, Object> features = new HashMap<>();
        
        // Add location-based features if available
        if (event.location() != null && 
            event.location().resolution() != CanonicalEvent.GeoLocation.GeoResolution.NONE) {
            features.put("hasLocation", true);
            features.put("locationResolution", event.location().resolution().name());
        } else {
            features.put("hasLocation", false);
        }
        
        // Add schema version
        features.put("schemaVersion", event.schemaVersion());
        
        // Add category
        if (event.category() != null) {
            features.put("category", event.category().name());
        }
        
        return features;
    }

    // ==================== Privacy Safety Validation ====================

    /**
     * Validates that extracted features contain no raw content.
     * Requirement 309.2, 309.3, 309.6: Ensure no raw content appears in ODX.
     */
    public boolean validateNoLeakage(ExtractedFeatures features) {
        // Check additional features for forbidden content
        for (Map.Entry<String, Object> entry : features.additionalFeatures().entrySet()) {
            if (FORBIDDEN_FIELDS.contains(entry.getKey().toLowerCase())) {
                return false;
            }
            if (entry.getValue() instanceof String str && containsPII(str)) {
                return false;
            }
        }
        
        // Check cluster tags
        for (String tag : features.clusterFeatures().tags()) {
            if (containsPII(tag)) {
                return false;
            }
        }
        
        return true;
    }

    private boolean containsPII(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        
        // Check for email patterns
        if (lower.matches(".*[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}.*")) {
            return true;
        }
        
        // Check for phone patterns (10+ digits)
        if (lower.matches(".*\\d{10,}.*")) {
            return true;
        }
        
        // Check for SSN patterns
        if (lower.matches(".*\\d{3}-\\d{2}-\\d{4}.*")) {
            return true;
        }
        
        // Check for credit card patterns
        if (lower.matches(".*\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}.*")) {
            return true;
        }
        
        return false;
    }

    /**
     * Interface for cluster ID generation.
     */
    public interface ClusterIdGenerator {
        String generateTopicClusterId(CanonicalEvent event);
        String generateMoodClusterId(CanonicalEvent event);
        String generateSceneClusterId(CanonicalEvent event);
        String generateActivityClusterId(CanonicalEvent event);
    }

    /**
     * Default cluster ID generator using event metadata.
     */
    public static class DefaultClusterIdGenerator implements ClusterIdGenerator {
        
        @Override
        public String generateTopicClusterId(CanonicalEvent event) {
            // Generate topic cluster from category and event type
            if (event.category() == null) return null;
            return "topic:" + event.category().name().toLowerCase();
        }

        @Override
        public String generateMoodClusterId(CanonicalEvent event) {
            // Mood clustering would require ML - return null for basic implementation
            Map<String, Object> attrs = event.attributes();
            if (attrs.containsKey("mood")) {
                Object mood = attrs.get("mood");
                if (mood instanceof String moodStr) {
                    return "mood:" + moodStr.toLowerCase();
                }
            }
            return null;
        }

        @Override
        public String generateSceneClusterId(CanonicalEvent event) {
            // Scene clustering for media events
            if (event.category() == CanonicalEvent.EventCategory.MEDIA) {
                Map<String, Object> attrs = event.attributes();
                if (attrs.containsKey("scene")) {
                    Object scene = attrs.get("scene");
                    if (scene instanceof String sceneStr) {
                        return "scene:" + sceneStr.toLowerCase();
                    }
                }
            }
            return null;
        }

        @Override
        public String generateActivityClusterId(CanonicalEvent event) {
            // Activity clustering from event type
            if (event.category() == CanonicalEvent.EventCategory.ACTIVITY ||
                event.category() == CanonicalEvent.EventCategory.HEALTH) {
                return "activity:" + event.eventType().toLowerCase();
            }
            return null;
        }
    }
}
