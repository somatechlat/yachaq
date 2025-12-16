package com.yachaq.node.key;

import java.security.KeyPair;
import java.time.Instant;

/**
 * Interface for secure key storage.
 * Implementations should use hardware-backed storage when available (Keychain/Keystore).
 * 
 * Requirement 303.1: Store in secure enclave/keystore.
 */
public interface SecureKeyStore {

    /**
     * Stores the root keypair securely.
     * 
     * @param keyPair The root keypair to store
     * @param createdAt When the keypair was created
     */
    void storeRootKeyPair(KeyPair keyPair, Instant createdAt);

    /**
     * Loads the root keypair from secure storage.
     * 
     * @return The root keypair, or null if not found
     */
    KeyPair loadRootKeyPair();

    /**
     * Gets the creation time of the root key.
     * 
     * @return Creation timestamp, or null if not found
     */
    Instant getRootKeyCreatedAt();

    /**
     * Stores a network identifier keypair.
     * 
     * @param networkId The network identifier
     * @param keyPair The keypair for this network ID
     * @param createdAt When the keypair was created
     */
    void storeNetworkIdentifier(String networkId, KeyPair keyPair, Instant createdAt);

    /**
     * Archives a rotated pairwise DID for audit purposes.
     * 
     * @param pairwiseDID The DID to archive
     */
    void archivePairwiseDID(KeyManagementService.PairwiseDID pairwiseDID);

    /**
     * Checks if hardware-backed storage is available.
     * 
     * @return true if hardware-backed storage is available
     */
    boolean isHardwareBacked();

    /**
     * Securely deletes all stored keys.
     */
    void wipe();
}
