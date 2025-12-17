package com.yachaq.node.odx;

import com.yachaq.node.extractor.ExtractedFeatures;
import com.yachaq.node.extractor.ExtractedFeatures.*;
import com.yachaq.node.labeler.Label;
import com.yachaq.node.labeler.LabelNamespace;
import com.yachaq.node.labeler.LabelOntology;
import com.yachaq.node.labeler.LabelSet;
import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.odx.ODXEntry.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ODX Builder for creating privacy-safe index entries.
 * Requirement 311.1: Create ODXEntry with facet_key, time_bucket, geo_bucket, count, quality, privacy_floor.
 * Requirement 311.2: Ensure no raw payload, reversible text, or precise GPS.
 * Requirement 311.4, 311.6: Create ODX.query(criteria) and ODX.upsert(entries) methods.
 */
public class ODXBuilder {

    private static final Set<String> FORBIDDEN_PATTERNS = Set.of(
            "raw", "payload", "content", "text", "email", "phone",
            "address", "name", "ssn", "password", "secret", "token",
            "body", "message", "creditcard", "bankaccount"
    );

    private static final int DEFAULT_PRIVACY_FLOOR = 50;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    private final ZoneId zoneId;
    private final String ontologyVersion;

    public ODXBuilder() {
        this(DEFAULT_ZONE, LabelOntology.CURRENT_VERSION);
    }

    public ODXBuilder(ZoneId zoneId, String ontologyVersion) {
        this.zoneId = Objects.requireNonNull(zoneId, "Zone ID cannot be null");
        this.ontologyVersion = Objects.requireNonNull(ontologyVersion, "Ontology version cannot be null");
    }

    /**
     * Builds ODX entries from a label set and extracted features.
     * Requirement 311.1: Create ODXEntry with all required fields.
     */
    public List<ODXEntry> build(LabelSet labelSet, ExtractedFeatures features) {
        Objects.requireNonNull(labelSet, "Label set cannot be null");
        Objects.requireNonNull(features, "Features cannot be null");

        List<ODXEntry> entries = new ArrayList<>();
        String timeBucket = formatTimeBucket(features.timeBucket(), TimeResolution.DAY);
        Quality quality = mapQuality(features.qualityFlags());
        int privacyFloor = determinePrivacyFloor(labelSet);

        // Create entries for each label
        for (Label label : labelSet.labels()) {
            if (isSafeForODX(label)) {
                String facetKey = formatFacetKey(label);
                
                ODXEntry entry = ODXEntry.builder()
                        .generateId()
                        .facetKey(facetKey)
                        .timeBucket(timeBucket)
                        .count(1)
                        .quality(quality)
                        .privacyFloor(privacyFloor)
                        .geoResolution(GeoResolution.NONE)
                        .timeResolution(TimeResolution.DAY)
                        .ontologyVersion(ontologyVersion)
                        .build();
                
                entries.add(entry);
            }
        }

        return entries;
    }

    /**
     * Builds ODX entries from a canonical event with labels and features.
     */
    public List<ODXEntry> buildFromEvent(CanonicalEvent event, LabelSet labelSet, ExtractedFeatures features) {
        Objects.requireNonNull(event, "Event cannot be null");
        
        List<ODXEntry> entries = build(labelSet, features);
        
        // Add geo bucket if location is available and coarse enough
        if (event.location() != null && 
            event.location().resolution() != CanonicalEvent.GeoLocation.GeoResolution.NONE &&
            event.location().resolution() != CanonicalEvent.GeoLocation.GeoResolution.EXACT) {
            
            String geoBucket = formatGeoBucket(event.location());
            GeoResolution geoRes = mapGeoResolution(event.location().resolution());
            
            // Update entries with geo information
            entries = entries.stream()
                    .map(e -> ODXEntry.builder()
                            .id(e.id())
                            .facetKey(e.facetKey())
                            .timeBucket(e.timeBucket())
                            .geoBucket(geoBucket)
                            .count(e.count())
                            .quality(e.quality())
                            .privacyFloor(e.privacyFloor())
                            .geoResolution(geoRes)
                            .timeResolution(e.timeResolution())
                            .ontologyVersion(e.ontologyVersion())
                            .build())
                    .toList();
        }
        
        return entries;
    }

    /**
     * Validates that an ODX entry is safe (no raw payload, reversible text, or precise GPS).
     * Requirement 311.2: Ensure no raw payload, reversible text, or precise GPS.
     */
    public boolean validateSafety(ODXEntry entry) {
        // Check facet key for forbidden patterns
        String facetLower = entry.facetKey().toLowerCase();
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (facetLower.contains(pattern)) {
                return false;
            }
        }
        
        // Check geo resolution
        if (entry.geoResolution() == GeoResolution.EXACT) {
            return false;
        }
        
        // Check geo bucket for precise coordinates
        if (entry.geoBucket() != null && containsPreciseCoordinates(entry.geoBucket())) {
            return false;
        }
        
        // Validate facet key format
        if (!entry.facetKey().matches("^[a-z]+:[a-z_]+$")) {
            return false;
        }
        
        return entry.isPrivacySafe();
    }

    /**
     * Validates a batch of entries.
     */
    public ValidationResult validateBatch(List<ODXEntry> entries) {
        List<String> violations = new ArrayList<>();
        int safeCount = 0;
        int unsafeCount = 0;
        
        for (ODXEntry entry : entries) {
            if (validateSafety(entry)) {
                safeCount++;
            } else {
                unsafeCount++;
                violations.add("Entry " + entry.id() + " with facet " + entry.facetKey() + " is not safe");
            }
        }
        
        return new ValidationResult(safeCount, unsafeCount, violations);
    }

    /**
     * Upserts entries into an existing index (merges counts for same composite key).
     * Requirement 311.6: Create ODX.upsert(entries) method.
     */
    public List<ODXEntry> upsert(List<ODXEntry> existingEntries, List<ODXEntry> newEntries) {
        Map<String, ODXEntry> index = new HashMap<>();
        
        // Add existing entries
        for (ODXEntry entry : existingEntries) {
            index.put(entry.getCompositeKey(), entry);
        }
        
        // Upsert new entries
        for (ODXEntry newEntry : newEntries) {
            if (!validateSafety(newEntry)) {
                continue; // Skip unsafe entries
            }
            
            String key = newEntry.getCompositeKey();
            if (index.containsKey(key)) {
                // Merge: increment count
                ODXEntry existing = index.get(key);
                ODXEntry merged = ODXEntry.builder()
                        .id(existing.id())
                        .facetKey(existing.facetKey())
                        .timeBucket(existing.timeBucket())
                        .geoBucket(existing.geoBucket())
                        .count(existing.count() + newEntry.count())
                        .quality(mergeQuality(existing.quality(), newEntry.quality()))
                        .privacyFloor(Math.max(existing.privacyFloor(), newEntry.privacyFloor()))
                        .geoResolution(existing.geoResolution())
                        .timeResolution(existing.timeResolution())
                        .ontologyVersion(ontologyVersion)
                        .createdAt(existing.createdAt())
                        .updatedAt(Instant.now())
                        .build();
                index.put(key, merged);
            } else {
                index.put(key, newEntry);
            }
        }
        
        return new ArrayList<>(index.values());
    }

    /**
     * Queries entries matching criteria.
     * Requirement 311.4: Create ODX.query(criteria) method.
     */
    public List<ODXEntry> query(List<ODXEntry> entries, ODXCriteria criteria) {
        return entries.stream()
                .filter(e -> matchesCriteria(e, criteria))
                .collect(Collectors.toList());
    }

    private boolean matchesCriteria(ODXEntry entry, ODXCriteria criteria) {
        // Match facet key pattern
        if (criteria.facetKeyPattern() != null && 
            !entry.facetKey().matches(criteria.facetKeyPattern())) {
            return false;
        }
        
        // Match time bucket
        if (criteria.timeBucketStart() != null && 
            entry.timeBucket().compareTo(criteria.timeBucketStart()) < 0) {
            return false;
        }
        if (criteria.timeBucketEnd() != null && 
            entry.timeBucket().compareTo(criteria.timeBucketEnd()) > 0) {
            return false;
        }
        
        // Match quality
        if (criteria.quality() != null && entry.quality() != criteria.quality()) {
            return false;
        }
        
        // Match minimum count
        if (criteria.minCount() != null && entry.count() < criteria.minCount()) {
            return false;
        }
        
        return true;
    }

    // ==================== Helper Methods ====================

    private boolean isSafeForODX(Label label) {
        String key = label.toKey().toLowerCase();
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (key.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    private String formatFacetKey(Label label) {
        // Format: namespace:category (value is aggregated, not stored)
        return label.namespace().prefix() + ":" + label.category();
    }

    private String formatTimeBucket(TimeBucket timeBucket, TimeResolution resolution) {
        // Create a date from the time bucket components
        int year = LocalDate.now().getYear();
        int month = timeBucket.monthOfYear();
        int week = timeBucket.weekOfYear();
        int dayOfWeek = timeBucket.dayOfWeek();
        
        return switch (resolution) {
            case DAY -> {
                // Approximate day from week and day of week
                LocalDate date = LocalDate.of(year, 1, 1)
                        .with(WeekFields.ISO.weekOfWeekBasedYear(), week)
                        .with(WeekFields.ISO.dayOfWeek(), dayOfWeek);
                yield date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            case WEEK -> String.format("%d-W%02d", year, week);
            case MONTH -> String.format("%d-%02d", year, month);
            case QUARTER -> String.format("%d-Q%d", year, timeBucket.quarter());
            case YEAR -> String.valueOf(year);
        };
    }

    private String formatGeoBucket(CanonicalEvent.GeoLocation location) {
        // Round to coarse precision based on resolution
        return switch (location.resolution()) {
            case CITY -> String.format("%.1f,%.1f", location.latitude(), location.longitude());
            case REGION -> String.format("%.0f,%.0f", location.latitude(), location.longitude());
            case COUNTRY -> "country"; // Would need reverse geocoding
            default -> null;
        };
    }

    private GeoResolution mapGeoResolution(CanonicalEvent.GeoLocation.GeoResolution resolution) {
        return switch (resolution) {
            case EXACT -> GeoResolution.EXACT; // Will be rejected
            case CITY -> GeoResolution.CITY;
            case REGION -> GeoResolution.REGION;
            case COUNTRY -> GeoResolution.COUNTRY;
            case NONE -> GeoResolution.NONE;
        };
    }

    private Quality mapQuality(QualityFlags flags) {
        return switch (flags.dataSource()) {
            case CONNECTOR -> Quality.VERIFIED;
            case USER_IMPORT -> Quality.IMPORTED;
            case MANUAL_ENTRY -> Quality.MANUAL;
            case DERIVED -> Quality.DERIVED;
            case UNKNOWN -> Quality.IMPORTED;
        };
    }

    private Quality mergeQuality(Quality q1, Quality q2) {
        // Prefer verified quality
        if (q1 == Quality.VERIFIED || q2 == Quality.VERIFIED) {
            return Quality.VERIFIED;
        }
        return q1;
    }

    private int determinePrivacyFloor(LabelSet labelSet) {
        // Check for high sensitivity labels
        for (Label label : labelSet.labels()) {
            if (label.namespace() == LabelNamespace.PRIVACY && 
                label.category().equals("sensitivity")) {
                if ("high".equals(label.value()) || "critical".equals(label.value())) {
                    return 100; // Higher privacy floor for sensitive data
                }
            }
        }
        return DEFAULT_PRIVACY_FLOOR;
    }

    private boolean containsPreciseCoordinates(String geo) {
        // Check for coordinates with more than 1 decimal place
        return geo.matches(".*\\d+\\.\\d{2,}.*");
    }

    // ==================== Supporting Records ====================

    /**
     * Query criteria for ODX entries.
     */
    public record ODXCriteria(
            String facetKeyPattern,
            String timeBucketStart,
            String timeBucketEnd,
            Quality quality,
            Integer minCount
    ) {
        public static ODXCriteria all() {
            return new ODXCriteria(null, null, null, null, null);
        }

        public static ODXCriteria byFacet(String pattern) {
            return new ODXCriteria(pattern, null, null, null, null);
        }

        public static ODXCriteria byTimeRange(String start, String end) {
            return new ODXCriteria(null, start, end, null, null);
        }
    }

    /**
     * Validation result for batch operations.
     */
    public record ValidationResult(
            int safeCount,
            int unsafeCount,
            List<String> violations
    ) {
        public boolean isAllSafe() {
            return unsafeCount == 0;
        }
    }
}
