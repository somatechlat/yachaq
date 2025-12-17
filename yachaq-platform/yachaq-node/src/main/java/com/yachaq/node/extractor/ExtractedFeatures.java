package com.yachaq.node.extractor;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Container for extracted features from canonical events.
 * Requirement 309.1: Extract privacy-safe features for ODX.
 * 
 * Features are designed to be privacy-safe - they contain only
 * bucketed/aggregated values, never raw content.
 */
public record ExtractedFeatures(
        String eventId,
        String sourceType,
        TimeBucket timeBucket,
        NumericFeatures numericFeatures,
        ClusterFeatures clusterFeatures,
        QualityFlags qualityFlags,
        Map<String, Object> additionalFeatures
) {
    
    public ExtractedFeatures {
        Objects.requireNonNull(eventId, "Event ID cannot be null");
        Objects.requireNonNull(sourceType, "Source type cannot be null");
        Objects.requireNonNull(timeBucket, "Time bucket cannot be null");
        Objects.requireNonNull(numericFeatures, "Numeric features cannot be null");
        Objects.requireNonNull(clusterFeatures, "Cluster features cannot be null");
        Objects.requireNonNull(qualityFlags, "Quality flags cannot be null");
        additionalFeatures = additionalFeatures != null ? Map.copyOf(additionalFeatures) : Map.of();
    }

    /**
     * Time bucket features extracted from timestamps.
     * Requirement 309.1: Extract hour-of-day, day-of-week, week index.
     */
    public record TimeBucket(
            int hourOfDay,        // 0-23
            int dayOfWeek,        // 1-7 (Monday=1, Sunday=7)
            int weekOfYear,       // 1-53
            int monthOfYear,      // 1-12
            int quarter,          // 1-4
            TimeOfDayBucket timeOfDayBucket,
            DayTypeBucket dayTypeBucket
    ) {
        public TimeBucket {
            if (hourOfDay < 0 || hourOfDay > 23) {
                throw new IllegalArgumentException("Hour must be 0-23");
            }
            if (dayOfWeek < 1 || dayOfWeek > 7) {
                throw new IllegalArgumentException("Day of week must be 1-7");
            }
            if (weekOfYear < 1 || weekOfYear > 53) {
                throw new IllegalArgumentException("Week of year must be 1-53");
            }
            if (monthOfYear < 1 || monthOfYear > 12) {
                throw new IllegalArgumentException("Month must be 1-12");
            }
            if (quarter < 1 || quarter > 4) {
                throw new IllegalArgumentException("Quarter must be 1-4");
            }
        }
    }


    /**
     * Coarse time-of-day buckets for privacy.
     */
    public enum TimeOfDayBucket {
        EARLY_MORNING,  // 5-8
        MORNING,        // 8-12
        AFTERNOON,      // 12-17
        EVENING,        // 17-21
        NIGHT           // 21-5
    }

    /**
     * Day type classification.
     */
    public enum DayTypeBucket {
        WEEKDAY,
        WEEKEND
    }

    /**
     * Numeric features extracted from event data.
     * Requirement 309.1: Extract durations, counts, distance buckets.
     */
    public record NumericFeatures(
            DurationBucket durationBucket,
            CountBucket countBucket,
            DistanceBucket distanceBucket,
            Long rawDurationSeconds,
            Long rawCount,
            Double rawDistanceMeters
    ) {
        public NumericFeatures {
            // Raw values are optional - buckets are required for privacy
            Objects.requireNonNull(durationBucket, "Duration bucket cannot be null");
            Objects.requireNonNull(countBucket, "Count bucket cannot be null");
            Objects.requireNonNull(distanceBucket, "Distance bucket cannot be null");
        }

        public static NumericFeatures empty() {
            return new NumericFeatures(
                    DurationBucket.NONE,
                    CountBucket.NONE,
                    DistanceBucket.NONE,
                    null, null, null
            );
        }
    }

    /**
     * Duration buckets for privacy-safe representation.
     */
    public enum DurationBucket {
        NONE,           // No duration
        INSTANT,        // < 1 minute
        VERY_SHORT,     // 1-5 minutes
        SHORT,          // 5-15 minutes
        MEDIUM,         // 15-30 minutes
        LONG,           // 30-60 minutes
        VERY_LONG,      // 1-2 hours
        EXTENDED        // > 2 hours
    }

    /**
     * Count buckets for privacy-safe representation.
     */
    public enum CountBucket {
        NONE,           // No count
        SINGLE,         // 1
        FEW,            // 2-5
        SEVERAL,        // 6-10
        MANY,           // 11-50
        VERY_MANY,      // 51-100
        NUMEROUS        // > 100
    }

    /**
     * Distance buckets for privacy-safe representation.
     */
    public enum DistanceBucket {
        NONE,           // No distance
        NEARBY,         // < 100m
        SHORT,          // 100m - 1km
        MEDIUM,         // 1-5km
        LONG,           // 5-20km
        VERY_LONG,      // 20-100km
        DISTANT         // > 100km
    }

    /**
     * Cluster features for content classification.
     * Requirement 309.1: Extract topic/mood/scene cluster IDs (not raw content).
     */
    public record ClusterFeatures(
            String topicClusterId,
            String moodClusterId,
            String sceneClusterId,
            String activityClusterId,
            Set<String> tags
    ) {
        public ClusterFeatures {
            tags = tags != null ? Set.copyOf(tags) : Set.of();
        }

        public static ClusterFeatures empty() {
            return new ClusterFeatures(null, null, null, null, Set.of());
        }
    }

    /**
     * Quality flags for data provenance.
     * Requirement 309.5: Distinguish verified sources from user imports.
     */
    public record QualityFlags(
            DataSource dataSource,
            VerificationLevel verificationLevel,
            boolean isComplete,
            boolean hasTimestamp,
            boolean hasLocation,
            double confidenceScore
    ) {
        public QualityFlags {
            Objects.requireNonNull(dataSource, "Data source cannot be null");
            Objects.requireNonNull(verificationLevel, "Verification level cannot be null");
            if (confidenceScore < 0.0 || confidenceScore > 1.0) {
                throw new IllegalArgumentException("Confidence score must be 0.0-1.0");
            }
        }

        public static QualityFlags verified() {
            return new QualityFlags(
                    DataSource.CONNECTOR,
                    VerificationLevel.VERIFIED,
                    true, true, false, 1.0
            );
        }

        public static QualityFlags userImport() {
            return new QualityFlags(
                    DataSource.USER_IMPORT,
                    VerificationLevel.UNVERIFIED,
                    true, true, false, 0.5
            );
        }
    }

    /**
     * Data source classification.
     */
    public enum DataSource {
        CONNECTOR,      // From verified OAuth connector
        USER_IMPORT,    // User-uploaded file
        MANUAL_ENTRY,   // Manually entered by user
        DERIVED,        // Computed from other data
        UNKNOWN
    }

    /**
     * Verification level for data quality.
     */
    public enum VerificationLevel {
        VERIFIED,       // From trusted source with attestation
        PARTIALLY_VERIFIED,  // Some verification available
        UNVERIFIED,     // No verification
        SUSPICIOUS      // Flagged for review
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId;
        private String sourceType;
        private TimeBucket timeBucket;
        private NumericFeatures numericFeatures = NumericFeatures.empty();
        private ClusterFeatures clusterFeatures = ClusterFeatures.empty();
        private QualityFlags qualityFlags;
        private Map<String, Object> additionalFeatures;

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder timeBucket(TimeBucket timeBucket) {
            this.timeBucket = timeBucket;
            return this;
        }

        public Builder numericFeatures(NumericFeatures numericFeatures) {
            this.numericFeatures = numericFeatures;
            return this;
        }

        public Builder clusterFeatures(ClusterFeatures clusterFeatures) {
            this.clusterFeatures = clusterFeatures;
            return this;
        }

        public Builder qualityFlags(QualityFlags qualityFlags) {
            this.qualityFlags = qualityFlags;
            return this;
        }

        public Builder additionalFeatures(Map<String, Object> additionalFeatures) {
            this.additionalFeatures = additionalFeatures;
            return this;
        }

        public ExtractedFeatures build() {
            return new ExtractedFeatures(
                    eventId, sourceType, timeBucket, numericFeatures,
                    clusterFeatures, qualityFlags, additionalFeatures
            );
        }
    }
}
