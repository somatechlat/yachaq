package com.yachaq.node.labeler;

import com.yachaq.node.normalizer.CanonicalEvent;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A rule for applying labels to events.
 * Requirement 310.1: Apply explainable rule-based labels.
 */
public record LabelingRule(
        String ruleId,
        String description,
        Predicate<CanonicalEvent> condition,
        Function<CanonicalEvent, Set<Label>> labelGenerator,
        int priority
) {
    
    public LabelingRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("Rule ID cannot be null or blank");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition cannot be null");
        }
        if (labelGenerator == null) {
            throw new IllegalArgumentException("Label generator cannot be null");
        }
    }

    /**
     * Creates a simple rule with default priority.
     */
    public static LabelingRule of(String ruleId, String description,
                                   Predicate<CanonicalEvent> condition,
                                   Function<CanonicalEvent, Set<Label>> labelGenerator) {
        return new LabelingRule(ruleId, description, condition, labelGenerator, 0);
    }

    /**
     * Checks if this rule applies to the given event.
     */
    public boolean appliesTo(CanonicalEvent event) {
        return condition.test(event);
    }

    /**
     * Generates labels for the given event.
     */
    public Set<Label> generateLabels(CanonicalEvent event) {
        return labelGenerator.apply(event);
    }
}
