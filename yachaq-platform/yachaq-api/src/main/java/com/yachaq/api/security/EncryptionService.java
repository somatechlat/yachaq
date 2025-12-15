package com.yachaq.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encryption Service - AES-256-GCM encryption at rest with key hierarchy.
 * 
 * Property 6: Data Encryption at Rest
 * Validates: Requirements 121.1, 183.1, 183.2
 * 
 * For any user data stored in the system (on-device or ephemeral cloud),
 * the data must be encrypted using AES-256-GCM with a unique key per data category.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_KEY_SIZE = 256;

    private final SecureRandom secureRandom = new SecureRandom();
    
    // Key hierarchy: K-ROOT -> K-DS -> K-CAT
    private final Map<String, SecretKey> keyCache = new ConcurrentHashMap<>();
    
    @Value("${yachaq.encryption.root-key:default-root-key-change-in-production}")
    private String rootKeyBase;

    /**
     * Encrypt data using AES-256-GCM with envelope encryption.
     * Property 6: Data Encryption at Rest
     * 
     * @param plaintext The data to encrypt
     * @param dataCategory The category for key derivation (e.g., "consent", "audit", "financial")
     * @param dsId The Data Sovereign ID for user-specific key derivation
     * @return EncryptionResult containing ciphertext, IV, and key ID
     */
    public EncryptionResult encrypt(byte[] plaintext, String dataCategory, UUID dsId) {
        try {
            // Get or derive category-specific key (K-CAT)
            String keyId = deriveKeyId(dataCategory, dsId);
            SecretKey key = getOrDeriveKey(keyId, dataCategory, dsId);
            
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            
            // Encrypt with AES-256-GCM
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            
            byte[] ciphertext = cipher.doFinal(plaintext);
            
            // Combine IV + ciphertext for storage
            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                .put(iv)
                .put(ciphertext)
                .array();
            
            return new EncryptionResult(
                combined,
                keyId,
                Base64.getEncoder().encodeToString(iv),
                true
            );
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /**
     * Decrypt data using AES-256-GCM.
     * Property 6: Data Encryption at Rest
     * 
     * @param encryptedData The combined IV + ciphertext
     * @param keyId The key ID used for encryption
     * @param dataCategory The data category
     * @param dsId The Data Sovereign ID
     * @return Decrypted plaintext
     */
    public byte[] decrypt(byte[] encryptedData, String keyId, String dataCategory, UUID dsId) {
        try {
            // Get the key
            SecretKey key = getOrDeriveKey(keyId, dataCategory, dsId);
            
            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            
            // Decrypt
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new DecryptionException("Decryption failed", e);
        }
    }

    /**
     * Generate a new data encryption key (DEK) for envelope encryption.
     * 
     * @return A new AES-256 key
     */
    public SecretKey generateDataKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(AES_KEY_SIZE, secureRandom);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new EncryptionException("Failed to generate data key", e);
        }
    }

    /**
     * Wrap (encrypt) a data key using the key encryption key (KEK).
     * Envelope encryption pattern.
     * 
     * @param dataKey The DEK to wrap
     * @param kekId The KEK identifier
     * @return Wrapped key bytes
     */
    public byte[] wrapKey(SecretKey dataKey, String kekId) {
        try {
            SecretKey kek = getKeyEncryptionKey(kekId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            cipher.init(Cipher.WRAP_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] wrappedKey = cipher.wrap(dataKey);
            
            // Combine IV + wrapped key
            return ByteBuffer.allocate(iv.length + wrappedKey.length)
                .put(iv)
                .put(wrappedKey)
                .array();
        } catch (Exception e) {
            throw new EncryptionException("Failed to wrap key", e);
        }
    }

    /**
     * Unwrap (decrypt) a data key using the key encryption key (KEK).
     * 
     * @param wrappedKey The wrapped DEK
     * @param kekId The KEK identifier
     * @return The unwrapped DEK
     */
    public SecretKey unwrapKey(byte[] wrappedKey, String kekId) {
        try {
            SecretKey kek = getKeyEncryptionKey(kekId);
            
            ByteBuffer buffer = ByteBuffer.wrap(wrappedKey);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] keyBytes = new byte[buffer.remaining()];
            buffer.get(keyBytes);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.UNWRAP_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return (SecretKey) cipher.unwrap(keyBytes, "AES", Cipher.SECRET_KEY);
        } catch (Exception e) {
            throw new DecryptionException("Failed to unwrap key", e);
        }
    }

    /**
     * Derive a key ID from category and DS ID.
     */
    private String deriveKeyId(String dataCategory, UUID dsId) {
        return "K-CAT-" + dataCategory + "-" + (dsId != null ? dsId.toString().substring(0, 8) : "system");
    }

    /**
     * Get or derive a category-specific key using the key hierarchy.
     * K-ROOT -> K-DS -> K-CAT
     */
    private SecretKey getOrDeriveKey(String keyId, String dataCategory, UUID dsId) {
        return keyCache.computeIfAbsent(keyId, k -> {
            try {
                // Derive key from root using HKDF-like derivation
                String derivationInput = rootKeyBase + "|" + dataCategory + "|" + (dsId != null ? dsId : "system");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(derivationInput.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception e) {
                throw new EncryptionException("Failed to derive key", e);
            }
        });
    }

    /**
     * Get the Key Encryption Key (KEK) for envelope encryption.
     */
    private SecretKey getKeyEncryptionKey(String kekId) {
        return keyCache.computeIfAbsent("KEK-" + kekId, k -> {
            try {
                String derivationInput = rootKeyBase + "|KEK|" + kekId;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(derivationInput.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(keyBytes, "AES");
            } catch (Exception e) {
                throw new EncryptionException("Failed to derive KEK", e);
            }
        });
    }

    // DTOs and Exceptions
    public record EncryptionResult(byte[] ciphertext, String keyId, String iv, boolean success) {}

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String message, Throwable cause) { super(message, cause); }
    }

    public static class DecryptionException extends RuntimeException {
        public DecryptionException(String message, Throwable cause) { super(message, cause); }
    }
}
