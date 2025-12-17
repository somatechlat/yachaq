package com.yachaq.node.labeler;

import java.util.Objects;

/**
 * Represents a privacy-safe label for ODX indexing.
 * Requirement 310.1: Apply explainable rule-based labels.
 * Requirement 310.4: Create domain.*, time.*, geo.*, quality.*, privacy.* namespaces.
 * 
 * Labels are structured as namespace:category:value for consistent querying.
 */
public record Label(
        LabelNamespace namespace,
        String category,
        String value,
        double confidence,
        String ruleId
) {
    
    public Label {
        Objects.requireNonNull(namespace, "Namespace cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
        }
    }

    /**
     * Creates a label with default confidence (1.0) and no rule ID.
     */
    public static Label of(LabelNamespace namespace, String category, String value) {
        return new Label(namespace, category, value, 1.0, null);
    }

    /**
     * Creates a label with specified confidence.
     */
    public static Label of(LabelNamespace namespace, String category, String value, double confidence) {
        return new Label(namespace, category, value, confidence, null);
    }

    /**
     * Creates a label with rule ID for explainability.
     */
    public static Label withRule(LabelNamespace namespace, String category, String value, String ruleId) {
        return new Label(namespace, category, value, 1.0, ruleId);
    }

    /**
     * Returns the fully qualified label key.
     */
    public String toKey() {
        return namespace.prefix() + ":" + category + ":" + value;
    }

    /**
     * Returns the facet key (namespace:category) for ODX indexing.
     */
    public String toFacetKey() {
        return namespace.prefix() + ":" + category;
    }

    @Override
    public String toString() {
        return toKey() + (ruleId != null ? " [" + ruleId + "]" : "");
    }
}
