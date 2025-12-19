package com.yachaq.node.onboarding;

import com.yachaq.node.capsule.TimeCapsule;
import com.yachaq.node.planvm.PlanVM;
import com.yachaq.node.planvm.PlanVM.ResourceUsage;
import com.yachaq.node.planvm.QueryPlan;
import com.yachaq.node.transport.P2PTransport;
import com.yachaq.node.transport.P2PTransport.*;
import com.yachaq.node.transport.P2PSession;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution & Delivery Monitor for Provider App UI.
 * Monitors query execution progress, resource usage, and capsule delivery.
 * 
 * Security: Verifies only ciphertext is transmitted (never raw data).
 * Performance: Tracks resource consumption and transfer efficiency.
 * UX: Provides clear progress indicators and resumability controls.
 * 
 * Validates: Requirements 345.1, 345.2, 345.3, 345.4, 345.5
 */
public class ExecutionDeliveryMonitor {

    private final P2PTransport transport;
    private final CiphertextVerifier ciphertextVerifier;
    private final Map<String, ExecutionState> activeExecutions;
    private final Map<String, DeliveryState> activeDeliveries;
    private final List<MonitorEventListener> listeners;

    public ExecutionDeliveryMonitor(P2PTransport transport) {
        this(transport, new DefaultCiphertextVerifier());
    }

    public ExecutionDeliveryMonitor(P2PTransport transport, CiphertextVerifier ciphertextVerifier) {
        this.transport = Objects.requireNonNull(transport, "Transport cannot be null");
        this.ciphertextVerifier = Objects.requireNonNull(ciphertextVerifier, "CiphertextVerifier cannot be null");
        this.activeExecutions = new ConcurrentHashMap<>();
        this.activeDeliveries = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
    }

    // ==================== Execution Monitoring (Task 89.1) ====================

    /**
     * Starts monitoring an execution.
     * Requirement 345.1: Show progress, resource usage, transfer stats.
     * 
     * @param executionId Unique execution identifier
     * @param plan The query plan being executed
     * @return ExecutionState for tracking
     */
    public ExecutionState startExecution(String executionId, QueryPlan plan) {
        Objects.requireNonNull(executionId, "Execution ID cannot be null");
        Objects.requireNonNull(plan, "Plan cannot be null");

        ExecutionState state = new ExecutionState(
                executionId,
                plan.id(),
                plan.steps().size(),
                0,
                ExecutionPhase.INITIALIZING,
                Instant.now(),
                null,
                new ResourceMetrics(0, 0, 0, 0),
                new ArrayList<>()
        );

        activeExecutions.put(executionId, state);
        notifyListeners(MonitorEvent.executionStarted(executionId, plan.id()));
        return state;
    }

    /**
     * Updates execution progress.
     * Requirement 345.1: Show progress, resource usage.
     * 
     * @param executionId Execution identifier
     * @param completedSteps Number of completed steps
     * @param phase Current execution phase
     * @param resourceUsage Current resource usage
     * @return Updated ExecutionState
     */
    public ExecutionState updateExecutionProgress(String executionId, int completedSteps,
                                                   ExecutionPhase phase, ResourceUsage resourceUsage) {
        ExecutionState current = activeExecutions.get(executionId);
        if (current == null) {
            throw new IllegalStateException("Execution not found: " + executionId);
        }

        ResourceMetrics metrics = resourceUsage != null ?
                new ResourceMetrics(
                        resourceUsage.executionMillis(),
                        resourceUsage.memoryBytes(),
                        resourceUsage.cpuMillis(),
                        calculateBatteryImpact(resourceUsage)
                ) : current.resourceMetrics();

        ExecutionState updated = new ExecutionState(
                current.executionId(),
                current.planId(),
                current.totalSteps(),
                completedSteps,
                phase,
                current.startedAt(),
                phase == ExecutionPhase.COMPLETED || phase == ExecutionPhase.FAILED ? Instant.now() : null,
                metrics,
                current.stepLogs()
        );

        activeExecutions.put(executionId, updated);
        notifyListeners(MonitorEvent.executionProgress(executionId, updated.progressPercent(), phase));
        return updated;
    }

    /**
     * Gets the current execution progress display.
     * Requirement 345.1: Show progress, resource usage, transfer stats.
     * 
     * @param executionId Execution identifier
     * @return ExecutionProgressDisplay for UI rendering
     */
    public ExecutionProgressDisplay getExecutionProgress(String executionId) {
        ExecutionState state = activeExecutions.get(executionId);
        if (state == null) {
            return null;
        }

        Duration elapsed = Duration.between(state.startedAt(), 
                state.completedAt() != null ? state.completedAt() : Instant.now());

        String statusMessage = formatStatusMessage(state);
        List<ResourceIndicator> resourceIndicators = buildResourceIndicators(state.resourceMetrics());

        return new ExecutionProgressDisplay(
                state.executionId(),
                state.planId(),
                state.progressPercent(),
                state.phase(),
                statusMessage,
                elapsed,
                resourceIndicators,
                state.stepLogs()
        );
    }

    /**
     * Completes an execution.
     */
    public ExecutionState completeExecution(String executionId, boolean success, String message) {
        ExecutionState current = activeExecutions.get(executionId);
        if (current == null) {
            throw new IllegalStateException("Execution not found: " + executionId);
        }

        ExecutionPhase finalPhase = success ? ExecutionPhase.COMPLETED : ExecutionPhase.FAILED;
        List<String> logs = new ArrayList<>(current.stepLogs());
        logs.add(message);

        ExecutionState completed = new ExecutionState(
                current.executionId(),
                current.planId(),
                current.totalSteps(),
                success ? current.totalSteps() : current.completedSteps(),
                finalPhase,
                current.startedAt(),
                Instant.now(),
                current.resourceMetrics(),
                logs
        );

        activeExecutions.put(executionId, completed);
        notifyListeners(MonitorEvent.executionCompleted(executionId, success));
        return completed;
    }

    // ==================== Ciphertext Verification (Task 89.2) ====================

    /**
     * Verifies that data is ciphertext only before transmission.
     * Requirement 345.2: Show that only ciphertext is transmitted.
     * 
     * @param data Data to verify
     * @return CiphertextVerificationResult
     */
    public CiphertextVerificationResult verifyCiphertext(byte[] data) {
        Objects.requireNonNull(data, "Data cannot be null");
        return ciphertextVerifier.verify(data);
    }

    /**
     * Gets the ciphertext indicator for UI display.
     * Requirement 345.2: Show that only ciphertext is transmitted.
     * 
     * @param deliveryId Delivery identifier
     * @return CiphertextIndicator for UI
     */
    public CiphertextIndicator getCiphertextIndicator(String deliveryId) {
        DeliveryState state = activeDeliveries.get(deliveryId);
        if (state == null) {
            return new CiphertextIndicator(
                    false, false, "No active delivery",
                    List.of(), EncryptionStatus.UNKNOWN
            );
        }

        return new CiphertextIndicator(
                state.ciphertextVerified(),
                state.ciphertextVerified(),
                state.ciphertextVerified() ? 
                        "All transmitted data is encrypted (ciphertext only)" :
                        "Verification pending",
                state.verificationProofs(),
                state.ciphertextVerified() ? EncryptionStatus.VERIFIED : EncryptionStatus.PENDING
        );
    }

    // ==================== Delivery Monitoring & Resumability (Task 89.3) ====================

    /**
     * Starts monitoring a delivery.
     * Requirement 345.3: Provide resume options for interrupted transfers.
     * 
     * @param deliveryId Unique delivery identifier
     * @param capsule The capsule being delivered
     * @param session P2P session
     * @return DeliveryState for tracking
     */
    public DeliveryState startDelivery(String deliveryId, TimeCapsule capsule, P2PSession session) {
        Objects.requireNonNull(deliveryId, "Delivery ID cannot be null");
        Objects.requireNonNull(capsule, "Capsule cannot be null");
        Objects.requireNonNull(session, "Session cannot be null");

        // Verify ciphertext before starting
        byte[] payloadData = capsule.payload().encryptedData();
        CiphertextVerificationResult verification = verifyCiphertext(payloadData);

        if (!verification.isCiphertext()) {
            throw new SecurityException("Cannot deliver non-ciphertext data: " + verification.reason());
        }

        DeliveryState state = new DeliveryState(
                deliveryId,
                capsule.getId(),
                session.sessionId(),
                capsule.payload().size(),
                0,
                0,
                DeliveryPhase.INITIALIZING,
                Instant.now(),
                null,
                true,
                List.of(verification.proof()),
                new TransferStats(0, 0, 0, 0),
                null,
                true
        );

        activeDeliveries.put(deliveryId, state);
        notifyListeners(MonitorEvent.deliveryStarted(deliveryId, capsule.getId()));
        return state;
    }

    /**
     * Updates delivery progress.
     * Requirement 345.1: Show transfer stats.
     * 
     * @param deliveryId Delivery identifier
     * @param transferResult Result from P2P transport
     * @return Updated DeliveryState
     */
    public DeliveryState updateDeliveryProgress(String deliveryId, TransferResult transferResult) {
        DeliveryState current = activeDeliveries.get(deliveryId);
        if (current == null) {
            throw new IllegalStateException("Delivery not found: " + deliveryId);
        }

        DeliveryPhase phase = transferResult.complete() ? 
                DeliveryPhase.COMPLETED : DeliveryPhase.TRANSFERRING;

        TransferStats stats = new TransferStats(
                transferResult.chunksTransferred(),
                transferResult.totalChunks(),
                transferResult.bytesTransferred(),
                calculateTransferRate(current, transferResult)
        );

        DeliveryState updated = new DeliveryState(
                current.deliveryId(),
                current.capsuleId(),
                current.sessionId(),
                current.totalBytes(),
                transferResult.bytesTransferred(),
                transferResult.chunksTransferred(),
                phase,
                current.startedAt(),
                phase == DeliveryPhase.COMPLETED ? Instant.now() : null,
                current.ciphertextVerified(),
                current.verificationProofs(),
                stats,
                transferResult.error(),
                transferResult.complete() || current.resumable()
        );

        activeDeliveries.put(deliveryId, updated);
        notifyListeners(MonitorEvent.deliveryProgress(deliveryId, updated.progressPercent(), phase));
        return updated;
    }

    /**
     * Gets resumability controls for an interrupted delivery.
     * Requirement 345.3: Provide resume options for interrupted transfers.
     * 
     * @param deliveryId Delivery identifier
     * @return ResumabilityControls for UI
     */
    public ResumabilityControls getResumabilityControls(String deliveryId) {
        DeliveryState state = activeDeliveries.get(deliveryId);
        if (state == null) {
            return new ResumabilityControls(
                    deliveryId, false, false, 
                    "Delivery not found", List.of()
            );
        }

        boolean canResume = state.resumable() && 
                (state.phase() == DeliveryPhase.INTERRUPTED || 
                 state.phase() == DeliveryPhase.FAILED);

        List<ResumeOption> options = new ArrayList<>();
        if (canResume) {
            options.add(new ResumeOption(
                    "RESUME_NOW",
                    "Resume Now",
                    "Continue transfer from where it stopped",
                    true
            ));
            options.add(new ResumeOption(
                    "RESUME_WIFI",
                    "Resume on Wi-Fi",
                    "Wait for Wi-Fi connection to resume",
                    false
            ));
            options.add(new ResumeOption(
                    "CANCEL",
                    "Cancel Transfer",
                    "Cancel and clean up partial transfer",
                    false
            ));
        }

        String statusMessage = formatResumabilityStatus(state);

        return new ResumabilityControls(
                deliveryId,
                canResume,
                state.phase() == DeliveryPhase.INTERRUPTED,
                statusMessage,
                options
        );
    }

    /**
     * Resumes an interrupted delivery.
     * Requirement 345.3: Provide resume options for interrupted transfers.
     * 
     * @param deliveryId Delivery identifier
     * @param capsule The capsule to resume
     * @param session P2P session
     * @return ResumeResult
     */
    public ResumeResult resumeDelivery(String deliveryId, TimeCapsule capsule, P2PSession session) {
        DeliveryState state = activeDeliveries.get(deliveryId);
        if (state == null) {
            return ResumeResult.failed("Delivery not found: " + deliveryId);
        }

        if (!state.resumable()) {
            return ResumeResult.failed("Delivery is not resumable");
        }

        try {
            // Get transfer ID from state (would be stored in real implementation)
            String transferId = "transfer-" + deliveryId;
            
            // Resume via P2P transport
            TransferResult result = transport.resumeTransfer(transferId, session, capsule);
            
            // Update state
            updateDeliveryProgress(deliveryId, result);
            
            return ResumeResult.success(
                    deliveryId,
                    result.chunksTransferred(),
                    result.totalChunks(),
                    result.complete()
            );
        } catch (P2PException e) {
            // Mark as interrupted
            markDeliveryInterrupted(deliveryId, e.getMessage());
            return ResumeResult.failed("Resume failed: " + e.getMessage());
        }
    }

    /**
     * Marks a delivery as interrupted.
     */
    public void markDeliveryInterrupted(String deliveryId, String reason) {
        DeliveryState current = activeDeliveries.get(deliveryId);
        if (current == null) return;

        DeliveryState interrupted = new DeliveryState(
                current.deliveryId(),
                current.capsuleId(),
                current.sessionId(),
                current.totalBytes(),
                current.transferredBytes(),
                current.chunksTransferred(),
                DeliveryPhase.INTERRUPTED,
                current.startedAt(),
                null,
                current.ciphertextVerified(),
                current.verificationProofs(),
                current.stats(),
                reason,
                true // Still resumable
        );

        activeDeliveries.put(deliveryId, interrupted);
        notifyListeners(MonitorEvent.deliveryInterrupted(deliveryId, reason));
    }

    /**
     * Gets the delivery progress display.
     * Requirement 345.1: Show progress, resource usage, transfer stats.
     * 
     * @param deliveryId Delivery identifier
     * @return DeliveryProgressDisplay for UI rendering
     */
    public DeliveryProgressDisplay getDeliveryProgress(String deliveryId) {
        DeliveryState state = activeDeliveries.get(deliveryId);
        if (state == null) {
            return null;
        }

        Duration elapsed = Duration.between(state.startedAt(),
                state.completedAt() != null ? state.completedAt() : Instant.now());

        CiphertextIndicator ciphertextIndicator = getCiphertextIndicator(deliveryId);
        ResumabilityControls resumeControls = getResumabilityControls(deliveryId);

        return new DeliveryProgressDisplay(
                state.deliveryId(),
                state.capsuleId(),
                state.progressPercent(),
                state.phase(),
                formatDeliveryStatus(state),
                elapsed,
                state.stats(),
                ciphertextIndicator,
                resumeControls,
                state.error()
        );
    }

    // ==================== Event Listeners ====================

    public void addListener(MonitorEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MonitorEventListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(MonitorEvent event) {
        for (MonitorEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                // Log but don't propagate listener errors
            }
        }
    }

    // ==================== Private Helper Methods ====================

    private double calculateBatteryImpact(ResourceUsage usage) {
        // Estimate battery impact based on CPU and memory usage
        // This is a simplified model - real implementation would use platform APIs
        double cpuFactor = usage.cpuMillis() / 1000.0 * 0.001; // ~0.1% per second of CPU
        double memoryFactor = usage.memoryBytes() / (1024.0 * 1024.0 * 100.0) * 0.0001; // ~0.01% per 100MB
        return Math.min(cpuFactor + memoryFactor, 100.0);
    }

    private double calculateTransferRate(DeliveryState state, TransferResult result) {
        Duration elapsed = Duration.between(state.startedAt(), Instant.now());
        if (elapsed.isZero()) return 0;
        return result.bytesTransferred() / (elapsed.toMillis() / 1000.0);
    }

    private String formatStatusMessage(ExecutionState state) {
        return switch (state.phase()) {
            case INITIALIZING -> "Initializing execution environment...";
            case EXECUTING -> String.format("Executing step %d of %d...", 
                    state.completedSteps() + 1, state.totalSteps());
            case PACKAGING -> "Packaging results into capsule...";
            case COMPLETED -> "Execution completed successfully";
            case FAILED -> "Execution failed";
        };
    }

    private String formatDeliveryStatus(DeliveryState state) {
        return switch (state.phase()) {
            case INITIALIZING -> "Preparing secure transfer...";
            case CONNECTING -> "Establishing P2P connection...";
            case TRANSFERRING -> String.format("Transferring chunk %d of %d (%.1f KB/s)...",
                    state.chunksTransferred(), state.stats().totalChunks(),
                    state.stats().bytesPerSecond() / 1024.0);
            case VERIFYING -> "Verifying delivery...";
            case COMPLETED -> "Delivery completed successfully";
            case INTERRUPTED -> "Transfer interrupted - can be resumed";
            case FAILED -> "Delivery failed: " + (state.error() != null ? state.error() : "Unknown error");
        };
    }

    private String formatResumabilityStatus(DeliveryState state) {
        if (state.phase() == DeliveryPhase.COMPLETED) {
            return "Transfer completed";
        }
        if (state.phase() == DeliveryPhase.INTERRUPTED) {
            return String.format("Transfer interrupted at %.1f%% - %d of %d chunks transferred",
                    state.progressPercent(), state.chunksTransferred(), state.stats().totalChunks());
        }
        if (!state.resumable()) {
            return "Transfer cannot be resumed";
        }
        return "Transfer in progress";
    }

    private List<ResourceIndicator> buildResourceIndicators(ResourceMetrics metrics) {
        List<ResourceIndicator> indicators = new ArrayList<>();
        
        indicators.add(new ResourceIndicator(
                "CPU Time",
                String.format("%.1f s", metrics.cpuMillis() / 1000.0),
                metrics.cpuMillis() > 5000 ? IndicatorLevel.WARNING : IndicatorLevel.NORMAL
        ));
        
        indicators.add(new ResourceIndicator(
                "Memory",
                String.format("%.1f MB", metrics.memoryBytes() / (1024.0 * 1024.0)),
                metrics.memoryBytes() > 100 * 1024 * 1024 ? IndicatorLevel.WARNING : IndicatorLevel.NORMAL
        ));
        
        indicators.add(new ResourceIndicator(
                "Battery Impact",
                String.format("%.2f%%", metrics.batteryImpactPercent()),
                metrics.batteryImpactPercent() > 1.0 ? IndicatorLevel.WARNING : IndicatorLevel.NORMAL
        ));
        
        indicators.add(new ResourceIndicator(
                "Duration",
                String.format("%.1f s", metrics.executionMillis() / 1000.0),
                metrics.executionMillis() > 30000 ? IndicatorLevel.WARNING : IndicatorLevel.NORMAL
        ));
        
        return indicators;
    }


    // ==================== Inner Types ====================

    /**
     * Execution phase enumeration.
     */
    public enum ExecutionPhase {
        INITIALIZING("Initializing"),
        EXECUTING("Executing"),
        PACKAGING("Packaging"),
        COMPLETED("Completed"),
        FAILED("Failed");

        private final String displayName;

        ExecutionPhase(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Delivery phase enumeration.
     */
    public enum DeliveryPhase {
        INITIALIZING("Initializing"),
        CONNECTING("Connecting"),
        TRANSFERRING("Transferring"),
        VERIFYING("Verifying"),
        COMPLETED("Completed"),
        INTERRUPTED("Interrupted"),
        FAILED("Failed");

        private final String displayName;

        DeliveryPhase(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Encryption status for ciphertext indicator.
     */
    public enum EncryptionStatus {
        VERIFIED("Verified - All data encrypted"),
        PENDING("Verification pending"),
        FAILED("Verification failed"),
        UNKNOWN("Status unknown");

        private final String description;

        EncryptionStatus(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }

    /**
     * Resource indicator level.
     */
    public enum IndicatorLevel {
        NORMAL, WARNING, CRITICAL
    }

    /**
     * Execution state tracking.
     */
    public record ExecutionState(
            String executionId,
            String planId,
            int totalSteps,
            int completedSteps,
            ExecutionPhase phase,
            Instant startedAt,
            Instant completedAt,
            ResourceMetrics resourceMetrics,
            List<String> stepLogs
    ) {
        public ExecutionState {
            stepLogs = stepLogs != null ? new ArrayList<>(stepLogs) : new ArrayList<>();
        }

        public double progressPercent() {
            return totalSteps > 0 ? (completedSteps * 100.0) / totalSteps : 0;
        }

        public boolean isComplete() {
            return phase == ExecutionPhase.COMPLETED || phase == ExecutionPhase.FAILED;
        }
    }

    /**
     * Delivery state tracking.
     */
    public record DeliveryState(
            String deliveryId,
            String capsuleId,
            String sessionId,
            long totalBytes,
            long transferredBytes,
            int chunksTransferred,
            DeliveryPhase phase,
            Instant startedAt,
            Instant completedAt,
            boolean ciphertextVerified,
            List<String> verificationProofs,
            TransferStats stats,
            String error,
            boolean resumable
    ) {
        public DeliveryState {
            verificationProofs = verificationProofs != null ? new ArrayList<>(verificationProofs) : new ArrayList<>();
        }

        public double progressPercent() {
            return totalBytes > 0 ? (transferredBytes * 100.0) / totalBytes : 0;
        }

        public boolean isComplete() {
            return phase == DeliveryPhase.COMPLETED || phase == DeliveryPhase.FAILED;
        }
    }

    /**
     * Resource metrics for execution.
     */
    public record ResourceMetrics(
            long executionMillis,
            long memoryBytes,
            long cpuMillis,
            double batteryImpactPercent
    ) {}

    /**
     * Transfer statistics.
     */
    public record TransferStats(
            int chunksTransferred,
            int totalChunks,
            long bytesTransferred,
            double bytesPerSecond
    ) {
        public double progressPercent() {
            return totalChunks > 0 ? (chunksTransferred * 100.0) / totalChunks : 0;
        }
    }

    /**
     * Execution progress display for UI.
     * Requirement 345.1: Show progress, resource usage, transfer stats.
     */
    public record ExecutionProgressDisplay(
            String executionId,
            String planId,
            double progressPercent,
            ExecutionPhase phase,
            String statusMessage,
            Duration elapsed,
            List<ResourceIndicator> resourceIndicators,
            List<String> stepLogs
    ) {}

    /**
     * Delivery progress display for UI.
     * Requirement 345.1: Show progress, resource usage, transfer stats.
     */
    public record DeliveryProgressDisplay(
            String deliveryId,
            String capsuleId,
            double progressPercent,
            DeliveryPhase phase,
            String statusMessage,
            Duration elapsed,
            TransferStats stats,
            CiphertextIndicator ciphertextIndicator,
            ResumabilityControls resumeControls,
            String error
    ) {}

    /**
     * Resource indicator for UI display.
     */
    public record ResourceIndicator(
            String name,
            String value,
            IndicatorLevel level
    ) {}

    /**
     * Ciphertext indicator for UI.
     * Requirement 345.2: Show that only ciphertext is transmitted.
     */
    public record CiphertextIndicator(
            boolean verified,
            boolean secure,
            String message,
            List<String> proofs,
            EncryptionStatus status
    ) {}

    /**
     * Resumability controls for UI.
     * Requirement 345.3: Provide resume options for interrupted transfers.
     */
    public record ResumabilityControls(
            String deliveryId,
            boolean canResume,
            boolean isInterrupted,
            String statusMessage,
            List<ResumeOption> options
    ) {}

    /**
     * Resume option for UI.
     */
    public record ResumeOption(
            String id,
            String displayName,
            String description,
            boolean recommended
    ) {}

    /**
     * Result of a resume operation.
     */
    public record ResumeResult(
            boolean success,
            String deliveryId,
            int chunksResumed,
            int totalChunks,
            boolean complete,
            String error
    ) {
        public static ResumeResult success(String deliveryId, int chunksResumed, int totalChunks, boolean complete) {
            return new ResumeResult(true, deliveryId, chunksResumed, totalChunks, complete, null);
        }

        public static ResumeResult failed(String error) {
            return new ResumeResult(false, null, 0, 0, false, error);
        }
    }

    /**
     * Ciphertext verification result.
     * Requirement 345.2: Show that only ciphertext is transmitted.
     */
    public record CiphertextVerificationResult(
            boolean isCiphertext,
            double entropyScore,
            String reason,
            String proof
    ) {
        public static CiphertextVerificationResult verified(double entropy, String proof) {
            return new CiphertextVerificationResult(true, entropy, 
                    "Data verified as ciphertext (high entropy)", proof);
        }

        public static CiphertextVerificationResult failed(double entropy, String reason) {
            return new CiphertextVerificationResult(false, entropy, reason, null);
        }
    }

    /**
     * Monitor event for listeners.
     */
    public record MonitorEvent(
            MonitorEventType type,
            String id,
            String relatedId,
            double progress,
            String message,
            Instant timestamp
    ) {
        public static MonitorEvent executionStarted(String executionId, String planId) {
            return new MonitorEvent(MonitorEventType.EXECUTION_STARTED, executionId, planId, 
                    0, "Execution started", Instant.now());
        }

        public static MonitorEvent executionProgress(String executionId, double progress, ExecutionPhase phase) {
            return new MonitorEvent(MonitorEventType.EXECUTION_PROGRESS, executionId, null,
                    progress, phase.getDisplayName(), Instant.now());
        }

        public static MonitorEvent executionCompleted(String executionId, boolean success) {
            return new MonitorEvent(success ? MonitorEventType.EXECUTION_COMPLETED : MonitorEventType.EXECUTION_FAILED,
                    executionId, null, 100, success ? "Completed" : "Failed", Instant.now());
        }

        public static MonitorEvent deliveryStarted(String deliveryId, String capsuleId) {
            return new MonitorEvent(MonitorEventType.DELIVERY_STARTED, deliveryId, capsuleId,
                    0, "Delivery started", Instant.now());
        }

        public static MonitorEvent deliveryProgress(String deliveryId, double progress, DeliveryPhase phase) {
            return new MonitorEvent(MonitorEventType.DELIVERY_PROGRESS, deliveryId, null,
                    progress, phase.getDisplayName(), Instant.now());
        }

        public static MonitorEvent deliveryInterrupted(String deliveryId, String reason) {
            return new MonitorEvent(MonitorEventType.DELIVERY_INTERRUPTED, deliveryId, null,
                    0, reason, Instant.now());
        }
    }

    /**
     * Monitor event types.
     */
    public enum MonitorEventType {
        EXECUTION_STARTED,
        EXECUTION_PROGRESS,
        EXECUTION_COMPLETED,
        EXECUTION_FAILED,
        DELIVERY_STARTED,
        DELIVERY_PROGRESS,
        DELIVERY_COMPLETED,
        DELIVERY_INTERRUPTED,
        DELIVERY_FAILED
    }

    /**
     * Event listener interface.
     */
    public interface MonitorEventListener {
        void onEvent(MonitorEvent event);
    }

    /**
     * Interface for ciphertext verification.
     * Requirement 345.2: Show that only ciphertext is transmitted.
     */
    public interface CiphertextVerifier {
        CiphertextVerificationResult verify(byte[] data);
    }

    /**
     * Default ciphertext verifier using entropy analysis.
     * Security: Ensures only encrypted data is transmitted.
     */
    public static class DefaultCiphertextVerifier implements CiphertextVerifier {
        private static final double MIN_ENTROPY_THRESHOLD = 7.0; // Bits per byte for random data
        private static final int MIN_DATA_SIZE = 16; // Minimum size for meaningful analysis

        @Override
        public CiphertextVerificationResult verify(byte[] data) {
            if (data == null || data.length < MIN_DATA_SIZE) {
                return CiphertextVerificationResult.failed(0, 
                        "Data too small for entropy analysis");
            }

            double entropy = calculateEntropy(data);
            
            if (entropy >= MIN_ENTROPY_THRESHOLD) {
                String proof = generateVerificationProof(data, entropy);
                return CiphertextVerificationResult.verified(entropy, proof);
            } else {
                return CiphertextVerificationResult.failed(entropy,
                        String.format("Low entropy (%.2f bits/byte) suggests non-encrypted data", entropy));
            }
        }

        private double calculateEntropy(byte[] data) {
            int[] frequency = new int[256];
            for (byte b : data) {
                frequency[b & 0xFF]++;
            }

            double entropy = 0;
            for (int freq : frequency) {
                if (freq > 0) {
                    double p = (double) freq / data.length;
                    entropy -= p * (Math.log(p) / Math.log(2));
                }
            }
            return entropy;
        }

        private String generateVerificationProof(byte[] data, double entropy) {
            // Generate a proof that can be verified later
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                String hashStr = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(java.util.Arrays.copyOf(hash, 8));
                return String.format("CIPHERTEXT_VERIFIED:entropy=%.2f:hash=%s:size=%d",
                        entropy, hashStr, data.length);
            } catch (Exception e) {
                return String.format("CIPHERTEXT_VERIFIED:entropy=%.2f:size=%d", entropy, data.length);
            }
        }
    }
}
