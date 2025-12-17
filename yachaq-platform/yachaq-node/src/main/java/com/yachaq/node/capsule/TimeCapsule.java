package com.yachaq.node.capsule;

import java.time.Instant;
import java.util.*;

/**
 * Time-limited encrypted data capsule.
 * Requirement 316.1: Include header with plan_id, TTL, schema, summary.
 * Requirement 316.2: Encrypt payload to requester public keys only.
 * Requirement 316.3: Attach signatures, capsule hash, contract_id.
 */
public record TimeCapsule(
        CapsuleHeader header,
        EncryptedPayload payload,
        CapsuleProofs proofs,
        CapsuleStatus status
) {
    
    public TimeCapsule {
        Objects.requireNonNull(header, "Header cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(proofs, "Proofs cannot be null");
        Objects.requireNonNull(status, "Status cannot be null");
    }

    /**
     * Checks if the capsule has expired.
     */
    public boolean isExpired() {
        return header.isExpired();
    }

    /**
     * Gets the capsule ID.
     */
    public String getId() {
        return header.capsuleId();
    }

    /**
     * Gets the contract ID.
     */
    public String getContractId() {
        return header.contractId();
    }

    /**
     * Gets the TTL.
     */
    public Instant getTtl() {
        return header.ttl();
    }

    /**
     * Encrypted payload container.
     * Requirement 316.2: Encrypt payload to requester public keys only.
     */
    public record EncryptedPayload(
            byte[] encryptedData,
            byte[] encryptedKey,
            byte[] iv,
            String keyId,
            String algorithm,
            String recipientKeyFingerprint
    ) {
        public EncryptedPayload {
            Objects.requireNonNull(encryptedData, "Encrypted data cannot be null");
            Objects.requireNonNull(encryptedKey, "Encrypted key cannot be null");
            Objects.requireNonNull(iv, "IV cannot be null");
            Objects.requireNonNull(keyId, "Key ID cannot be null");
            Objects.requireNonNull(algorithm, "Algorithm cannot be null");
            Objects.requireNonNull(recipientKeyFingerprint, "Recipient key fingerprint cannot be null");
            
            // Defensive copy
            encryptedData = encryptedData.clone();
            encryptedKey = encryptedKey.clone();
            iv = iv.clone();
        }

        /**
         * Gets the size of the encrypted payload.
         */
        public int size() {
            return encryptedData.length;
        }
    }

    /**
     * Cryptographic proofs for the capsule.
     * Requirement 316.3: Attach signatures, capsule hash, contract_id.
     */
    public record CapsuleProofs(
            String capsuleHash,
            String dsSignature,
            String contractId,
            String planHash,
            Instant signedAt,
            List<String> attestations
    ) {
        public CapsuleProofs {
            Objects.requireNonNull(capsuleHash, "Capsule hash cannot be null");
            Objects.requireNonNull(dsSignature, "DS signature cannot be null");
            Objects.requireNonNull(contractId, "Contract ID cannot be null");
            Objects.requireNonNull(signedAt, "Signed at cannot be null");
            attestations = attestations != null ? List.copyOf(attestations) : List.of();
        }

        /**
         * Builder for CapsuleProofs.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String capsuleHash;
            private String dsSignature;
            private String contractId;
            private String planHash;
            private Instant signedAt = Instant.now();
            private List<String> attestations = new ArrayList<>();

            public Builder capsuleHash(String hash) { this.capsuleHash = hash; return this; }
            public Builder dsSignature(String sig) { this.dsSignature = sig; return this; }
            public Builder contractId(String id) { this.contractId = id; return this; }
            public Builder planHash(String hash) { this.planHash = hash; return this; }
            public Builder signedAt(Instant at) { this.signedAt = at; return this; }
            public Builder attestations(List<String> atts) { this.attestations = new ArrayList<>(atts); return this; }
            public Builder addAttestation(String att) { this.attestations.add(att); return this; }

            public CapsuleProofs build() {
                return new CapsuleProofs(capsuleHash, dsSignature, contractId, planHash, signedAt, attestations);
            }
        }
    }

    /**
     * Capsule lifecycle status.
     */
    public enum CapsuleStatus {
        CREATED,        // Capsule created, not yet delivered
        DELIVERED,      // Capsule delivered to requester
        ACCESSED,       // Capsule accessed by requester
        EXPIRED,        // TTL expired
        SHREDDED        // Crypto-shredded
    }

    /**
     * Builder for TimeCapsule.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private CapsuleHeader header;
        private EncryptedPayload payload;
        private CapsuleProofs proofs;
        private CapsuleStatus status = CapsuleStatus.CREATED;

        public Builder header(CapsuleHeader header) { this.header = header; return this; }
        public Builder payload(EncryptedPayload payload) { this.payload = payload; return this; }
        public Builder proofs(CapsuleProofs proofs) { this.proofs = proofs; return this; }
        public Builder status(CapsuleStatus status) { this.status = status; return this; }

        public TimeCapsule build() {
            return new TimeCapsule(header, payload, proofs, status);
        }
    }
}
