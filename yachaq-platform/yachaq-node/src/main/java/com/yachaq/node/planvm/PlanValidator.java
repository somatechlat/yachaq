package com.yachaq.node.planvm;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.planvm.QueryPlan.*;

import java.util.*;

/**
 * Static validator for query plans.
 * Requirement 315.6: Reject plans with disallowed operators, scope violations, output conflicts.
 */
public class PlanValidator {

    /**
     * Validates a query plan against contract constraints.
     */
    public ValidationResult validate(QueryPlan plan, ContractDraft contract) {
        Objects.requireNonNull(plan, "Plan cannot be null");
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check expiration
        if (plan.isExpired()) {
            errors.add("Plan has expired");
        }

        // Check signature
        if (!plan.isSigned()) {
            errors.add("Plan is not signed");
        }

        // Validate operators
        validateOperators(plan, errors);

        // Validate field access
        if (contract != null) {
            validateFieldAccess(plan, contract, errors);
        }

        // Validate output configuration
        validateOutputConfig(plan, contract, errors, warnings);

        // Validate resource limits
        validateResourceLimits(plan, errors);

        // Validate step ordering
        validateStepOrdering(plan, errors);

        return new ValidationResult(
                errors.isEmpty(),
                errors,
                warnings
        );
    }

    /**
     * Validates that all operators are allowed.
     * Requirement 315.1: Allow only specific operators.
     */
    private void validateOperators(QueryPlan plan, List<String> errors) {
        for (PlanStep step : plan.steps()) {
            if (!PlanOperator.isAllowed(step.operator().getName())) {
                errors.add("Disallowed operator: " + step.operator().getName());
            }
        }
    }

    /**
     * Validates field access against contract scope.
     * Requirement 315.6: Reject plans with scope violations.
     */
    private void validateFieldAccess(QueryPlan plan, ContractDraft contract, List<String> errors) {
        Set<String> allowedFields = contract.selectedLabels();
        
        for (PlanStep step : plan.steps()) {
            for (String field : step.inputFields()) {
                if (!isFieldAllowed(field, allowedFields)) {
                    errors.add("Field not in contract scope: " + field);
                }
            }
        }
    }

    /**
     * Checks if a field is allowed by the contract.
     */
    private boolean isFieldAllowed(String field, Set<String> allowedFields) {
        // Direct match
        if (allowedFields.contains(field)) {
            return true;
        }
        
        // Wildcard match (e.g., "domain:*" allows "domain:activity")
        for (String allowed : allowedFields) {
            if (allowed.endsWith("*")) {
                String prefix = allowed.substring(0, allowed.length() - 1);
                if (field.startsWith(prefix)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Validates output configuration.
     * Requirement 315.6: Reject plans with output conflicts.
     */
    private void validateOutputConfig(QueryPlan plan, ContractDraft contract, 
                                       List<String> errors, List<String> warnings) {
        OutputConfig config = plan.outputConfig();
        
        // Check export permission
        if (config.allowExport() && contract != null) {
            if (contract.outputMode() == com.yachaq.node.inbox.DataRequest.OutputMode.AGGREGATE_ONLY) {
                errors.add("Export not allowed for aggregate-only contracts");
            }
        }

        // Check record limits
        if (config.maxRecords() > 10000) {
            warnings.add("Large record limit may impact performance");
        }

        // Check byte limits
        if (config.maxBytes() > 10_000_000) {
            warnings.add("Large byte limit may impact memory");
        }
    }

    /**
     * Validates resource limits are reasonable.
     */
    private void validateResourceLimits(QueryPlan plan, List<String> errors) {
        ResourceLimits limits = plan.resourceLimits();
        
        if (limits.maxCpuMillis() > 60_000) {
            errors.add("CPU limit exceeds maximum (60 seconds)");
        }
        
        if (limits.maxMemoryBytes() > 100_000_000) {
            errors.add("Memory limit exceeds maximum (100 MB)");
        }
        
        if (limits.maxExecutionMillis() > 120_000) {
            errors.add("Execution time limit exceeds maximum (2 minutes)");
        }
        
        if (limits.maxBatteryPercent() > 10) {
            errors.add("Battery limit exceeds maximum (10%)");
        }
    }

    /**
     * Validates step ordering and dependencies.
     */
    private void validateStepOrdering(QueryPlan plan, List<String> errors) {
        boolean hasPackCapsule = false;
        
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            
            // PACK_CAPSULE must be last
            if (step.operator() == PlanOperator.PACK_CAPSULE) {
                if (i != plan.steps().size() - 1) {
                    errors.add("PACK_CAPSULE must be the last step");
                }
                hasPackCapsule = true;
            }
            
            // EXPORT cannot follow PACK_CAPSULE
            if (step.operator() == PlanOperator.EXPORT && hasPackCapsule) {
                errors.add("EXPORT cannot follow PACK_CAPSULE");
            }
        }
    }

    /**
     * Validation result.
     */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {
        public ValidationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }
    }
}
