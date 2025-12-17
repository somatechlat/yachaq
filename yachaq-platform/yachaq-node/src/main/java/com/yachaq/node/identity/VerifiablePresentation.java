package com.yachaq.node.identity;

import java.util.List;
import java.util.Objects;

/**
 * W3C Verifiable Presentation for credential exchange.
 * 
 * Validates: Requirements 326.2, 326.5
 */
public record VerifiablePresentation(
        String id,
        List<String> type,
        String holder,
        List<VerifiableCredential> verifiableCredential,
        VerifiableCredential.Proof proof,
        String challenge
) {
    public VerifiablePresentation {
        Objects.requireNonNull(id, "Presentation ID required");
        Objects.requireNonNull(type, "Presentation type required");
        Objects.requireNonNull(holder, "Holder required");
        Objects.requireNonNull(verifiableCredential, "Credentials required");
        Objects.requireNonNull(proof, "Proof required");
    }

    /**
     * Check if presentation contains credentials of a specific type.
     */
    public boolean containsCredentialType(String credentialType) {
        return verifiableCredential.stream()
                .anyMatch(vc -> vc.type().contains(credentialType));
    }

    /**
     * Get all credential types in this presentation.
     */
    public List<String> getCredentialTypes() {
        return verifiableCredential.stream()
                .flatMap(vc -> vc.type().stream())
                .distinct()
                .toList();
    }
}
