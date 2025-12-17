package com.yachaq.node.identity;

import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.vault.LocalVault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DID/VC Wallet for managing identity credentials on-device.
 * 
 * Validates: Requirements 326.1, 326.2, 326.3, 326.4, 326.5
 * 
 * Key features:
 * - Anonymous mode by default (326.1)
 * - Verifiable credential presentation (326.2)
 * - Local credential storage with validation state (326.3)
 * - P2P credential transmission (326.4)
 * - W3C VC and DID standards support (326.5)
 */
public class CredentialWallet {

    private static final String CALLER_MODULE = "credential_wallet";

    private final KeyManagementService keyManagementService;
    private final LocalVault vault;
    private final Map<String, StoredCredential> credentials;
    private final Map<String, DIDDocument> didDocuments;
    private IdentityMode currentMode;
    private String primaryDid;

    public CredentialWallet(KeyManagementService keyManagementService, LocalVault vault) {
        this.keyManagementService = Objects.requireNonNull(keyManagementService);
        this.vault = Objects.requireNonNull(vault);
        this.credentials = new ConcurrentHashMap<>();
        this.didDocuments = new ConcurrentHashMap<>();
        this.currentMode = IdentityMode.ANONYMOUS; // Default to anonymous (326.1)
    }

    /**
     * Get current identity mode.
     * Default is ANONYMOUS per requirement 326.1.
     */
    public IdentityMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Set identity mode. Requires explicit user action.
     */
    public void setIdentityMode(IdentityMode mode) {
        this.currentMode = Objects.requireNonNull(mode);
    }

    /**
     * Check if operating in anonymous mode (default).
     */
    public boolean isAnonymous() {
        return currentMode == IdentityMode.ANONYMOUS;
    }

    /**
     * Get primary DID for this wallet.
     */
    public Optional<String> getPrimaryDid() {
        return Optional.ofNullable(primaryDid);
    }

    /**
     * Create a new DID for this wallet.
     */
    public DIDDocument createDid(String method) {
        KeyManagementService.NodeDID nodeDID = keyManagementService.getOrCreateNodeDID();
        String didId = "did:" + method + ":" + extractPublicKeyMultibase(nodeDID.publicKey());
        
        DIDDocument.VerificationMethod verificationMethod = new DIDDocument.VerificationMethod(
                didId + "#key-1",
                "Ed25519VerificationKey2020",
                didId,
                extractPublicKeyMultibase(nodeDID.publicKey())
        );

        DIDDocument doc = new DIDDocument(
                didId,
                List.of("https://www.w3.org/ns/did/v1"),
                List.of(verificationMethod),
                List.of(didId + "#key-1"),
                List.of(didId + "#key-1"),
                List.of(),
                Instant.now(),
                Instant.now()
        );

        didDocuments.put(didId, doc);
        if (primaryDid == null) {
            primaryDid = didId;
        }
        return doc;
    }

    /**
     * Generate a pairwise DID for a specific requester.
     * This provides anti-correlation protection (303.2).
     */
    public String generatePairwiseDid(String requesterId) {
        KeyManagementService.PairwiseDID pairwiseDID = keyManagementService.getOrCreatePairwiseDID(requesterId);
        return pairwiseDID.id();
    }
    
    /**
     * Extract multibase-encoded public key from a PublicKey.
     */
    private String extractPublicKeyMultibase(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        return "z" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
    }

    /**
     * Store a verifiable credential in the wallet.
     * Validates: Requirements 326.3
     */
    public void storeCredential(VerifiableCredential credential) {
        Objects.requireNonNull(credential);
        
        StoredCredential stored = new StoredCredential(
                credential,
                Instant.now(),
                credential.expirationDate(),
                Instant.now(),
                CredentialValidationStatus.VALID
        );
        
        credentials.put(credential.id(), stored);
        
        // Persist to encrypted vault
        persistCredentialToVault(stored);
    }

    /**
     * Get a stored credential by ID.
     */
    public Optional<StoredCredential> getCredential(String credentialId) {
        return Optional.ofNullable(credentials.get(credentialId));
    }

    /**
     * Get all credentials of a specific type.
     */
    public List<StoredCredential> getCredentialsByType(String type) {
        return credentials.values().stream()
                .filter(sc -> sc.credential().type().contains(type))
                .toList();
    }

    /**
     * Get all valid (non-expired, non-revoked) credentials.
     */
    public List<StoredCredential> getValidCredentials() {
        return credentials.values().stream()
                .filter(sc -> sc.status() == CredentialValidationStatus.VALID)
                .filter(sc -> sc.credential().isValid())
                .toList();
    }

    /**
     * Prepare a credential presentation for P2P transmission.
     * Validates: Requirements 326.2, 326.4
     * 
     * @param credentialIds IDs of credentials to present
     * @param requesterId Requester to present to (for pairwise DID)
     * @param challenge Challenge from requester for replay protection
     * @return Verifiable presentation ready for P2P transmission
     */
    public VerifiablePresentation preparePresentation(
            List<String> credentialIds,
            String requesterId,
            String challenge) {
        
        if (currentMode == IdentityMode.ANONYMOUS) {
            throw new IllegalStateException("Cannot present credentials in anonymous mode");
        }

        List<VerifiableCredential> selectedCredentials = credentialIds.stream()
                .map(credentials::get)
                .filter(Objects::nonNull)
                .filter(sc -> sc.credential().isValid())
                .map(StoredCredential::credential)
                .toList();

        if (selectedCredentials.isEmpty()) {
            throw new IllegalArgumentException("No valid credentials found for presentation");
        }

        // Use pairwise DID for anti-correlation
        String holderDid = generatePairwiseDid(requesterId);

        // Create proof
        String proofValue = signPresentation(selectedCredentials, challenge, holderDid);
        
        VerifiableCredential.Proof proof = new VerifiableCredential.Proof(
                "Ed25519Signature2020",
                Instant.now(),
                holderDid + "#key-1",
                "authentication",
                proofValue
        );

        return new VerifiablePresentation(
                "urn:uuid:" + UUID.randomUUID(),
                List.of("VerifiablePresentation"),
                holderDid,
                selectedCredentials,
                proof,
                challenge
        );
    }

    /**
     * Update credential status by checking with issuer.
     */
    public void refreshCredentialStatus(String credentialId) {
        StoredCredential stored = credentials.get(credentialId);
        if (stored == null) {
            return;
        }

        CredentialValidationStatus newStatus = checkCredentialStatus(stored.credential());
        
        StoredCredential updated = new StoredCredential(
                stored.credential(),
                stored.issuedAt(),
                stored.expiresAt(),
                Instant.now(),
                newStatus
        );
        
        credentials.put(credentialId, updated);
        persistCredentialToVault(updated);
    }

    /**
     * Remove a credential from the wallet.
     */
    public void removeCredential(String credentialId) {
        StoredCredential stored = credentials.remove(credentialId);
        if (stored != null) {
            // Note: In production, we'd track the vault rawRef for deletion
            // For now, credentials are removed from in-memory cache
        }
    }

    /**
     * Get DID document by DID.
     */
    public Optional<DIDDocument> getDIDDocument(String did) {
        return Optional.ofNullable(didDocuments.get(did));
    }

    // Private helper methods

    private void persistCredentialToVault(StoredCredential credential) {
        byte[] data = serializeCredential(credential);
        LocalVault.VaultMetadata metadata = LocalVault.VaultMetadata.of(
                "credential_wallet",
                "verifiable_credential"
        );
        vault.put(data, metadata, CALLER_MODULE);
    }

    private byte[] serializeCredential(StoredCredential credential) {
        // Simplified serialization - in production use proper JSON-LD serialization
        return credential.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String signPresentation(List<VerifiableCredential> credentialList, String challenge, String holderDid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (VerifiableCredential vc : credentialList) {
                digest.update(vc.id().getBytes(StandardCharsets.UTF_8));
            }
            digest.update(challenge.getBytes(StandardCharsets.UTF_8));
            digest.update(holderDid.getBytes(StandardCharsets.UTF_8));
            
            byte[] hash = digest.digest();
            byte[] signature = keyManagementService.signWithRootKey(hash);
            return Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private CredentialValidationStatus checkCredentialStatus(VerifiableCredential credential) {
        if (credential.isExpired()) {
            return CredentialValidationStatus.EXPIRED;
        }
        if (credential.credentialStatus() != null && credential.credentialStatus().revoked()) {
            return CredentialValidationStatus.REVOKED;
        }
        return CredentialValidationStatus.VALID;
    }

    /**
     * Stored credential with validation state.
     * Validates: Requirements 326.3
     */
    public record StoredCredential(
            VerifiableCredential credential,
            Instant issuedAt,
            Instant expiresAt,
            Instant lastStatusCheck,
            CredentialValidationStatus status
    ) {}

    /**
     * Credential validation status.
     */
    public enum CredentialValidationStatus {
        VALID,
        EXPIRED,
        REVOKED,
        UNKNOWN
    }
}
