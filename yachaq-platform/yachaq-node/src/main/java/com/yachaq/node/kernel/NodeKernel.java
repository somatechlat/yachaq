package com.yachaq.node.kernel;

import com.yachaq.node.kernel.event.EventBus;
import com.yachaq.node.kernel.event.KernelEvent;
import com.yachaq.node.kernel.event.KernelEventType;
import com.yachaq.node.kernel.job.JobConstraints;
import com.yachaq.node.kernel.job.JobResult;
import com.yachaq.node.kernel.job.JobScheduler;
import com.yachaq.node.kernel.job.JobType;
import com.yachaq.node.kernel.resource.ResourceMonitor;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Node Runtime Kernel - Lifecycle management, scheduling, resource governance, and module wiring.
 * 
 * This is the core runtime for the Phone-as-Node P2P architecture. It manages:
 * - Boot sequence: key init → vault mount → ODX load → connectors init → scheduler
 * - Job scheduling with resource constraints (battery, thermal, network)
 * - Internal event bus for module communication
 * - Resource monitoring and governance
 * 
 * Validates: Requirements 302.1, 302.2, 302.3
 */
public class NodeKernel {

    private final String nodeId;
    private final AtomicReference<KernelState> state;
    private final EventBus eventBus;
    private final JobScheduler jobScheduler;
    private final ResourceMonitor resourceMonitor;
    private final BootSequence bootSequence;
    
    private Instant bootTime;
    private Instant lastHealthCheck;

    /**
     * Creates a new NodeKernel instance.
     * 
     * @param nodeId Unique identifier for this node
     * @param eventBus Event bus for inter-module communication
     * @param jobScheduler Scheduler for background jobs
     * @param resourceMonitor Monitor for device resources
     * @param bootSequence Boot sequence executor
     */
    public NodeKernel(
            String nodeId,
            EventBus eventBus,
            JobScheduler jobScheduler,
            ResourceMonitor resourceMonitor,
            BootSequence bootSequence) {
        
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("Node ID cannot be null or blank");
        }
        
        this.nodeId = nodeId;
        this.state = new AtomicReference<>(KernelState.CREATED);
        this.eventBus = eventBus != null ? eventBus : new EventBus();
        this.jobScheduler = jobScheduler != null ? jobScheduler : new JobScheduler(this.eventBus);
        this.resourceMonitor = resourceMonitor != null ? resourceMonitor : new ResourceMonitor();
        this.bootSequence = bootSequence != null ? bootSequence : new BootSequence();
    }

    /**
     * Creates a NodeKernel with default components.
     * 
     * @param nodeId Unique identifier for this node
     */
    public NodeKernel(String nodeId) {
        this(nodeId, null, null, null, null);
    }

    /**
     * Executes the boot sequence to initialize the kernel.
     * Boot sequence: key init → vault mount → ODX load → connectors init → scheduler
     * 
     * Requirement 302.1: Implement boot sequence
     * 
     * @return CompletableFuture that completes when boot is finished
     * @throws KernelException if boot fails or kernel is already running
     */
    public CompletableFuture<BootResult> start() {
        // Idempotency check - if already running, return success
        if (state.get() == KernelState.RUNNING) {
            return CompletableFuture.completedFuture(
                    new BootResult(true, "Kernel already running", bootTime));
        }
        
        // Only allow starting from CREATED or STOPPED state
        if (!state.compareAndSet(KernelState.CREATED, KernelState.BOOTING) &&
            !state.compareAndSet(KernelState.STOPPED, KernelState.BOOTING)) {
            return CompletableFuture.failedFuture(
                    new KernelException("Cannot start kernel from state: " + state.get()));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                emit(new KernelEvent(KernelEventType.BOOT_STARTED, nodeId, Instant.now()));

                // Execute boot sequence steps
                bootSequence.executeKeyInit();
                emit(new KernelEvent(KernelEventType.KEY_INIT_COMPLETE, nodeId, Instant.now()));

                bootSequence.executeVaultMount();
                emit(new KernelEvent(KernelEventType.VAULT_MOUNTED, nodeId, Instant.now()));

                bootSequence.executeODXLoad();
                emit(new KernelEvent(KernelEventType.ODX_LOADED, nodeId, Instant.now()));

                bootSequence.executeConnectorsInit();
                emit(new KernelEvent(KernelEventType.CONNECTORS_INITIALIZED, nodeId, Instant.now()));

                jobScheduler.start();
                emit(new KernelEvent(KernelEventType.SCHEDULER_STARTED, nodeId, Instant.now()));

                bootTime = Instant.now();
                lastHealthCheck = bootTime;
                state.set(KernelState.RUNNING);
                
                emit(new KernelEvent(KernelEventType.BOOT_COMPLETE, nodeId, bootTime));
                
                return new BootResult(true, "Boot sequence completed successfully", bootTime);
                
            } catch (Exception e) {
                state.set(KernelState.FAILED);
                emit(new KernelEvent(KernelEventType.BOOT_FAILED, nodeId, Instant.now(), e.getMessage()));
                throw new KernelException("Boot sequence failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Stops the kernel and releases resources.
     * 
     * @return CompletableFuture that completes when shutdown is finished
     */
    public CompletableFuture<Void> stop() {
        if (state.get() != KernelState.RUNNING) {
            return CompletableFuture.completedFuture(null);
        }
        
        state.set(KernelState.STOPPING);
        emit(new KernelEvent(KernelEventType.SHUTDOWN_STARTED, nodeId, Instant.now()));
        
        return CompletableFuture.runAsync(() -> {
            try {
                jobScheduler.stop();
                bootSequence.executeShutdown();
                state.set(KernelState.STOPPED);
                emit(new KernelEvent(KernelEventType.SHUTDOWN_COMPLETE, nodeId, Instant.now()));
            } catch (Exception e) {
                state.set(KernelState.FAILED);
                throw new KernelException("Shutdown failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Runs a compute job under specified constraints.
     * Requirement 302.2: Job scheduling with constraints
     * 
     * @param jobType Type of job to run
     * @param constraints Resource constraints for the job
     * @return CompletableFuture with job result
     * @throws KernelException if kernel is not running or constraints not met
     */
    public CompletableFuture<JobResult> runJob(JobType jobType, JobConstraints constraints) {
        if (state.get() != KernelState.RUNNING) {
            return CompletableFuture.failedFuture(
                    new KernelException("Cannot run job - kernel not running. State: " + state.get()));
        }
        
        if (jobType == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Job type cannot be null"));
        }
        
        JobConstraints effectiveConstraints = constraints != null ? constraints : JobConstraints.defaults();
        
        // Check resource constraints before scheduling
        ResourceMonitor.ResourceStatus resourceStatus = resourceMonitor.checkResources(effectiveConstraints);
        if (!resourceStatus.constraintsMet()) {
            String reason = "Resource constraints not met: " + resourceStatus.reason();
            emit(new KernelEvent(KernelEventType.JOB_REJECTED, nodeId, Instant.now(), reason));
            return CompletableFuture.completedFuture(
                    new JobResult(UUID.randomUUID().toString(), jobType, false, reason, null));
        }
        
        return jobScheduler.schedule(jobType, effectiveConstraints);
    }

    /**
     * Emits an event to the internal event bus.
     * Requirement 302.3: Internal event bus
     * 
     * @param event Event to emit
     */
    public void emit(KernelEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }
        eventBus.emit(event);
    }

    /**
     * Subscribes to events of a specific type.
     * Requirement 302.3: Internal event bus
     * 
     * @param eventType Type of events to subscribe to
     * @param handler Handler to invoke when event occurs
     * @return Subscription ID for unsubscribing
     */
    public String on(KernelEventType eventType, Consumer<KernelEvent> handler) {
        if (eventType == null || handler == null) {
            throw new IllegalArgumentException("Event type and handler cannot be null");
        }
        return eventBus.subscribe(eventType, handler);
    }

    /**
     * Unsubscribes from events.
     * 
     * @param subscriptionId Subscription ID returned from on()
     */
    public void off(String subscriptionId) {
        eventBus.unsubscribe(subscriptionId);
    }

    /**
     * Gets the current kernel state.
     */
    public KernelState getState() {
        return state.get();
    }

    /**
     * Gets the node ID.
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * Gets the boot time (null if not booted).
     */
    public Instant getBootTime() {
        return bootTime;
    }

    /**
     * Checks if the kernel is running.
     */
    public boolean isRunning() {
        return state.get() == KernelState.RUNNING;
    }

    /**
     * Gets the resource monitor for checking device status.
     */
    public ResourceMonitor getResourceMonitor() {
        return resourceMonitor;
    }

    /**
     * Gets the event bus for direct access.
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    // ==================== Inner Types ====================

    /**
     * Kernel lifecycle states.
     */
    public enum KernelState {
        CREATED,    // Initial state
        BOOTING,    // Boot sequence in progress
        RUNNING,    // Fully operational
        STOPPING,   // Shutdown in progress
        STOPPED,    // Cleanly stopped
        FAILED      // Error state
    }

    /**
     * Result of boot sequence.
     */
    public record BootResult(
            boolean success,
            String message,
            Instant bootTime
    ) {}

    /**
     * Kernel-specific exception.
     */
    public static class KernelException extends RuntimeException {
        public KernelException(String message) {
            super(message);
        }
        
        public KernelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
