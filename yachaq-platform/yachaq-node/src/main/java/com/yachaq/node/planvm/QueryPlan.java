package com.yachaq.node.planvm;

import java.time.Instant;
import java.util.*;

/**
 * Represents a query plan to be executed in the sandbox.
 * Requirement 315.1: Define plan structure with allowed operators.
 */
public record QueryPlan(
        String id,
        String contractId,
        List<PlanStep> steps,
        Set<String> allowedFields,
        OutputConfig outputConfig,
        ResourceLimits resourceLimits,
        String signature,
        Instant createdAt,
        Instant expiresAt
) {
    
    public QueryPlan {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(contractId, "Contract ID cannot be null");
        Objects.requireNonNull(steps, "Steps cannot be null");
        Objects.requireNonNull(allowedFields, "Allowed fields cannot be null");
        Objects.requireNonNull(outputConfig, "Output config cannot be null");
        Objects.requireNonNull(resourceLimits, "Resource limits cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        Objects.requireNonNull(expiresAt, "Expires at cannot be null");
        
        steps = List.copyOf(steps);
        allowedFields = Set.copyOf(allowedFields);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isSigned() {
        return signature != null && !signature.isBlank();
    }

    /**
     * A single step in the query plan.
     */
    public record PlanStep(
            int index,
            PlanOperator operator,
            Map<String, Object> parameters,
            Set<String> inputFields,
            Set<String> outputFields
    ) {
        public PlanStep {
            Objects.requireNonNull(operator, "Operator cannot be null");
            parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
            inputFields = inputFields != null ? Set.copyOf(inputFields) : Set.of();
            outputFields = outputFields != null ? Set.copyOf(outputFields) : Set.of();
        }
    }

    /**
     * Output configuration.
     */
    public record OutputConfig(
            OutputMode mode,
            int maxRecords,
            int maxBytes,
            boolean allowExport
    ) {
        public enum OutputMode {
            AGGREGATE_ONLY,
            CLEAN_ROOM,
            CAPSULE
        }
    }

    /**
     * Resource limits for execution.
     * Requirement 315.4: Enforce CPU, memory, time, and battery limits.
     */
    public record ResourceLimits(
            long maxCpuMillis,
            long maxMemoryBytes,
            long maxExecutionMillis,
            int maxBatteryPercent
    ) {
        public static ResourceLimits defaults() {
            return new ResourceLimits(
                    5000,           // 5 seconds CPU
                    50_000_000,     // 50 MB memory
                    30_000,         // 30 seconds wall time
                    5               // 5% battery max
            );
        }

        public static ResourceLimits strict() {
            return new ResourceLimits(
                    1000,           // 1 second CPU
                    10_000_000,     // 10 MB memory
                    10_000,         // 10 seconds wall time
                    2               // 2% battery max
            );
        }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String contractId;
        private List<PlanStep> steps = new ArrayList<>();
        private Set<String> allowedFields = new HashSet<>();
        private OutputConfig outputConfig;
        private ResourceLimits resourceLimits = ResourceLimits.defaults();
        private String signature;
        private Instant createdAt;
        private Instant expiresAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder generateId() { this.id = UUID.randomUUID().toString(); return this; }
        public Builder contractId(String contractId) { this.contractId = contractId; return this; }
        public Builder steps(List<PlanStep> steps) { this.steps = new ArrayList<>(steps); return this; }
        public Builder addStep(PlanStep step) { this.steps.add(step); return this; }
        public Builder allowedFields(Set<String> fields) { this.allowedFields = new HashSet<>(fields); return this; }
        public Builder addAllowedField(String field) { this.allowedFields.add(field); return this; }
        public Builder outputConfig(OutputConfig config) { this.outputConfig = config; return this; }
        public Builder resourceLimits(ResourceLimits limits) { this.resourceLimits = limits; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

        public QueryPlan build() {
            if (id == null) id = UUID.randomUUID().toString();
            if (createdAt == null) createdAt = Instant.now();
            if (expiresAt == null) expiresAt = createdAt.plusSeconds(3600);
            if (outputConfig == null) {
                outputConfig = new OutputConfig(OutputConfig.OutputMode.AGGREGATE_ONLY, 1000, 1_000_000, false);
            }
            return new QueryPlan(id, contractId, steps, allowedFields, outputConfig, resourceLimits, signature, createdAt, expiresAt);
        }
    }
}
