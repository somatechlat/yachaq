package com.yachaq.node.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * W3C Verifiable Credential representation.
 * 
 * Validates: Requirements 326.5, 154.1
 */
public record VerifiableCredential(
        String id,
        List<String> type,
        String issuer,
        Instant issuanceDate,
        Instant expirationDate,
        Map<String, Object> credentialSubject,
        Proof proof,
        CredentialStatus credentialStatus
) {
    public VerifiableCredential {
        Objects.requireNonNull(id, "Credential ID required");
        Objects.requireNonNull(type, "Credential type required");
        Objects.requireNonNull(issuer, "Issuer required");
        Objects.requireNonNull(issuanceDate, "Issuance date required");
        Objects.requireNonNull(credentialSubject, "Credential subject required");
        Objects.requireNonNull(proof, "Proof required");
    }

    /**
     * Check if credential is expired.
     */
    public boolean isExpired() {
        return expirationDate != null && Instant.now().isAfter(expirationDate);
    }

    /**
     * Check if credential is valid (not expired and not revoked).
     */
    public boolean isValid() {
        if (isExpired()) {
            return false;
        }
        if (credentialStatus != null && credentialStatus.revoked()) {
            return false;
        }
        return true;
    }

    /**
     * Cryptographic proof for the credential.
     */
    public record Proof(
            String type,
            Instant created,
            String verificationMethod,
            String proofPurpose,
            String proofValue
    ) {
        public Proof {
            Objects.requireNonNull(type, "Proof type required");
            Objects.requireNonNull(created, "Proof creation time required");
            Objects.requireNonNull(verificationMethod, "Verification method required");
            Objects.requireNonNull(proofPurpose, "Proof purpose required");
            Objects.requireNonNull(proofValue, "Proof value required");
        }
    }

    /**
     * Credential status for revocation checking.
     */
    public record CredentialStatus(
            String id,
            String type,
            boolean revoked,
            Instant lastChecked
    ) {}
}
