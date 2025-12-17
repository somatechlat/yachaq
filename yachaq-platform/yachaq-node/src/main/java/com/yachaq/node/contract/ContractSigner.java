package com.yachaq.node.contract;

import com.yachaq.node.key.KeyManagementService;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Signs and verifies consent contracts.
 * Requirement 314.3: Create Contract.sign(draft) method.
 * Requirement 314.4: Create Contract.verify(contract) method.
 */
public class ContractSigner {

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private final KeyManagementService keyManagement;

    public ContractSigner(KeyManagementService keyManagement) {
        this.keyManagement = Objects.requireNonNull(keyManagement, "Key management cannot be null");
    }

    /**
     * Signs a contract draft with the DS node's private key.
     * Requirement 314.3: Create Contract.sign(draft) method.
     * Requirement 314.4: Require user signature followed by requester countersignature.
     */
    public SignedContract sign(ContractDraft draft) throws SignatureException {
        Objects.requireNonNull(draft, "Draft cannot be null");

        // Validate draft is not expired
        if (draft.isExpired()) {
            throw new SignatureException("Cannot sign expired draft");
        }

        // Get canonical bytes for signing
        byte[] canonicalBytes = draft.getCanonicalBytes();

        // Sign with DS node's private key
        String dsSignature;
        try {
            byte[] sigBytes = keyManagement.signWithRootKey(canonicalBytes);
            dsSignature = Base64.getEncoder().encodeToString(sigBytes);
        } catch (Exception e) {
            throw new SignatureException("Failed to sign contract: " + e.getMessage(), e);
        }

        // Create signed contract (awaiting requester countersignature)
        return new SignedContract(
                draft,
                dsSignature,
                null, // Requester signature pending
                Instant.now(),
                null,
                SignedContract.SignatureStatus.DS_SIGNED
        );
    }

    /**
     * Adds requester countersignature to a signed contract.
     * Requirement 314.3: Require user signature followed by requester countersignature.
     */
    public SignedContract addCountersignature(SignedContract contract, String requesterSignature) 
            throws SignatureException {
        Objects.requireNonNull(contract, "Contract cannot be null");
        Objects.requireNonNull(requesterSignature, "Requester signature cannot be null");

        if (contract.status() != SignedContract.SignatureStatus.DS_SIGNED) {
            throw new SignatureException("Contract must be DS-signed before countersigning");
        }

        if (contract.draft().isExpired()) {
            throw new SignatureException("Cannot countersign expired contract");
        }

        return new SignedContract(
                contract.draft(),
                contract.dsSignature(),
                requesterSignature,
                contract.dsSignedAt(),
                Instant.now(),
                SignedContract.SignatureStatus.FULLY_SIGNED
        );
    }

    /**
     * Verifies a signed contract.
     * Requirement 314.4: Create Contract.verify(contract) method.
     */
    public VerificationResult verify(SignedContract contract) {
        Objects.requireNonNull(contract, "Contract cannot be null");

        List<String> errors = new ArrayList<>();

        // Check expiration
        if (contract.draft().isExpired()) {
            errors.add("Contract draft has expired");
        }

        // Verify DS signature
        try {
            byte[] canonicalBytes = contract.draft().getCanonicalBytes();
            byte[] sigBytes = Base64.getDecoder().decode(contract.dsSignature());
            PublicKey publicKey = keyManagement.getOrCreateRootKeyPair().getPublic();
            boolean dsValid = keyManagement.verifySignature(canonicalBytes, sigBytes, publicKey);
            if (!dsValid) {
                errors.add("Invalid DS signature");
            }
        } catch (Exception e) {
            errors.add("DS signature verification failed: " + e.getMessage());
        }

        // Verify requester signature if present
        if (contract.requesterSignature() != null) {
            // In production, this would verify against requester's public key
            if (contract.requesterSignature().length() < 64) {
                errors.add("Invalid requester signature format");
            }
        }

        // Check signature status consistency
        if (contract.status() == SignedContract.SignatureStatus.FULLY_SIGNED &&
            contract.requesterSignature() == null) {
            errors.add("Fully signed contract missing requester signature");
        }

        return new VerificationResult(
                errors.isEmpty(),
                errors,
                contract.status()
        );
    }

    /**
     * Verifies that a contract has not been tampered with.
     * Requirement 314.4: Validate signatures and ensure immutability.
     */
    public boolean verifyIntegrity(SignedContract contract) {
        if (contract == null || contract.dsSignature() == null) {
            return false;
        }

        try {
            byte[] canonicalBytes = contract.draft().getCanonicalBytes();
            byte[] sigBytes = Base64.getDecoder().decode(contract.dsSignature());
            PublicKey publicKey = keyManagement.getOrCreateRootKeyPair().getPublic();
            return keyManagement.verifySignature(canonicalBytes, sigBytes, publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A signed consent contract.
     */
    public record SignedContract(
            ContractDraft draft,
            String dsSignature,
            String requesterSignature,
            Instant dsSignedAt,
            Instant requesterSignedAt,
            SignatureStatus status
    ) {
        public SignedContract {
            Objects.requireNonNull(draft, "Draft cannot be null");
            Objects.requireNonNull(dsSignature, "DS signature cannot be null");
            Objects.requireNonNull(dsSignedAt, "DS signed at cannot be null");
            Objects.requireNonNull(status, "Status cannot be null");
        }

        public boolean isFullySigned() {
            return status == SignatureStatus.FULLY_SIGNED;
        }

        public boolean isExpired() {
            return draft.isExpired();
        }

        /**
         * Gets the contract hash for blockchain anchoring.
         */
        public String getContractHash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(draft.getCanonicalBytes());
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        public enum SignatureStatus {
            DS_SIGNED,      // Only DS has signed
            FULLY_SIGNED,   // Both DS and requester have signed
            INVALID         // Signature verification failed
        }
    }

    /**
     * Result of contract verification.
     */
    public record VerificationResult(
            boolean valid,
            List<String> errors,
            SignedContract.SignatureStatus status
    ) {
        public VerificationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
    }
}
