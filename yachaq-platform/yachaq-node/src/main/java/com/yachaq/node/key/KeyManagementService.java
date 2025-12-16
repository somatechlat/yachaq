package com.yachaq.node.key;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Key Management Service for Phone-as-Node P2P architecture.
 * Manages root keypairs, DID derivation, key rotation, and session keys.
 * 
 * Validates: Requirements 303.1, 303.2, 303.3, 303.4, 303.5
 */
public class KeyManagementService {

    private final SecureKeyStore keyStore;
    private final Map<String, PairwiseDID> pairwiseDIDs;
    private final Map<String, SessionKey> sessionKeys;
    private final KeyRotationPolicy rotationPolicy;
    
    private NodeDID nodeDID;
    private KeyPair rootKeyPair;
    private Instant rootKeyCreatedAt;

    public KeyManagementService(SecureKeyStore keyStore, KeyRotationPolicy rotationPolicy) {
        if (keyStore == null) {
            throw new IllegalArgumentException("KeyStore cannot be null");
        }
        this.keyStore = keyStore;
        this.rotationPolicy = rotationPolicy != null ? rotationPolicy : KeyRotationPolicy.defaults();
        this.pairwiseDIDs = new ConcurrentHashMap<>();
        this.sessionKeys = new ConcurrentHashMap<>();
    }

    public KeyManagementService() {
        this(new InMemoryKeyStore(), KeyRotationPolicy.defaults());
    }

    /**
     * Generates or retrieves the root keypair.
     * Requirement 303.1: Generate long-term root keypair with hardware-backed storage.
     * 
     * @return The root keypair
     * @throws KeyManagementException if key generation fails
     */
    public synchronized KeyPair getOrCreateRootKeyPair() {
        if (rootKeyPair != null) {
            return rootKeyPair;
        }

        // Try to load from secure storage first
        rootKeyPair = keyStore.loadRootKeyPair();
        if (rootKeyPair != null) {
            rootKeyCreatedAt = keyStore.getRootKeyCreatedAt();
            return rootKeyPair;
        }

        // Generate new root keypair
        rootKeyPair = generateECKeyPair();
        rootKeyCreatedAt = Instant.now();
        
        // Store in secure storage
        keyStore.storeRootKeyPair(rootKeyPair, rootKeyCreatedAt);
        
        return rootKeyPair;
    }


    /**
     * Gets or creates the Node DID (local identity).
     * Requirement 303.2: Create Node DID for local identity.
     * 
     * @return The Node DID
     */
    public synchronized NodeDID getOrCreateNodeDID() {
        if (nodeDID != null) {
            return nodeDID;
        }

        KeyPair rootKP = getOrCreateRootKeyPair();
        String didId = "did:yachaq:node:" + computePublicKeyHash(rootKP.getPublic());
        nodeDID = new NodeDID(didId, rootKP.getPublic(), rootKeyCreatedAt);
        
        return nodeDID;
    }

    /**
     * Creates a pairwise DID for a specific requester.
     * Requirement 303.2: Create pairwise DIDs per requester for anti-correlation.
     * 
     * @param requesterId The requester's identifier
     * @return A unique pairwise DID for this requester
     */
    public PairwiseDID getOrCreatePairwiseDID(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            throw new IllegalArgumentException("Requester ID cannot be null or blank");
        }

        return pairwiseDIDs.computeIfAbsent(requesterId, id -> {
            KeyPair pairwiseKP = generateECKeyPair();
            String didId = "did:yachaq:pairwise:" + computePublicKeyHash(pairwiseKP.getPublic());
            return new PairwiseDID(didId, id, pairwiseKP, Instant.now());
        });
    }

    /**
     * Derives a session key for P2P transfer.
     * Requirement 303.3: Derive unique session keys for P2P transfers.
     * 
     * @param sessionId Unique session identifier
     * @param peerPublicKey The peer's public key for key agreement
     * @return Derived session key
     */
    public SessionKey deriveSessionKey(String sessionId, PublicKey peerPublicKey) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank");
        }
        if (peerPublicKey == null) {
            throw new IllegalArgumentException("Peer public key cannot be null");
        }

        return sessionKeys.computeIfAbsent(sessionId, id -> {
            try {
                KeyPair ephemeralKP = generateECKeyPair();
                byte[] sharedSecret = performKeyAgreement(ephemeralKP.getPrivate(), peerPublicKey);
                byte[] derivedKey = deriveKeyFromSecret(sharedSecret, sessionId.getBytes());
                
                return new SessionKey(
                        id,
                        derivedKey,
                        ephemeralKP.getPublic(),
                        Instant.now(),
                        rotationPolicy.sessionKeyTTL()
                );
            } catch (Exception e) {
                throw new KeyManagementException("Failed to derive session key", e);
            }
        });
    }

    /**
     * Rotates the network identifier.
     * Requirement 303.4: Rotate network identifiers daily/weekly.
     * 
     * @return New network identifier
     */
    public String rotateNetworkIdentifier() {
        KeyPair newNetworkKP = generateECKeyPair();
        String networkId = "net:" + computePublicKeyHash(newNetworkKP.getPublic());
        keyStore.storeNetworkIdentifier(networkId, newNetworkKP, Instant.now());
        return networkId;
    }

    /**
     * Rotates a pairwise DID for a specific requester.
     * Requirement 303.5: Rotate pairwise identifiers per relationship or contract.
     * 
     * @param requesterId The requester's identifier
     * @return New pairwise DID
     */
    public PairwiseDID rotatePairwiseDID(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            throw new IllegalArgumentException("Requester ID cannot be null or blank");
        }

        // Remove old DID
        PairwiseDID oldDID = pairwiseDIDs.remove(requesterId);
        
        // Generate new one
        KeyPair pairwiseKP = generateECKeyPair();
        String didId = "did:yachaq:pairwise:" + computePublicKeyHash(pairwiseKP.getPublic());
        PairwiseDID newDID = new PairwiseDID(didId, requesterId, pairwiseKP, Instant.now());
        
        pairwiseDIDs.put(requesterId, newDID);
        
        // Archive old DID for audit
        if (oldDID != null) {
            keyStore.archivePairwiseDID(oldDID);
        }
        
        return newDID;
    }

    /**
     * Checks if a pairwise DID needs rotation based on policy.
     */
    public boolean needsRotation(String requesterId) {
        PairwiseDID did = pairwiseDIDs.get(requesterId);
        if (did == null) {
            return false;
        }
        
        Duration age = Duration.between(did.createdAt(), Instant.now());
        return age.compareTo(rotationPolicy.pairwiseRotationInterval()) > 0;
    }

    /**
     * Signs data using the root private key.
     */
    public byte[] signWithRootKey(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(getOrCreateRootKeyPair().getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new KeyManagementException("Failed to sign data", e);
        }
    }

    /**
     * Verifies a signature using a public key.
     */
    public boolean verifySignature(byte[] data, byte[] signatureBytes, PublicKey publicKey) {
        if (data == null || signatureBytes == null || publicKey == null) {
            throw new IllegalArgumentException("Data, signature, and public key cannot be null");
        }
        
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invalidates a session key.
     */
    public void invalidateSessionKey(String sessionId) {
        sessionKeys.remove(sessionId);
    }

    /**
     * Gets the count of active pairwise DIDs.
     */
    public int getPairwiseDIDCount() {
        return pairwiseDIDs.size();
    }

    /**
     * Gets the count of active session keys.
     */
    public int getSessionKeyCount() {
        return sessionKeys.size();
    }


    // ==================== Private Helper Methods ====================

    private KeyPair generateECKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new KeyManagementException("Failed to generate EC keypair", e);
        }
    }

    private String computePublicKeyHash(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey.getEncoded());
            return bytesToHex(hash).substring(0, 32); // First 16 bytes as hex
        } catch (Exception e) {
            throw new KeyManagementException("Failed to compute public key hash", e);
        }
    }

    private byte[] performKeyAgreement(PrivateKey privateKey, PublicKey peerPublicKey) throws Exception {
        javax.crypto.KeyAgreement keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(peerPublicKey, true);
        return keyAgreement.generateSecret();
    }

    private byte[] deriveKeyFromSecret(byte[] sharedSecret, byte[] info) throws Exception {
        // HKDF-like key derivation
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(sharedSecret);
        digest.update(info);
        return digest.digest();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== Inner Types ====================

    /**
     * Node DID representing the local identity.
     */
    public record NodeDID(
            String id,
            PublicKey publicKey,
            Instant createdAt
    ) {}

    /**
     * Pairwise DID for anti-correlation with specific requesters.
     */
    public record PairwiseDID(
            String id,
            String requesterId,
            KeyPair keyPair,
            Instant createdAt
    ) {
        public PublicKey publicKey() {
            return keyPair.getPublic();
        }
    }

    /**
     * Session key for P2P transfers.
     */
    public record SessionKey(
            String sessionId,
            byte[] derivedKey,
            PublicKey ephemeralPublicKey,
            Instant createdAt,
            Duration ttl
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(createdAt.plus(ttl));
        }
    }

    /**
     * Key rotation policy configuration.
     */
    public record KeyRotationPolicy(
            Duration networkIdRotationInterval,
            Duration pairwiseRotationInterval,
            Duration sessionKeyTTL
    ) {
        public static KeyRotationPolicy defaults() {
            return new KeyRotationPolicy(
                    Duration.ofDays(1),      // Network ID rotates daily
                    Duration.ofDays(30),     // Pairwise DIDs rotate monthly
                    Duration.ofHours(24)     // Session keys valid for 24 hours
            );
        }

        public static KeyRotationPolicy strict() {
            return new KeyRotationPolicy(
                    Duration.ofHours(6),     // Network ID rotates every 6 hours
                    Duration.ofDays(7),      // Pairwise DIDs rotate weekly
                    Duration.ofHours(1)      // Session keys valid for 1 hour
            );
        }
    }

    /**
     * Exception for key management errors.
     */
    public static class KeyManagementException extends RuntimeException {
        public KeyManagementException(String message) {
            super(message);
        }

        public KeyManagementException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
