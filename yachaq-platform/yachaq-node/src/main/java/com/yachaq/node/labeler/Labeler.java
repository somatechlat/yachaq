package com.yachaq.node.labeler;

import com.yachaq.node.normalizer.CanonicalEvent;
import com.yachaq.node.normalizer.CanonicalEvent.*;

import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Label Engine for applying privacy-safe labels to canonical events.
 * Requirement 310.1: Create Labeler.label(event) method with explainable rule-based labels.
 * Requirement 310.4: Create domain.*, time.*, geo.*, quality.*, privacy.* namespaces.
 * Requirement 310.3: Create Labeler.migrate(fromVersion, toVersion) method.
 */
public class Labeler {

    private final LabelOntology ontology;
    private final List<LabelingRule> rules;
    private final ZoneId zoneId;

    public Labeler() {
        this(new LabelOntology(), ZoneId.of("UTC"));
    }

    public Labeler(LabelOntology ontology, ZoneId zoneId) {
        this.ontology = Objects.requireNonNull(ontology, "Ontology cannot be null");
        this.zoneId = Objects.requireNonNull(zoneId, "Zone ID cannot be null");
        this.rules = initializeRules();
    }

    /**
     * Labels an event with privacy-safe labels.
     * Requirement 310.1: Create Labeler.label(event) method.
     */
    public LabelSet label(CanonicalEvent event) {
        Objects.requireNonNull(event, "Event cannot be null");

        LabelSet.Builder builder = LabelSet.builder()
                .eventId(event.id())
                .ontologyVersion(ontology.getVersion());

        // Apply all matching rules
        for (LabelingRule rule : rules) {
            if (rule.appliesTo(event)) {
                Set<Label> labels = rule.generateLabels(event);
                for (Label label : labels) {
                    if (ontology.isValidLabel(label)) {
                        builder.addLabel(label);
                    }
                }
            }
        }

        // Always add core labels
        builder.addLabels(generateDomainLabels(event));
        builder.addLabels(generateTimeLabels(event));
        builder.addLabels(generateQualityLabels(event));
        builder.addLabels(generatePrivacyLabels(event));

        // Add geo labels if location available
        if (event.location() != null && 
            event.location().resolution() != GeoLocation.GeoResolution.NONE) {
            builder.addLabels(generateGeoLabels(event));
        }

        // Add source labels
        builder.addLabels(generateSourceLabels(event));

        return builder.build();
    }

    /**
     * Labels a batch of events.
     */
    public List<LabelSet> labelBatch(List<CanonicalEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .map(this::label)
                .toList();
    }

    /**
     * Migrates a label set to a new ontology version.
     * Requirement 310.3: Create Labeler.migrate(fromVersion, toVersion) method.
     */
    public LabelSet migrate(LabelSet labelSet, String toVersion) {
        return ontology.migrate(labelSet, toVersion);
    }

    /**
     * Returns the current ontology version.
     */
    public String getOntologyVersion() {
        return ontology.getVersion();
    }

    // ==================== Domain Labels (54.2) ====================

    private Set<Label> generateDomainLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        
        // Category-based domain label
        String activityType = mapCategoryToActivity(event.category());
        labels.add(Label.withRule(
                LabelNamespace.DOMAIN, "activity", activityType, "DOMAIN_CATEGORY"
        ));

        // Event type specific labels
        String eventType = event.eventType().toLowerCase();
        if (eventType.contains("workout") || eventType.contains("exercise")) {
            labels.add(Label.withRule(
                    LabelNamespace.DOMAIN, "activity", "exercise", "DOMAIN_EXERCISE"
            ));
        } else if (eventType.contains("sleep")) {
            labels.add(Label.withRule(
                    LabelNamespace.DOMAIN, "activity", "sleep", "DOMAIN_SLEEP"
            ));
        } else if (eventType.contains("commute") || eventType.contains("transit")) {
            labels.add(Label.withRule(
                    LabelNamespace.DOMAIN, "activity", "commute", "DOMAIN_COMMUTE"
            ));
        }

        return labels;
    }

    private String mapCategoryToActivity(EventCategory category) {
        return switch (category) {
            case ACTIVITY, HEALTH -> "exercise";
            case LOCATION, TRAVEL -> "commute";
            case COMMUNICATION, SOCIAL -> "leisure";
            case MEDIA, CONTENT -> "entertainment";
            case TRANSACTION -> "shopping";
            case DEVICE -> "work";
            case OTHER -> "other";
        };
    }

    // ==================== Time Labels (54.2) ====================

    private Set<Label> generateTimeLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        
        ZonedDateTime zdt = event.timestamp().atZone(zoneId);
        int hour = zdt.getHour();
        int dayOfWeek = zdt.getDayOfWeek().getValue();
        int month = zdt.getMonthValue();

        // Time period label
        String period = categorizeTimePeriod(hour);
        labels.add(Label.withRule(
                LabelNamespace.TIME, "period", period, "TIME_PERIOD"
        ));

        // Day type label
        String dayType = (dayOfWeek >= 6) ? "weekend" : "weekday";
        labels.add(Label.withRule(
                LabelNamespace.TIME, "daytype", dayType, "TIME_DAYTYPE"
        ));

        // Season label
        String season = categorizeSeason(month);
        labels.add(Label.withRule(
                LabelNamespace.TIME, "season", season, "TIME_SEASON"
        ));

        return labels;
    }

    private String categorizeTimePeriod(int hour) {
        if (hour >= 5 && hour < 8) return "early_morning";
        if (hour >= 8 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        return "night";
    }

    private String categorizeSeason(int month) {
        if (month >= 3 && month <= 5) return "spring";
        if (month >= 6 && month <= 8) return "summer";
        if (month >= 9 && month <= 11) return "autumn";
        return "winter";
    }

    // ==================== Geo Labels (54.2) ====================

    private Set<Label> generateGeoLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        GeoLocation location = event.location();
        
        if (location == null || location.resolution() == GeoLocation.GeoResolution.NONE) {
            return labels;
        }

        // Resolution-based type (coarse)
        String geoType = inferGeoType(event);
        labels.add(Label.withRule(
                LabelNamespace.GEO, "type", geoType, "GEO_TYPE"
        ));

        // Density estimation (very coarse, based on attributes if available)
        String density = inferDensity(event);
        if (density != null) {
            labels.add(Label.withRule(
                    LabelNamespace.GEO, "density", density, "GEO_DENSITY"
            ));
        }

        return labels;
    }

    private String inferGeoType(CanonicalEvent event) {
        Map<String, Object> attrs = event.attributes();
        
        // Check for explicit location type
        if (attrs.containsKey("locationType")) {
            String type = attrs.get("locationType").toString().toLowerCase();
            if (type.contains("home")) return "home";
            if (type.contains("work") || type.contains("office")) return "work";
            if (type.contains("transit") || type.contains("station")) return "transit";
            if (type.contains("shop") || type.contains("store")) return "commercial";
            if (type.contains("park") || type.contains("gym")) return "recreational";
        }
        
        // Infer from event type
        String eventType = event.eventType().toLowerCase();
        if (eventType.contains("commute") || eventType.contains("transit")) return "transit";
        if (eventType.contains("workout") || eventType.contains("run")) return "recreational";
        
        return "other";
    }

    private String inferDensity(CanonicalEvent event) {
        Map<String, Object> attrs = event.attributes();
        
        if (attrs.containsKey("density")) {
            return attrs.get("density").toString().toLowerCase();
        }
        
        // Cannot reliably infer density without more data
        return null;
    }

    // ==================== Quality Labels (54.2) ====================

    private Set<Label> generateQualityLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        
        // Source quality
        String sourceQuality = determineSourceQuality(event);
        labels.add(Label.withRule(
                LabelNamespace.QUALITY, "source", sourceQuality, "QUALITY_SOURCE"
        ));

        // Verification level
        String verification = determineVerification(event);
        labels.add(Label.withRule(
                LabelNamespace.QUALITY, "verification", verification, "QUALITY_VERIFICATION"
        ));

        // Completeness
        String completeness = determineCompleteness(event);
        labels.add(Label.withRule(
                LabelNamespace.QUALITY, "completeness", completeness, "QUALITY_COMPLETENESS"
        ));

        return labels;
    }

    private String determineSourceQuality(CanonicalEvent event) {
        String sourceType = event.sourceType().toLowerCase();
        
        if (sourceType.contains("healthkit") || sourceType.contains("health_connect") ||
            sourceType.contains("spotify") || sourceType.contains("strava") ||
            sourceType.contains("google") || sourceType.contains("apple")) {
            return "connector";
        }
        if (sourceType.contains("import") || sourceType.contains("takeout")) {
            return "import";
        }
        if (sourceType.contains("manual")) {
            return "manual";
        }
        if (sourceType.contains("derived") || sourceType.contains("computed")) {
            return "derived";
        }
        return "unknown";
    }

    private String determineVerification(CanonicalEvent event) {
        String sourceType = event.sourceType().toLowerCase();
        
        // OAuth connectors are verified
        if (sourceType.contains("healthkit") || sourceType.contains("spotify") ||
            sourceType.contains("strava") || sourceType.contains("google")) {
            return "verified";
        }
        
        // Imports with content hash are partially verified
        if (event.contentHash() != null && !event.contentHash().isEmpty()) {
            return "partial";
        }
        
        return "unverified";
    }

    private String determineCompleteness(CanonicalEvent event) {
        int score = 0;
        int total = 5;
        
        if (event.id() != null) score++;
        if (event.timestamp() != null) score++;
        if (event.attributes() != null && !event.attributes().isEmpty()) score++;
        if (event.location() != null && 
            event.location().resolution() != GeoLocation.GeoResolution.NONE) score++;
        if (event.contentHash() != null) score++;
        
        double ratio = (double) score / total;
        if (ratio >= 0.8) return "high";
        if (ratio >= 0.5) return "medium";
        return "low";
    }

    // ==================== Privacy Labels (54.2) ====================

    private Set<Label> generatePrivacyLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        
        // Sensitivity level
        String sensitivity = determineSensitivity(event);
        labels.add(Label.withRule(
                LabelNamespace.PRIVACY, "sensitivity", sensitivity, "PRIVACY_SENSITIVITY"
        ));

        // Privacy floor
        String floor = determinePrivacyFloor(event);
        labels.add(Label.withRule(
                LabelNamespace.PRIVACY, "floor", floor, "PRIVACY_FLOOR"
        ));

        // PII indicator
        if (containsPotentialPII(event)) {
            labels.add(Label.withRule(
                    LabelNamespace.PRIVACY, "pii", "detected", "PRIVACY_PII"
            ));
        }

        return labels;
    }

    private String determineSensitivity(CanonicalEvent event) {
        EventCategory category = event.category();
        
        // Health and financial data are high sensitivity
        if (category == EventCategory.HEALTH || category == EventCategory.TRANSACTION) {
            return "high";
        }
        
        // Communication and location are medium sensitivity
        if (category == EventCategory.COMMUNICATION || category == EventCategory.LOCATION) {
            return "medium";
        }
        
        // Check for sensitive attributes
        Map<String, Object> attrs = event.attributes();
        if (attrs.containsKey("biometric") || attrs.containsKey("medical") ||
            attrs.containsKey("financial")) {
            return "high";
        }
        
        return "low";
    }

    private String determinePrivacyFloor(CanonicalEvent event) {
        String sensitivity = determineSensitivity(event);
        
        // High sensitivity requires cleanroom
        if ("high".equals(sensitivity) || "critical".equals(sensitivity)) {
            return "cleanroom";
        }
        
        // Medium sensitivity requires aggregate
        if ("medium".equals(sensitivity)) {
            return "aggregate";
        }
        
        return "public";
    }

    private boolean containsPotentialPII(CanonicalEvent event) {
        Map<String, Object> attrs = event.attributes();
        
        // Check for PII-related attribute keys
        Set<String> piiKeys = Set.of(
                "email", "phone", "name", "address", "ssn", 
                "creditCard", "bankAccount", "personalId"
        );
        
        for (String key : attrs.keySet()) {
            if (piiKeys.contains(key.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }

    // ==================== Source Labels (54.2) ====================

    private Set<Label> generateSourceLabels(CanonicalEvent event) {
        Set<Label> labels = new HashSet<>();
        
        String sourceType = event.sourceType().toLowerCase();
        
        // Connector type
        if (sourceType.contains("healthkit")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "connector", "healthkit", "SOURCE_HEALTHKIT"
            ));
        } else if (sourceType.contains("health_connect")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "connector", "health_connect", "SOURCE_HEALTH_CONNECT"
            ));
        } else if (sourceType.contains("spotify")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "connector", "spotify", "SOURCE_SPOTIFY"
            ));
        } else if (sourceType.contains("strava")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "connector", "strava", "SOURCE_STRAVA"
            ));
        } else if (sourceType.contains("import") || sourceType.contains("takeout")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "import", "file", "SOURCE_IMPORT"
            ));
        } else if (sourceType.contains("manual")) {
            labels.add(Label.withRule(
                    LabelNamespace.SOURCE, "manual", "entry", "SOURCE_MANUAL"
            ));
        }

        return labels;
    }

    // ==================== Rule Initialization ====================

    private List<LabelingRule> initializeRules() {
        List<LabelingRule> rules = new ArrayList<>();
        
        // Rule: High-intensity workout detection
        rules.add(LabelingRule.of(
                "BEHAVIOR_HIGH_INTENSITY",
                "Detects high-intensity workouts based on duration and heart rate",
                event -> {
                    if (event.category() != EventCategory.ACTIVITY && 
                        event.category() != EventCategory.HEALTH) {
                        return false;
                    }
                    Map<String, Object> attrs = event.attributes();
                    if (attrs.containsKey("heartRate")) {
                        Object hr = attrs.get("heartRate");
                        if (hr instanceof Number && ((Number) hr).intValue() > 150) {
                            return true;
                        }
                    }
                    return false;
                },
                event -> Set.of(Label.withRule(
                        LabelNamespace.BEHAVIOR, "pattern", "high_intensity", "BEHAVIOR_HIGH_INTENSITY"
                ))
        ));

        // Rule: Long duration activity
        rules.add(LabelingRule.of(
                "BEHAVIOR_LONG_DURATION",
                "Detects activities lasting more than 1 hour",
                event -> event.duration() != null && event.duration().toSeconds() > 3600,
                event -> Set.of(Label.withRule(
                        LabelNamespace.BEHAVIOR, "pattern", "long_duration", "BEHAVIOR_LONG_DURATION"
                ))
        ));

        // Rule: Night activity
        rules.add(LabelingRule.of(
                "BEHAVIOR_NIGHT_ACTIVITY",
                "Detects activities during night hours (9pm-5am)",
                event -> {
                    int hour = event.timestamp().atZone(zoneId).getHour();
                    return hour >= 21 || hour < 5;
                },
                event -> Set.of(Label.withRule(
                        LabelNamespace.BEHAVIOR, "pattern", "night_activity", "BEHAVIOR_NIGHT_ACTIVITY"
                ))
        ));

        return rules;
    }
}
