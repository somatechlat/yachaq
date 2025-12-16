package com.yachaq.node.kernel.job;

/**
 * Types of jobs that can be scheduled on the node.
 */
public enum JobType {
    // Data synchronization jobs
    DATA_SYNC,
    CONNECTOR_SYNC,
    
    // Processing jobs
    FEATURE_EXTRACTION,
    LABEL_COMPUTATION,
    ODX_UPDATE,
    
    // Query execution jobs
    QUERY_EXECUTION,
    RESPONSE_PACKAGING,
    
    // Maintenance jobs
    KEY_ROTATION,
    VAULT_CLEANUP,
    CACHE_CLEANUP,
    
    // Network jobs
    P2P_DISCOVERY,
    CAPSULE_UPLOAD
}
