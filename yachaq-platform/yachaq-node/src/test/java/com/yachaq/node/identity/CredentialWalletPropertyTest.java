package com.yachaq.node.identity;

import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.key.SecureKeyStore;
import com.yachaq.node.vault.LocalVault;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for DID/VC Wallet.
 * 
 * Feature: yachaq-platform, Property: DID/VC Wallet
 * Validates: Requirements 326.1, 326.2, 326.3, 326.4, 326.5
 */
class CredentialWalletPropertyTest {

    /**
     * Property: Anonymous mode is the default.
     * For any newly created wallet, the identity mode SHALL be ANONYMOUS.
     * 
     * Validates: Requirements 326.1
     */
    @Property(tries = 100)
    void anonymousModeIsDefault() {
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        
        CredentialWallet wallet = new CredentialWallet(keyService, vault);
        
        assertEquals(IdentityMode.ANONYMOUS, wallet.getCurrentMode());
        assertTrue(wallet.isAnonymous());
    }

    /**
     * Property: Credential storage preserves validation state.
     * For any stored credential, the wallet SHALL maintain issued_at, expires_at, and status.
     * 
     * Validates: Requirements 326.3
     */
    @Property(tries = 100)
    void credentialStoragePreservesValidationState(
            @ForAll @NotBlank String credentialId,
            @ForAll @NotBlank String issuer) {
        
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        Instant issuedAt = Instant.now();
        Instant expiresAt = Instant.now().plusSeconds(3600);

        VerifiableCredential credential = createTestCredential(credentialId, issuer, issuedAt, expiresAt);
        wallet.storeCredential(credential);

        var stored = wallet.getCredential(credentialId);
        assertTrue(stored.isPresent());
        assertEquals(credential.id(), stored.get().credential().id());
        assertNotNull(stored.get().issuedAt());
        assertEquals(expiresAt, stored.get().expiresAt());
        assertNotNull(stored.get().lastStatusCheck());
        assertEquals(CredentialWallet.CredentialValidationStatus.VALID, stored.get().status());
    }

    /**
     * Property: Cannot present credentials in anonymous mode.
     * For any wallet in anonymous mode, attempting to present credentials SHALL fail.
     * 
     * Validates: Requirements 326.1, 326.2
     */
    @Property(tries = 100)
    void cannotPresentCredentialsInAnonymousMode(
            @ForAll @NotBlank String credentialId,
            @ForAll @NotBlank String requesterId) {
        
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        // Store a credential
        VerifiableCredential credential = createTestCredential(credentialId, "issuer", Instant.now(), Instant.now().plusSeconds(3600));
        wallet.storeCredential(credential);

        // Wallet is in anonymous mode by default
        assertTrue(wallet.isAnonymous());

        // Attempting to present should fail
        assertThrows(IllegalStateException.class, () -> 
            wallet.preparePresentation(List.of(credentialId), requesterId, "challenge")
        );
    }

    /**
     * Property: Can present credentials when not in anonymous mode.
     * For any wallet in VERIFIED mode with valid credentials, presentation SHALL succeed.
     * 
     * Validates: Requirements 326.2
     */
    @Property(tries = 100)
    void canPresentCredentialsInVerifiedMode(
            @ForAll @NotBlank String credentialId,
            @ForAll @NotBlank String requesterId,
            @ForAll @NotBlank String challenge) {
        
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        // Store a valid credential
        VerifiableCredential credential = createTestCredential(credentialId, "issuer", Instant.now(), Instant.now().plusSeconds(3600));
        wallet.storeCredential(credential);

        // Switch to verified mode
        wallet.setIdentityMode(IdentityMode.VERIFIED);
        assertFalse(wallet.isAnonymous());

        // Presentation should succeed
        VerifiablePresentation presentation = wallet.preparePresentation(
                List.of(credentialId), requesterId, challenge);

        assertNotNull(presentation);
        assertEquals(1, presentation.verifiableCredential().size());
        assertEquals(credentialId, presentation.verifiableCredential().get(0).id());
        assertNotNull(presentation.proof());
        assertEquals(challenge, presentation.challenge());
    }

    /**
     * Property: Pairwise DIDs are unique per requester.
     * For any two different requesters, the pairwise DIDs SHALL be different.
     * 
     * Validates: Requirements 326.4, 303.2
     */
    @Property(tries = 100)
    void pairwiseDidsAreUniquePerRequester(
            @ForAll @NotBlank String requester1,
            @ForAll @NotBlank String requester2) {
        
        Assume.that(!requester1.equals(requester2));

        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        String did1 = wallet.generatePairwiseDid(requester1);
        String did2 = wallet.generatePairwiseDid(requester2);

        assertNotEquals(did1, did2);
    }

    /**
     * Property: Same requester gets same pairwise DID.
     * For any requester, repeated calls SHALL return the same pairwise DID.
     * 
     * Validates: Requirements 326.4
     */
    @Property(tries = 100)
    void samePairwiseDidForSameRequester(@ForAll @NotBlank String requesterId) {
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        String did1 = wallet.generatePairwiseDid(requesterId);
        String did2 = wallet.generatePairwiseDid(requesterId);

        assertEquals(did1, did2);
    }

    /**
     * Property: Expired credentials are not valid.
     * For any credential past its expiration date, isValid() SHALL return false.
     * 
     * Validates: Requirements 326.3
     */
    @Property(tries = 100)
    void expiredCredentialsAreNotValid(@ForAll @NotBlank String credentialId) {
        Instant issuedAt = Instant.now().minusSeconds(7200);
        Instant expiresAt = Instant.now().minusSeconds(3600); // Expired 1 hour ago

        VerifiableCredential credential = createTestCredential(credentialId, "issuer", issuedAt, expiresAt);

        assertTrue(credential.isExpired());
        assertFalse(credential.isValid());
    }

    /**
     * Property: Valid credentials are returned by getValidCredentials.
     * For any stored valid credential, it SHALL appear in getValidCredentials().
     * 
     * Validates: Requirements 326.3
     */
    @Property(tries = 100)
    void validCredentialsAreReturned(
            @ForAll @Size(min = 1, max = 5) List<@NotBlank String> credentialIds) {
        
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        // Store valid credentials
        for (String id : credentialIds) {
            VerifiableCredential credential = createTestCredential(
                    id, "issuer", Instant.now(), Instant.now().plusSeconds(3600));
            wallet.storeCredential(credential);
        }

        List<CredentialWallet.StoredCredential> validCredentials = wallet.getValidCredentials();
        
        assertEquals(credentialIds.size(), validCredentials.size());
        for (String id : credentialIds) {
            assertTrue(validCredentials.stream()
                    .anyMatch(sc -> sc.credential().id().equals(id)));
        }
    }

    /**
     * Property: DID format is valid.
     * For any created DID, it SHALL start with "did:" and contain a method.
     * 
     * Validates: Requirements 326.5
     */
    @Property(tries = 100)
    void didFormatIsValid(@ForAll("didMethods") String method) {
        KeyManagementService keyService = createKeyManagementService();
        LocalVault vault = createVault();
        CredentialWallet wallet = new CredentialWallet(keyService, vault);

        DIDDocument doc = wallet.createDid(method);

        assertTrue(doc.id().startsWith("did:"));
        assertEquals(method, doc.getMethod());
        assertFalse(doc.verificationMethod().isEmpty());
    }

    @Provide
    Arbitrary<String> didMethods() {
        return Arbitraries.of("key", "web", "ion", "ethr");
    }

    // Helper methods

    private KeyManagementService createKeyManagementService() {
        return new KeyManagementService(new SecureKeyStore() {
            @Override
            public java.security.KeyPair loadRootKeyPair() {
                return null; // Force generation of new keypair
            }

            @Override
            public void storeRootKeyPair(java.security.KeyPair keyPair, Instant createdAt) {
                // No-op for testing
            }

            @Override
            public Instant getRootKeyCreatedAt() {
                return Instant.now();
            }

            @Override
            public void storeNetworkIdentifier(String networkId, java.security.KeyPair keyPair, Instant createdAt) {
                // No-op for testing
            }

            @Override
            public void archivePairwiseDID(KeyManagementService.PairwiseDID did) {
                // No-op for testing
            }

            @Override
            public boolean isHardwareBacked() {
                return false;
            }

            @Override
            public void wipe() {
                // No-op for testing
            }
        }, KeyManagementService.KeyRotationPolicy.defaults());
    }

    private LocalVault createVault() {
        // Use real LocalVault with credential_wallet in allowed modules
        return new LocalVault();
    }

    private VerifiableCredential createTestCredential(
            String id, String issuer, Instant issuedAt, Instant expiresAt) {
        return new VerifiableCredential(
                id,
                List.of("VerifiableCredential", "IdentityCredential"),
                issuer,
                issuedAt,
                expiresAt,
                Map.of("id", "did:example:subject"),
                new VerifiableCredential.Proof(
                        "Ed25519Signature2020",
                        issuedAt,
                        issuer + "#key-1",
                        "assertionMethod",
                        "proof_value_" + id
                ),
                null
        );
    }
}
