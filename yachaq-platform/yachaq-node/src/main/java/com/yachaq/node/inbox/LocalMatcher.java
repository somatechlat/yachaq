package com.yachaq.node.inbox;

import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.odx.ODXEntry;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Local Matcher for evaluating request eligibility using ODX only.
 * Requirement 313.2: Create Matcher.isEligible(request) method.
 * Requirement 313.2: Evaluate using ODX and local geo only.
 * Requirement 313.4: Support Mode A (broadcast) and Mode B (rotating geo topics).
 */
public class LocalMatcher {

    private final List<ODXEntry> localODX;
    private final String localGeoRegion;
    private final MatchingMode defaultMode;

    public LocalMatcher(List<ODXEntry> localODX, String localGeoRegion) {
        this(localODX, localGeoRegion, MatchingMode.BROADCAST);
    }

    public LocalMatcher(List<ODXEntry> localODX, String localGeoRegion, MatchingMode defaultMode) {
        this.localODX = Objects.requireNonNull(localODX, "Local ODX cannot be null");
        this.localGeoRegion = localGeoRegion;
        this.defaultMode = Objects.requireNonNull(defaultMode, "Default mode cannot be null");
    }

    /**
     * Checks if the local node is eligible for a request.
     * Requirement 313.2: Create Matcher.isEligible(request) method.
     */
    public EligibilityResult isEligible(DataRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        List<String> reasons = new ArrayList<>();
        Set<String> matchedLabels = new HashSet<>();
        Set<String> missingLabels = new HashSet<>();

        // Check if request is expired
        if (request.isExpired()) {
            reasons.add("Request has expired");
            return new EligibilityResult(false, 0.0, matchedLabels, missingLabels, reasons);
        }

        // Check geo constraint (local geo only - no precise location)
        if (request.geoConstraint() != null && !matchesGeoConstraint(request.geoConstraint())) {
            reasons.add("Geo constraint not satisfied");
            return new EligibilityResult(false, 0.0, matchedLabels, missingLabels, reasons);
        }

        // Check required labels against ODX
        for (String requiredLabel : request.requiredLabels()) {
            if (hasMatchingODXEntry(requiredLabel, request.timeWindow())) {
                matchedLabels.add(requiredLabel);
            } else {
                missingLabels.add(requiredLabel);
            }
        }

        // All required labels must be present
        if (!missingLabels.isEmpty()) {
            reasons.add("Missing required labels: " + missingLabels);
            return new EligibilityResult(false, 0.0, matchedLabels, missingLabels, reasons);
        }

        // Check optional labels for scoring
        for (String optionalLabel : request.optionalLabels()) {
            if (hasMatchingODXEntry(optionalLabel, request.timeWindow())) {
                matchedLabels.add(optionalLabel);
            }
        }

        // Calculate match score
        double score = calculateMatchScore(request, matchedLabels);

        // Apply matching mode rules
        MatchingMode mode = determineMatchingMode(request);
        if (!satisfiesMatchingMode(request, mode)) {
            reasons.add("Does not satisfy matching mode: " + mode);
            return new EligibilityResult(false, score, matchedLabels, missingLabels, reasons);
        }

        return new EligibilityResult(true, score, matchedLabels, missingLabels, reasons);
    }

    /**
     * Matches multiple requests and returns ranked results.
     */
    public List<MatchResult> matchRequests(List<DataRequest> requests) {
        return requests.stream()
                .map(r -> new MatchResult(r, isEligible(r)))
                .filter(m -> m.eligibility().isEligible())
                .sorted(Comparator.comparingDouble(m -> -m.eligibility().score()))
                .collect(Collectors.toList());
    }

    /**
     * Checks if local ODX has an entry matching the label.
     */
    private boolean hasMatchingODXEntry(String label, TimeWindow timeWindow) {
        return localODX.stream().anyMatch(entry -> {
            // Match facet key pattern
            if (!matchesFacetKey(entry.facetKey(), label)) {
                return false;
            }
            
            // Check time window if specified
            if (timeWindow != null) {
                // ODX entries have time buckets, not precise timestamps
                // This is a coarse match based on bucket overlap
                return timeBucketOverlaps(entry.timeBucket(), timeWindow);
            }
            
            return true;
        });
    }

    /**
     * Matches facet key against label pattern.
     */
    private boolean matchesFacetKey(String facetKey, String labelPattern) {
        // Support wildcard patterns like "domain:*" or exact matches
        if (labelPattern.endsWith("*")) {
            String prefix = labelPattern.substring(0, labelPattern.length() - 1);
            return facetKey.startsWith(prefix);
        }
        return facetKey.equals(labelPattern);
    }

    /**
     * Checks if time bucket overlaps with time window.
     */
    private boolean timeBucketOverlaps(String timeBucket, TimeWindow window) {
        // Parse time bucket format: YYYY-MM-DD, YYYY-Www, YYYY-MM
        try {
            if (timeBucket.contains("-W")) {
                // Week format: YYYY-Www
                int year = Integer.parseInt(timeBucket.substring(0, 4));
                int week = Integer.parseInt(timeBucket.substring(6, 8));
                // Approximate: check if year matches
                int windowYear = window.start().atZone(java.time.ZoneId.of("UTC")).getYear();
                return year == windowYear;
            } else if (timeBucket.length() == 10) {
                // Day format: YYYY-MM-DD
                java.time.LocalDate date = java.time.LocalDate.parse(timeBucket);
                Instant bucketStart = date.atStartOfDay(java.time.ZoneId.of("UTC")).toInstant();
                Instant bucketEnd = date.plusDays(1).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant();
                return !bucketEnd.isBefore(window.start()) && !bucketStart.isAfter(window.end());
            } else if (timeBucket.length() == 7) {
                // Month format: YYYY-MM
                java.time.YearMonth ym = java.time.YearMonth.parse(timeBucket);
                Instant bucketStart = ym.atDay(1).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant();
                Instant bucketEnd = ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("UTC")).toInstant();
                return !bucketEnd.isBefore(window.start()) && !bucketStart.isAfter(window.end());
            }
        } catch (Exception e) {
            // Invalid format, assume no match
        }
        return false;
    }

    /**
     * Checks if local geo matches the constraint.
     */
    private boolean matchesGeoConstraint(GeoConstraint constraint) {
        if (localGeoRegion == null || constraint.regionCode() == null) {
            return true; // No constraint or no local geo
        }
        
        // Match based on resolution
        return switch (constraint.resolution()) {
            case COUNTRY -> localGeoRegion.startsWith(constraint.regionCode().substring(0, 2));
            case REGION -> localGeoRegion.startsWith(constraint.regionCode());
            case CITY -> localGeoRegion.equals(constraint.regionCode());
        };
    }

    /**
     * Calculates match score based on matched labels.
     */
    private double calculateMatchScore(DataRequest request, Set<String> matchedLabels) {
        int totalLabels = request.requiredLabels().size() + request.optionalLabels().size();
        if (totalLabels == 0) return 1.0;
        
        // Required labels are weighted more heavily
        double requiredWeight = 0.7;
        double optionalWeight = 0.3;
        
        int matchedRequired = (int) matchedLabels.stream()
                .filter(request.requiredLabels()::contains)
                .count();
        int matchedOptional = (int) matchedLabels.stream()
                .filter(request.optionalLabels()::contains)
                .count();
        
        double requiredScore = request.requiredLabels().isEmpty() ? 1.0 :
                (double) matchedRequired / request.requiredLabels().size();
        double optionalScore = request.optionalLabels().isEmpty() ? 1.0 :
                (double) matchedOptional / request.optionalLabels().size();
        
        return requiredWeight * requiredScore + optionalWeight * optionalScore;
    }

    /**
     * Determines the matching mode for a request.
     */
    private MatchingMode determineMatchingMode(DataRequest request) {
        return switch (request.type()) {
            case BROADCAST -> MatchingMode.BROADCAST;
            case GEO_TOPIC -> MatchingMode.GEO_TOPIC;
            case TARGETED -> MatchingMode.TARGETED;
        };
    }

    /**
     * Checks if request satisfies matching mode requirements.
     * Requirement 313.4: Support Mode A (broadcast) and Mode B (rotating geo topics).
     */
    private boolean satisfiesMatchingMode(DataRequest request, MatchingMode mode) {
        return switch (mode) {
            case BROADCAST -> true; // Mode A: All eligible nodes
            case GEO_TOPIC -> {
                // Mode B: Must match rotating geo topic
                if (request.geoConstraint() == null) {
                    yield true;
                }
                yield matchesGeoConstraint(request.geoConstraint());
            }
            case TARGETED -> {
                // Targeted requires explicit node selection (handled elsewhere)
                yield true;
            }
        };
    }

    /**
     * Matching modes.
     */
    public enum MatchingMode {
        BROADCAST,      // Mode A: Broadcast to all eligible
        GEO_TOPIC,      // Mode B: Rotating geo topics
        TARGETED        // Direct targeting
    }

    /**
     * Result of eligibility check.
     */
    public record EligibilityResult(
            boolean isEligible,
            double score,
            Set<String> matchedLabels,
            Set<String> missingLabels,
            List<String> reasons
    ) {}

    /**
     * Match result pairing request with eligibility.
     */
    public record MatchResult(
            DataRequest request,
            EligibilityResult eligibility
    ) {}
}
