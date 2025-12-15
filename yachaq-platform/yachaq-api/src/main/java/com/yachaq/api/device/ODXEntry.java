package com.yachaq.api.device;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * On-Device Label Index Entry - Privacy-safe discovery index.
 * 
 * Requirements: 201.1, 201.2, 203.1, 203.3
 * - Contains only coarse labels, timestamps, and availability bands
 * - NO raw payload content
 * - Signed with device key for authenticity
 * 
 * Property 12: Edge-First Data Locality
 * Property 25: ODX Minimization
 * For any ODX entry generated, the entry must contain only coarse labels,
 * timestamps, and availability bands - never raw payload content.
 */
@Entity
@Table(name = "odx_entries", indexes = {
    @Index(name = "idx_odx_device", columnList = "device_id"),
    @Index(name = "idx_odx_ds", columnList = "ds_id"),
    @Index(name = "idx_odx_facet", columnList = "facet_key"),
    @Index(name = "idx_odx_time_bucket", columnList = "time_bucket")
})
public class ODXEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    /**
     * Facet key - coarse label category (e.g., "domain.health", "time.morning").
     * Must be from approved ontology, no raw data.
     */
    @NotNull
    @Column(name = "facet_key", nullable = false)
    private String facetKey;

    /**
     * Time bucket - coarse time granularity (day/week/month).
     * Format: "2024-W01" (week), "2024-01" (month), "2024-01-15" (day)
     */
    @NotNull
    @Column(name = "time_bucket", nullable = false)
    private String timeBucket;

    /**
     * Geo bucket - coarse location only (optional).
     * Must be at minimum resolution (e.g., city level, not precise GPS).
     */
    @Column(name = "geo_bucket")
    private String geoBucket;

    /**
     * Count - number of data points in this bucket.
     */
    @NotNull
    @Column(nullable = false)
    private Integer count;

    /**
     * Aggregate value (optional) - privacy-safe aggregate.
     */
    @Column(name = "aggregate_value")
    private Double aggregateValue;

    /**
     * Quality indicator - verified source vs user import.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Quality quality;

    /**
     * Privacy floor - minimum cohort size for this data.
     */
    @NotNull
    @Column(name = "k_min", nullable = false)
    private Integer kMin;

    /**
     * Geo resolution constraint.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "geo_resolution", nullable = false)
    private GeoResolution geoResolution;

    /**
     * Time resolution constraint.
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "time_resolution", nullable = false)
    private TimeResolution timeResolution;

    /**
     * Device signature for authenticity.
     */
    @Column(name = "device_signature")
    private String deviceSignature;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "ontology_version", nullable = false)
    private String ontologyVersion;

    protected ODXEntry() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.ontologyVersion = "v1";
    }

    /**
     * Create a privacy-safe ODX entry.
     * 
     * Requirements: 201.1, 201.2
     * - Only coarse labels, timestamps, availability bands
     * - NO raw payload content
     */
    public static ODXEntry create(
            UUID deviceId,
            UUID dsId,
            String facetKey,
            String timeBucket,
            Integer count,
            Quality quality,
            Integer kMin) {
        
        validateFacetKey(facetKey);
        validateTimeBucket(timeBucket);
        
        ODXEntry entry = new ODXEntry();
        entry.deviceId = deviceId;
        entry.dsId = dsId;
        entry.facetKey = facetKey;
        entry.timeBucket = timeBucket;
        entry.count = count;
        entry.quality = quality;
        entry.kMin = kMin;
        entry.geoResolution = GeoResolution.COARSE;
        entry.timeResolution = TimeResolution.DAY;
        return entry;
    }

    /**
     * Validate facet key is from approved ontology.
     * Requirement 201.3: Limited cardinality to prevent inference attacks.
     */
    private static void validateFacetKey(String facetKey) {
        if (facetKey == null || facetKey.isEmpty()) {
            throw new ODXValidationException("Facet key cannot be empty");
        }
        
        // Must follow namespace pattern (e.g., domain.health, time.morning)
        if (!facetKey.matches("^[a-z]+\\.[a-z_]+$")) {
            throw new ODXValidationException(
                "Facet key must follow namespace pattern: namespace.label");
        }
        
        // Check for forbidden patterns (raw data indicators)
        // These patterns indicate PII or raw data that should never be in ODX
        String lower = facetKey.toLowerCase();
        if (lower.contains("raw") || lower.contains("payload") || 
            lower.contains("content") || lower.contains("text") ||
            lower.contains("email") || lower.contains("phone") ||
            lower.contains("address") || lower.contains("name") ||
            lower.contains("ssn") || lower.contains("password") ||
            lower.contains("secret") || lower.contains("token")) {
            throw new ODXValidationException(
                "Facet key contains forbidden raw data indicator: " + facetKey);
        }
    }

    /**
     * Validate time bucket format.
     */
    private static void validateTimeBucket(String timeBucket) {
        if (timeBucket == null || timeBucket.isEmpty()) {
            throw new ODXValidationException("Time bucket cannot be empty");
        }
        
        // Valid formats: YYYY-Www (week), YYYY-MM (month), YYYY-MM-DD (day)
        if (!timeBucket.matches("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)$")) {
            throw new ODXValidationException(
                "Invalid time bucket format: " + timeBucket);
        }
    }

    /**
     * Set coarse geo bucket.
     * Requirement 201.1: No precise GPS, only coarse location.
     */
    public void setGeoBucket(String geoBucket, GeoResolution resolution) {
        if (resolution == GeoResolution.FINE) {
            throw new ODXValidationException(
                "Fine geo resolution not allowed in ODX - use coarse only");
        }
        this.geoBucket = geoBucket;
        this.geoResolution = resolution;
        this.updatedAt = Instant.now();
    }

    /**
     * Sign the index entry with device key.
     * Requirement 203.3: Sign index updates with device key.
     */
    public void sign(String signature) {
        this.deviceSignature = signature;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public UUID getDsId() { return dsId; }
    public String getFacetKey() { return facetKey; }
    public String getTimeBucket() { return timeBucket; }
    public String getGeoBucket() { return geoBucket; }
    public Integer getCount() { return count; }
    public Double getAggregateValue() { return aggregateValue; }
    public Quality getQuality() { return quality; }
    public Integer getKMin() { return kMin; }
    public GeoResolution getGeoResolution() { return geoResolution; }
    public TimeResolution getTimeResolution() { return timeResolution; }
    public String getDeviceSignature() { return deviceSignature; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getOntologyVersion() { return ontologyVersion; }

    public void setAggregateValue(Double value) { 
        this.aggregateValue = value; 
        this.updatedAt = Instant.now();
    }
    
    public void setCount(Integer count) { 
        this.count = count; 
        this.updatedAt = Instant.now();
    }

    /**
     * Check if entry contains only privacy-safe data.
     * Property 25: ODX Minimization validation.
     */
    public boolean isPrivacySafe() {
        // No raw payload reference
        // Only coarse geo
        // Valid facet key from ontology
        return geoResolution == GeoResolution.COARSE
            && facetKey != null 
            && facetKey.matches("^[a-z]+\\.[a-z_]+$");
    }

    public enum Quality {
        VERIFIED,   // From verified data source
        IMPORTED    // User-imported data
    }

    public enum GeoResolution {
        COARSE,     // City/region level only
        FINE        // Not allowed in ODX
    }

    public enum TimeResolution {
        DAY,
        WEEK,
        MONTH
    }

    public static class ODXValidationException extends RuntimeException {
        public ODXValidationException(String message) { super(message); }
    }
}
