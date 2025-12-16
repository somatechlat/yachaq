package com.yachaq.node.key;

import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Key Management Service.
 * 
 * Property 59: Pairwise DID Anti-Correlation
 * Validates: Requirements 303.1, 303.2, 303.3, 303.4, 303.5
 */
class KeyManagementServicePropertyTest {

    // ==================== Property 59: Pairwise DID Anti-Correlation ====================

    @Property(tries = 100)
    void property59_pairwiseDIDsAreUniquePerRequester(
            @ForAll("requesterIds") String requesterId1,
            @ForAll("requesterIds") String requesterId2) {
        // Property: Different requesters must get different pairwise DIDs
        Assume.that(!requesterId1.equals(requesterId2));
        
        KeyManagementService kms = new KeyManagementService();
        
        KeyManagementService.PairwiseDID did1 = kms.getOrCreatePairwiseDID(requesterId1);
        KeyManagementService.PairwiseDID did2 = kms.getOrCreatePairwiseDID(requesterId2);
        
        // DIDs must be different
        assertThat(did1.id()).isNotEqualTo(did2.id());
        
        // Public keys must be different
        assertThat(did1.publicKey()).isNotEqualTo(did2.publicKey());
    }

    @Property(tries = 100)
    void property59_samRequesterGetsSamePairwiseDID(
            @ForAll("requesterIds") String requesterId) {
        // Property: Same requester must get the same pairwise DID (idempotent)
        KeyManagementService kms = new KeyManagementService();
        
        KeyManagementService.PairwiseDID did1 = kms.getOrCreatePairwiseDID(requesterId);
        KeyManagementService.PairwiseDID did2 = kms.getOrCreatePairwiseDID(requesterId);
        
        assertThat(did1.id()).isEqualTo(did2.id());
        assertThat(did1.publicKey()).isEqualTo(did2.publicKey());
    }

    @Property(tries = 50)
    void property59_rotatedPairwiseDIDIsDifferent(
            @ForAll("requesterIds") String requesterId) {
        // Property: Rotated pairwise DID must be different from original
        KeyManagementService kms = new KeyManagementService();
        
        KeyManagementService.PairwiseDID originalDID = kms.getOrCreatePairwiseDID(requesterId);
        KeyManagementService.PairwiseDID rotatedDID = kms.rotatePairwiseDID(requesterId);
        
        assertThat(rotatedDID.id()).isNotEqualTo(originalDID.id());
        assertThat(rotatedDID.publicKey()).isNotEqualTo(originalDID.publicKey());
    }

    @Property(tries = 50)
    void property59_pairwiseDIDsCannotCorrelateRequesters(
            @ForAll @AlphaChars @StringLength(min = 5, max = 20) String baseId) {
        // Property: Multiple pairwise DIDs from same node cannot be correlated
        KeyManagementService kms = new KeyManagementService();
        Set<String> didIds = new HashSet<>();
        Set<PublicKey> publicKeys = new HashSet<>();
        
        // Create DIDs for multiple requesters
        for (int i = 0; i < 10; i++) {
            String requesterId = baseId + "-" + i;
            KeyManagementService.PairwiseDID did = kms.getOrCreatePairwiseDID(requesterId);
            
            // Each DID ID must be unique
            assertThat(didIds.add(did.id())).isTrue();
            
            // Each public key must be unique
            assertThat(publicKeys.add(did.publicKey())).isTrue();
        }
    }

    // ==================== Root Keypair Tests (303.1) ====================

    @Property(tries = 50)
    void rootKeyPair_isIdempotent(@ForAll("requesterIds") String nodeId) {
        // Property: Getting root keypair multiple times returns same keypair
        KeyManagementService kms = new KeyManagementService();
        
        KeyPair kp1 = kms.getOrCreateRootKeyPair();
        KeyPair kp2 = kms.getOrCreateRootKeyPair();
        
        assertThat(kp1.getPublic()).isEqualTo(kp2.getPublic());
    }

    @Property(tries = 50)
    void rootKeyPair_generatesValidECKey(@ForAll("requesterIds") String nodeId) {
        // Property: Root keypair must be valid EC key
        KeyManagementService kms = new KeyManagementService();
        
        KeyPair kp = kms.getOrCreateRootKeyPair();
        
        assertThat(kp.getPublic().getAlgorithm()).isEqualTo("EC");
        assertThat(kp.getPrivate().getAlgorithm()).isEqualTo("EC");
    }


    // ==================== Node DID Tests (303.2) ====================

    @Property(tries = 50)
    void nodeDID_isIdempotent(@ForAll("requesterIds") String nodeId) {
        // Property: Getting node DID multiple times returns same DID
        KeyManagementService kms = new KeyManagementService();
        
        KeyManagementService.NodeDID did1 = kms.getOrCreateNodeDID();
        KeyManagementService.NodeDID did2 = kms.getOrCreateNodeDID();
        
        assertThat(did1.id()).isEqualTo(did2.id());
        assertThat(did1.publicKey()).isEqualTo(did2.publicKey());
    }

    @Property(tries = 50)
    void nodeDID_hasCorrectFormat(@ForAll("requesterIds") String nodeId) {
        // Property: Node DID must have correct format
        KeyManagementService kms = new KeyManagementService();
        
        KeyManagementService.NodeDID did = kms.getOrCreateNodeDID();
        
        assertThat(did.id()).startsWith("did:yachaq:node:");
        assertThat(did.publicKey()).isNotNull();
        assertThat(did.createdAt()).isNotNull();
    }

    // ==================== Session Key Tests (303.3) ====================

    @Property(tries = 50)
    void sessionKey_derivesUniqueKeysPerSession(
            @ForAll("sessionIds") String sessionId1,
            @ForAll("sessionIds") String sessionId2) throws Exception {
        // Property: Different sessions must get different derived keys
        Assume.that(!sessionId1.equals(sessionId2));
        
        KeyManagementService kms = new KeyManagementService();
        PublicKey peerKey = generateTestPublicKey();
        
        KeyManagementService.SessionKey sk1 = kms.deriveSessionKey(sessionId1, peerKey);
        KeyManagementService.SessionKey sk2 = kms.deriveSessionKey(sessionId2, peerKey);
        
        assertThat(sk1.derivedKey()).isNotEqualTo(sk2.derivedKey());
    }

    @Property(tries = 50)
    void sessionKey_isIdempotentForSameSession(
            @ForAll("sessionIds") String sessionId) throws Exception {
        // Property: Same session ID returns same session key
        KeyManagementService kms = new KeyManagementService();
        PublicKey peerKey = generateTestPublicKey();
        
        KeyManagementService.SessionKey sk1 = kms.deriveSessionKey(sessionId, peerKey);
        KeyManagementService.SessionKey sk2 = kms.deriveSessionKey(sessionId, peerKey);
        
        assertThat(sk1.derivedKey()).isEqualTo(sk2.derivedKey());
    }

    @Property(tries = 50)
    void sessionKey_hasCorrectTTL(@ForAll("sessionIds") String sessionId) throws Exception {
        // Property: Session key must have TTL set
        KeyManagementService kms = new KeyManagementService();
        PublicKey peerKey = generateTestPublicKey();
        
        KeyManagementService.SessionKey sk = kms.deriveSessionKey(sessionId, peerKey);
        
        assertThat(sk.ttl()).isNotNull();
        assertThat(sk.ttl()).isPositive();
        assertThat(sk.isExpired()).isFalse();
    }

    // ==================== Key Rotation Tests (303.4, 303.5) ====================

    @Property(tries = 50)
    void networkIdentifier_rotatesSuccessfully(@ForAll("requesterIds") String nodeId) {
        // Property: Network identifier rotation produces new identifier
        KeyManagementService kms = new KeyManagementService();
        
        String netId1 = kms.rotateNetworkIdentifier();
        String netId2 = kms.rotateNetworkIdentifier();
        
        assertThat(netId1).startsWith("net:");
        assertThat(netId2).startsWith("net:");
        assertThat(netId1).isNotEqualTo(netId2);
    }

    @Property(tries = 50)
    void pairwiseDID_rotationArchivesOldDID(
            @ForAll("requesterIds") String requesterId) {
        // Property: Rotating pairwise DID archives the old one
        InMemoryKeyStore keyStore = new InMemoryKeyStore();
        KeyManagementService kms = new KeyManagementService(keyStore, null);
        
        kms.getOrCreatePairwiseDID(requesterId);
        assertThat(keyStore.getArchivedDIDCount()).isEqualTo(0);
        
        kms.rotatePairwiseDID(requesterId);
        assertThat(keyStore.getArchivedDIDCount()).isEqualTo(1);
    }

    // ==================== Signature Tests ====================

    @Property(tries = 50)
    void signature_roundTrip(@ForAll byte[] data) {
        // Property: Sign then verify must succeed
        Assume.that(data != null && data.length > 0);
        
        KeyManagementService kms = new KeyManagementService();
        KeyPair rootKP = kms.getOrCreateRootKeyPair();
        
        byte[] signature = kms.signWithRootKey(data);
        boolean verified = kms.verifySignature(data, signature, rootKP.getPublic());
        
        assertThat(verified).isTrue();
    }

    @Property(tries = 50)
    void signature_failsWithTamperedData(@ForAll byte[] data) {
        // Property: Verification must fail with tampered data
        Assume.that(data != null && data.length > 1);
        
        KeyManagementService kms = new KeyManagementService();
        KeyPair rootKP = kms.getOrCreateRootKeyPair();
        
        byte[] signature = kms.signWithRootKey(data);
        
        // Tamper with data
        byte[] tamperedData = data.clone();
        tamperedData[0] = (byte) (tamperedData[0] ^ 0xFF);
        
        boolean verified = kms.verifySignature(tamperedData, signature, rootKP.getPublic());
        
        assertThat(verified).isFalse();
    }

    @Property(tries = 50)
    void signature_failsWithWrongKey(@ForAll byte[] data) throws Exception {
        // Property: Verification must fail with wrong public key
        Assume.that(data != null && data.length > 0);
        
        KeyManagementService kms = new KeyManagementService();
        
        byte[] signature = kms.signWithRootKey(data);
        PublicKey wrongKey = generateTestPublicKey();
        
        boolean verified = kms.verifySignature(data, signature, wrongKey);
        
        assertThat(verified).isFalse();
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<String> requesterIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(8)
                .ofMaxLength(32)
                .map(s -> "req-" + s);
    }

    @Provide
    Arbitrary<String> sessionIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(8)
                .ofMaxLength(32)
                .map(s -> "session-" + s);
    }

    // ==================== Helper Methods ====================

    private PublicKey generateTestPublicKey() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"));
        return keyGen.generateKeyPair().getPublic();
    }

    // ==================== Edge Case Tests ====================

    @Test
    void pairwiseDID_rejectsNullRequesterId() {
        KeyManagementService kms = new KeyManagementService();
        
        assertThatThrownBy(() -> kms.getOrCreatePairwiseDID(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pairwiseDID_rejectsBlankRequesterId() {
        KeyManagementService kms = new KeyManagementService();
        
        assertThatThrownBy(() -> kms.getOrCreatePairwiseDID("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionKey_rejectsNullSessionId() throws Exception {
        KeyManagementService kms = new KeyManagementService();
        PublicKey peerKey = generateTestPublicKey();
        
        assertThatThrownBy(() -> kms.deriveSessionKey(null, peerKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionKey_rejectsNullPeerKey() {
        KeyManagementService kms = new KeyManagementService();
        
        assertThatThrownBy(() -> kms.deriveSessionKey("session-1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signWithRootKey_rejectsNullData() {
        KeyManagementService kms = new KeyManagementService();
        
        assertThatThrownBy(() -> kms.signWithRootKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidateSessionKey_removesKey() throws Exception {
        KeyManagementService kms = new KeyManagementService();
        PublicKey peerKey = generateTestPublicKey();
        
        kms.deriveSessionKey("session-1", peerKey);
        assertThat(kms.getSessionKeyCount()).isEqualTo(1);
        
        kms.invalidateSessionKey("session-1");
        assertThat(kms.getSessionKeyCount()).isEqualTo(0);
    }

    @Test
    void needsRotation_returnsFalseForNewDID() {
        KeyManagementService kms = new KeyManagementService();
        kms.getOrCreatePairwiseDID("requester-1");
        
        assertThat(kms.needsRotation("requester-1")).isFalse();
    }

    @Test
    void needsRotation_returnsFalseForUnknownRequester() {
        KeyManagementService kms = new KeyManagementService();
        
        assertThat(kms.needsRotation("unknown-requester")).isFalse();
    }
}
