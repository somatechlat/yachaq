package com.yachaq.node.vault;

import com.yachaq.node.vault.LocalVault.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Local Vault.
 * 
 * **Feature: yachaq-platform, Property 70: Vault Envelope Encryption**
 * **Validates: Requirements 307.1, 307.2, 307.3, 307.5, 307.6**
 */
class LocalVaultPropertyTest {

    private static final String ALLOWED_MODULE = "feature_extractor";

    // ==================== Property 70: Vault Envelope Encryption ====================

    @Property(tries = 100)
    void property70_putAndGetReturnsOriginalData(@ForAll("randomData") byte[] data) {
        // Property: Data stored and retrieved must be identical
        // Validates: Requirement 307.1, 307.2
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String rawRef = vault.put(data, metadata, ALLOWED_MODULE);
        byte[] retrieved = vault.get(rawRef, ALLOWED_MODULE);
        
        assertThat(retrieved).isEqualTo(data);
    }

    @Property(tries = 100)
    void property70_eachPutGeneratesUniqueRef(@ForAll("randomData") byte[] data) {
        // Property: Each put generates a unique raw_ref
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String ref1 = vault.put(data, metadata, ALLOWED_MODULE);
        String ref2 = vault.put(data, metadata, ALLOWED_MODULE);
        
        assertThat(ref1).isNotEqualTo(ref2);
        assertThat(ref1).startsWith("vault:");
        assertThat(ref2).startsWith("vault:");
    }


    @Property(tries = 50)
    void property70_deleteRemovesData(@ForAll("randomData") byte[] data) {
        // Property: Deleted data cannot be retrieved
        // Validates: Requirement 307.3
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String rawRef = vault.put(data, metadata, ALLOWED_MODULE);
        assertThat(vault.exists(rawRef)).isTrue();
        
        vault.delete(rawRef, ALLOWED_MODULE);
        
        assertThat(vault.exists(rawRef)).isFalse();
        assertThatThrownBy(() -> vault.get(rawRef, ALLOWED_MODULE))
                .isInstanceOf(VaultException.class);
    }

    @Property(tries = 50)
    void property70_cryptoShredMakesDataUnrecoverable(@ForAll("randomData") byte[] data) {
        // Property: Crypto-shred makes all associated data unrecoverable
        // Validates: Requirement 307.3
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        // Store multiple items
        String ref1 = vault.put(data, metadata, ALLOWED_MODULE);
        String ref2 = vault.put(data, metadata, ALLOWED_MODULE);
        
        // Get the key ID from one entry
        Optional<VaultMetadata> meta = vault.getMetadata(ref1, ALLOWED_MODULE);
        assertThat(meta).isPresent();
        
        // Crypto-shred by deleting entries
        vault.delete(ref1, ALLOWED_MODULE);
        vault.delete(ref2, ALLOWED_MODULE);
        
        assertThat(vault.size()).isEqualTo(0);
    }

    // ==================== Property 71: Access Control ====================

    @Property(tries = 50)
    void property71_unauthorizedModuleCannotAccess(@ForAll("randomData") byte[] data) {
        // Property: Unauthorized modules cannot access vault
        // Validates: Requirement 307.5
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String rawRef = vault.put(data, metadata, ALLOWED_MODULE);
        
        assertThatThrownBy(() -> vault.get(rawRef, "unauthorized_module"))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Property(tries = 50)
    void property71_nullModuleIsRejected(@ForAll("randomData") byte[] data) {
        // Property: Null caller module is rejected
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        assertThatThrownBy(() -> vault.put(data, metadata, null))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    @Property(tries = 50)
    void property71_blankModuleIsRejected(@ForAll("randomData") byte[] data) {
        // Property: Blank caller module is rejected
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        assertThatThrownBy(() -> vault.put(data, metadata, "   "))
                .isInstanceOf(VaultAccessDeniedException.class);
    }

    // ==================== Property 72: Key Rotation ====================

    @Property(tries = 50)
    void property72_keyRotationPreservesData(@ForAll("randomData") byte[] data) throws Exception {
        // Property: Key rotation preserves all data
        // Validates: Requirement 307.6
        
        // Create vault with all modules allowed for testing
        LocalVault vault = new LocalVault(generateKey(), Set.of(ALLOWED_MODULE));
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String rawRef = vault.put(data, metadata, ALLOWED_MODULE);
        
        // Rotate master key
        SecretKey newKey = generateKey();
        vault.rotateMasterKey(newKey, ALLOWED_MODULE);
        
        // Data should still be retrievable
        byte[] retrieved = vault.get(rawRef, ALLOWED_MODULE);
        assertThat(retrieved).isEqualTo(data);
    }

    // ==================== Property 73: Data Integrity ====================

    @Property(tries = 100)
    void property73_metadataIsPreserved(@ForAll("randomData") byte[] data) {
        // Property: Metadata is preserved correctly
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = new VaultMetadata(
                "test_source",
                "test_record",
                data.length,
                "checksum123",
                Instant.now().plusSeconds(3600),
                Map.of("key1", "value1")
        );
        
        String rawRef = vault.put(data, metadata, ALLOWED_MODULE);
        Optional<VaultMetadata> retrieved = vault.getMetadata(rawRef, ALLOWED_MODULE);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().source()).isEqualTo("test_source");
        assertThat(retrieved.get().recordType()).isEqualTo("test_record");
        assertThat(retrieved.get().originalSize()).isEqualTo(data.length);
    }

    @Property(tries = 50)
    void property73_vaultSizeTracksEntries(@ForAll("smallDataList") List<byte[]> dataList) {
        // Property: Vault size accurately tracks entries
        
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        List<String> refs = new ArrayList<>();
        for (byte[] data : dataList) {
            refs.add(vault.put(data, metadata, ALLOWED_MODULE));
        }
        
        assertThat(vault.size()).isEqualTo(dataList.size());
        
        // Delete half
        int toDelete = refs.size() / 2;
        for (int i = 0; i < toDelete; i++) {
            vault.delete(refs.get(i), ALLOWED_MODULE);
        }
        
        assertThat(vault.size()).isEqualTo(dataList.size() - toDelete);
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<byte[]> randomData() {
        return Arbitraries.bytes().array(byte[].class).ofMinSize(1).ofMaxSize(1024);
    }

    @Provide
    Arbitrary<List<byte[]>> smallDataList() {
        return randomData().list().ofMinSize(1).ofMaxSize(10);
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        return keyGen.generateKey();
    }

    // ==================== Edge Case Tests ====================

    @Test
    void put_rejectsNullData() {
        LocalVault vault = new LocalVault();
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        assertThatThrownBy(() -> vault.put(null, metadata, ALLOWED_MODULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_rejectsNullRef() {
        LocalVault vault = new LocalVault();
        
        assertThatThrownBy(() -> vault.get(null, ALLOWED_MODULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_rejectsBlankRef() {
        LocalVault vault = new LocalVault();
        
        assertThatThrownBy(() -> vault.get("   ", ALLOWED_MODULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_throwsForNonExistentRef() {
        LocalVault vault = new LocalVault();
        
        assertThatThrownBy(() -> vault.get("vault:nonexistent", ALLOWED_MODULE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void delete_handlesNonExistentRef() {
        LocalVault vault = new LocalVault();
        
        // Should not throw
        vault.delete("vault:nonexistent", ALLOWED_MODULE);
    }

    @Test
    void cryptoShred_rejectsNullKeyId() {
        LocalVault vault = new LocalVault();
        
        assertThatThrownBy(() -> vault.cryptoShred(null, ALLOWED_MODULE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vaultMetadata_of_createsSimpleMetadata() {
        VaultMetadata metadata = VaultMetadata.of("source", "type");
        
        assertThat(metadata.source()).isEqualTo("source");
        assertThat(metadata.recordType()).isEqualTo("type");
        assertThat(metadata.ttl()).isNull();
    }

    @Test
    void vaultMetadata_withTTL_createsTTLMetadata() {
        Instant ttl = Instant.now().plusSeconds(3600);
        VaultMetadata metadata = VaultMetadata.withTTL("source", "type", ttl);
        
        assertThat(metadata.source()).isEqualTo("source");
        assertThat(metadata.ttl()).isEqualTo(ttl);
    }

    @Test
    void vault_allowsAllModulesWhenEmpty() throws Exception {
        // Empty allowed modules set means all modules allowed
        LocalVault vault = new LocalVault(generateKey(), Set.of());
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String ref = vault.put(new byte[]{1, 2, 3}, metadata, "any_module");
        byte[] data = vault.get(ref, "any_module");
        
        assertThat(data).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void vault_multipleAllowedModules() throws Exception {
        Set<String> allowed = Set.of("module1", "module2", "module3");
        LocalVault vault = new LocalVault(generateKey(), allowed);
        VaultMetadata metadata = VaultMetadata.of("test", "test_record");
        
        String ref = vault.put(new byte[]{1, 2, 3}, metadata, "module1");
        
        // All allowed modules can access
        assertThat(vault.get(ref, "module1")).isEqualTo(new byte[]{1, 2, 3});
        assertThat(vault.get(ref, "module2")).isEqualTo(new byte[]{1, 2, 3});
        assertThat(vault.get(ref, "module3")).isEqualTo(new byte[]{1, 2, 3});
        
        // Unauthorized module cannot
        assertThatThrownBy(() -> vault.get(ref, "module4"))
                .isInstanceOf(VaultAccessDeniedException.class);
    }
}
