package com.yachaq.node.key;

import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of SecureKeyStore for testing and development.
 * Production implementations should use platform-specific secure storage.
 */
public class InMemoryKeyStore implements SecureKeyStore {

    private KeyPair rootKeyPair;
    private Instant rootKeyCreatedAt;
    private final Map<String, NetworkIdEntry> networkIdentifiers;
    private final List<KeyManagementService.PairwiseDID> archivedDIDs;

    public InMemoryKeyStore() {
        this.networkIdentifiers = new ConcurrentHashMap<>();
        this.archivedDIDs = new ArrayList<>();
    }

    @Override
    public void storeRootKeyPair(KeyPair keyPair, Instant createdAt) {
        this.rootKeyPair = keyPair;
        this.rootKeyCreatedAt = createdAt;
    }

    @Override
    public KeyPair loadRootKeyPair() {
        return rootKeyPair;
    }

    @Override
    public Instant getRootKeyCreatedAt() {
        return rootKeyCreatedAt;
    }

    @Override
    public void storeNetworkIdentifier(String networkId, KeyPair keyPair, Instant createdAt) {
        networkIdentifiers.put(networkId, new NetworkIdEntry(networkId, keyPair, createdAt));
    }

    @Override
    public synchronized void archivePairwiseDID(KeyManagementService.PairwiseDID pairwiseDID) {
        archivedDIDs.add(pairwiseDID);
    }

    @Override
    public boolean isHardwareBacked() {
        return false; // In-memory is not hardware-backed
    }

    @Override
    public void wipe() {
        rootKeyPair = null;
        rootKeyCreatedAt = null;
        networkIdentifiers.clear();
        archivedDIDs.clear();
    }

    /**
     * Gets the count of archived DIDs (for testing).
     */
    public int getArchivedDIDCount() {
        return archivedDIDs.size();
    }

    /**
     * Gets the count of network identifiers (for testing).
     */
    public int getNetworkIdentifierCount() {
        return networkIdentifiers.size();
    }

    private record NetworkIdEntry(
            String networkId,
            KeyPair keyPair,
            Instant createdAt
    ) {}
}
