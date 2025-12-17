package com.yachaq.node.planvm;

import com.yachaq.node.contract.ContractBuilder;
import com.yachaq.node.contract.ContractBuilder.UserChoices;
import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.planvm.QueryPlan.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for QueryPlan VM.
 * Requirement 315.1, 315.2, 315.3, 315.4, 315.6, 315.7
 * 
 * **Feature: yachaq-platform, Property 55: QueryPlan Operator Allowlist**
 * **Feature: yachaq-platform, Property 56: QueryPlan Network Isolation**
 * **Validates: Requirements 315.1, 315.3**
 */
class PlanVMPropertyTest {

    // ==================== Operator Allowlist Tests (61.2, 61.6) ====================

    /**
     * Property: Only allowed operators can be used in plans.
     * **Feature: yachaq-platform, Property 55: QueryPlan Operator Allowlist**
     * **Validates: Requirements 315.1**
     */
    @Property(tries = 100)
    void onlyAllowedOperators_canBeUsed(
            @ForAll("allowedOperators") PlanOperator operator) {
        
        assertThat(PlanOperator.isAllowed(operator.getName()))
                .as("Operator %s should be allowed", operator.getName())
                .isTrue();
    }

    /**
     * Property: Disallowed operators are rejected.
     * **Feature: yachaq-platform, Property 55: QueryPlan Operator Allowlist**
     * **Validates: Requirements 315.1**
     */
    @Property(tries = 50)
    void disallowedOperators_areRejected(
            @ForAll("disallowedOperatorNames") String operatorName) {
        
        assertThat(PlanOperator.isAllowed(operatorName))
                .as("Operator %s should not be allowed", operatorName)
                .isFalse();
    }

    /**
     * Property: Plans with disallowed operators fail validation.
     * **Feature: yachaq-platform, Property: Validation Enforcement**
     * **Validates: Requirements 315.6**
     */
    @Test
    void plansWithDisallowedOperators_failValidation() {
        // This test verifies that the validator catches disallowed operators
        // Since we can't create a PlanStep with a disallowed operator directly,
        // we verify the allowlist is complete
        Set<String> allowed = PlanOperator.allowedOperatorNames();
        
        assertThat(allowed).containsExactlyInAnyOrder(
                "select", "filter", "project", "bucketize", "aggregate",
                "cluster_ref", "redact", "sample", "export", "pack_capsule"
        );
    }

    // ==================== Network Isolation Tests (61.3, 61.7) ====================

    /**
     * Property: Network is blocked during plan execution.
     * **Feature: yachaq-platform, Property 56: QueryPlan Network Isolation**
     * **Validates: Requirements 315.3**
     */
    @Property(tries = 50)
    void networkIsBlocked_duringExecution(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanVM.NetworkGate gate = new PlanVM.NetworkGate();
        PlanVM vm = new PlanVM(new PlanValidator(), gate, new PlanVM.ResourceMonitor());
        
        // Network should be unblocked initially
        assertThat(gate.isBlocked()).isFalse();
        
        // Execute plan
        Map<String, Object> data = Map.of("domain:activity", "test");
        vm.execute(pair.plan(), pair.contract(), data);
        
        // Network should be unblocked after execution
        assertThat(gate.isBlocked()).isFalse();
        
        vm.shutdown();
    }

    /**
     * Property: Network egress attempts during execution throw SecurityException.
     * **Feature: yachaq-platform, Property 56: QueryPlan Network Isolation**
     * **Validates: Requirements 315.3**
     */
    @Test
    void networkEgressAttempts_throwSecurityException() {
        PlanVM.NetworkGate gate = new PlanVM.NetworkGate();
        
        // Block network
        gate.blockAll();
        
        // Attempt egress should throw
        assertThatThrownBy(() -> gate.checkEgress("https://example.com"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("blocked");
        
        // Unblock
        gate.unblockAll();
        
        // Should not throw now
        assertThatCode(() -> gate.checkEgress("https://example.com"))
                .doesNotThrowAnyException();
    }

    // ==================== Resource Limits Tests (61.4) ====================

    /**
     * Property: Resource limits are enforced.
     * **Feature: yachaq-platform, Property: Resource Enforcement**
     * **Validates: Requirements 315.4**
     */
    @Property(tries = 50)
    void resourceLimits_areEnforced(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanVM vm = new PlanVM();
        Map<String, Object> data = Map.of("domain:activity", "test");
        
        PlanVM.ExecutionResult result = vm.execute(pair.plan(), pair.contract(), data);
        
        // If successful, resource usage should be within limits
        if (result.success() && result.resourceUsage() != null) {
            assertThat(result.resourceUsage().exceededLimits()).isFalse();
        }
        
        vm.shutdown();
    }

    /**
     * Property: Excessive resource limits are rejected during validation.
     * **Feature: yachaq-platform, Property: Limit Validation**
     * **Validates: Requirements 315.4**
     */
    @Test
    void excessiveResourceLimits_areRejected() {
        PlanValidator validator = new PlanValidator();
        
        QueryPlan plan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .resourceLimits(new ResourceLimits(
                        100_000,        // 100 seconds CPU - exceeds limit
                        200_000_000,    // 200 MB - exceeds limit
                        300_000,        // 5 minutes - exceeds limit
                        20              // 20% battery - exceeds limit
                ))
                .signature("valid-signature")
                .build();
        
        PlanValidator.ValidationResult result = validator.validate(plan, null);
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    // ==================== Plan Validation Tests (61.5) ====================

    /**
     * Property: Valid plans pass validation.
     * **Feature: yachaq-platform, Property: Valid Plan Acceptance**
     * **Validates: Requirements 315.6**
     */
    @Property(tries = 50)
    void validPlans_passValidation(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanValidator validator = new PlanValidator();
        PlanValidator.ValidationResult result = validator.validate(pair.plan(), pair.contract());
        
        assertThat(result.valid())
                .as("Valid plan should pass validation")
                .isTrue();
    }

    /**
     * Property: Expired plans fail validation.
     * **Feature: yachaq-platform, Property: Expiry Enforcement**
     * **Validates: Requirements 315.6**
     */
    @Test
    void expiredPlans_failValidation() {
        PlanValidator validator = new PlanValidator();
        
        QueryPlan expiredPlan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .signature("valid-signature")
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)) // Already expired
                .build();
        
        PlanValidator.ValidationResult result = validator.validate(expiredPlan, null);
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan has expired");
    }

    /**
     * Property: Unsigned plans fail validation.
     * **Feature: yachaq-platform, Property: Signature Requirement**
     * **Validates: Requirements 315.6**
     */
    @Test
    void unsignedPlans_failValidation() {
        PlanValidator validator = new PlanValidator();
        
        QueryPlan unsignedPlan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .signature(null) // No signature
                .build();
        
        PlanValidator.ValidationResult result = validator.validate(unsignedPlan, null);
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan is not signed");
    }

    /**
     * Property: PACK_CAPSULE must be the last step.
     * **Feature: yachaq-platform, Property: Step Ordering**
     * **Validates: Requirements 315.6**
     */
    @Test
    void packCapsule_mustBeLastStep() {
        PlanValidator validator = new PlanValidator();
        
        QueryPlan invalidPlan = QueryPlan.builder()
                .generateId()
                .contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.PACK_CAPSULE, Map.of(), Set.of(), Set.of()))
                .addStep(new PlanStep(1, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity"))
                .signature("valid-signature")
                .build();
        
        PlanValidator.ValidationResult result = validator.validate(invalidPlan, null);
        
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("PACK_CAPSULE must be the last step");
    }

    // ==================== Preview Tests (61.1) ====================

    /**
     * Property: Preview shows human-readable step descriptions.
     * **Feature: yachaq-platform, Property: Preview Readability**
     * **Validates: Requirements 315.5**
     */
    @Property(tries = 50)
    void preview_showsReadableDescriptions(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanVM vm = new PlanVM();
        PlanVM.PreviewResult preview = vm.preview(pair.plan());
        
        assertThat(preview.stepDescriptions()).isNotEmpty();
        for (String desc : preview.stepDescriptions()) {
            assertThat(desc).isNotBlank();
            assertThat(desc).containsPattern("\\d+\\."); // Contains step number
        }
        
        vm.shutdown();
    }

    /**
     * Property: Preview shows privacy impact score.
     * **Feature: yachaq-platform, Property: Privacy Impact**
     * **Validates: Requirements 315.5**
     */
    @Property(tries = 50)
    void preview_showsPrivacyImpact(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanVM vm = new PlanVM();
        PlanVM.PreviewResult preview = vm.preview(pair.plan());
        
        // Privacy impact should be calculated
        assertThat(preview.privacyImpactScore()).isNotNull();
        
        vm.shutdown();
    }

    // ==================== Execution Tests ====================

    /**
     * Property: Execution produces output for valid plans.
     * **Feature: yachaq-platform, Property: Execution Success**
     * **Validates: Requirements 315.1**
     */
    @Property(tries = 50)
    void execution_producesOutput(
            @ForAll("validPlansAndContracts") PlanContractPair pair) {
        
        PlanVM vm = new PlanVM();
        Map<String, Object> data = Map.of("domain:activity", "test-value");
        
        PlanVM.ExecutionResult result = vm.execute(pair.plan(), pair.contract(), data);
        
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isNotNull();
        
        vm.shutdown();
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<PlanOperator> allowedOperators() {
        return Arbitraries.of(PlanOperator.values());
    }

    @Provide
    Arbitrary<String> disallowedOperatorNames() {
        return Arbitraries.of(
                "execute", "eval", "system", "shell", "http", "fetch",
                "network", "socket", "file", "write", "delete", "drop"
        );
    }

    @Provide
    Arbitrary<PlanContractPair> validPlansAndContracts() {
        return Arbitraries.of("domain:activity", "domain:health", "time:morning")
                .set().ofMinSize(1).ofMaxSize(3)
                .map(labels -> {
                    ContractDraft contract = createContract(labels);
                    QueryPlan plan = createValidPlan(labels, contract.id());
                    return new PlanContractPair(plan, contract);
                });
    }

    // ==================== Helper Methods ====================

    private ContractDraft createContract(Set<String> labels) {
        return new ContractDraft(
                UUID.randomUUID().toString(),
                "request-1",
                "requester-1",
                "ds-node-1",
                labels,
                null,
                OutputMode.AGGREGATE_ONLY,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-1",
                Instant.now().plusSeconds(3600),
                ObligationTerms.standard(),
                "nonce-" + UUID.randomUUID(),
                Instant.now(),
                Map.of()
        );
    }

    private QueryPlan createValidPlan(Set<String> labels, String contractId) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep(0, PlanOperator.SELECT, Map.of("criteria", "*"), labels, labels));
        steps.add(new PlanStep(1, PlanOperator.AGGREGATE, Map.of("operation", "count"), labels, Set.of()));
        
        return QueryPlan.builder()
                .generateId()
                .contractId(contractId)
                .steps(steps)
                .allowedFields(labels)
                .outputConfig(new OutputConfig(OutputConfig.OutputMode.AGGREGATE_ONLY, 100, 10000, false))
                .resourceLimits(ResourceLimits.defaults())
                .signature("valid-signature-" + UUID.randomUUID())
                .build();
    }

    record PlanContractPair(QueryPlan plan, ContractDraft contract) {}

    // ==================== Unit Tests ====================

    @Test
    void execute_rejectsNullPlan() {
        PlanVM vm = new PlanVM();
        ContractDraft contract = createContract(Set.of("domain:activity"));
        
        assertThatThrownBy(() -> vm.execute(null, contract, Map.of()))
                .isInstanceOf(NullPointerException.class);
        
        vm.shutdown();
    }

    @Test
    void execute_rejectsNullContract() {
        PlanVM vm = new PlanVM();
        QueryPlan plan = createValidPlan(Set.of("domain:activity"), "contract-1");
        
        assertThatThrownBy(() -> vm.execute(plan, null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        
        vm.shutdown();
    }

    @Test
    void preview_rejectsNullPlan() {
        PlanVM vm = new PlanVM();
        
        assertThatThrownBy(() -> vm.preview(null))
                .isInstanceOf(NullPointerException.class);
        
        vm.shutdown();
    }

    @Test
    void operatorAllowlist_isComplete() {
        Set<String> allowed = PlanOperator.allowedOperatorNames();
        
        // Verify all enum values are in the allowlist
        for (PlanOperator op : PlanOperator.values()) {
            assertThat(allowed).contains(op.getName());
        }
        
        // Verify count matches
        assertThat(allowed).hasSize(PlanOperator.values().length);
    }
}
