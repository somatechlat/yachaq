package com.yachaq.node.planvm;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.planvm.QueryPlan.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * PlanVM Safety Proofs - Static Validation and Differential Tests.
 * Feature: yachaq-platform, Task 103: PlanVM Safety Proofs
 * Validates: Requirements 356.1, 356.2, 356.3, 356.5
 */
class PlanVMSafetyProofsTest {

    @Test
    void allOperators_haveCompleteSemantics() {
        for (PlanOperator op : PlanOperator.values()) {
            assertThat(op.getName()).as("Operator %s should have a name", op).isNotBlank();
            assertThat(op.getDescription()).as("Operator %s should have a description", op).isNotBlank();
        }
    }

    @Test
    void operatorAllowlist_isCompleteAndClosed() {
        Set<String> allowed = PlanOperator.allowedOperatorNames();
        for (PlanOperator op : PlanOperator.values()) {
            assertThat(allowed).contains(op.getName());
        }
        assertThat(allowed).hasSize(PlanOperator.values().length);
        assertThat(allowed).containsExactlyInAnyOrder(
                "select", "filter", "project", "bucketize", "aggregate",
                "cluster_ref", "redact", "sample", "export", "pack_capsule"
        );
    }

    @Property(tries = 100)
    void validator_rejectsDangerousOperators(@ForAll("dangerousOperatorNames") String operatorName) {
        assertThat(PlanOperator.isAllowed(operatorName)).as("Dangerous operator '%s' should be rejected", operatorName).isFalse();
    }

    @Test
    void validator_enforcesResourceLimits() {
        PlanValidator validator = new PlanValidator();
        QueryPlan excessiveCpu = createPlanWithLimits(new ResourceLimits(100_000, 50_000_000, 30_000, 5));
        assertThat(validator.validate(excessiveCpu, null).valid()).isFalse();
        QueryPlan excessiveMemory = createPlanWithLimits(new ResourceLimits(10_000, 200_000_000, 30_000, 5));
        assertThat(validator.validate(excessiveMemory, null).valid()).isFalse();
        QueryPlan excessiveTime = createPlanWithLimits(new ResourceLimits(10_000, 50_000_000, 300_000, 5));
        assertThat(validator.validate(excessiveTime, null).valid()).isFalse();
        QueryPlan excessiveBattery = createPlanWithLimits(new ResourceLimits(10_000, 50_000_000, 30_000, 20));
        assertThat(validator.validate(excessiveBattery, null).valid()).isFalse();
    }

    @Test
    void validator_enforcesSignatureRequirement() {
        PlanValidator validator = new PlanValidator();
        QueryPlan unsigned = QueryPlan.builder()
                .generateId().contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity")).signature(null).build();
        PlanValidator.ValidationResult result = validator.validate(unsigned, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan is not signed");
    }

    @Test
    void validator_enforcesExpiry() {
        PlanValidator validator = new PlanValidator();
        QueryPlan expired = QueryPlan.builder()
                .generateId().contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity")).signature("valid-sig")
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)).build();
        PlanValidator.ValidationResult result = validator.validate(expired, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Plan has expired");
    }

    @Test
    void validator_enforcesPackCapsuleOrdering() {
        PlanValidator validator = new PlanValidator();
        QueryPlan invalidOrder = QueryPlan.builder()
                .generateId().contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.PACK_CAPSULE, Map.of(), Set.of(), Set.of()))
                .addStep(new PlanStep(1, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity")).signature("valid-sig").build();
        PlanValidator.ValidationResult result = validator.validate(invalidOrder, null);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("PACK_CAPSULE must be the last step");
    }

    @Property(tries = 50)
    void sameInput_producesSameOutput(@ForAll("validPlansAndData") PlanDataPair pair) {
        PlanVM vm1 = new PlanVM();
        PlanVM vm2 = new PlanVM();
        ContractDraft contract = createContract(pair.plan().allowedFields());
        PlanVM.ExecutionResult result1 = vm1.execute(pair.plan(), contract, pair.data());
        PlanVM.ExecutionResult result2 = vm2.execute(pair.plan(), contract, pair.data());
        assertThat(result1.success()).isEqualTo(result2.success());
        if (result1.success() && result2.success()) {
            assertThat(result1.output()).isEqualTo(result2.output());
        }
        vm1.shutdown();
        vm2.shutdown();
    }

    @Test
    void selectOperator_isIdempotent() {
        PlanVM vm = new PlanVM();
        Map<String, Object> data = Map.of("domain:activity", "test", "domain:health", "data");
        QueryPlan singleSelect = createPlanWithSteps(List.of(
                new PlanStep(0, PlanOperator.SELECT, Map.of("criteria", "domain:"), Set.of("domain:activity"), Set.of("domain:activity"))));
        QueryPlan doubleSelect = createPlanWithSteps(List.of(
                new PlanStep(0, PlanOperator.SELECT, Map.of("criteria", "domain:"), Set.of("domain:activity"), Set.of("domain:activity")),
                new PlanStep(1, PlanOperator.SELECT, Map.of("criteria", "domain:"), Set.of("domain:activity"), Set.of("domain:activity"))));
        ContractDraft contract = createContract(Set.of("domain:activity", "domain:health"));
        PlanVM.ExecutionResult result1 = vm.execute(singleSelect, contract, data);
        PlanVM.ExecutionResult result2 = vm.execute(doubleSelect, contract, data);
        assertThat(result1.success()).isTrue();
        assertThat(result2.success()).isTrue();
        assertThat(result1.output()).isEqualTo(result2.output());
        vm.shutdown();
    }

    @Test
    void aggregateOperator_producesConsistentResults() {
        PlanVM vm = new PlanVM();
        Map<String, Object> data = Map.of("value1", 10, "value2", 20, "value3", 30);
        QueryPlan plan = createPlanWithSteps(List.of(
                new PlanStep(0, PlanOperator.AGGREGATE, Map.of("operation", "count"), Set.of("value1", "value2", "value3"), Set.of())));
        ContractDraft contract = createContract(Set.of("value1", "value2", "value3"));
        for (int i = 0; i < 10; i++) {
            PlanVM.ExecutionResult result = vm.execute(plan, contract, data);
            assertThat(result.success()).isTrue();
            assertThat(result.output().get("_aggregate_count")).isEqualTo(3);
        }
        vm.shutdown();
    }

    @Test
    void redactOperator_isIrreversible() {
        PlanVM vm = new PlanVM();
        Map<String, Object> data = new HashMap<>();
        data.put("sensitive", "secret-value");
        data.put("public", "public-value");
        QueryPlan plan = createPlanWithSteps(List.of(
                new PlanStep(0, PlanOperator.REDACT, Map.of(), Set.of("sensitive"), Set.of("sensitive", "public"))));
        ContractDraft contract = createContract(Set.of("sensitive", "public"));
        PlanVM.ExecutionResult result = vm.execute(plan, contract, data);
        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sensitive")).isEqualTo("[REDACTED]");
        assertThat(result.output().get("public")).isEqualTo("public-value");
        assertThat(result.output().values()).doesNotContain("secret-value");
        vm.shutdown();
    }

    @Test
    void allOperators_canBeExecuted() {
        PlanVM vm = new PlanVM();
        Map<String, Object> data = Map.of("field1", "value1", "field2", 100, "field3", "value3");
        Set<String> fields = Set.of("field1", "field2", "field3");
        ContractDraft contract = createContract(fields);
        for (PlanOperator op : PlanOperator.values()) {
            QueryPlan plan = createPlanForOperator(op, fields);
            PlanVM.ExecutionResult result = vm.execute(plan, contract, data);
            assertThat(result.success()).as("Operator %s should execute successfully", op).isTrue();
        }
        vm.shutdown();
    }

    @Test
    void preview_worksForAllOperators() {
        PlanVM vm = new PlanVM();
        Set<String> fields = Set.of("field1", "field2");
        for (PlanOperator op : PlanOperator.values()) {
            QueryPlan plan = createPlanForOperator(op, fields);
            PlanVM.PreviewResult preview = vm.preview(plan);
            assertThat(preview.stepDescriptions()).as("Preview for %s should have descriptions", op).isNotEmpty();
            assertThat(preview.privacyImpactScore()).as("Preview for %s should have privacy score", op).isNotNull();
        }
        vm.shutdown();
    }

    @Test
    void networkGate_blocksAllEgress() {
        PlanVM.NetworkGate gate = new PlanVM.NetworkGate();
        assertThat(gate.isBlocked()).isFalse();
        assertThatCode(() -> gate.checkEgress("http://example.com")).doesNotThrowAnyException();
        gate.blockAll();
        assertThat(gate.isBlocked()).isTrue();
        String[] destinations = {"http://example.com", "https://api.service.com", "tcp://database:5432", "udp://dns:53"};
        for (String dest : destinations) {
            assertThatThrownBy(() -> gate.checkEgress(dest)).isInstanceOf(SecurityException.class);
        }
        gate.unblockAll();
        assertThat(gate.isBlocked()).isFalse();
    }

    @Test
    void resourceMonitor_tracksUsage() {
        PlanVM.ResourceMonitor monitor = new PlanVM.ResourceMonitor();
        ResourceLimits limits = ResourceLimits.defaults();
        monitor.start(limits);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        PlanVM.ResourceUsage usage = monitor.stop();
        assertThat(usage.executionMillis()).isGreaterThanOrEqualTo(100);
        assertThat(usage.memoryBytes()).isGreaterThanOrEqualTo(0);
    }

    @Provide
    Arbitrary<String> dangerousOperatorNames() {
        return Arbitraries.of("execute", "eval", "exec", "run", "system", "shell", "cmd",
                "http", "https", "fetch", "request", "socket", "connect", "network",
                "file", "read", "write", "delete", "mkdir", "rmdir", "fs",
                "sql", "query", "insert", "update", "drop", "truncate",
                "spawn", "fork", "process", "thread", "kill", "reflect", "invoke", "class", "method", "field");
    }

    @Provide
    Arbitrary<PlanDataPair> validPlansAndData() {
        return Arbitraries.of("domain:activity", "domain:health", "time:morning")
                .set().ofMinSize(1).ofMaxSize(3)
                .map(labels -> {
                    QueryPlan plan = createValidPlan(labels);
                    Map<String, Object> data = new HashMap<>();
                    for (String label : labels) { data.put(label, "test-value-" + label); }
                    return new PlanDataPair(plan, data);
                });
    }

    private QueryPlan createPlanWithLimits(ResourceLimits limits) {
        return QueryPlan.builder().generateId().contractId("contract-1")
                .addStep(new PlanStep(0, PlanOperator.SELECT, Map.of(), Set.of(), Set.of()))
                .allowedFields(Set.of("domain:activity")).resourceLimits(limits).signature("valid-signature").build();
    }

    private QueryPlan createPlanWithSteps(List<PlanStep> steps) {
        Set<String> allFields = new HashSet<>();
        for (PlanStep step : steps) { allFields.addAll(step.inputFields()); allFields.addAll(step.outputFields()); }
        if (allFields.isEmpty()) { allFields.add("domain:activity"); }
        QueryPlan.Builder builder = QueryPlan.builder().generateId().contractId("contract-1")
                .allowedFields(allFields).resourceLimits(ResourceLimits.defaults()).signature("valid-signature");
        for (PlanStep step : steps) { builder.addStep(step); }
        return builder.build();
    }

    private QueryPlan createValidPlan(Set<String> labels) {
        List<PlanStep> steps = new ArrayList<>();
        steps.add(new PlanStep(0, PlanOperator.SELECT, Map.of("criteria", "*"), labels, labels));
        steps.add(new PlanStep(1, PlanOperator.AGGREGATE, Map.of("operation", "count"), labels, Set.of()));
        return QueryPlan.builder().generateId().contractId("contract-1").steps(steps).allowedFields(labels)
                .outputConfig(new OutputConfig(OutputConfig.OutputMode.AGGREGATE_ONLY, 100, 10000, false))
                .resourceLimits(ResourceLimits.defaults()).signature("valid-signature-" + UUID.randomUUID()).build();
    }

    private QueryPlan createPlanForOperator(PlanOperator op, Set<String> fields) {
        Map<String, Object> params = switch (op) {
            case SELECT -> Map.of("criteria", "*");
            case FILTER -> Map.of("field", fields.iterator().next());
            case PROJECT -> Map.of();
            case BUCKETIZE -> Map.of("field", "field2", "bucketSize", 10);
            case AGGREGATE -> Map.of("operation", "count");
            case CLUSTER_REF -> Map.of();
            case REDACT -> Map.of();
            case SAMPLE -> Map.of("rate", 0.5);
            case EXPORT -> Map.of("format", "json");
            case PACK_CAPSULE -> Map.of("ttl", 3600);
        };
        return QueryPlan.builder().generateId().contractId("contract-1")
                .addStep(new PlanStep(0, op, params, fields, fields))
                .allowedFields(fields).resourceLimits(ResourceLimits.defaults()).signature("valid-signature").build();
    }

    private ContractDraft createContract(Set<String> labels) {
        return new ContractDraft(UUID.randomUUID().toString(), "request-1", "requester-1", "ds-node-1",
                labels, null, OutputMode.AGGREGATE_ONLY, IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"), "escrow-1", Instant.now().plusSeconds(3600),
                ObligationTerms.standard(), "nonce-" + UUID.randomUUID(), Instant.now(), Map.of());
    }

    record PlanDataPair(QueryPlan plan, Map<String, Object> data) {}
}
