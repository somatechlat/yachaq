package com.yachaq.node.onboarding;

import com.yachaq.node.odx.ODXBuilder;
import com.yachaq.node.odx.ODXEntry;
import com.yachaq.node.odx.ODXEntry.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ODX Index Inspector for Provider App.
 * Shows labels with counts/buckets only - NEVER raw data.
 * 
 * Validates: Requirements 342.1, 342.2, 342.3, 342.5
 * 
 * SECURITY: This class is designed to NEVER expose raw payload content.
 * All data shown is aggregated counts and coarse labels only.
 */
public class ODXInspector {

    private static final Set<String> FORBIDDEN_DISPLAY_PATTERNS = Set.of(
            "raw", "payload", "content", "text", "email", "phone",
            "address", "name", "ssn", "password", "secret", "token",
            "body", "message", "creditcard", "bankaccount"
    );

    private final ODXBuilder odxBuilder;
    private final List<ODXEntry> indexEntries;

    public ODXInspector(ODXBuilder odxBuilder) {
        if (odxBuilder == null) {
            throw new IllegalArgumentException("ODXBuilder cannot be null");
        }
        this.odxBuilder = odxBuilder;
        this.indexEntries = new ArrayList<>();
    }

    /**
     * Loads ODX entries for inspection.
     * 
     * @param entries The ODX entries to inspect
     */
    public void loadEntries(List<ODXEntry> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("Entries cannot be null");
        }
        // Only load entries that pass safety validation
        this.indexEntries.clear();
        for (ODXEntry entry : entries) {
            if (odxBuilder.validateSafety(entry)) {
                this.indexEntries.add(entry);
            }
        }
    }

    /**
     * Gets the ODX label browser view.
     * Requirement 342.1: Show labels with counts/buckets only (never raw data).
     * 
     * @return LabelBrowserView with aggregated label information
     */
    public LabelBrowserView getLabelBrowser() {
        Map<String, LabelCategory> categories = new LinkedHashMap<>();
        
        // Group entries by namespace (domain, time, geo, quality, privacy)
        Map<String, List<ODXEntry>> byNamespace = indexEntries.stream()
                .collect(Collectors.groupingBy(e -> extractNamespace(e.facetKey())));
        
        for (Map.Entry<String, List<ODXEntry>> nsEntry : byNamespace.entrySet()) {
            String namespace = nsEntry.getKey();
            List<ODXEntry> nsEntries = nsEntry.getValue();
            
            // Group by category within namespace
            Map<String, List<ODXEntry>> byCategory = nsEntries.stream()
                    .collect(Collectors.groupingBy(e -> extractCategory(e.facetKey())));
            
            List<LabelItem> items = new ArrayList<>();
            for (Map.Entry<String, List<ODXEntry>> catEntry : byCategory.entrySet()) {
                String category = catEntry.getKey();
                List<ODXEntry> catEntries = catEntry.getValue();
                
                // Aggregate counts
                int totalCount = catEntries.stream().mapToInt(ODXEntry::count).sum();
                Set<String> timeBuckets = catEntries.stream()
                        .map(ODXEntry::timeBucket)
                        .collect(Collectors.toSet());
                
                // Only show safe information
                if (isSafeToDisplay(category)) {
                    items.add(new LabelItem(
                            namespace + ":" + category,
                            formatDisplayName(category),
                            totalCount,
                            timeBuckets.size(),
                            getQualityBreakdown(catEntries)
                    ));
                }
            }
            
            if (!items.isEmpty()) {
                categories.put(namespace, new LabelCategory(
                        namespace,
                        formatNamespaceDisplayName(namespace),
                        items,
                        items.stream().mapToInt(LabelItem::count).sum()
                ));
            }
        }
        
        return new LabelBrowserView(
                new ArrayList<>(categories.values()),
                indexEntries.size(),
                Instant.now()
        );
    }


    /**
     * Explains why a user matched a specific request.
     * Requirement 342.2: Provide match explanations per request.
     * 
     * @param requestCriteria The request criteria that was matched
     * @return MatchExplanation with reasons for the match
     */
    public MatchExplanation explainMatch(RequestCriteria requestCriteria) {
        if (requestCriteria == null) {
            throw new IllegalArgumentException("Request criteria cannot be null");
        }

        List<MatchReason> reasons = new ArrayList<>();
        List<String> matchedLabels = new ArrayList<>();
        
        // Find which labels matched the request criteria
        for (String requiredLabel : requestCriteria.requiredLabels()) {
            List<ODXEntry> matching = indexEntries.stream()
                    .filter(e -> e.facetKey().matches(requiredLabel.replace("*", ".*")))
                    .toList();
            
            if (!matching.isEmpty()) {
                int totalCount = matching.stream().mapToInt(ODXEntry::count).sum();
                matchedLabels.add(requiredLabel);
                reasons.add(new MatchReason(
                        requiredLabel,
                        "You have " + totalCount + " data points matching this label",
                        MatchType.LABEL_MATCH
                ));
            }
        }
        
        // Check time window match
        if (requestCriteria.timeWindowStart() != null && requestCriteria.timeWindowEnd() != null) {
            long entriesInWindow = indexEntries.stream()
                    .filter(e -> isInTimeWindow(e.timeBucket(), 
                            requestCriteria.timeWindowStart(), 
                            requestCriteria.timeWindowEnd()))
                    .count();
            
            if (entriesInWindow > 0) {
                reasons.add(new MatchReason(
                        "time_window",
                        "You have data within the requested time period",
                        MatchType.TIME_MATCH
                ));
            }
        }
        
        // Check geo match if applicable
        if (requestCriteria.geoRegion() != null) {
            long geoMatches = indexEntries.stream()
                    .filter(e -> e.geoBucket() != null && 
                            e.geoBucket().contains(requestCriteria.geoRegion()))
                    .count();
            
            if (geoMatches > 0) {
                reasons.add(new MatchReason(
                        "geo_region",
                        "Your coarse location matches the requested region",
                        MatchType.GEO_MATCH
                ));
            }
        }
        
        boolean isMatch = !matchedLabels.isEmpty() || 
                (requestCriteria.requiredLabels().isEmpty() && !reasons.isEmpty());
        
        return new MatchExplanation(
                requestCriteria.requestId(),
                isMatch,
                reasons,
                matchedLabels,
                "Match based on ODX labels only - no raw data was accessed"
        );
    }

    /**
     * Gets the "What is hidden?" explanation.
     * Requirement 342.3: Explain raw vault is never shown to coordinator.
     * 
     * @return HiddenDataExplanation with privacy guarantees
     */
    public HiddenDataExplanation getHiddenDataExplanation() {
        List<PrivacyGuarantee> guarantees = List.of(
                new PrivacyGuarantee(
                        "RAW_DATA_LOCAL",
                        "Raw data stays on your device",
                        "Your actual messages, photos, health readings, and location history " +
                        "are stored only in your local encrypted vault. They never leave your phone."
                ),
                new PrivacyGuarantee(
                        "ODX_ONLY_DISCOVERY",
                        "Only aggregated labels are shared",
                        "The coordinator only sees coarse labels like 'health:steps' with counts, " +
                        "never the actual step counts or when you walked."
                ),
                new PrivacyGuarantee(
                        "NO_PRECISE_LOCATION",
                        "No precise location shared",
                        "Location is coarsened to city or region level. Your exact GPS coordinates " +
                        "are never included in the ODX index."
                ),
                new PrivacyGuarantee(
                        "NO_MESSAGE_CONTENT",
                        "No message content shared",
                        "If you have messaging data, only metadata like 'communication:messages' " +
                        "with counts is indexed. Message text is never exposed."
                ),
                new PrivacyGuarantee(
                        "P2P_DELIVERY",
                        "Data delivered peer-to-peer",
                        "When you consent to share data, it's encrypted and sent directly to the " +
                        "requester. YACHAQ servers never see your raw data."
                )
        );
        
        // Calculate what types of data are in the vault but hidden
        Set<String> hiddenDataTypes = new HashSet<>();
        for (ODXEntry entry : indexEntries) {
            String namespace = extractNamespace(entry.facetKey());
            hiddenDataTypes.add(formatNamespaceDisplayName(namespace));
        }
        
        return new HiddenDataExplanation(
                guarantees,
                new ArrayList<>(hiddenDataTypes),
                "Your raw data is encrypted and stored locally. " +
                "Only privacy-safe labels are used for matching.",
                Instant.now()
        );
    }

    /**
     * Validates that no raw payload content would be displayed.
     * Requirement 342.5: Verify no raw payload content displayed.
     * 
     * @return ValidationResult indicating if display is safe
     */
    public DisplayValidationResult validateDisplaySafety() {
        List<String> violations = new ArrayList<>();
        int safeEntries = 0;
        int unsafeEntries = 0;
        
        for (ODXEntry entry : indexEntries) {
            if (isSafeToDisplay(entry.facetKey())) {
                safeEntries++;
            } else {
                unsafeEntries++;
                violations.add("Entry with facet '" + entry.facetKey() + "' may contain sensitive patterns");
            }
        }
        
        return new DisplayValidationResult(
                violations.isEmpty(),
                safeEntries,
                unsafeEntries,
                violations
        );
    }

    /**
     * Gets the total entry count.
     */
    public int getEntryCount() {
        return indexEntries.size();
    }

    // ==================== Private Helper Methods ====================

    private String extractNamespace(String facetKey) {
        int colonIndex = facetKey.indexOf(':');
        return colonIndex > 0 ? facetKey.substring(0, colonIndex) : "unknown";
    }

    private String extractCategory(String facetKey) {
        int colonIndex = facetKey.indexOf(':');
        return colonIndex > 0 ? facetKey.substring(colonIndex + 1) : facetKey;
    }

    private boolean isSafeToDisplay(String text) {
        String lower = text.toLowerCase();
        for (String pattern : FORBIDDEN_DISPLAY_PATTERNS) {
            if (lower.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    private String formatDisplayName(String category) {
        // Convert snake_case to Title Case
        return Arrays.stream(category.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String formatNamespaceDisplayName(String namespace) {
        return switch (namespace) {
            case "domain" -> "Activity Data";
            case "time" -> "Time Patterns";
            case "geo" -> "Location (Coarse)";
            case "quality" -> "Data Quality";
            case "privacy" -> "Privacy Flags";
            case "health" -> "Health & Fitness";
            case "media" -> "Media & Entertainment";
            case "communication" -> "Communication";
            case "finance" -> "Financial";
            case "fitness" -> "Fitness Activities";
            default -> namespace.substring(0, 1).toUpperCase() + namespace.substring(1);
        };
    }

    private Map<Quality, Integer> getQualityBreakdown(List<ODXEntry> entries) {
        return entries.stream()
                .collect(Collectors.groupingBy(
                        ODXEntry::quality,
                        Collectors.summingInt(ODXEntry::count)
                ));
    }

    private boolean isInTimeWindow(String timeBucket, String start, String end) {
        return timeBucket.compareTo(start) >= 0 && timeBucket.compareTo(end) <= 0;
    }


    // ==================== Inner Types ====================

    /**
     * Label browser view showing aggregated label information.
     */
    public record LabelBrowserView(
            List<LabelCategory> categories,
            int totalEntries,
            Instant generatedAt
    ) {}

    /**
     * Label category grouping.
     */
    public record LabelCategory(
            String namespace,
            String displayName,
            List<LabelItem> items,
            int totalCount
    ) {}

    /**
     * Individual label item with counts only.
     */
    public record LabelItem(
            String facetKey,
            String displayName,
            int count,
            int timeBucketCount,
            Map<Quality, Integer> qualityBreakdown
    ) {}

    /**
     * Request criteria for match explanation.
     */
    public record RequestCriteria(
            String requestId,
            List<String> requiredLabels,
            String timeWindowStart,
            String timeWindowEnd,
            String geoRegion
    ) {}

    /**
     * Match explanation for a request.
     */
    public record MatchExplanation(
            String requestId,
            boolean isMatch,
            List<MatchReason> reasons,
            List<String> matchedLabels,
            String privacyNote
    ) {}

    /**
     * Individual match reason.
     */
    public record MatchReason(
            String criterion,
            String explanation,
            MatchType matchType
    ) {}

    /**
     * Match type.
     */
    public enum MatchType {
        LABEL_MATCH,
        TIME_MATCH,
        GEO_MATCH,
        QUALITY_MATCH
    }

    /**
     * Hidden data explanation.
     */
    public record HiddenDataExplanation(
            List<PrivacyGuarantee> guarantees,
            List<String> hiddenDataTypes,
            String summary,
            Instant generatedAt
    ) {}

    /**
     * Privacy guarantee.
     */
    public record PrivacyGuarantee(
            String id,
            String title,
            String description
    ) {}

    /**
     * Display validation result.
     */
    public record DisplayValidationResult(
            boolean isSafe,
            int safeEntries,
            int unsafeEntries,
            List<String> violations
    ) {}
}
