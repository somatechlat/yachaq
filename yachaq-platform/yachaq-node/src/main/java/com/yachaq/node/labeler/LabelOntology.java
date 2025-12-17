package com.yachaq.node.labeler;

import java.util.*;

/**
 * Label Ontology with versioning support.
 * Requirement 310.3, 310.5: Ontology versioning and migration.
 * Requirement 310.4: Create domain.*, time.*, geo.*, quality.*, privacy.* namespaces.
 */
public class LabelOntology {

    public static final String CURRENT_VERSION = "1.0.0";

    private final String version;
    private final Map<String, Set<String>> validCategories;
    private final Map<String, Set<String>> validValues;
    private final Map<String, OntologyMigration> migrations;

    public LabelOntology() {
        this(CURRENT_VERSION);
    }

    public LabelOntology(String version) {
        this.version = version;
        this.validCategories = initializeCategories();
        this.validValues = initializeValues();
        this.migrations = initializeMigrations();
    }

    public String getVersion() {
        return version;
    }

    /**
     * Validates a label against the ontology.
     */
    public boolean isValidLabel(Label label) {
        String nsKey = label.namespace().prefix();
        
        // Check if category is valid for namespace
        Set<String> categories = validCategories.get(nsKey);
        if (categories != null && !categories.contains(label.category())) {
            return false;
        }
        
        // Check if value is valid for category (if restricted)
        String categoryKey = nsKey + ":" + label.category();
        Set<String> values = validValues.get(categoryKey);
        if (values != null && !values.contains(label.value())) {
            return false;
        }
        
        return true;
    }

    /**
     * Gets all valid categories for a namespace.
     */
    public Set<String> getCategories(LabelNamespace namespace) {
        return validCategories.getOrDefault(namespace.prefix(), Set.of());
    }

    /**
     * Gets all valid values for a category.
     */
    public Set<String> getValues(LabelNamespace namespace, String category) {
        String key = namespace.prefix() + ":" + category;
        return validValues.getOrDefault(key, Set.of());
    }

    /**
     * Migrates a label set from one version to another.
     * Requirement 310.3: Create Labeler.migrate(fromVersion, toVersion) method.
     */
    public LabelSet migrate(LabelSet labelSet, String toVersion) {
        if (labelSet.ontologyVersion().equals(toVersion)) {
            return labelSet;
        }
        
        String currentVersion = labelSet.ontologyVersion();
        Set<Label> migratedLabels = new HashSet<>(labelSet.labels());
        
        // Apply migrations in order
        List<String> migrationPath = getMigrationPath(currentVersion, toVersion);
        for (String migrationKey : migrationPath) {
            OntologyMigration migration = migrations.get(migrationKey);
            if (migration != null) {
                migratedLabels = migration.migrate(migratedLabels);
            }
        }
        
        return LabelSet.builder()
                .eventId(labelSet.eventId())
                .ontologyVersion(toVersion)
                .addLabels(migratedLabels)
                .build();
    }

    private List<String> getMigrationPath(String fromVersion, String toVersion) {
        // Simple linear migration path for now
        List<String> path = new ArrayList<>();
        
        // Version comparison (simplified)
        if (compareVersions(fromVersion, toVersion) < 0) {
            // Upgrade path
            if (fromVersion.equals("0.9.0") && compareVersions(toVersion, "1.0.0") >= 0) {
                path.add("0.9.0->1.0.0");
            }
        } else if (compareVersions(fromVersion, toVersion) > 0) {
            // Downgrade path (if supported)
            if (fromVersion.equals("1.0.0") && toVersion.equals("0.9.0")) {
                path.add("1.0.0->0.9.0");
            }
        }
        
        return path;
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    // ==================== Ontology Initialization ====================

    private Map<String, Set<String>> initializeCategories() {
        Map<String, Set<String>> categories = new HashMap<>();
        
        // Domain namespace categories
        categories.put("domain", Set.of(
                "activity", "content", "communication", "health", 
                "location", "media", "social", "transaction", "travel"
        ));
        
        // Time namespace categories
        categories.put("time", Set.of(
                "period", "daytype", "season", "frequency"
        ));
        
        // Geo namespace categories
        categories.put("geo", Set.of(
                "type", "density", "region", "climate"
        ));
        
        // Quality namespace categories
        categories.put("quality", Set.of(
                "source", "verification", "completeness", "freshness"
        ));
        
        // Privacy namespace categories
        categories.put("privacy", Set.of(
                "sensitivity", "pii", "floor", "risk"
        ));
        
        // Source namespace categories
        categories.put("source", Set.of(
                "connector", "import", "manual", "derived"
        ));
        
        // Behavior namespace categories
        categories.put("behavior", Set.of(
                "pattern", "habit", "routine", "anomaly"
        ));
        
        return categories;
    }

    private Map<String, Set<String>> initializeValues() {
        Map<String, Set<String>> values = new HashMap<>();
        
        // Domain:activity values
        values.put("domain:activity", Set.of(
                "exercise", "sleep", "work", "leisure", "commute", 
                "shopping", "dining", "entertainment", "other"
        ));
        
        // Time:period values
        values.put("time:period", Set.of(
                "early_morning", "morning", "afternoon", "evening", "night"
        ));
        
        // Time:daytype values
        values.put("time:daytype", Set.of(
                "weekday", "weekend", "holiday"
        ));
        
        // Time:season values
        values.put("time:season", Set.of(
                "spring", "summer", "autumn", "winter"
        ));
        
        // Geo:type values
        values.put("geo:type", Set.of(
                "home", "work", "transit", "commercial", "recreational", "other"
        ));
        
        // Geo:density values
        values.put("geo:density", Set.of(
                "urban", "suburban", "rural"
        ));
        
        // Quality:source values
        values.put("quality:source", Set.of(
                "connector", "import", "manual", "derived", "unknown"
        ));
        
        // Quality:verification values
        values.put("quality:verification", Set.of(
                "verified", "partial", "unverified", "suspicious"
        ));
        
        // Privacy:sensitivity values
        values.put("privacy:sensitivity", Set.of(
                "low", "medium", "high", "critical"
        ));
        
        // Privacy:floor values
        values.put("privacy:floor", Set.of(
                "public", "aggregate", "cleanroom", "restricted"
        ));
        
        return values;
    }

    private Map<String, OntologyMigration> initializeMigrations() {
        Map<String, OntologyMigration> migrations = new HashMap<>();
        
        // Migration from 0.9.0 to 1.0.0
        migrations.put("0.9.0->1.0.0", labels -> {
            Set<Label> migrated = new HashSet<>();
            for (Label label : labels) {
                // Example migration: rename "activity" category to "domain:activity"
                if (label.namespace() == LabelNamespace.DOMAIN && 
                    label.category().equals("type")) {
                    migrated.add(new Label(
                            label.namespace(),
                            "activity",
                            label.value(),
                            label.confidence(),
                            label.ruleId()
                    ));
                } else {
                    migrated.add(label);
                }
            }
            return migrated;
        });
        
        return migrations;
    }

    /**
     * Functional interface for ontology migrations.
     */
    @FunctionalInterface
    public interface OntologyMigration {
        Set<Label> migrate(Set<Label> labels);
    }
}
