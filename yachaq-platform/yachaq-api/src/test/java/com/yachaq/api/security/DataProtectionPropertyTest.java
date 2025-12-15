package com.yachaq.api.security;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Property-based tests for Data Protection Layer.
 * 
 * **Feature: yachaq-platform, Property 6: Data Encryption at Rest**
 * **Validates: Requirements 121.1**
 * 
 * For any user data stored in the system (on-device or ephemeral cloud),
 * the data must be encrypted using AES-256-GCM with a unique key per data category.
 * 
 * **Feature: yachaq-platform, Property 7: Data Integrity Verification**
 * **Validates: Requirements 125.1**
 * 
 * For any critical data stored with a cryptographic hash, reading the data
 * and recomputing the hash must produce the same value as the stored hash.
 */
class DataProtectionPropertyTest {

    private final EncryptionService encryptionService = new EncryptionService();
    private final DataIntegrityService integrityService = new DataIntegrityService();

    // ========================================================================
    // Property 6: Data Encryption at Rest
    // ========================================================================

    @Property(tries = 100)
    void property6_encryptDecryptRoundTrip(
            @ForAll @StringLength(min = 1, max = 10000) String plaintext,
            @ForAll("dataCategories") String category) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        UUID dsId = UUID.randomUUID();
        
        // Encrypt
        EncryptionService.EncryptionResult result = encryptionService.encrypt(data, category, dsId);
        
        // Property 6: Encryption must succeed
        assert result.success() : "Encryption should succeed";
        assert result.ciphertext() != null : "Ciphertext should not be null";
        assert result.keyId() != null : "Key ID should not be null";
        
        // Decrypt
        byte[] decrypted = encryptionService.decrypt(result.ciphertext(), result.keyId(), category, dsId);
        
        // Property 6: Round-trip must preserve data
        assert Arrays.equals(data, decrypted) 
            : "Decrypted data must match original plaintext";
    }

    @Property(tries = 100)
    void property6_ciphertextDiffersFromPlaintext(
            @ForAll @StringLength(min = 10, max = 1000) String plaintext,
            @ForAll("dataCategories") String category) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        UUID dsId = UUID.randomUUID();
        
        EncryptionService.EncryptionResult result = encryptionService.encrypt(data, category, dsId);
        
        // Property 6: Ciphertext must differ from plaintext (encryption actually happened)
        assert !Arrays.equals(data, result.ciphertext()) 
            : "Ciphertext must differ from plaintext";
    }

    @Property(tries = 100)
    void property6_differentCategoriesUseDifferentKeys(
            @ForAll @StringLength(min = 10, max = 100) String plaintext) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        UUID dsId = UUID.randomUUID();
        
        // Encrypt same data with different categories
        EncryptionService.EncryptionResult result1 = encryptionService.encrypt(data, "consent", dsId);
        EncryptionService.EncryptionResult result2 = encryptionService.encrypt(data, "audit", dsId);
        
        // Property 6: Different categories should use different keys
        assert !result1.keyId().equals(result2.keyId()) 
            : "Different categories must use different keys";
    }

    @Property(tries = 100)
    void property6_differentUsersUseDifferentKeys(
            @ForAll @StringLength(min = 10, max = 100) String plaintext,
            @ForAll("dataCategories") String category) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        UUID dsId1 = UUID.randomUUID();
        UUID dsId2 = UUID.randomUUID();
        
        // Encrypt same data for different users
        EncryptionService.EncryptionResult result1 = encryptionService.encrypt(data, category, dsId1);
        EncryptionService.EncryptionResult result2 = encryptionService.encrypt(data, category, dsId2);
        
        // Property 6: Different users should use different keys
        assert !result1.keyId().equals(result2.keyId()) 
            : "Different users must use different keys";
    }

    @Property(tries = 100)
    void property6_sameInputProducesDifferentCiphertext(
            @ForAll @StringLength(min = 10, max = 100) String plaintext,
            @ForAll("dataCategories") String category) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        UUID dsId = UUID.randomUUID();
        
        // Encrypt same data twice
        EncryptionService.EncryptionResult result1 = encryptionService.encrypt(data, category, dsId);
        EncryptionService.EncryptionResult result2 = encryptionService.encrypt(data, category, dsId);
        
        // Property 6: Same plaintext should produce different ciphertext (due to random IV)
        assert !Arrays.equals(result1.ciphertext(), result2.ciphertext()) 
            : "Same plaintext should produce different ciphertext (random IV)";
    }

    @Property(tries = 50)
    void property6_envelopeEncryptionRoundTrip(
            @ForAll @StringLength(min = 10, max = 100) String plaintext) {
        
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        
        // Generate a data encryption key (DEK)
        SecretKey dek = encryptionService.generateDataKey();
        
        // Wrap the DEK with a key encryption key (KEK)
        byte[] wrappedKey = encryptionService.wrapKey(dek, "test-kek");
        
        // Unwrap the DEK
        SecretKey unwrappedDek = encryptionService.unwrapKey(wrappedKey, "test-kek");
        
        // Property 6: Wrapped/unwrapped key should be equivalent
        assert Arrays.equals(dek.getEncoded(), unwrappedDek.getEncoded())
            : "Unwrapped key must match original DEK";
    }

    // ========================================================================
    // Property 7: Data Integrity Verification
    // ========================================================================

    @Property(tries = 100)
    void property7_hashIsConsistent(
            @ForAll @StringLength(min = 1, max = 10000) String data) {
        
        // Compute hash twice
        String hash1 = integrityService.computeHash(data);
        String hash2 = integrityService.computeHash(data);
        
        // Property 7: Same data must produce same hash
        assert hash1.equals(hash2) 
            : "Same data must produce same hash";
    }

    @Property(tries = 100)
    void property7_differentDataProducesDifferentHash(
            @ForAll @StringLength(min = 1, max = 1000) String data1,
            @ForAll @StringLength(min = 1, max = 1000) String data2) {
        
        Assume.that(!data1.equals(data2));
        
        String hash1 = integrityService.computeHash(data1);
        String hash2 = integrityService.computeHash(data2);
        
        // Property 7: Different data should produce different hashes (collision resistance)
        assert !hash1.equals(hash2) 
            : "Different data should produce different hashes";
    }

    @Property(tries = 100)
    void property7_integrityVerificationSucceeds(
            @ForAll @StringLength(min = 1, max = 10000) String data) {
        
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        String storedHash = integrityService.computeHash(bytes);
        
        // Property 7: Verification of unmodified data must succeed
        assert integrityService.verifyIntegrity(bytes, storedHash) 
            : "Integrity verification should succeed for unmodified data";
    }

    @Property(tries = 100)
    void property7_integrityVerificationFailsOnTampering(
            @ForAll @StringLength(min = 10, max = 1000) String originalData,
            @ForAll @IntRange(min = 0, max = 9) int tamperPosition) {
        
        byte[] original = originalData.getBytes(StandardCharsets.UTF_8);
        String storedHash = integrityService.computeHash(original);
        
        // Tamper with the data
        byte[] tampered = original.clone();
        int pos = Math.min(tamperPosition, tampered.length - 1);
        tampered[pos] = (byte) (tampered[pos] ^ 0xFF); // Flip bits
        
        // Property 7: Verification of tampered data must fail
        assert !integrityService.verifyIntegrity(tampered, storedHash) 
            : "Integrity verification should fail for tampered data";
    }

    @Property(tries = 100)
    void property7_integrityRecordCreation(
            @ForAll @StringLength(min = 1, max = 1000) String data,
            @ForAll("resourceTypes") String resourceType) {
        
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        UUID resourceId = UUID.randomUUID();
        
        DataIntegrityService.IntegrityRecord record = 
            integrityService.createIntegrityRecord(resourceId, resourceType, bytes);
        
        // Property 7: Record must contain valid hash
        assert record.hash() != null && !record.hash().isEmpty()
            : "Integrity record must contain hash";
        assert record.resourceId().equals(resourceId)
            : "Record must reference correct resource";
        assert record.dataLength() == bytes.length
            : "Record must store correct data length";
    }

    @Property(tries = 100)
    void property7_integrityRecordVerification(
            @ForAll @StringLength(min = 1, max = 1000) String data,
            @ForAll("resourceTypes") String resourceType) {
        
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        UUID resourceId = UUID.randomUUID();
        
        // Create record
        DataIntegrityService.IntegrityRecord record = 
            integrityService.createIntegrityRecord(resourceId, resourceType, bytes);
        
        // Verify record
        DataIntegrityService.VerificationResult result = 
            integrityService.verifyRecord(record, bytes);
        
        // Property 7: Verification of unmodified data must succeed
        assert result.valid() 
            : "Verification should succeed for unmodified data";
        assert result.storedHash().equals(result.computedHash())
            : "Stored and computed hashes should match";
    }

    @Property(tries = 100)
    void property7_hashChainIsValid(
            @ForAll @StringLength(min = 1, max = 500) String data1,
            @ForAll @StringLength(min = 1, max = 500) String data2,
            @ForAll @StringLength(min = 1, max = 500) String data3) {
        
        // Build a hash chain
        String hash1 = integrityService.computeChainedHash(null, data1.getBytes(StandardCharsets.UTF_8));
        String hash2 = integrityService.computeChainedHash(hash1, data2.getBytes(StandardCharsets.UTF_8));
        String hash3 = integrityService.computeChainedHash(hash2, data3.getBytes(StandardCharsets.UTF_8));
        
        // Property 7: Chain verification must succeed
        assert integrityService.verifyChain(null, data1.getBytes(StandardCharsets.UTF_8), hash1)
            : "First link verification should succeed";
        assert integrityService.verifyChain(hash1, data2.getBytes(StandardCharsets.UTF_8), hash2)
            : "Second link verification should succeed";
        assert integrityService.verifyChain(hash2, data3.getBytes(StandardCharsets.UTF_8), hash3)
            : "Third link verification should succeed";
    }

    @Property(tries = 100)
    void property7_hashChainDetectsTampering(
            @ForAll @StringLength(min = 10, max = 500) String data1,
            @ForAll @StringLength(min = 10, max = 500) String data2) {
        
        // Build a hash chain
        String hash1 = integrityService.computeChainedHash(null, data1.getBytes(StandardCharsets.UTF_8));
        String hash2 = integrityService.computeChainedHash(hash1, data2.getBytes(StandardCharsets.UTF_8));
        
        // Tamper with data2
        String tamperedData2 = data2 + "TAMPERED";
        
        // Property 7: Chain verification must fail for tampered data
        assert !integrityService.verifyChain(hash1, tamperedData2.getBytes(StandardCharsets.UTF_8), hash2)
            : "Chain verification should fail for tampered data";
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> dataCategories() {
        return Arbitraries.of("consent", "audit", "financial", "profile", "request");
    }

    @Provide
    Arbitrary<String> resourceTypes() {
        return Arbitraries.of("consent_contract", "audit_receipt", "escrow_account", "time_capsule", "query_plan");
    }
}
