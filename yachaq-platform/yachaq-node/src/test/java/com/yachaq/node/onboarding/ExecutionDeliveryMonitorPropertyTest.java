package com.yachaq.node.onboarding;

import com.yachaq.node.capsule.CapsuleHeader;
import com.yachaq.node.capsule.CapsuleHeader.CapsuleSummary;
import com.yachaq.node.capsule.TimeCapsule;
import com.yachaq.node.capsule.TimeCapsule.*;
import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.onboarding.ExecutionDeliveryMonitor.*;
import com.yachaq.node.planvm.PlanOperator;
import com.yachaq.node.planvm.PlanVM.ResourceUsage;
import com.yachaq.node.planvm.QueryPlan;
import com.yachaq.node.planvm.QueryPlan.*;
import com.yachaq.node.transport.P2PSession;
import com.yachaq.node.transport.P2PTransport;
import com.yachaq.node.transport.P2PTransport.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ExecutionDeliveryMonitor.
 * 
 * Validates: Requirements 345.1, 345.2, 345.3, 345.4, 345.5
 */
class ExecutionDeliveryMonitorPropertyTest {

    // ==================== Test Fixtures ====================

    private ExecutionDeliveryMonitor createMonitor() {
        KeyManagementService keyMgmt = new KeyManagementService();
        P2PTransport transport = new P2PTransport(keyMgmt, "test-node-id");
        return new ExecutionDeliveryMonitor(transport);
    }

    private QueryPlan createQueryPlan(String planId, int stepCount) {
        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new PlanStep(
                    i,
                    PlanOperator.SELECT,
                    Map.of("criteria", "*"),
                    Set.of("field" + i),
                    Set.of("output" + i)
            ));
        }

        return QueryPlan.builder()
                .id(planId)
                .contractId("contract-" + planId)
                .steps(steps)
                .allowedFields(Set.of("field1", "field2"))
                .outputConfig(new OutputConfig(OutputConfig.OutputMode.AGGREGATE_ONLY, 100, 10000, false))
                .resourceLimits(new ResourceLimits(30000, 100 * 1024 * 1024, 10000, 5))
                .build();
    }

    private TimeCapsule createCapsule(String capsuleId, byte[] encryptedData) {
        CapsuleHeader header = CapsuleHeader.builder()
                .capsuleId(capsuleId)
                .planId("plan-" + capsuleId)
                .contractId("contract-" + capsuleId)
                .ttl(Instant.now().plusSeconds(3600))
                .dsNodeId("ds-node-1")
                .requesterId("requester-1")
                .summary(CapsuleSummary.of(10, Set.of("field1"), encryptedData.length, "AGGREGATE_ONLY"))
                .build();

        EncryptedPayload payload = new EncryptedPayload(
                encryptedData,
                new byte[256], // encrypted key
                new byte[12],  // IV
                "key-" + capsuleId,
                "AES/GCM/NoPadding",
                "fingerprint-123"
        );

        CapsuleProofs proofs = CapsuleProofs.builder()
                .capsuleHash("hash-" + capsuleId)
                .dsSignature("sig-" + capsuleId)
                .contractId("contract-" + capsuleId)
                .build();

        return TimeCapsule.builder()
                .header(header)
                .payload(payload)
                .proofs(proofs)
                .status(CapsuleStatus.CREATED)
                .build();
    }

    private P2PSession createSession(String sessionId) {
        return P2PSession.builder()
                .sessionId(sessionId)
                .localNodeId("local-node")
                .remoteNodeId("remote-node")
                .state(P2PSession.SessionState.CONNECTED)
                .protocol(P2PSession.TransportProtocol.WEBRTC_DATACHANNEL)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private byte[] generateHighEntropyData(int size) {
        byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        return data;
    }

    private byte[] generateLowEntropyData(int size) {
        byte[] data = new byte[size];
        // Fill with repeating pattern (low entropy)
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 4);
        }
        return data;
    }

    // ==================== Task 89.1: Execution Progress Tests ====================

    @Test
    void executionProgress_showsCorrectPercentage() {
        // Requirement 345.1: Show progress
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-1", 10);

        ExecutionState state = monitor.startExecution("exec-1", plan);
        assertThat(state.progressPercent()).isEqualTo(0);

        state = monitor.updateExecutionProgress("exec-1", 5, ExecutionPhase.EXECUTING, null);
        assertThat(state.progressPercent()).isEqualTo(50.0);

        state = monitor.updateExecutionProgress("exec-1", 10, ExecutionPhase.COMPLETED, null);
        assertThat(state.progressPercent()).isEqualTo(100.0);
    }

    @Test
    void executionProgress_showsResourceUsage() {
        // Requirement 345.1: Show resource usage
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-1", 5);

        monitor.startExecution("exec-1", plan);

        ResourceUsage usage = new ResourceUsage(1500, 50 * 1024 * 1024, 1000, List.of());
        ExecutionState state = monitor.updateExecutionProgress("exec-1", 3, ExecutionPhase.EXECUTING, usage);

        assertThat(state.resourceMetrics().executionMillis()).isEqualTo(1500);
        assertThat(state.resourceMetrics().memoryBytes()).isEqualTo(50 * 1024 * 1024);
        assertThat(state.resourceMetrics().cpuMillis()).isEqualTo(1000);
    }

    @Test
    void executionProgress_displaysCorrectPhase() {
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-1", 5);

        ExecutionState state = monitor.startExecution("exec-1", plan);
        assertThat(state.phase()).isEqualTo(ExecutionPhase.INITIALIZING);

        state = monitor.updateExecutionProgress("exec-1", 2, ExecutionPhase.EXECUTING, null);
        assertThat(state.phase()).isEqualTo(ExecutionPhase.EXECUTING);

        state = monitor.updateExecutionProgress("exec-1", 5, ExecutionPhase.PACKAGING, null);
        assertThat(state.phase()).isEqualTo(ExecutionPhase.PACKAGING);
    }

    @Test
    void executionProgressDisplay_containsAllRequiredInfo() {
        // Requirement 345.1: Show progress, resource usage, transfer stats
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-1", 5);

        monitor.startExecution("exec-1", plan);
        ResourceUsage usage = new ResourceUsage(2000, 30 * 1024 * 1024, 1500, List.of());
        monitor.updateExecutionProgress("exec-1", 3, ExecutionPhase.EXECUTING, usage);

        ExecutionProgressDisplay display = monitor.getExecutionProgress("exec-1");

        assertThat(display).isNotNull();
        assertThat(display.executionId()).isEqualTo("exec-1");
        assertThat(display.planId()).isEqualTo("plan-1");
        assertThat(display.progressPercent()).isEqualTo(60.0);
        assertThat(display.phase()).isEqualTo(ExecutionPhase.EXECUTING);
        assertThat(display.statusMessage()).isNotEmpty();
        assertThat(display.elapsed()).isNotNull();
        assertThat(display.resourceIndicators()).isNotEmpty();
    }

    @Property
    void executionProgress_progressNeverExceeds100(@ForAll("stepCounts") int totalSteps,
                                                    @ForAll("completedStepCounts") int completed) {
        // Property: Progress percentage should never exceed 100%
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-prop", totalSteps);

        monitor.startExecution("exec-prop", plan);
        int actualCompleted = Math.min(completed, totalSteps);
        ExecutionState state = monitor.updateExecutionProgress("exec-prop", actualCompleted, 
                ExecutionPhase.EXECUTING, null);

        assertThat(state.progressPercent()).isBetween(0.0, 100.0);
    }

    // ==================== Task 89.2: Ciphertext Indicator Tests ====================

    @Test
    void ciphertextVerification_detectsEncryptedData() {
        // Requirement 345.2: Show that only ciphertext is transmitted
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(1024);
        CiphertextVerificationResult result = monitor.verifyCiphertext(encryptedData);

        assertThat(result.isCiphertext()).isTrue();
        assertThat(result.entropyScore()).isGreaterThanOrEqualTo(7.0);
        assertThat(result.proof()).isNotNull();
    }

    @Test
    void ciphertextVerification_rejectsPlaintext() {
        // Requirement 345.2: Show that only ciphertext is transmitted
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] plaintext = generateLowEntropyData(1024);
        CiphertextVerificationResult result = monitor.verifyCiphertext(plaintext);

        assertThat(result.isCiphertext()).isFalse();
        assertThat(result.entropyScore()).isLessThan(7.0);
        assertThat(result.reason()).contains("entropy");
    }

    @Test
    void ciphertextIndicator_showsVerificationStatus() {
        // Requirement 345.2: Show that only ciphertext is transmitted
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(512);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        DeliveryState state = monitor.startDelivery("delivery-1", capsule, session);
        CiphertextIndicator indicator = monitor.getCiphertextIndicator("delivery-1");

        assertThat(indicator.verified()).isTrue();
        assertThat(indicator.secure()).isTrue();
        assertThat(indicator.status()).isEqualTo(EncryptionStatus.VERIFIED);
        assertThat(indicator.message()).contains("encrypted");
    }

    @Test
    void ciphertextVerification_rejectsTooSmallData() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] tooSmall = new byte[10];
        CiphertextVerificationResult result = monitor.verifyCiphertext(tooSmall);

        assertThat(result.isCiphertext()).isFalse();
        assertThat(result.reason()).contains("too small");
    }

    @Property
    void ciphertextVerification_highEntropyAlwaysVerified(@ForAll("largeSizes") int size) {
        // Property: High entropy data (large enough) should always be verified as ciphertext
        // Note: Small random samples may not have high entropy due to statistical variance
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] data = generateHighEntropyData(size);
        CiphertextVerificationResult result = monitor.verifyCiphertext(data);

        // For large enough random data, entropy should be high
        assertThat(result.isCiphertext()).isTrue();
        assertThat(result.entropyScore()).isGreaterThanOrEqualTo(7.0);
    }

    // ==================== Task 89.3: Resumability Tests ====================

    @Test
    void resumabilityControls_availableForInterruptedDelivery() {
        // Requirement 345.3: Provide resume options for interrupted transfers
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(512);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        monitor.startDelivery("delivery-1", capsule, session);
        monitor.markDeliveryInterrupted("delivery-1", "Network disconnected");

        ResumabilityControls controls = monitor.getResumabilityControls("delivery-1");

        assertThat(controls.canResume()).isTrue();
        assertThat(controls.isInterrupted()).isTrue();
        assertThat(controls.options()).isNotEmpty();
        assertThat(controls.options()).extracting(ResumeOption::id)
                .contains("RESUME_NOW", "RESUME_WIFI", "CANCEL");
    }

    @Test
    void resumabilityControls_notAvailableForCompletedDelivery() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(512);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        monitor.startDelivery("delivery-1", capsule, session);

        // Simulate completion
        TransferResult result = TransferResult.success("transfer-1", 512, List.of());
        monitor.updateDeliveryProgress("delivery-1", result);

        ResumabilityControls controls = monitor.getResumabilityControls("delivery-1");

        assertThat(controls.canResume()).isFalse();
        assertThat(controls.options()).isEmpty();
    }

    @Test
    void deliveryProgress_tracksChunksAndBytes() {
        // Requirement 345.1: Show transfer stats
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(1024);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        monitor.startDelivery("delivery-1", capsule, session);

        TransferResult partialResult = TransferResult.partial("transfer-1", 5, 10);
        DeliveryState state = monitor.updateDeliveryProgress("delivery-1", partialResult);

        assertThat(state.chunksTransferred()).isEqualTo(5);
        assertThat(state.stats().totalChunks()).isEqualTo(10);
        assertThat(state.phase()).isEqualTo(DeliveryPhase.TRANSFERRING);
    }

    @Test
    void deliveryProgressDisplay_containsAllRequiredInfo() {
        // Requirement 345.1: Show progress, resource usage, transfer stats
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] encryptedData = generateHighEntropyData(1024);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        monitor.startDelivery("delivery-1", capsule, session);

        DeliveryProgressDisplay display = monitor.getDeliveryProgress("delivery-1");

        assertThat(display).isNotNull();
        assertThat(display.deliveryId()).isEqualTo("delivery-1");
        assertThat(display.capsuleId()).isEqualTo("capsule-1");
        assertThat(display.ciphertextIndicator()).isNotNull();
        assertThat(display.resumeControls()).isNotNull();
        assertThat(display.statusMessage()).isNotEmpty();
    }

    // ==================== Security Tests ====================

    @Test
    void delivery_rejectsNonCiphertextData() {
        // Security: Cannot deliver non-encrypted data
        ExecutionDeliveryMonitor monitor = createMonitor();

        byte[] plaintext = generateLowEntropyData(512);
        TimeCapsule capsule = createCapsule("capsule-bad", plaintext);
        P2PSession session = createSession("session-1");

        assertThatThrownBy(() -> monitor.startDelivery("delivery-bad", capsule, session))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("non-ciphertext");
    }

    // ==================== Event Listener Tests ====================

    @Test
    void eventListener_receivesExecutionEvents() {
        ExecutionDeliveryMonitor monitor = createMonitor();
        List<MonitorEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        QueryPlan plan = createQueryPlan("plan-1", 5);
        monitor.startExecution("exec-1", plan);
        monitor.updateExecutionProgress("exec-1", 3, ExecutionPhase.EXECUTING, null);
        monitor.completeExecution("exec-1", true, "Done");

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(MonitorEventType.EXECUTION_STARTED);
        assertThat(events.get(1).type()).isEqualTo(MonitorEventType.EXECUTION_PROGRESS);
        assertThat(events.get(2).type()).isEqualTo(MonitorEventType.EXECUTION_COMPLETED);
    }

    @Test
    void eventListener_receivesDeliveryEvents() {
        ExecutionDeliveryMonitor monitor = createMonitor();
        List<MonitorEvent> events = new ArrayList<>();
        monitor.addListener(events::add);

        byte[] encryptedData = generateHighEntropyData(512);
        TimeCapsule capsule = createCapsule("capsule-1", encryptedData);
        P2PSession session = createSession("session-1");

        monitor.startDelivery("delivery-1", capsule, session);
        monitor.markDeliveryInterrupted("delivery-1", "Test interruption");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(MonitorEventType.DELIVERY_STARTED);
        assertThat(events.get(1).type()).isEqualTo(MonitorEventType.DELIVERY_INTERRUPTED);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullTransport() {
        assertThatThrownBy(() -> new ExecutionDeliveryMonitor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void startExecution_rejectsNullExecutionId() {
        ExecutionDeliveryMonitor monitor = createMonitor();
        QueryPlan plan = createQueryPlan("plan-1", 5);

        assertThatThrownBy(() -> monitor.startExecution(null, plan))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void startExecution_rejectsNullPlan() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        assertThatThrownBy(() -> monitor.startExecution("exec-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateExecutionProgress_rejectsUnknownExecution() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        assertThatThrownBy(() -> monitor.updateExecutionProgress("unknown", 1, ExecutionPhase.EXECUTING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getExecutionProgress_returnsNullForUnknown() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        ExecutionProgressDisplay display = monitor.getExecutionProgress("unknown");

        assertThat(display).isNull();
    }

    @Test
    void getDeliveryProgress_returnsNullForUnknown() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        DeliveryProgressDisplay display = monitor.getDeliveryProgress("unknown");

        assertThat(display).isNull();
    }

    @Test
    void getCiphertextIndicator_returnsUnknownForMissingDelivery() {
        ExecutionDeliveryMonitor monitor = createMonitor();

        CiphertextIndicator indicator = monitor.getCiphertextIndicator("unknown");

        assertThat(indicator.status()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(indicator.verified()).isFalse();
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<Integer> stepCounts() {
        return Arbitraries.integers().between(1, 20);
    }

    @Provide
    Arbitrary<Integer> completedStepCounts() {
        return Arbitraries.integers().between(0, 25);
    }

    @Provide
    Arbitrary<Integer> dataSizes() {
        return Arbitraries.integers().between(64, 4096);
    }

    @Provide
    Arbitrary<Integer> largeSizes() {
        // Use larger sizes to ensure statistical entropy is high
        return Arbitraries.integers().between(512, 4096);
    }
}
