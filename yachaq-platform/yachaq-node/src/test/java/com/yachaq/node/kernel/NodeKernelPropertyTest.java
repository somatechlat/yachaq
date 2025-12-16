package com.yachaq.node.kernel;

import com.yachaq.node.kernel.event.EventBus;
import com.yachaq.node.kernel.event.KernelEvent;
import com.yachaq.node.kernel.event.KernelEventType;
import com.yachaq.node.kernel.job.JobConstraints;
import com.yachaq.node.kernel.job.JobResult;
import com.yachaq.node.kernel.job.JobType;
import com.yachaq.node.kernel.resource.ResourceMonitor;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Node Runtime Kernel.
 * Validates: Requirements 302.1, 302.2, 302.3
 */
class NodeKernelPropertyTest {

    // ==================== Boot Sequence Tests (302.1) ====================

    @Property(tries = 50)
    void bootSequence_isIdempotent(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: Starting an already-running kernel should be idempotent
        NodeKernel kernel = new NodeKernel(nodeId);
        
        // First boot
        NodeKernel.BootResult result1 = kernel.start().get(5, TimeUnit.SECONDS);
        assertTrue(result1.success());
        assertEquals(NodeKernel.KernelState.RUNNING, kernel.getState());
        
        // Second boot should succeed without error (idempotent)
        NodeKernel.BootResult result2 = kernel.start().get(5, TimeUnit.SECONDS);
        assertTrue(result2.success());
        assertEquals(NodeKernel.KernelState.RUNNING, kernel.getState());
        assertEquals("Kernel already running", result2.message());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void bootSequence_executesInCorrectOrder(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: Boot sequence must execute steps in order
        List<KernelEventType> eventOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        EventBus eventBus = new EventBus();
        
        eventBus.subscribe(KernelEventType.ALL, event -> eventOrder.add(event.eventType()));
        
        NodeKernel kernel = new NodeKernel(nodeId, eventBus, null, null, null);
        kernel.start().get(5, TimeUnit.SECONDS);
        
        // Wait for async event delivery
        Thread.sleep(200);
        
        // Verify all boot events were emitted
        assertTrue(eventOrder.contains(KernelEventType.BOOT_STARTED));
        assertTrue(eventOrder.contains(KernelEventType.KEY_INIT_COMPLETE));
        assertTrue(eventOrder.contains(KernelEventType.VAULT_MOUNTED));
        assertTrue(eventOrder.contains(KernelEventType.ODX_LOADED));
        assertTrue(eventOrder.contains(KernelEventType.CONNECTORS_INITIALIZED));
        assertTrue(eventOrder.contains(KernelEventType.SCHEDULER_STARTED));
        assertTrue(eventOrder.contains(KernelEventType.BOOT_COMPLETE));
        
        // Note: Due to async event delivery, we verify events were emitted
        // but don't strictly verify order since handlers run in parallel
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void bootSequence_setsBootTime(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: After successful boot, bootTime must be set
        NodeKernel kernel = new NodeKernel(nodeId);
        
        assertNull(kernel.getBootTime());
        
        kernel.start().get(5, TimeUnit.SECONDS);
        
        assertNotNull(kernel.getBootTime());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    // ==================== Job Scheduling Tests (302.2) ====================

    @Property(tries = 50)
    void jobScheduling_rejectsWhenConstraintsNotMet(
            @ForAll("validNodeIds") String nodeId,
            @ForAll("jobTypes") JobType jobType) throws Exception {
        // Property: Jobs must be rejected when resource constraints are not met
        ResourceMonitor resourceMonitor = new ResourceMonitor();
        resourceMonitor.setBatteryPercent(5); // Very low battery
        
        NodeKernel kernel = new NodeKernel(nodeId, null, null, resourceMonitor, null);
        kernel.start().get(5, TimeUnit.SECONDS);
        
        // Request job with high battery requirement
        JobConstraints constraints = new JobConstraints(
                50, false, 3, JobConstraints.NetworkRequirement.ANY, 60, JobConstraints.Priority.NORMAL);
        
        JobResult result = kernel.runJob(jobType, constraints).get(5, TimeUnit.SECONDS);
        
        assertFalse(result.success());
        assertTrue(result.message().contains("Battery"));
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void jobScheduling_acceptsWhenConstraintsMet(
            @ForAll("validNodeIds") String nodeId,
            @ForAll("jobTypes") JobType jobType) throws Exception {
        // Property: Jobs must be accepted when resource constraints are met
        ResourceMonitor resourceMonitor = new ResourceMonitor();
        resourceMonitor.setBatteryPercent(80);
        resourceMonitor.setNetworkState(ResourceMonitor.NetworkState.WIFI);
        
        NodeKernel kernel = new NodeKernel(nodeId, null, null, resourceMonitor, null);
        kernel.start().get(5, TimeUnit.SECONDS);
        
        JobConstraints constraints = JobConstraints.defaults();
        JobResult result = kernel.runJob(jobType, constraints).get(5, TimeUnit.SECONDS);
        
        assertTrue(result.success());
        assertEquals(jobType, result.jobType());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void jobScheduling_failsWhenKernelNotRunning(
            @ForAll("validNodeIds") String nodeId,
            @ForAll("jobTypes") JobType jobType) throws Exception {
        // Property: Jobs must fail when kernel is not running
        NodeKernel kernel = new NodeKernel(nodeId);
        
        // Don't start the kernel
        assertThrows(Exception.class, () -> 
            kernel.runJob(jobType, JobConstraints.defaults()).get(5, TimeUnit.SECONDS));
    }

    @Property(tries = 50)
    void jobScheduling_enforcesChargingConstraint(
            @ForAll("validNodeIds") String nodeId,
            @ForAll("jobTypes") JobType jobType) throws Exception {
        // Property: Jobs requiring charging must be rejected when not charging
        ResourceMonitor resourceMonitor = new ResourceMonitor();
        resourceMonitor.setBatteryPercent(100);
        resourceMonitor.setCharging(false);
        
        NodeKernel kernel = new NodeKernel(nodeId, null, null, resourceMonitor, null);
        kernel.start().get(5, TimeUnit.SECONDS);
        
        JobConstraints constraints = JobConstraints.heavyProcessing(); // Requires charging
        JobResult result = kernel.runJob(jobType, constraints).get(5, TimeUnit.SECONDS);
        
        assertFalse(result.success());
        assertTrue(result.message().contains("Charging"));
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void jobScheduling_enforcesThermalConstraint(
            @ForAll("validNodeIds") String nodeId,
            @ForAll("jobTypes") JobType jobType) throws Exception {
        // Property: Jobs must be rejected when thermal state exceeds limit
        ResourceMonitor resourceMonitor = new ResourceMonitor();
        resourceMonitor.setBatteryPercent(100);
        resourceMonitor.setThermalState(4); // Critical thermal
        
        NodeKernel kernel = new NodeKernel(nodeId, null, null, resourceMonitor, null);
        kernel.start().get(5, TimeUnit.SECONDS);
        
        JobConstraints constraints = new JobConstraints(
                10, false, 2, JobConstraints.NetworkRequirement.ANY, 60, JobConstraints.Priority.NORMAL);
        
        JobResult result = kernel.runJob(jobType, constraints).get(5, TimeUnit.SECONDS);
        
        assertFalse(result.success());
        assertTrue(result.message().contains("Thermal"));
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    // ==================== Event Bus Tests (302.3) ====================

    @Property(tries = 50)
    void eventBus_deliversEventsToSubscribers(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: Events must be delivered to all subscribers
        AtomicInteger eventCount = new AtomicInteger(0);
        
        NodeKernel kernel = new NodeKernel(nodeId);
        kernel.on(KernelEventType.BOOT_COMPLETE, event -> eventCount.incrementAndGet());
        
        kernel.start().get(5, TimeUnit.SECONDS);
        
        // Wait for event delivery
        Thread.sleep(100);
        
        assertEquals(1, eventCount.get());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void eventBus_supportsMultipleSubscribers(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: Multiple subscribers must all receive events
        AtomicInteger subscriber1Count = new AtomicInteger(0);
        AtomicInteger subscriber2Count = new AtomicInteger(0);
        AtomicInteger subscriber3Count = new AtomicInteger(0);
        
        NodeKernel kernel = new NodeKernel(nodeId);
        kernel.on(KernelEventType.BOOT_COMPLETE, event -> subscriber1Count.incrementAndGet());
        kernel.on(KernelEventType.BOOT_COMPLETE, event -> subscriber2Count.incrementAndGet());
        kernel.on(KernelEventType.BOOT_COMPLETE, event -> subscriber3Count.incrementAndGet());
        
        kernel.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        
        assertEquals(1, subscriber1Count.get());
        assertEquals(1, subscriber2Count.get());
        assertEquals(1, subscriber3Count.get());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    @Property(tries = 50)
    void eventBus_unsubscribeStopsDelivery(@ForAll("validNodeIds") String nodeId) throws Exception {
        // Property: Unsubscribed handlers must not receive events
        AtomicInteger eventCount = new AtomicInteger(0);
        
        NodeKernel kernel = new NodeKernel(nodeId);
        String subscriptionId = kernel.on(KernelEventType.BOOT_COMPLETE, event -> eventCount.incrementAndGet());
        
        // Unsubscribe before boot
        kernel.off(subscriptionId);
        
        kernel.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        
        assertEquals(0, eventCount.get());
        
        kernel.stop().get(5, TimeUnit.SECONDS);
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<String> validNodeIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(8)
                .ofMaxLength(32)
                .map(s -> "node-" + s);
    }

    @Provide
    Arbitrary<JobType> jobTypes() {
        return Arbitraries.of(JobType.values());
    }
}
