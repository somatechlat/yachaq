package com.yachaq.node.kernel.event;

/**
 * Types of events emitted by the kernel.
 */
public enum KernelEventType {
    // Lifecycle events
    BOOT_STARTED,
    KEY_INIT_COMPLETE,
    VAULT_MOUNTED,
    ODX_LOADED,
    CONNECTORS_INITIALIZED,
    SCHEDULER_STARTED,
    BOOT_COMPLETE,
    BOOT_FAILED,
    SHUTDOWN_STARTED,
    SHUTDOWN_COMPLETE,
    
    // Job events
    JOB_SCHEDULED,
    JOB_STARTED,
    JOB_COMPLETED,
    JOB_FAILED,
    JOB_REJECTED,
    
    // Resource events
    BATTERY_LOW,
    BATTERY_CRITICAL,
    THERMAL_WARNING,
    THERMAL_CRITICAL,
    NETWORK_AVAILABLE,
    NETWORK_UNAVAILABLE,
    
    // Data events
    DATA_SYNC_STARTED,
    DATA_SYNC_COMPLETE,
    ODX_UPDATED,
    
    // Wildcard for subscribing to all events
    ALL
}
