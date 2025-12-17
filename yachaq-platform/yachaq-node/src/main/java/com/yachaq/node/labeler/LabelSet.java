package com.yachaq.node.labeler;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A collection of labels applied to an event.
 * Requirement 310.1: Apply explainable rule-based labels.
 */
public record LabelSet(
        String eventId,
        String ontologyVersion,
        Set<Label> labels,
        Map<String, String> appliedRules
) {
    
    public LabelSet {
        Objects.requireNonNull(eventId, "Event ID cannot be null");
        Objects.requireNonNull(ontologyVersion, "Ontology version cannot be null");
        labels = labels != null ? Set.copyOf(labels) : Set.of();
        appliedRules = appliedRules != null ? Map.copyOf(appliedRules) : Map.of();
    }

    /**
     * Returns labels filtered by namespace.
     */
    public Set<Label> byNamespace(LabelNamespace namespace) {
        return labels.stream()
                .filter(l -> l.namespace() == namespace)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns labels filtered by category within a namespace.
     */
    public Set<Label> byCategory(LabelNamespace namespace, String category) {
        return labels.stream()
                .filter(l -> l.namespace() == namespace && l.category().equals(category))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Checks if a specific label exists.
     */
    public boolean hasLabel(LabelNamespace namespace, String category, String value) {
        return labels.stream()
                .anyMatch(l -> l.namespace() == namespace && 
                              l.category().equals(category) && 
                              l.value().equals(value));
    }

    /**
     * Returns all facet keys for ODX indexing.
     */
    public Set<String> getFacetKeys() {
        return labels.stream()
                .map(Label::toFacetKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns all label keys.
     */
    public Set<String> getLabelKeys() {
        return labels.stream()
                .map(Label::toKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the count of labels.
     */
    public int size() {
        return labels.size();
    }

    /**
     * Checks if the label set is empty.
     */
    public boolean isEmpty() {
        return labels.isEmpty();
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId;
        private String ontologyVersion;
        private final Set<Label> labels = new HashSet<>();
        private final Map<String, String> appliedRules = new HashMap<>();

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder ontologyVersion(String ontologyVersion) {
            this.ontologyVersion = ontologyVersion;
            return this;
        }

        public Builder addLabel(Label label) {
            labels.add(label);
            if (label.ruleId() != null) {
                appliedRules.put(label.toKey(), label.ruleId());
            }
            return this;
        }

        public Builder addLabels(Collection<Label> labels) {
            labels.forEach(this::addLabel);
            return this;
        }

        public LabelSet build() {
            return new LabelSet(eventId, ontologyVersion, labels, appliedRules);
        }
    }
}
