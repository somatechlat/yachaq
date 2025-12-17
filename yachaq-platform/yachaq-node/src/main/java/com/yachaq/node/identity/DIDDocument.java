package com.yachaq.node.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * W3C DID Document representation.
 * 
 * Validates: Requirements 326.5, 153.1
 */
public record DIDDocument(
        String id,
        List<String> context,
        List<VerificationMethod> verificationMethod,
        List<String> authentication,
        List<String> assertionMethod,
        List<Service> service,
        Instant created,
        Instant updated
) {
    public DIDDocument {
        Objects.requireNonNull(id, "DID required");
        Objects.requireNonNull(verificationMethod, "Verification methods required");
        if (!id.startsWith("did:")) {
            throw new IllegalArgumentException("Invalid DID format: must start with 'did:'");
        }
    }

    /**
     * Get the DID method (e.g., "key", "web", "ion").
     */
    public String getMethod() {
        String[] parts = id.split(":");
        return parts.length > 1 ? parts[1] : "";
    }

    /**
     * Verification method for cryptographic operations.
     */
    public record VerificationMethod(
            String id,
            String type,
            String controller,
            String publicKeyMultibase
    ) {
        public VerificationMethod {
            Objects.requireNonNull(id, "Verification method ID required");
            Objects.requireNonNull(type, "Verification method type required");
            Objects.requireNonNull(controller, "Controller required");
        }
    }

    /**
     * Service endpoint for DID resolution.
     */
    public record Service(
            String id,
            String type,
            String serviceEndpoint
    ) {
        public Service {
            Objects.requireNonNull(id, "Service ID required");
            Objects.requireNonNull(type, "Service type required");
            Objects.requireNonNull(serviceEndpoint, "Service endpoint required");
        }
    }
}
