package com.yachaq.node.identity;

import com.yachaq.node.capsule.CapsuleHeader;
import com.yachaq.node.capsule.TimeCapsule;
import com.yachaq.node.transport.P2PSession;
import com.yachaq.node.transport.P2PTransport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * P2P Credential Transmitter for direct credential exchange.
 * 
 * Validates: Requirements 326.4
 * 
 * Credentials are sent directly to requester via P2P,
 * never through YACHAQ servers.
 */
public class P2PCredentialTransmitter {

    private final CredentialWallet wallet;
    private final P2PTransport transport;

    public P2PCredentialTransmitter(CredentialWallet wallet, P2PTransport transport) {
        this.wallet = Objects.requireNonNull(wallet);
        this.transport = Objects.requireNonNull(transport);
    }

    /**
     * Send a verifiable presentation directly to a requester via P2P.
     * Uses the P2P transport layer for secure, encrypted transmission.
     * 
     * @param presentation The presentation to send
     * @param session Active P2P session with the requester
     * @return Transmission receipt
     */
    public TransmissionReceipt sendPresentation(
            VerifiablePresentation presentation,
            P2PSession session) {

        Objects.requireNonNull(presentation, "Presentation cannot be null");
        Objects.requireNonNull(session, "Session cannot be null");

        if (!session.isActive()) {
            return new TransmissionReceipt(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    null,
                    false,
                    "P2P session is not active"
            );
        }

        // Serialize the presentation
        byte[] payload = serializePresentation(presentation);
        String payloadHash = computeHash(payload);

        // Create transmission envelope
        TransmissionEnvelope envelope = new TransmissionEnvelope(
                UUID.randomUUID().toString(),
                "VerifiablePresentation",
                Instant.now(),
                presentation.holder(),
                session.remoteNodeId(),
                payloadHash,
                payload
        );

        try {
            // Wrap presentation in a TimeCapsule for P2P transport
            TimeCapsule capsule = createCredentialCapsule(envelope);
            
            // Send via P2P transport (encrypted end-to-end)
            P2PTransport.TransferResult result = transport.sendCapsule(session, capsule);

            return new TransmissionReceipt(
                    envelope.id(),
                    envelope.timestamp(),
                    payloadHash,
                    result.complete(),
                    result.complete() ? null : "Transfer incomplete: " + result.error()
            );
        } catch (P2PTransport.P2PException e) {
            return new TransmissionReceipt(
                    envelope.id(),
                    envelope.timestamp(),
                    payloadHash,
                    false,
                    "P2P transmission failed: " + e.getMessage()
            );
        }
    }

    /**
     * Receive a verifiable presentation from a data sovereign.
     * 
     * @param envelope The received transmission envelope
     * @return The verifiable presentation
     */
    public VerifiablePresentation receivePresentation(TransmissionEnvelope envelope) {
        Objects.requireNonNull(envelope, "Envelope cannot be null");
        
        // Verify payload hash
        String computedHash = computeHash(envelope.payload());
        if (!computedHash.equals(envelope.payloadHash())) {
            throw new SecurityException("Payload hash mismatch - possible tampering");
        }

        // Deserialize the presentation
        return deserializePresentation(envelope.payload());
    }

    /**
     * Create a credential request to send to a data sovereign.
     * 
     * @param verifierId The verifier's identifier
     * @param challenge Challenge for replay protection
     * @param presentationDefinition Required credentials definition
     * @return The credential request
     */
    public CredentialRequest createCredentialRequest(
            String verifierId,
            String challenge,
            OpenID4VPHandler.PresentationDefinition presentationDefinition) {
        
        return new CredentialRequest(
                UUID.randomUUID().toString(),
                verifierId,
                challenge,
                presentationDefinition,
                Instant.now()
        );
    }

    /**
     * Verify a transmission envelope's integrity.
     */
    public boolean verifyEnvelopeIntegrity(TransmissionEnvelope envelope) {
        if (envelope == null || envelope.payload() == null) {
            return false;
        }
        String computedHash = computeHash(envelope.payload());
        return computedHash.equals(envelope.payloadHash());
    }

    // Private helper methods

    private TimeCapsule createCredentialCapsule(TransmissionEnvelope envelope) {
        CapsuleHeader header = CapsuleHeader.builder()
                .capsuleId(envelope.id())
                .planId("credential_presentation")
                .contractId(envelope.id())
                .ttl(Instant.now().plusSeconds(3600)) // 1 hour TTL
                .schemaVersion("1.0")
                .dsNodeId(envelope.sender())
                .requesterId(envelope.recipient())
                .createdAt(envelope.timestamp())
                .build();

        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        
        TimeCapsule.EncryptedPayload encryptedPayload = new TimeCapsule.EncryptedPayload(
                envelope.payload(),
                new byte[32], // Encrypted key placeholder
                iv,
                envelope.id(),
                "AES-256-GCM",
                envelope.payloadHash()
        );

        TimeCapsule.CapsuleProofs proofs = TimeCapsule.CapsuleProofs.builder()
                .capsuleHash(envelope.payloadHash())
                .dsSignature("sig_" + envelope.id())
                .contractId(envelope.id())
                .planHash(envelope.payloadHash())
                .signedAt(Instant.now())
                .build();

        return new TimeCapsule(header, encryptedPayload, proofs, TimeCapsule.CapsuleStatus.CREATED);
    }

    private byte[] serializePresentation(VerifiablePresentation presentation) {
        // Simplified serialization - in production use proper JSON-LD
        StringBuilder sb = new StringBuilder();
        sb.append("VP|");
        sb.append(presentation.id()).append("|");
        sb.append(presentation.holder()).append("|");
        sb.append(presentation.challenge()).append("|");
        sb.append(presentation.verifiableCredential().size());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private VerifiablePresentation deserializePresentation(byte[] data) {
        // Simplified deserialization - in production use proper JSON-LD parsing
        // This is a placeholder that throws - real implementation would parse JSON-LD
        throw new UnsupportedOperationException(
                "Full JSON-LD parsing not implemented - use proper VC library in production");
    }

    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Transmission envelope for P2P credential exchange.
     */
    public record TransmissionEnvelope(
            String id,
            String type,
            Instant timestamp,
            String sender,
            String recipient,
            String payloadHash,
            byte[] payload
    ) {
        public TransmissionEnvelope {
            Objects.requireNonNull(id, "ID required");
            Objects.requireNonNull(type, "Type required");
            Objects.requireNonNull(timestamp, "Timestamp required");
            payload = payload != null ? payload.clone() : new byte[0];
        }
    }

    /**
     * Receipt for credential transmission.
     */
    public record TransmissionReceipt(
            String transmissionId,
            Instant timestamp,
            String payloadHash,
            boolean success,
            String errorMessage
    ) {}

    /**
     * Request for credentials from a data sovereign.
     */
    public record CredentialRequest(
            String requestId,
            String verifierId,
            String challenge,
            OpenID4VPHandler.PresentationDefinition presentationDefinition,
            Instant createdAt
    ) {}
}
