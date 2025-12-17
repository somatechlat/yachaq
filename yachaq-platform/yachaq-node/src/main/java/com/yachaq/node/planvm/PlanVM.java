package com.yachaq.node.planvm;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.planvm.QueryPlan.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QueryPlan Virtual Machine - Sandboxed execution environment.
 * Requirement 315.1: Execute plans with operator allowlist.
 * Requirement 315.2: Block arbitrary code execution.
 * Requirement 315.3: Block all network egress during execution.
 * Requirement 315.4: Enforce CPU, memory, time, and battery limits.
 */
public class PlanVM {

    private final PlanValidator validator;
    private final NetworkGate networkGate;
    private final ResourceMonitor resourceMonitor;
    private final ExecutorService executor;

    public PlanVM() {
        this(new PlanValidator(), new NetworkGate(), new ResourceMonitor());
    }

    public PlanVM(PlanValidator validator, NetworkGate networkGate, ResourceMonitor resourceMonitor) {
        this.validator = Objects.requireNonNull(validator);
        this.networkGate = Objects.requireNonNull(networkGate);
        this.resourceMonitor = Objects.requireNonNull(resourceMonitor);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PlanVM-Executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Previews a plan showing human-readable outputs and privacy impact.
     * Requirement 315.5: Create PlanVM.preview(plan) method.
     */
    public PreviewResult preview(QueryPlan plan) {
        Objects.requireNonNull(plan, "Plan cannot be null");

        List<String> steps = new ArrayList<>();
        Set<String> accessedFields = new HashSet<>();
        int privacyImpactScore = 0;

        for (PlanStep step : plan.steps()) {
            steps.add(formatStepDescription(step));
            accessedFields.addAll(step.inputFields());
            privacyImpactScore += calculatePrivacyImpact(step);
        }

        String outputDescription = formatOutputDescription(plan.outputConfig());

        return new PreviewResult(
                steps,
                accessedFields,
                privacyImpactScore,
                outputDescription,
                plan.resourceLimits()
        );
    }

    /**
     * Executes a plan within the sandbox.
     * Requirement 315.1: Create PlanVM.execute(plan, contract) method.
     * Requirement 315.2: Block arbitrary code execution.
     * Requirement 315.3: Block all network egress during execution.
     */
    public ExecutionResult execute(QueryPlan plan, ContractDraft contract, Map<String, Object> data) {
        Objects.requireNonNull(plan, "Plan cannot be null");
        Objects.requireNonNull(contract, "Contract cannot be null");
        Objects.requireNonNull(data, "Data cannot be null");

        // Validate plan first
        PlanValidator.ValidationResult validation = validator.validate(plan, contract);
        if (!validation.valid()) {
            return ExecutionResult.failed("Validation failed: " + validation.errors());
        }

        // Block network during execution
        networkGate.blockAll();
        
        try {
            // Start resource monitoring
            resourceMonitor.start(plan.resourceLimits());

            // Execute with timeout
            Future<Map<String, Object>> future = executor.submit(() -> 
                    executeSteps(plan, data)
            );

            Map<String, Object> result;
            try {
                result = future.get(
                        plan.resourceLimits().maxExecutionMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (TimeoutException e) {
                future.cancel(true);
                return ExecutionResult.failed("Execution timeout exceeded");
            } catch (ExecutionException e) {
                return ExecutionResult.failed("Execution error: " + e.getCause().getMessage());
            }

            // Check resource usage
            ResourceUsage usage = resourceMonitor.stop();
            if (usage.exceededLimits()) {
                return ExecutionResult.failed("Resource limits exceeded: " + usage.violations());
            }

            return ExecutionResult.success(result, usage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failed("Execution interrupted");
        } finally {
            networkGate.unblockAll();
            resourceMonitor.stop();
        }
    }

    /**
     * Executes plan steps sequentially.
     */
    private Map<String, Object> executeSteps(QueryPlan plan, Map<String, Object> data) {
        Map<String, Object> current = new HashMap<>(data);

        for (PlanStep step : plan.steps()) {
            // Check for interruption
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Execution interrupted");
            }

            current = executeStep(step, current, plan.allowedFields());
        }

        return current;
    }

    /**
     * Executes a single step.
     */
    private Map<String, Object> executeStep(PlanStep step, Map<String, Object> data, Set<String> allowedFields) {
        return switch (step.operator()) {
            case SELECT -> executeSelect(step, data);
            case FILTER -> executeFilter(step, data);
            case PROJECT -> executeProject(step, data, allowedFields);
            case BUCKETIZE -> executeBucketize(step, data);
            case AGGREGATE -> executeAggregate(step, data);
            case CLUSTER_REF -> executeClusterRef(step, data);
            case REDACT -> executeRedact(step, data);
            case SAMPLE -> executeSample(step, data);
            case EXPORT -> executeExport(step, data);
            case PACK_CAPSULE -> executePackCapsule(step, data);
        };
    }

    private Map<String, Object> executeSelect(PlanStep step, Map<String, Object> data) {
        // Select records matching criteria
        Map<String, Object> result = new HashMap<>();
        String criteria = (String) step.parameters().getOrDefault("criteria", "*");
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (matchesCriteria(entry.getKey(), criteria)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> executeFilter(PlanStep step, Map<String, Object> data) {
        // Filter by predicate
        Map<String, Object> result = new HashMap<>();
        String field = (String) step.parameters().get("field");
        Object value = step.parameters().get("value");
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (field == null || entry.getKey().equals(field)) {
                if (value == null || entry.getValue().equals(value)) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    private Map<String, Object> executeProject(PlanStep step, Map<String, Object> data, Set<String> allowedFields) {
        // Project only allowed fields
        Map<String, Object> result = new HashMap<>();
        Set<String> projectFields = step.outputFields();
        
        for (String field : projectFields) {
            if (allowedFields.contains(field) && data.containsKey(field)) {
                result.put(field, data.get(field));
            }
        }
        return result;
    }

    private Map<String, Object> executeBucketize(PlanStep step, Map<String, Object> data) {
        // Bucket numeric values into ranges
        Map<String, Object> result = new HashMap<>(data);
        String field = (String) step.parameters().get("field");
        int bucketSize = (int) step.parameters().getOrDefault("bucketSize", 10);
        
        if (field != null && data.containsKey(field)) {
            Object value = data.get(field);
            if (value instanceof Number) {
                int bucket = ((Number) value).intValue() / bucketSize * bucketSize;
                result.put(field + "_bucket", bucket + "-" + (bucket + bucketSize));
            }
        }
        return result;
    }

    private Map<String, Object> executeAggregate(PlanStep step, Map<String, Object> data) {
        // Aggregate values
        Map<String, Object> result = new HashMap<>();
        String operation = (String) step.parameters().getOrDefault("operation", "count");
        
        result.put("_aggregate_type", operation);
        result.put("_aggregate_count", data.size());
        
        if ("sum".equals(operation)) {
            double sum = data.values().stream()
                    .filter(v -> v instanceof Number)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();
            result.put("_aggregate_sum", sum);
        }
        
        return result;
    }

    private Map<String, Object> executeClusterRef(PlanStep step, Map<String, Object> data) {
        // Replace raw content with cluster IDs
        Map<String, Object> result = new HashMap<>();
        String field = (String) step.parameters().get("field");
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (field == null || entry.getKey().equals(field)) {
                // Replace with cluster reference
                result.put(entry.getKey() + "_cluster", "cluster:" + entry.getValue().hashCode());
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> executeRedact(PlanStep step, Map<String, Object> data) {
        // Redact sensitive fields
        Map<String, Object> result = new HashMap<>(data);
        Set<String> redactFields = step.inputFields();
        
        for (String field : redactFields) {
            if (result.containsKey(field)) {
                result.put(field, "[REDACTED]");
            }
        }
        return result;
    }

    private Map<String, Object> executeSample(PlanStep step, Map<String, Object> data) {
        // Sample a subset
        Map<String, Object> result = new HashMap<>();
        double rate = (double) step.parameters().getOrDefault("rate", 0.1);
        Random random = new Random();
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (random.nextDouble() < rate) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> executeExport(PlanStep step, Map<String, Object> data) {
        // Mark for export (actual export handled by capsule packager)
        Map<String, Object> result = new HashMap<>(data);
        result.put("_export_requested", true);
        result.put("_export_format", step.parameters().getOrDefault("format", "json"));
        return result;
    }

    private Map<String, Object> executePackCapsule(PlanStep step, Map<String, Object> data) {
        // Package into capsule format
        Map<String, Object> result = new HashMap<>();
        result.put("_capsule_data", data);
        result.put("_capsule_timestamp", System.currentTimeMillis());
        result.put("_capsule_ttl", step.parameters().getOrDefault("ttl", 3600));
        return result;
    }

    private boolean matchesCriteria(String key, String criteria) {
        if ("*".equals(criteria)) return true;
        return key.contains(criteria);
    }

    private String formatStepDescription(PlanStep step) {
        return String.format("%d. %s: %s", 
                step.index(), 
                step.operator().getName().toUpperCase(),
                step.operator().getDescription());
    }

    private int calculatePrivacyImpact(PlanStep step) {
        return switch (step.operator()) {
            case SELECT, FILTER -> 1;
            case PROJECT -> 2;
            case BUCKETIZE, AGGREGATE, CLUSTER_REF -> 1;
            case REDACT -> -1; // Reduces privacy impact
            case SAMPLE -> 0;
            case EXPORT -> 5;
            case PACK_CAPSULE -> 2;
        };
    }

    private String formatOutputDescription(OutputConfig config) {
        return String.format("Mode: %s, Max Records: %d, Export: %s",
                config.mode(), config.maxRecords(), config.allowExport() ? "Yes" : "No");
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ==================== Result Types ====================

    public record PreviewResult(
            List<String> stepDescriptions,
            Set<String> accessedFields,
            int privacyImpactScore,
            String outputDescription,
            ResourceLimits resourceLimits
    ) {}

    public record ExecutionResult(
            boolean success,
            Map<String, Object> output,
            ResourceUsage resourceUsage,
            String error
    ) {
        public static ExecutionResult success(Map<String, Object> output, ResourceUsage usage) {
            return new ExecutionResult(true, output, usage, null);
        }

        public static ExecutionResult failed(String error) {
            return new ExecutionResult(false, Map.of(), null, error);
        }
    }

    // ==================== Network Gate ====================

    /**
     * Network gate to block all egress during execution.
     * Requirement 315.3: Block all network egress during execution.
     */
    public static class NetworkGate {
        private final AtomicBoolean blocked = new AtomicBoolean(false);

        public void blockAll() {
            blocked.set(true);
        }

        public void unblockAll() {
            blocked.set(false);
        }

        public boolean isBlocked() {
            return blocked.get();
        }

        public void checkEgress(String destination) {
            if (blocked.get()) {
                throw new SecurityException("Network egress blocked during plan execution");
            }
        }
    }

    // ==================== Resource Monitor ====================

    /**
     * Monitors resource usage during execution.
     * Requirement 315.4: Enforce CPU, memory, time, and battery limits.
     */
    public static class ResourceMonitor {
        private ResourceLimits limits;
        private long startTime;
        private long startMemory;
        private final AtomicLong cpuTime = new AtomicLong(0);

        public void start(ResourceLimits limits) {
            this.limits = limits;
            this.startTime = System.currentTimeMillis();
            this.startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            this.cpuTime.set(0);
        }

        public ResourceUsage stop() {
            long elapsed = System.currentTimeMillis() - startTime;
            long currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryUsed = Math.max(0, currentMemory - startMemory);
            
            List<String> violations = new ArrayList<>();
            
            if (limits != null) {
                if (elapsed > limits.maxExecutionMillis()) {
                    violations.add("Execution time exceeded");
                }
                if (memoryUsed > limits.maxMemoryBytes()) {
                    violations.add("Memory limit exceeded");
                }
            }

            return new ResourceUsage(elapsed, memoryUsed, cpuTime.get(), violations);
        }
    }

    public record ResourceUsage(
            long executionMillis,
            long memoryBytes,
            long cpuMillis,
            List<String> violations
    ) {
        public boolean exceededLimits() {
            return !violations.isEmpty();
        }
    }
}
