package com.yachaq.node.planvm;

import java.util.Set;

/**
 * Allowed operators in QueryPlan VM.
 * Requirement 315.1: Allow only specific operators.
 */
public enum PlanOperator {
    SELECT("select", "Select specific records"),
    FILTER("filter", "Filter records by criteria"),
    PROJECT("project", "Project specific fields"),
    BUCKETIZE("bucketize", "Bucket values into ranges"),
    AGGREGATE("aggregate", "Aggregate values (sum, count, avg)"),
    CLUSTER_REF("cluster_ref", "Reference cluster ID (not raw content)"),
    REDACT("redact", "Redact sensitive fields"),
    SAMPLE("sample", "Sample a subset of records"),
    EXPORT("export", "Export results (if allowed)"),
    PACK_CAPSULE("pack_capsule", "Package into time capsule");

    private final String name;
    private final String description;

    PlanOperator(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Gets the set of all allowed operator names.
     */
    public static Set<String> allowedOperatorNames() {
        return Set.of(
                SELECT.name, FILTER.name, PROJECT.name, BUCKETIZE.name,
                AGGREGATE.name, CLUSTER_REF.name, REDACT.name, SAMPLE.name,
                EXPORT.name, PACK_CAPSULE.name
        );
    }

    /**
     * Checks if an operator name is allowed.
     */
    public static boolean isAllowed(String operatorName) {
        return allowedOperatorNames().contains(operatorName.toLowerCase());
    }

    /**
     * Parses an operator from name.
     */
    public static PlanOperator fromName(String name) {
        for (PlanOperator op : values()) {
            if (op.name.equalsIgnoreCase(name)) {
                return op;
            }
        }
        throw new IllegalArgumentException("Unknown operator: " + name);
    }
}
