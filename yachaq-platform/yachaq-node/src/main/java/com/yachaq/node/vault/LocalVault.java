package com.yachaq.node.vault;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local Vault for encrypted on-device storage.
 * Requirement 307.1: Use envelope encryption with per-object keys wrapped by vault master key.
 * Requirement 307.2: Provide only raw_ref handles, never direct access to encrypted blobs.
 * Requirement 307.3: Support secure delete and crypto-shred operations.
 * Requirement 307.5: Restrict access to only allowed modules.
 * 
 * Implements envelope encryption pattern:
 * - Each object has its own Data Encryption Key (DEK)
 * - DEKs are wrapped (encrypted) by the Key Encryption Key (KEK/master key)
 * - Crypto-shred: destroy DEK to make data unrecoverable
 */
public class LocalVault {

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int KEY_SIZE = 256;

    private final SecretKey masterKey;
    private final Map<String, VaultEntry> storage;
    private final Map<String, SecretKey> keyCache;
    private final Set<String> allowedModules;
    private final SecureRandom secureRandom;

    /**
     * Creates a new LocalVault with a generated master key.
     */
    public LocalVault() {
        this(generateMasterKey(), Set.of("feature_extractor", "plan_vm", "normalizer"));
    }

    /**
     * Creates a LocalVault with a specific master key and allowed modules.
     */
    public LocalVault(SecretKey masterKey, Set<String> allowedModules) {
        if (masterKey == null) {
            throw new IllegalArgumentException("Master key cannot be null");
        }
        this.masterKey = masterKey;
        this.storage = new ConcurrentHashMap<>();
        this.keyCache = new ConcurrentHashMap<>();
        this.allowedModules = allowedModules != null ? Set.copyOf(allowedModules) : Set.of();
        this.secureRandom = new SecureRandom();
    }


    /**
     * Stores data in the vault with envelope encryption.
     * Requirement 307.1: Use envelope encryption with per-object keys.
     * 
     * @param data The data to store
     * @param metadata Metadata about the data
     * @param callerModule The module requesting storage
     * @return raw_ref handle to the stored data
     */
    public String put(byte[] data, VaultMetadata metadata, String callerModule) {
        validateModuleAccess(callerModule);
        
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        try {
            // Generate unique object key (DEK)
            SecretKey objectKey = generateObjectKey();
            String keyId = UUID.randomUUID().toString();
            
            // Encrypt data with object key
            byte[] iv = generateIV();
            byte[] encryptedData = encrypt(data, objectKey, iv);
            
            // Wrap object key with master key
            byte[] wrappedKey = wrapKey(objectKey);
            
            // Generate raw_ref
            String rawRef = "vault:" + UUID.randomUUID().toString();
            
            // Store entry
            VaultEntry entry = new VaultEntry(
                    rawRef,
                    keyId,
                    encryptedData,
                    wrappedKey,
                    iv,
                    metadata,
                    Instant.now()
            );
            storage.put(rawRef, entry);
            
            // Cache the unwrapped key for performance
            keyCache.put(keyId, objectKey);
            
            return rawRef;
            
        } catch (Exception e) {
            throw new VaultException("Failed to store data in vault", e);
        }
    }

    /**
     * Retrieves data from the vault.
     * Requirement 307.2: Provide only raw_ref handles.
     * 
     * @param rawRef The raw_ref handle
     * @param callerModule The module requesting retrieval
     * @return The decrypted data
     */
    public byte[] get(String rawRef, String callerModule) {
        validateModuleAccess(callerModule);
        
        if (rawRef == null || rawRef.isBlank()) {
            throw new IllegalArgumentException("Raw ref cannot be null or blank");
        }

        VaultEntry entry = storage.get(rawRef);
        if (entry == null) {
            throw new VaultException("Entry not found: " + rawRef);
        }

        try {
            // Get object key (from cache or unwrap)
            SecretKey objectKey = keyCache.get(entry.keyId());
            if (objectKey == null) {
                objectKey = unwrapKey(entry.wrappedKey());
                keyCache.put(entry.keyId(), objectKey);
            }
            
            // Decrypt data
            return decrypt(entry.encryptedData(), objectKey, entry.iv());
            
        } catch (Exception e) {
            throw new VaultException("Failed to retrieve data from vault", e);
        }
    }

    /**
     * Deletes data from the vault.
     * Requirement 307.3: Support secure delete.
     * 
     * @param rawRef The raw_ref handle
     * @param callerModule The module requesting deletion
     */
    public void delete(String rawRef, String callerModule) {
        validateModuleAccess(callerModule);
        
        if (rawRef == null || rawRef.isBlank()) {
            throw new IllegalArgumentException("Raw ref cannot be null or blank");
        }

        VaultEntry entry = storage.remove(rawRef);
        if (entry != null) {
            // Remove key from cache
            keyCache.remove(entry.keyId());
            // Overwrite sensitive data in memory (best effort)
            Arrays.fill(entry.encryptedData(), (byte) 0);
            Arrays.fill(entry.wrappedKey(), (byte) 0);
            Arrays.fill(entry.iv(), (byte) 0);
        }
    }

    /**
     * Crypto-shreds all data associated with a key ID.
     * Requirement 307.3: Support crypto-shred operations.
     * 
     * @param keyId The key ID to shred
     * @param callerModule The module requesting shred
     * @return Number of entries shredded
     */
    public int cryptoShred(String keyId, String callerModule) {
        validateModuleAccess(callerModule);
        
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }

        // Remove the key from cache - this makes data unrecoverable
        keyCache.remove(keyId);
        
        // Find and remove all entries with this key ID
        int shreddedCount = 0;
        Iterator<Map.Entry<String, VaultEntry>> iterator = storage.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VaultEntry> mapEntry = iterator.next();
            VaultEntry entry = mapEntry.getValue();
            if (entry.keyId().equals(keyId)) {
                // Overwrite sensitive data
                Arrays.fill(entry.encryptedData(), (byte) 0);
                Arrays.fill(entry.wrappedKey(), (byte) 0);
                Arrays.fill(entry.iv(), (byte) 0);
                iterator.remove();
                shreddedCount++;
            }
        }
        
        return shreddedCount;
    }

    /**
     * Checks if an entry exists in the vault.
     */
    public boolean exists(String rawRef) {
        return rawRef != null && storage.containsKey(rawRef);
    }

    /**
     * Gets metadata for an entry without decrypting.
     */
    public Optional<VaultMetadata> getMetadata(String rawRef, String callerModule) {
        validateModuleAccess(callerModule);
        VaultEntry entry = storage.get(rawRef);
        return entry != null ? Optional.of(entry.metadata()) : Optional.empty();
    }

    /**
     * Returns the number of entries in the vault.
     */
    public int size() {
        return storage.size();
    }

    /**
     * Rotates the master key and re-wraps all object keys.
     * Requirement 307.6: Re-encrypt affected objects without data loss.
     */
    public void rotateMasterKey(SecretKey newMasterKey, String callerModule) {
        validateModuleAccess(callerModule);
        
        if (newMasterKey == null) {
            throw new IllegalArgumentException("New master key cannot be null");
        }

        // Re-wrap all object keys with new master key
        for (VaultEntry entry : storage.values()) {
            try {
                // Unwrap with old key
                SecretKey objectKey = unwrapKey(entry.wrappedKey());
                
                // Wrap with new key
                byte[] newWrappedKey = wrapKeyWithKey(objectKey, newMasterKey);
                
                // Update entry (create new entry since records are immutable)
                VaultEntry newEntry = new VaultEntry(
                        entry.rawRef(),
                        entry.keyId(),
                        entry.encryptedData(),
                        newWrappedKey,
                        entry.iv(),
                        entry.metadata(),
                        entry.createdAt()
                );
                storage.put(entry.rawRef(), newEntry);
                
            } catch (Exception e) {
                throw new VaultException("Failed to rotate key for entry: " + entry.rawRef(), e);
            }
        }
    }

    // ==================== Private Methods ====================

    private void validateModuleAccess(String callerModule) {
        if (callerModule == null || callerModule.isBlank()) {
            throw new VaultAccessDeniedException("Caller module must be specified");
        }
        if (!allowedModules.isEmpty() && !allowedModules.contains(callerModule)) {
            throw new VaultAccessDeniedException("Module not authorized: " + callerModule);
        }
    }

    private static SecretKey generateMasterKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(KEY_SIZE);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new VaultException("Failed to generate master key", e);
        }
    }

    private SecretKey generateObjectKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(KEY_SIZE);
        return keyGen.generateKey();
    }

    private byte[] generateIV() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        return iv;
    }

    private byte[] encrypt(byte[] data, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        return cipher.doFinal(data);
    }

    private byte[] decrypt(byte[] encryptedData, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        return cipher.doFinal(encryptedData);
    }

    private byte[] wrapKey(SecretKey keyToWrap) throws Exception {
        return wrapKeyWithKey(keyToWrap, masterKey);
    }

    private byte[] wrapKeyWithKey(SecretKey keyToWrap, SecretKey wrappingKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = generateIV();
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.WRAP_MODE, wrappingKey, spec);
        byte[] wrappedKey = cipher.wrap(keyToWrap);
        
        // Prepend IV to wrapped key
        byte[] result = new byte[iv.length + wrappedKey.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(wrappedKey, 0, result, iv.length, wrappedKey.length);
        return result;
    }

    private SecretKey unwrapKey(byte[] wrappedKeyWithIV) throws Exception {
        // Extract IV and wrapped key
        byte[] iv = Arrays.copyOfRange(wrappedKeyWithIV, 0, GCM_IV_LENGTH);
        byte[] wrappedKey = Arrays.copyOfRange(wrappedKeyWithIV, GCM_IV_LENGTH, wrappedKeyWithIV.length);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.UNWRAP_MODE, masterKey, spec);
        return (SecretKey) cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY);
    }

    // ==================== Inner Types ====================

    /**
     * Vault entry containing encrypted data and metadata.
     */
    public record VaultEntry(
            String rawRef,
            String keyId,
            byte[] encryptedData,
            byte[] wrappedKey,
            byte[] iv,
            VaultMetadata metadata,
            Instant createdAt
    ) {}

    /**
     * Metadata about stored data.
     */
    public record VaultMetadata(
            String source,
            String recordType,
            long originalSize,
            String checksum,
            Instant ttl,
            Map<String, String> attributes
    ) {
        public VaultMetadata {
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }

        public static VaultMetadata of(String source, String recordType) {
            return new VaultMetadata(source, recordType, 0, null, null, Map.of());
        }

        public static VaultMetadata withTTL(String source, String recordType, Instant ttl) {
            return new VaultMetadata(source, recordType, 0, null, ttl, Map.of());
        }
    }

    /**
     * Exception for vault operations.
     */
    public static class VaultException extends RuntimeException {
        public VaultException(String message) {
            super(message);
        }

        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception for access denied.
     */
    public static class VaultAccessDeniedException extends VaultException {
        public VaultAccessDeniedException(String message) {
            super(message);
        }
    }
}
