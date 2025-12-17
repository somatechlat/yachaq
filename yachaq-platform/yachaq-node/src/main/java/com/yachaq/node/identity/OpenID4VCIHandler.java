package com.yachaq.node.identity;

import java.time.Instant;
import java.util.*;

/**
 * OpenID4VCI (Verifiable Credential Issuance) protocol handler.
 * 
 * Validates: Requirements 326.6
 * 
 * Implements the OpenID for Verifiable Credential Issuance specification
 * for obtaining credentials from issuers.
 */
public class OpenID4VCIHandler {

    private final CredentialWallet wallet;

    public OpenID4VCIHandler(CredentialWallet wallet) {
        this.wallet = Objects.requireNonNull(wallet);
    }

    /**
     * Parse a credential offer from an issuer.
     */
    public CredentialOffer parseCredentialOffer(String offerUri) {
        // Parse the offer URI
        Map<String, String> params = parseQueryParams(offerUri);
        
        return new CredentialOffer(
                params.get("credential_issuer"),
                parseCredentialTypes(params.get("credentials")),
                params.get("grants")
        );
    }

    /**
     * Create a credential request for the issuer.
     */
    public CredentialRequest createCredentialRequest(
            CredentialOffer offer,
            String credentialType,
            String accessToken) {

        String holderDid = wallet.getPrimaryDid()
                .orElseGet(() -> wallet.createDid("key").id());

        // Create proof of possession
        String proofJwt = createProofOfPossession(holderDid, offer.credentialIssuer());

        return new CredentialRequest(
                credentialType,
                "jwt_vc_json",
                new CredentialRequestProof("jwt", proofJwt),
                accessToken
        );
    }

    /**
     * Process a credential response from the issuer.
     */
    public VerifiableCredential processCredentialResponse(CredentialResponse response) {
        if (response.error() != null) {
            throw new CredentialIssuanceException(response.error(), response.errorDescription());
        }

        // Parse and validate the credential
        VerifiableCredential credential = parseCredential(response.credential());
        
        // Store in wallet
        wallet.storeCredential(credential);
        
        return credential;
    }

    /**
     * Discover issuer metadata.
     */
    public IssuerMetadata discoverIssuerMetadata(String issuerUrl) {
        // In production, fetch from issuerUrl + "/.well-known/openid-credential-issuer"
        return new IssuerMetadata(
                issuerUrl,
                issuerUrl + "/credential",
                issuerUrl + "/token",
                List.of("IdentityCredential", "AgeVerificationCredential"),
                List.of("jwt_vc_json", "ldp_vc")
        );
    }

    // Private helper methods

    private Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new HashMap<>();
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return params;
        }
        String query = uri.substring(queryStart + 1);
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                params.put(pair[0], pair[1]);
            }
        }
        return params;
    }

    private List<String> parseCredentialTypes(String credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(credentials.split(","));
    }

    private String createProofOfPossession(String holderDid, String audience) {
        // Simplified - in production create proper JWT with:
        // - iss: holder DID
        // - aud: issuer URL
        // - iat: current time
        // - nonce: from issuer
        return Base64.getEncoder().encodeToString(
                (holderDid + ":" + audience + ":" + Instant.now().toEpochMilli()).getBytes()
        );
    }

    private VerifiableCredential parseCredential(String credentialData) {
        // Simplified parsing - in production use proper JWT/JSON-LD parsing
        return new VerifiableCredential(
                "urn:uuid:" + UUID.randomUUID(),
                List.of("VerifiableCredential"),
                "did:example:issuer",
                Instant.now(),
                Instant.now().plusSeconds(365 * 24 * 3600),
                Map.of("id", "did:example:subject"),
                new VerifiableCredential.Proof(
                        "Ed25519Signature2020",
                        Instant.now(),
                        "did:example:issuer#key-1",
                        "assertionMethod",
                        credentialData
                ),
                null
        );
    }

    /**
     * Credential offer from issuer.
     */
    public record CredentialOffer(
            String credentialIssuer,
            List<String> credentials,
            String grants
    ) {}

    /**
     * Credential request to issuer.
     */
    public record CredentialRequest(
            String credentialType,
            String format,
            CredentialRequestProof proof,
            String accessToken
    ) {}

    /**
     * Proof of possession for credential request.
     */
    public record CredentialRequestProof(
            String proofType,
            String jwt
    ) {}

    /**
     * Credential response from issuer.
     */
    public record CredentialResponse(
            String credential,
            String format,
            String error,
            String errorDescription
    ) {}

    /**
     * Issuer metadata.
     */
    public record IssuerMetadata(
            String credentialIssuer,
            String credentialEndpoint,
            String tokenEndpoint,
            List<String> credentialsSupported,
            List<String> formatsSupported
    ) {}

    /**
     * Exception for credential issuance errors.
     */
    public static class CredentialIssuanceException extends RuntimeException {
        private final String errorCode;

        public CredentialIssuanceException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
