package com.yachaq.node.odx;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * On-Device Label Index Entry for the Phone-as-Node architecture.
 * Requirement 311.1: Create ODXEntry with facet_key, time_bucket, geo_bucket, count, quality, privacy_floor.
 * Requirement 311.2: Ensure no raw payload, reversible text, or precise GPS.
 * 
 * This is the node-side representation of ODX entries, designed for
 * privacy-safe discovery without exposing raw data.
 */
public record ODXEntry(
        String id,
        String facetKey,
        String timeBucket,
        String geoBucket,
        int count,
        Quality quality,
        int privacyFloor,
        GeoResolution geoResolution,
        TimeResolution timeResolution,
        String ontologyVersion,
        Instant createdAt,
        Instant updatedAt,
        String signature
) {
    
    public ODXEntry {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(facetKey, "Facet key cannot be null");
        Objects.requireNonNull(timeBucket, "Time bucket cannot be null");
        Objects.requireNonNull(quality, "Quality cannot be null");
        Objects.requireNonNull(geoResolution, "Geo resolution cannot be null");
        Objects.requireNonNull(timeResolution, "Time resolution cannot be null");
        Objects.requireNonNull(ontologyVersion, "Ontology version cannot be null");
        
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        if (privacyFloor < 1) {
            throw new IllegalArgumentException("Privacy floor must be at least 1");
        }
        
        // Validate facet key format
        validateFacetKey(facetKey);
        
        // Validate time bucket format
        validateTimeBucket(timeBucket);
        
        // Validate geo resolution
        if (geoResolution == GeoResolution.EXACT) {
            throw new ODXSafetyException("Exact geo resolution not allowed in ODX");
        }
        
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    /**
     * Validates facet key follows namespace:category format and contains no PII.
     */
    private static void validateFacetKey(String facetKey) {
        // Must follow namespace:category pattern
        if (!facetKey.matches("^[a-z]+:[a-z_]+$")) {
            throw new ODXSafetyException(
                    "Facet key must follow namespace:category pattern: " + facetKey);
        }
        
        // Check for forbidden patterns indicating raw data
        String lower = facetKey.toLowerCase();
        if (containsForbiddenPattern(lower)) {
            throw new ODXSafetyException(
                    "Facet key contains forbidden raw data indicator: " + facetKey);
        }
    }

    /**
     * Validates time bucket format.
     */
    private static void validateTimeBucket(String timeBucket) {
        // Valid formats: YYYY-Www (week), YYYY-MM (month), YYYY-MM-DD (day)
        if (!timeBucket.matches("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)$")) {
            throw new ODXSafetyException(
                    "Invalid time bucket format: " + timeBucket);
        }
    }

    /**
     * Checks for forbidden patterns that indicate PII or raw data.
     */
    private static boolean containsForbiddenPattern(String value) {
        return value.contains("raw") || value.contains("payload") ||
               value.contains("content") || value.contains("text") ||
               value.contains("email") || value.contains("phone") ||
               value.contains("address") || value.contains("name") ||
               value.contains("ssn") || value.contains("password") ||
               value.contains("secret") || value.contains("token") ||
               value.contains("body") || value.contains("message");
    }

    /**
     * Checks if this entry is privacy-safe.
     * Requirement 311.2: Ensure no raw payload, reversible text, or precise GPS.
     */
    public boolean isPrivacySafe() {
        return geoResolution != GeoResolution.EXACT &&
               facetKey.matches("^[a-z]+:[a-z_]+$") &&
               !containsForbiddenPattern(facetKey.toLowerCase()) &&
               (geoBucket == null || !containsPreciseGPS(geoBucket));
    }

    private boolean containsPreciseGPS(String geo) {
        // Check for precise coordinates (more than 2 decimal places)
        return geo.matches(".*\\d+\\.\\d{3,}.*");
    }

    /**
     * Returns the composite key for deduplication.
     */
    public String getCompositeKey() {
        return facetKey + "|" + timeBucket + "|" + (geoBucket != null ? geoBucket : "");
    }

    /**
     * Data quality indicator.
     */
    public enum Quality {
        VERIFIED,       // From verified OAuth connector
        IMPORTED,       // User-imported data
        MANUAL,         // Manually entered
        DERIVED         // Computed from other data
    }

    /**
     * Geographic resolution levels.
     */
    public enum GeoResolution {
        NONE,           // No location
        COUNTRY,        // Country level
        REGION,         // Region/state level
        CITY,           // City level (~10km)
        EXACT           // Not allowed in ODX
    }

    /**
     * Time resolution levels.
     */
    public enum TimeResolution {
        DAY,
        WEEK,
        MONTH,
        QUARTER,
        YEAR
    }

    /**
     * Exception for ODX safety violations.
     */
    public static class ODXSafetyException extends RuntimeException {
        public ODXSafetyException(String message) {
            super(message);
        }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String facetKey;
        private String timeBucket;
        private String geoBucket;
        private int count = 1;
        private Quality quality = Quality.VERIFIED;
        private int privacyFloor = 50;
        private GeoResolution geoResolution = GeoResolution.NONE;
        private TimeResolution timeResolution = TimeResolution.DAY;
        private String ontologyVersion = "1.0.0";
        private Instant createdAt;
        private Instant updatedAt;
        private String signature;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder generateId() {
            this.id = UUID.randomUUID().toString();
            return this;
        }

        public Builder facetKey(String facetKey) {
            this.facetKey = facetKey;
            return this;
        }

        public Builder timeBucket(String timeBucket) {
            this.timeBucket = timeBucket;
            return this;
        }

        public Builder geoBucket(String geoBucket) {
            this.geoBucket = geoBucket;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder quality(Quality quality) {
            this.quality = quality;
            return this;
        }

        public Builder privacyFloor(int privacyFloor) {
            this.privacyFloor = privacyFloor;
            return this;
        }

        public Builder geoResolution(GeoResolution geoResolution) {
            this.geoResolution = geoResolution;
            return this;
        }

        public Builder timeResolution(TimeResolution timeResolution) {
            this.timeResolution = timeResolution;
            return this;
        }

        public Builder ontologyVersion(String ontologyVersion) {
            this.ontologyVersion = ontologyVersion;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public ODXEntry build() {
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            return new ODXEntry(
                    id, facetKey, timeBucket, geoBucket, count, quality,
                    privacyFloor, geoResolution, timeResolution, ontologyVersion,
                    createdAt, updatedAt, signature
            );
        }
    }
}
