package com.yachaq.node.normalizer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical Event Model for normalized data representation.
 * Requirement 308.1: Create CanonicalEvent class with all required fields.
 * 
 * All data from various sources is normalized into this canonical format
 * to enable consistent processing, labeling, and querying.
 */
public record CanonicalEvent(
        String id,
        String sourceType,
        String sourceId,
        EventCategory category,
        String eventType,
        Instant timestamp,
        Instant ingestedAt,
        GeoLocation location,
        Duration duration,
        Map<String, Object> attributes,
        Map<String, String> metadata,
        String schemaVersion,
        String contentHash
) {
    
    public static final String CURRENT_SCHEMA_VERSION = "1.0.0";

    public CanonicalEvent {
        Objects.requireNonNull(id, "Event ID cannot be null");
        Objects.requireNonNull(sourceType, "Source type cannot be null");
        Objects.requireNonNull(sourceId, "Source ID cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(eventType, "Event type cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        
        if (ingestedAt == null) {
            ingestedAt = Instant.now();
        }
        if (schemaVersion == null) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Event categories for classification.
     */
    public enum EventCategory {
        ACTIVITY,       // Physical activities (steps, workouts, sleep)
        LOCATION,       // Location-based events
        COMMUNICATION,  // Messages, calls, emails
        MEDIA,          // Photos, videos, music
        TRANSACTION,    // Financial transactions
        SOCIAL,         // Social interactions
        HEALTH,         // Health metrics
        DEVICE,         // Device events
        TRAVEL,         // Travel and transportation
        CONTENT,        // Content consumption
        OTHER
    }

    /**
     * Geographic location with privacy-aware precision levels.
     */
    public record GeoLocation(
            double latitude,
            double longitude,
            Double altitude,
            Double accuracy,
            GeoResolution resolution
    ) {
        public enum GeoResolution {
            EXACT,      // Full precision
            CITY,       // City-level (~10km)
            REGION,     // Region/state level (~100km)
            COUNTRY,    // Country level
            NONE        // No location
        }

        public static GeoLocation none() {
            return new GeoLocation(0, 0, null, null, GeoResolution.NONE);
        }

        public static GeoLocation city(double lat, double lon) {
            // Round to ~10km precision
            return new GeoLocation(
                    Math.round(lat * 10) / 10.0,
                    Math.round(lon * 10) / 10.0,
                    null, null, GeoResolution.CITY
            );
        }

        public static GeoLocation exact(double lat, double lon, Double accuracy) {
            return new GeoLocation(lat, lon, null, accuracy, GeoResolution.EXACT);
        }
    }

    /**
     * Duration representation for time-based events.
     */
    public record Duration(
            long seconds,
            DurationUnit unit
    ) {
        public enum DurationUnit {
            SECONDS, MINUTES, HOURS, DAYS
        }

        public static Duration ofSeconds(long seconds) {
            return new Duration(seconds, DurationUnit.SECONDS);
        }

        public static Duration ofMinutes(long minutes) {
            return new Duration(minutes * 60, DurationUnit.MINUTES);
        }

        public static Duration ofHours(long hours) {
            return new Duration(hours * 3600, DurationUnit.HOURS);
        }

        public long toSeconds() {
            return seconds;
        }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String sourceType;
        private String sourceId;
        private EventCategory category;
        private String eventType;
        private Instant timestamp;
        private Instant ingestedAt;
        private GeoLocation location;
        private Duration duration;
        private Map<String, Object> attributes;
        private Map<String, String> metadata;
        private String schemaVersion;
        private String contentHash;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder generateId() {
            this.id = UUID.randomUUID().toString();
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder category(EventCategory category) {
            this.category = category;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder ingestedAt(Instant ingestedAt) {
            this.ingestedAt = ingestedAt;
            return this;
        }

        public Builder location(GeoLocation location) {
            this.location = location;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder schemaVersion(String schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder contentHash(String contentHash) {
            this.contentHash = contentHash;
            return this;
        }

        public CanonicalEvent build() {
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            return new CanonicalEvent(
                    id, sourceType, sourceId, category, eventType,
                    timestamp, ingestedAt, location, duration,
                    attributes, metadata, schemaVersion, contentHash
            );
        }
    }
}
