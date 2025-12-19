package com.yachaq.api.verification;

import com.yachaq.api.audit.MerkleTree;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/**
 * Capsule Verification Service for verifying time capsule integrity.
 * Provides signature verification, schema validation, and hash receipt verification.
 * 
 * Security: All verification is cryptographic and tamper-evident.
 * Performance: Verification is O(1) for signatures, O(n) for schema.
 * 
 * Validates: Requirements 349.1, 349.2, 349.3, 349.4
 */
@Service
public class CapsuleVerificationService {

    // ==================== Task 94.1: Signature Verification ====================

    /**
     * Verifies a capsule's signature.
     * Requirement 349.1: Verify capsule signatures.
     */
    public SignatureVerificationResult verifySignature(CapsuleData capsule) {
        Objects.requireNonNull(capsule, "Capsule cannot be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Verify DS signature
        boolean dsSignatureValid = false;
        if (capsule.dsSignature() != null && capsule.dsPublicKey() != null) {
            dsSignatureValid = verifyEd25519Signature(
                    capsule.capsuleHash(),
                    capsule.dsSignature(),
                    capsule.dsPublicKey()
            );
            if (!dsSignatureValid) {
                errors.add("DS signature verification failed");
            }
        } else {
            errors.add("Missing DS signature or public key");
        }

        // Verify platform signature if present
        boolean platformSignatureValid = true;
        if (capsule.platformSignature() != null && capsule.platformPublicKey() != null) {
            platformSignatureValid = verifyEd25519Signature(
                    capsule.capsuleHash(),
                    capsule.platformSignature(),
                    capsule.platformPublicKey()
            );
            if (!platformSignatureValid) {
                errors.add("Platform signature verification failed");
            }
        }

        // Verify signature timestamp
        if (capsule.signedAt() != null) {
            if (capsule.signedAt().isAfter(Instant.now())) {
                errors.add("Signature timestamp is in the future");
            }
            if (capsule.signedAt().isBefore(Instant.now().minusSeconds(86400 * 365))) {
                warnings.add("Signature is older than 1 year");
            }
        }

        boolean valid = errors.isEmpty() && dsSignatureValid && platformSignatureValid;

        return new SignatureVerificationResult(
                valid,
                dsSignatureValid,
                platformSignatureValid,
                errors,
                warnings,
                capsule.signedAt()
        );
    }

    // ==================== Task 94.2: Schema Validation ====================

    /**
     * Validates a capsule against its schema.
     * Requirement 349.1: Validate capsule against schema.
     */
    public SchemaValidationResult validateSchema(CapsuleData capsule, CapsuleSchema schema) {
        Objects.requireNonNull(capsule, "Capsule cannot be null");
        Objects.requireNonNull(schema, "Schema cannot be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate schema version
        if (!schema.version().equals(capsule.schemaVersion())) {
            errors.add("Schema version mismatch: expected " + schema.version() + 
                       ", got " + capsule.schemaVersion());
        }

        // Validate required fields
        for (SchemaField field : schema.requiredFields()) {
            if (!capsule.hasField(field.name())) {
                errors.add("Missing required field: " + field.name());
            } else {
                Object value = capsule.getField(field.name());
                if (!isValidType(value, field.type())) {
                    errors.add("Invalid type for field " + field.name() + 
                               ": expected " + field.type() + ", got " + 
                               (value != null ? value.getClass().getSimpleName() : "null"));
                }
            }
        }

        // Validate optional fields if present
        for (SchemaField field : schema.optionalFields()) {
            if (capsule.hasField(field.name())) {
                Object value = capsule.getField(field.name());
                if (!isValidType(value, field.type())) {
                    warnings.add("Invalid type for optional field " + field.name());
                }
            }
        }

        // Validate output mode constraints
        if (schema.outputMode() != null) {
            if (!schema.outputMode().equals(capsule.outputMode())) {
                errors.add("Output mode mismatch: expected " + schema.outputMode() + 
                           ", got " + capsule.outputMode());
            }
        }

        // Validate size constraints
        if (schema.maxPayloadSize() > 0 && capsule.payloadSize() > schema.maxPayloadSize()) {
            errors.add("Payload size exceeds maximum: " + capsule.payloadSize() + 
                       " > " + schema.maxPayloadSize());
        }

        return new SchemaValidationResult(
                errors.isEmpty(),
                schema.version(),
                capsule.schemaVersion(),
                errors,
                warnings
        );
    }

    // ==================== Task 94.3: Hash Receipt Verification ====================

    /**
     * Verifies hash receipts for a capsule.
     * Requirement 349.1: Verify hash receipts.
     */
    public HashReceiptVerificationResult verifyHashReceipt(CapsuleData capsule, HashReceipt receipt) {
        Objects.requireNonNull(capsule, "Capsule cannot be null");
        Objects.requireNonNull(receipt, "Receipt cannot be null");

        List<String> errors = new ArrayList<>();

        // Verify capsule hash matches receipt
        String computedHash = computeCapsuleHash(capsule);
        boolean hashMatches = computedHash.equals(receipt.capsuleHash());
        if (!hashMatches) {
            errors.add("Capsule hash mismatch: computed " + computedHash + 
                       ", receipt has " + receipt.capsuleHash());
        }

        // Verify Merkle proof if present
        boolean merkleProofValid = true;
        if (receipt.merkleProof() != null && !receipt.merkleProof().isEmpty()) {
            merkleProofValid = verifyMerkleProof(
                    receipt.capsuleHash(),
                    receipt.merkleRoot(),
                    receipt.merkleProof()
            );
            if (!merkleProofValid) {
                errors.add("Merkle proof verification failed");
            }
        }

        // Verify receipt signature
        boolean receiptSignatureValid = true;
        if (receipt.signature() != null && receipt.signerPublicKey() != null) {
            String receiptData = receipt.capsuleHash() + "|" + receipt.timestamp().toString();
            receiptSignatureValid = verifyEd25519Signature(
                    receiptData,
                    receipt.signature(),
                    receipt.signerPublicKey()
            );
            if (!receiptSignatureValid) {
                errors.add("Receipt signature verification failed");
            }
        }

        // Verify timestamp
        if (receipt.timestamp().isAfter(Instant.now())) {
            errors.add("Receipt timestamp is in the future");
        }

        boolean valid = errors.isEmpty() && hashMatches && merkleProofValid && receiptSignatureValid;

        return new HashReceiptVerificationResult(
                valid,
                hashMatches,
                merkleProofValid,
                receiptSignatureValid,
                receipt.merkleRoot(),
                errors
        );
    }

    /**
     * Performs complete verification of a capsule.
     * Requirement 349.1: Complete verification.
     */
    public CompleteVerificationResult verifyComplete(CapsuleData capsule, 
                                                      CapsuleSchema schema,
                                                      HashReceipt receipt) {
        SignatureVerificationResult sigResult = verifySignature(capsule);
        SchemaValidationResult schemaResult = validateSchema(capsule, schema);
        HashReceiptVerificationResult receiptResult = verifyHashReceipt(capsule, receipt);

        boolean allValid = sigResult.valid() && schemaResult.valid() && receiptResult.valid();

        List<String> allErrors = new ArrayList<>();
        allErrors.addAll(sigResult.errors());
        allErrors.addAll(schemaResult.errors());
        allErrors.addAll(receiptResult.errors());

        return new CompleteVerificationResult(
                allValid,
                sigResult,
                schemaResult,
                receiptResult,
                allErrors,
                Instant.now()
        );
    }

    // ==================== Private Helper Methods ====================

    private boolean verifyEd25519Signature(String data, String signatureBase64, String publicKeyBase64) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64);
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(dataBytes);

            return signature.verify(signatureBytes);
        } catch (Exception e) {
            // Signature verification failed
            return false;
        }
    }

    private String computeCapsuleHash(CapsuleData capsule) {
        String data = String.join("|",
                capsule.capsuleId(),
                capsule.contractId(),
                String.valueOf(capsule.payloadSize()),
                capsule.schemaVersion(),
                capsule.outputMode()
        );
        return MerkleTree.sha256(data);
    }

    private boolean verifyMerkleProof(String leafHash, String merkleRoot, List<String> proof) {
        String currentHash = leafHash;
        for (String siblingHash : proof) {
            // Combine hashes in sorted order for consistency
            if (currentHash.compareTo(siblingHash) < 0) {
                currentHash = MerkleTree.sha256(currentHash + siblingHash);
            } else {
                currentHash = MerkleTree.sha256(siblingHash + currentHash);
            }
        }
        return currentHash.equals(merkleRoot);
    }

    private boolean isValidType(Object value, String expectedType) {
        if (value == null) return false;
        return switch (expectedType.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer", "int" -> value instanceof Integer || value instanceof Long;
            case "number", "double", "float" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array", "list" -> value instanceof List;
            case "object", "map" -> value instanceof Map;
            case "bytes" -> value instanceof byte[];
            default -> true; // Unknown type, accept
        };
    }

    // ==================== Inner Types ====================

    public record CapsuleData(
            String capsuleId,
            String contractId,
            String capsuleHash,
            String dsSignature,
            String dsPublicKey,
            String platformSignature,
            String platformPublicKey,
            Instant signedAt,
            String schemaVersion,
            String outputMode,
            int payloadSize,
            Map<String, Object> fields
    ) {
        public boolean hasField(String name) {
            return fields != null && fields.containsKey(name);
        }

        public Object getField(String name) {
            return fields != null ? fields.get(name) : null;
        }
    }

    public record CapsuleSchema(
            String version,
            String outputMode,
            List<SchemaField> requiredFields,
            List<SchemaField> optionalFields,
            int maxPayloadSize
    ) {}

    public record SchemaField(String name, String type, String description) {}

    public record HashReceipt(
            String capsuleHash,
            String merkleRoot,
            List<String> merkleProof,
            String signature,
            String signerPublicKey,
            Instant timestamp
    ) {}

    public record SignatureVerificationResult(
            boolean valid,
            boolean dsSignatureValid,
            boolean platformSignatureValid,
            List<String> errors,
            List<String> warnings,
            Instant signedAt
    ) {}

    public record SchemaValidationResult(
            boolean valid,
            String expectedVersion,
            String actualVersion,
            List<String> errors,
            List<String> warnings
    ) {}

    public record HashReceiptVerificationResult(
            boolean valid,
            boolean hashMatches,
            boolean merkleProofValid,
            boolean receiptSignatureValid,
            String merkleRoot,
            List<String> errors
    ) {}

    public record CompleteVerificationResult(
            boolean valid,
            SignatureVerificationResult signatureResult,
            SchemaValidationResult schemaResult,
            HashReceiptVerificationResult receiptResult,
            List<String> allErrors,
            Instant verifiedAt
    ) {}
}
