package com.yachaq.node.labeler;

/**
 * Label namespaces for organizing labels by domain.
 * Requirement 310.4: Create domain.*, time.*, geo.*, quality.*, privacy.* namespaces.
 */
public enum LabelNamespace {
    
    /**
     * Domain-specific labels (activity type, content category, etc.)
     */
    DOMAIN("domain"),
    
    /**
     * Time-based labels (time of day, day type, season, etc.)
     */
    TIME("time"),
    
    /**
     * Geographic labels (region type, urban/rural, etc.)
     */
    GEO("geo"),
    
    /**
     * Data quality labels (source verification, completeness, etc.)
     */
    QUALITY("quality"),
    
    /**
     * Privacy-related labels (sensitivity level, PII indicators, etc.)
     */
    PRIVACY("privacy"),
    
    /**
     * Source-specific labels (connector type, import source, etc.)
     */
    SOURCE("source"),
    
    /**
     * Behavioral labels (patterns, habits, etc.)
     */
    BEHAVIOR("behavior");

    private final String prefix;

    LabelNamespace(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * Parses a namespace from a label key prefix.
     */
    public static LabelNamespace fromPrefix(String prefix) {
        for (LabelNamespace ns : values()) {
            if (ns.prefix.equals(prefix)) {
                return ns;
            }
        }
        throw new IllegalArgumentException("Unknown namespace prefix: " + prefix);
    }
}
