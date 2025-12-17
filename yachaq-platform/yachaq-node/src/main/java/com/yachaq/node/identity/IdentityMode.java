package com.yachaq.node.identity;

/**
 * Identity revelation modes for the DID/VC wallet.
 * 
 * Validates: Requirements 326.1
 */
public enum IdentityMode {
    /**
     * Anonymous mode - no identity revealed (default).
     * Uses pairwise pseudonymous identifiers.
     */
    ANONYMOUS,
    
    /**
     * Pseudonymous mode - consistent pseudonym per requester.
     * Allows reputation building without revealing real identity.
     */
    PSEUDONYMOUS,
    
    /**
     * Verified mode - verifiable credentials presented.
     * Requires explicit user consent for each revelation.
     */
    VERIFIED
}
