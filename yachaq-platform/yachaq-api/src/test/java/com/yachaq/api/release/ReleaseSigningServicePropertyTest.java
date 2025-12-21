package com.yachaq.api.release;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for ReleaseSigningService.
 * 
 * **Feature: yachaq-platform, Task 104: Supply Chain & Reproducible Builds**
 * **Validates: Requirements 357.1, 357.2, 357.3**
 */
class ReleaseSigningServicePropertyTest {

    private final ReleaseSigningService service = new ReleaseSigningService();

    // ========================================================================
    // Property 1: Hash Determinism
    // For any byte array, computing SHA-256 twice produces identical results
    // ========================================================================
    
    @Property(tries = 100)
    @Label("SHA-256 hash is deterministic")
    void sha256HashIsDeterministic(@ForAll byte[] data) {
        String hash1 = service.computeSha256(data);
        String hash2 = service.computeSha256(data);
        
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 produces 64 hex chars
    }

    // ========================================================================
    // Property 2: Hash Uniqueness
    // Different inputs produce different hashes (with high probability)
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Different inputs produce different hashes")
    void differentInputsProduceDifferentHashes(
            @ForAll @Size(min = 1, max = 1000) byte[] data1,
            @ForAll @Size(min = 1, max = 1000) byte[] data2) {
        
        Assume.that(!Arrays.equals(data1, data2));
        
        String hash1 = service.computeSha256(data1);
        String hash2 = service.computeSha256(data2);
        
        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ========================================================================
    // Property 3: Signature Round-Trip
    // Signing and verifying with matching keys succeeds
    // ========================================================================
    
    @Property(tries = 20)
    @Label("Signature round-trip succeeds with matching keys")
    void signatureRoundTripSucceeds(@ForAll @Size(min = 1, max = 10000) byte[] content) 
            throws Exception {
        
        // Create temp file
        Path tempFile = Files.createTempFile("test-artifact-", ".jar");
        try {
            Files.write(tempFile, content);
            
            // Generate key pair
            KeyPair keyPair = service.generateKeyPair(2048);
            
            // Sign
            ReleaseSigningService.SigningResult signingResult = 
                service.signArtifact(tempFile, keyPair.getPrivate(), "test-key-001");
            
            // Verify
            ReleaseSigningService.VerificationResult verificationResult = 
                service.verifyArtifact(
                    tempFile, 
                    signingResult.sha256Hash(), 
                    signingResult.signature(), 
                    keyPair.getPublic()
                );
            
            assertThat(verificationResult.valid()).isTrue();
            assertThat(verificationResult.message()).contains("verified successfully");
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ========================================================================
    // Property 4: Tampered Artifact Detection
    // Modified artifacts fail verification
    // ========================================================================
    
    @Property(tries = 20)
    @Label("Tampered artifacts fail verification")
    void tamperedArtifactsFailVerification(
            @ForAll @Size(min = 10, max = 1000) byte[] originalContent,
            @ForAll @IntRange(min = 0, max = 9) int modifyIndex) throws Exception {
        
        Path tempFile = Files.createTempFile("test-artifact-", ".jar");
        try {
            Files.write(tempFile, originalContent);
            
            KeyPair keyPair = service.generateKeyPair(2048);
            
            // Sign original
            ReleaseSigningService.SigningResult signingResult = 
                service.signArtifact(tempFile, keyPair.getPrivate(), "test-key-001");
            
            // Tamper with file
            byte[] tamperedContent = originalContent.clone();
            tamperedContent[modifyIndex] = (byte) (tamperedContent[modifyIndex] ^ 0xFF);
            Files.write(tempFile, tamperedContent);
            
            // Verify should fail
            ReleaseSigningService.VerificationResult verificationResult = 
                service.verifyArtifact(
                    tempFile, 
                    signingResult.sha256Hash(), 
                    signingResult.signature(), 
                    keyPair.getPublic()
                );
            
            assertThat(verificationResult.valid()).isFalse();
            assertThat(verificationResult.message()).contains("mismatch");
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ========================================================================
    // Property 5: Wrong Key Detection
    // Verification with wrong public key fails
    // ========================================================================
    
    @Property(tries = 20)
    @Label("Wrong public key fails verification")
    void wrongKeyFailsVerification(@ForAll @Size(min = 1, max = 1000) byte[] content) 
            throws Exception {
        
        Path tempFile = Files.createTempFile("test-artifact-", ".jar");
        try {
            Files.write(tempFile, content);
            
            // Generate two different key pairs
            KeyPair signingKeyPair = service.generateKeyPair(2048);
            KeyPair wrongKeyPair = service.generateKeyPair(2048);
            
            // Sign with first key
            ReleaseSigningService.SigningResult signingResult = 
                service.signArtifact(tempFile, signingKeyPair.getPrivate(), "signing-key");
            
            // Verify with wrong key
            ReleaseSigningService.VerificationResult verificationResult = 
                service.verifyArtifact(
                    tempFile, 
                    signingResult.sha256Hash(), 
                    signingResult.signature(), 
                    wrongKeyPair.getPublic()
                );
            
            assertThat(verificationResult.valid()).isFalse();
            assertThat(verificationResult.message()).contains("Signature verification failed");
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ========================================================================
    // Property 6: Hash Manifest Round-Trip
    // Writing and parsing hash manifest preserves all entries
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Hash manifest round-trip preserves entries")
    void hashManifestRoundTrip(
            @ForAll @Size(min = 1, max = 10) List<@AlphaChars @Size(min = 5, max = 20) String> filenames) 
            throws Exception {
        
        // Create unique filenames
        Set<String> uniqueFilenames = new LinkedHashSet<>(filenames);
        Assume.that(uniqueFilenames.size() >= 1);
        
        // Create manifest with random hashes
        Map<String, String> originalManifest = new LinkedHashMap<>();
        for (String filename : uniqueFilenames) {
            String hash = service.computeSha256((filename + System.nanoTime()).getBytes());
            originalManifest.put(filename + ".jar", hash);
        }
        
        // Write and parse
        Path manifestFile = Files.createTempFile("manifest-", ".txt");
        try {
            service.writeHashManifest(originalManifest, manifestFile);
            Map<String, String> parsedManifest = service.parseHashManifest(manifestFile);
            
            assertThat(parsedManifest).containsExactlyEntriesOf(originalManifest);
            
        } finally {
            Files.deleteIfExists(manifestFile);
        }
    }

    // ========================================================================
    // Property 7: SBOM Entry PURL Format
    // SBOM entries have valid Package URL format
    // ========================================================================
    
    @Property(tries = 100)
    @Label("SBOM entries have valid PURL format")
    void sbomEntriesHaveValidPurl(
            @ForAll @AlphaChars @Size(min = 2, max = 20) String groupId,
            @ForAll @AlphaChars @Size(min = 2, max = 20) String artifactId,
            @ForAll @Size(min = 1, max = 10) String version) {
        
        String sha256 = service.computeSha256(groupId.getBytes());
        
        ReleaseSigningService.SBOMEntry entry = 
            service.createSBOMEntry(groupId, artifactId, version, sha256, "Apache-2.0");
        
        assertThat(entry.purl()).startsWith("pkg:maven/");
        assertThat(entry.purl()).contains(groupId);
        assertThat(entry.purl()).contains(artifactId);
        assertThat(entry.purl()).contains("@" + version);
    }

    // ========================================================================
    // Property 8: SBOM Serialization Contains All Components
    // Serialized SBOM JSON contains all component entries
    // ========================================================================
    
    @Property(tries = 50)
    @Label("SBOM serialization contains all components")
    void sbomSerializationContainsAllComponents(
            @ForAll @Size(min = 1, max = 5) List<@AlphaChars @Size(min = 3, max = 10) String> artifactIds) {
        
        List<ReleaseSigningService.SBOMEntry> components = new ArrayList<>();
        for (String artifactId : artifactIds) {
            String sha256 = service.computeSha256(artifactId.getBytes());
            components.add(service.createSBOMEntry(
                "com.yachaq", artifactId, "1.0.0", sha256, "Apache-2.0"
            ));
        }
        
        ReleaseSigningService.SBOM sbom = 
            service.createSBOM("yachaq-platform", "1.0.0", components);
        
        String json = service.serializeSBOMToJson(sbom);
        
        // Verify all artifacts are in JSON
        for (String artifactId : artifactIds) {
            assertThat(json).contains(artifactId);
        }
        
        // Verify CycloneDX format
        assertThat(json).contains("\"bomFormat\": \"CycloneDX\"");
        assertThat(json).contains("\"specVersion\": \"1.5\"");
    }

    // ========================================================================
    // Property 9: Hash Verification Consistency
    // Hash verification is consistent across multiple calls
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Hash verification is consistent")
    void hashVerificationIsConsistent(@ForAll @Size(min = 1, max = 1000) byte[] content) 
            throws Exception {
        
        Path tempFile = Files.createTempFile("test-", ".bin");
        try {
            Files.write(tempFile, content);
            String expectedHash = service.computeSha256(tempFile);
            
            // Verify multiple times
            for (int i = 0; i < 5; i++) {
                ReleaseSigningService.VerificationResult result = 
                    service.verifyHash(tempFile, expectedHash);
                
                assertThat(result.valid()).isTrue();
                assertThat(result.actualHash()).isEqualTo(expectedHash);
            }
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ========================================================================
    // Property 10: Key Generation Produces Unique Keys
    // Each key generation produces different keys
    // ========================================================================
    
    @Property(tries = 10)
    @Label("Key generation produces unique keys")
    void keyGenerationProducesUniqueKeys() throws Exception {
        Set<String> publicKeyHashes = new HashSet<>();
        
        for (int i = 0; i < 5; i++) {
            KeyPair keyPair = service.generateKeyPair(2048);
            String keyHash = service.computeSha256(keyPair.getPublic().getEncoded());
            publicKeyHashes.add(keyHash);
        }
        
        // All keys should be unique
        assertThat(publicKeyHashes).hasSize(5);
    }

    // ========================================================================
    // Property 11: Signing Result Contains All Required Fields
    // ========================================================================
    
    @Property(tries = 20)
    @Label("Signing result contains all required fields")
    void signingResultContainsAllFields(@ForAll @Size(min = 1, max = 500) byte[] content) 
            throws Exception {
        
        Path tempFile = Files.createTempFile("test-", ".jar");
        try {
            Files.write(tempFile, content);
            KeyPair keyPair = service.generateKeyPair(2048);
            
            ReleaseSigningService.SigningResult result = 
                service.signArtifact(tempFile, keyPair.getPrivate(), "key-123");
            
            assertThat(result.artifactPath()).isNotEmpty();
            assertThat(result.sha256Hash()).hasSize(64);
            assertThat(result.sha512Hash()).hasSize(128);
            assertThat(result.signature()).isNotEmpty();
            assertThat(result.signedAt()).isNotNull();
            assertThat(result.signerKeyId()).isEqualTo("key-123");
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // ========================================================================
    // Property 12: Empty File Handling
    // Empty files can be hashed and signed
    // ========================================================================
    
    @Example
    @Label("Empty files can be hashed and signed")
    void emptyFilesCanBeHashedAndSigned() throws Exception {
        Path tempFile = Files.createTempFile("empty-", ".jar");
        try {
            // File is empty by default
            KeyPair keyPair = service.generateKeyPair(2048);
            
            ReleaseSigningService.SigningResult result = 
                service.signArtifact(tempFile, keyPair.getPrivate(), "key-empty");
            
            // SHA-256 of empty file is well-known
            assertThat(result.sha256Hash())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            
            // Verification should succeed
            ReleaseSigningService.VerificationResult verification = 
                service.verifyArtifact(tempFile, result.sha256Hash(), result.signature(), keyPair.getPublic());
            
            assertThat(verification.valid()).isTrue();
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
