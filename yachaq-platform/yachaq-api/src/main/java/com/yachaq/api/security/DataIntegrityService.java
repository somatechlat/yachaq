package com.yachaq.api.security;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Data Integrity Service - Cryptographic hash verification for tamper detection.
 * 
 * Property 7: Data Integrity Verification
 * Validates: Requirements 125.1, 125.2
 * 
 * For any critical data stored with a cryptographic hash, reading the data
 * and recomputing the hash must produce the same value as the stored hash.
 */
@Service
public class DataIntegrityService {

    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Compute SHA-256 hash of data.
     * Property 7: Data Integrity Verification
     * 
     * @param data The data to hash
     * @return Base64-encoded hash
     */
    public String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IntegrityException("Hash algorithm not available", e);
        }
    }

    /**
     * Compute SHA-256 hash of string data.
     * 
     * @param data The string to hash
     * @return Base64-encoded hash
     */
    public String computeHash(String data) {
        return computeHash(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verify data integrity by comparing computed hash with stored hash.
     * Property 7: Data Integrity Verification
     * 
     * @param data The data to verify
     * @param storedHash The previously stored hash
     * @return true if hashes match, false otherwise
     */
    public boolean verifyIntegrity(byte[] data, String storedHash) {
        String computedHash = computeHash(data);
        return MessageDigest.isEqual(
            computedHash.getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Verify string data integrity.
     * 
     * @param data The string data to verify
     * @param storedHash The previously stored hash
     * @return true if hashes match, false otherwise
     */
    public boolean verifyIntegrity(String data, String storedHash) {
        return verifyIntegrity(data.getBytes(StandardCharsets.UTF_8), storedHash);
    }

    /**
     * Create an integrity record for critical data.
     * 
     * @param resourceId The resource identifier
     * @param resourceType The type of resource
     * @param data The data to protect
     * @return IntegrityRecord with hash and metadata
     */
    public IntegrityRecord createIntegrityRecord(UUID resourceId, String resourceType, byte[] data) {
        String hash = computeHash(data);
        return new IntegrityRecord(
            UUID.randomUUID(),
            resourceId,
            resourceType,
            hash,
            HASH_ALGORITHM,
            Instant.now(),
            data.length
        );
    }

    /**
     * Verify an integrity record against current data.
     * Property 7: Data Integrity Verification
     * 
     * @param record The stored integrity record
     * @param currentData The current data to verify
     * @return VerificationResult with status and details
     */
    public VerificationResult verifyRecord(IntegrityRecord record, byte[] currentData) {
        String currentHash = computeHash(currentData);
        boolean matches = MessageDigest.isEqual(
            currentHash.getBytes(StandardCharsets.UTF_8),
            record.hash().getBytes(StandardCharsets.UTF_8)
        );

        if (!matches) {
            return new VerificationResult(
                false,
                "Hash mismatch - data may have been tampered",
                record.hash(),
                currentHash,
                Instant.now()
            );
        }

        if (currentData.length != record.dataLength()) {
            return new VerificationResult(
                false,
                "Data length mismatch",
                record.hash(),
                currentHash,
                Instant.now()
            );
        }

        return new VerificationResult(
            true,
            "Integrity verified",
            record.hash(),
            currentHash,
            Instant.now()
        );
    }

    /**
     * Compute hash chain for linked records (e.g., audit receipts).
     * 
     * @param previousHash The hash of the previous record
     * @param currentData The current record data
     * @return The chained hash
     */
    public String computeChainedHash(String previousHash, byte[] currentData) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            if (previousHash != null && !previousHash.isEmpty()) {
                digest.update(previousHash.getBytes(StandardCharsets.UTF_8));
            }
            digest.update(currentData);
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IntegrityException("Hash algorithm not available", e);
        }
    }

    /**
     * Verify a hash chain.
     * 
     * @param previousHash The hash of the previous record
     * @param currentData The current record data
     * @param expectedHash The expected chained hash
     * @return true if chain is valid
     */
    public boolean verifyChain(String previousHash, byte[] currentData, String expectedHash) {
        String computedHash = computeChainedHash(previousHash, currentData);
        return MessageDigest.isEqual(
            computedHash.getBytes(StandardCharsets.UTF_8),
            expectedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    // DTOs
    public record IntegrityRecord(
        UUID id,
        UUID resourceId,
        String resourceType,
        String hash,
        String algorithm,
        Instant createdAt,
        int dataLength
    ) {}

    public record VerificationResult(
        boolean valid,
        String message,
        String storedHash,
        String computedHash,
        Instant verifiedAt
    ) {}

    public static class IntegrityException extends RuntimeException {
        public IntegrityException(String message, Throwable cause) { super(message, cause); }
    }
}
