package com.yachaq.api.release;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;

/**
 * Service for signing release artifacts and generating verification hashes.
 * 
 * Implements Requirements:
 * - 357.1: Sign all releases
 * - 357.2: Support reproducible build verification procedure
 * - 357.3: Publish Software Bill of Materials (SBOM)
 * 
 * @see <a href="https://reproducible-builds.org/">Reproducible Builds</a>
 */
@Service
public class ReleaseSigningService {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    
    /**
     * Result of a signing operation.
     */
    public record SigningResult(
        String artifactPath,
        String sha256Hash,
        String sha512Hash,
        String signature,
        Instant signedAt,
        String signerKeyId
    ) {}
    
    /**
     * Result of a verification operation.
     */
    public record VerificationResult(
        boolean valid,
        String artifactPath,
        String expectedHash,
        String actualHash,
        String message
    ) {}
    
    /**
     * SBOM (Software Bill of Materials) entry.
     */
    public record SBOMEntry(
        String groupId,
        String artifactId,
        String version,
        String sha256,
        String license,
        String purl
    ) {}
    
    /**
     * Complete SBOM document.
     */
    public record SBOM(
        String specVersion,
        String serialNumber,
        Instant timestamp,
        String projectName,
        String projectVersion,
        List<SBOMEntry> components
    ) {}

    /**
     * Computes SHA-256 hash of a file.
     * 
     * @param filePath path to the file
     * @return hex-encoded SHA-256 hash
     * @throws IOException if file cannot be read
     */
    public String computeSha256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Computes SHA-512 hash of a file.
     * 
     * @param filePath path to the file
     * @return hex-encoded SHA-512 hash
     * @throws IOException if file cannot be read
     */
    public String computeSha512(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] hashBytes = digest.digest(fileBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 algorithm not available", e);
        }
    }
    
    /**
     * Computes hash of byte array.
     * 
     * @param data byte array to hash
     * @return hex-encoded SHA-256 hash
     */
    public String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Signs an artifact using RSA private key.
     * 
     * @param artifactPath path to the artifact
     * @param privateKey RSA private key for signing
     * @param keyId identifier for the signing key
     * @return signing result with hashes and signature
     * @throws IOException if file cannot be read
     * @throws GeneralSecurityException if signing fails
     */
    public SigningResult signArtifact(Path artifactPath, PrivateKey privateKey, String keyId) 
            throws IOException, GeneralSecurityException {
        
        byte[] fileBytes = Files.readAllBytes(artifactPath);
        
        // Compute hashes
        String sha256 = computeSha256(fileBytes);
        String sha512;
        try {
            MessageDigest digest512 = MessageDigest.getInstance("SHA-512");
            sha512 = bytesToHex(digest512.digest(fileBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 algorithm not available", e);
        }
        
        // Sign the SHA-256 hash
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(sha256.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = signature.sign();
        String signatureHex = bytesToHex(signatureBytes);
        
        return new SigningResult(
            artifactPath.toString(),
            sha256,
            sha512,
            signatureHex,
            Instant.now(),
            keyId
        );
    }
    
    /**
     * Verifies an artifact signature.
     * 
     * @param artifactPath path to the artifact
     * @param expectedHash expected SHA-256 hash
     * @param signature hex-encoded signature
     * @param publicKey RSA public key for verification
     * @return verification result
     * @throws IOException if file cannot be read
     * @throws GeneralSecurityException if verification fails
     */
    public VerificationResult verifyArtifact(Path artifactPath, String expectedHash, 
            String signature, PublicKey publicKey) throws IOException, GeneralSecurityException {
        
        String actualHash = computeSha256(artifactPath);
        
        if (!actualHash.equals(expectedHash)) {
            return new VerificationResult(
                false,
                artifactPath.toString(),
                expectedHash,
                actualHash,
                "Hash mismatch: artifact has been modified"
            );
        }
        
        // Verify signature
        Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
        sig.initVerify(publicKey);
        sig.update(expectedHash.getBytes(StandardCharsets.UTF_8));
        
        byte[] signatureBytes = hexToBytes(signature);
        boolean signatureValid = sig.verify(signatureBytes);
        
        if (!signatureValid) {
            return new VerificationResult(
                false,
                artifactPath.toString(),
                expectedHash,
                actualHash,
                "Signature verification failed"
            );
        }
        
        return new VerificationResult(
            true,
            artifactPath.toString(),
            expectedHash,
            actualHash,
            "Artifact verified successfully"
        );
    }
    
    /**
     * Verifies hash only (without signature).
     * 
     * @param artifactPath path to the artifact
     * @param expectedHash expected SHA-256 hash
     * @return verification result
     * @throws IOException if file cannot be read
     */
    public VerificationResult verifyHash(Path artifactPath, String expectedHash) throws IOException {
        String actualHash = computeSha256(artifactPath);
        
        boolean matches = actualHash.equalsIgnoreCase(expectedHash);
        
        return new VerificationResult(
            matches,
            artifactPath.toString(),
            expectedHash,
            actualHash,
            matches ? "Hash verified successfully" : "Hash mismatch"
        );
    }

    
    /**
     * Generates a hash manifest for multiple artifacts.
     * 
     * @param artifactPaths list of artifact paths
     * @return map of artifact path to SHA-256 hash
     * @throws IOException if any file cannot be read
     */
    public Map<String, String> generateHashManifest(List<Path> artifactPaths) throws IOException {
        Map<String, String> manifest = new LinkedHashMap<>();
        
        for (Path path : artifactPaths) {
            String hash = computeSha256(path);
            manifest.put(path.getFileName().toString(), hash);
        }
        
        return manifest;
    }
    
    /**
     * Writes hash manifest to file in standard format.
     * 
     * @param manifest hash manifest
     * @param outputPath output file path
     * @throws IOException if file cannot be written
     */
    public void writeHashManifest(Map<String, String> manifest, Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# YACHAQ Platform Release Hashes\n");
        sb.append("# Generated: ").append(Instant.now()).append("\n");
        sb.append("# Format: SHA256 *filename\n\n");
        
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            sb.append(entry.getValue()).append(" *").append(entry.getKey()).append("\n");
        }
        
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
    }
    
    /**
     * Parses a hash manifest file.
     * 
     * @param manifestPath path to manifest file
     * @return map of filename to hash
     * @throws IOException if file cannot be read
     */
    public Map<String, String> parseHashManifest(Path manifestPath) throws IOException {
        Map<String, String> manifest = new LinkedHashMap<>();
        
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            // Format: hash *filename or hash filename
            String[] parts = line.split("\\s+\\*?", 2);
            if (parts.length == 2) {
                String hash = parts[0].trim();
                String filename = parts[1].trim();
                if (filename.startsWith("*")) {
                    filename = filename.substring(1);
                }
                manifest.put(filename, hash);
            }
        }
        
        return manifest;
    }
    
    /**
     * Creates an SBOM entry from Maven coordinates.
     * 
     * @param groupId Maven group ID
     * @param artifactId Maven artifact ID
     * @param version Maven version
     * @param sha256 SHA-256 hash of the artifact
     * @param license SPDX license identifier
     * @return SBOM entry
     */
    public SBOMEntry createSBOMEntry(String groupId, String artifactId, String version, 
            String sha256, String license) {
        String purl = String.format("pkg:maven/%s/%s@%s", groupId, artifactId, version);
        return new SBOMEntry(groupId, artifactId, version, sha256, license, purl);
    }
    
    /**
     * Creates a complete SBOM document.
     * 
     * @param projectName project name
     * @param projectVersion project version
     * @param components list of SBOM entries
     * @return complete SBOM
     */
    public SBOM createSBOM(String projectName, String projectVersion, List<SBOMEntry> components) {
        return new SBOM(
            "1.5",
            "urn:uuid:" + UUID.randomUUID(),
            Instant.now(),
            projectName,
            projectVersion,
            components
        );
    }
    
    /**
     * Serializes SBOM to JSON format.
     * 
     * @param sbom SBOM document
     * @return JSON string
     */
    public String serializeSBOMToJson(SBOM sbom) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"bomFormat\": \"CycloneDX\",\n");
        json.append("  \"specVersion\": \"").append(sbom.specVersion()).append("\",\n");
        json.append("  \"serialNumber\": \"").append(sbom.serialNumber()).append("\",\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"metadata\": {\n");
        json.append("    \"timestamp\": \"").append(sbom.timestamp()).append("\",\n");
        json.append("    \"component\": {\n");
        json.append("      \"type\": \"application\",\n");
        json.append("      \"name\": \"").append(sbom.projectName()).append("\",\n");
        json.append("      \"version\": \"").append(sbom.projectVersion()).append("\"\n");
        json.append("    }\n");
        json.append("  },\n");
        json.append("  \"components\": [\n");
        
        List<SBOMEntry> components = sbom.components();
        for (int i = 0; i < components.size(); i++) {
            SBOMEntry entry = components.get(i);
            json.append("    {\n");
            json.append("      \"type\": \"library\",\n");
            json.append("      \"group\": \"").append(entry.groupId()).append("\",\n");
            json.append("      \"name\": \"").append(entry.artifactId()).append("\",\n");
            json.append("      \"version\": \"").append(entry.version()).append("\",\n");
            json.append("      \"purl\": \"").append(entry.purl()).append("\",\n");
            json.append("      \"hashes\": [\n");
            json.append("        {\n");
            json.append("          \"alg\": \"SHA-256\",\n");
            json.append("          \"content\": \"").append(entry.sha256()).append("\"\n");
            json.append("        }\n");
            json.append("      ]");
            if (entry.license() != null && !entry.license().isEmpty()) {
                json.append(",\n");
                json.append("      \"licenses\": [\n");
                json.append("        {\n");
                json.append("          \"license\": {\n");
                json.append("            \"id\": \"").append(entry.license()).append("\"\n");
                json.append("          }\n");
                json.append("        }\n");
                json.append("      ]\n");
            } else {
                json.append("\n");
            }
            json.append("    }");
            if (i < components.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}\n");
        
        return json.toString();
    }
    
    /**
     * Generates a key pair for signing.
     * 
     * @param keySize RSA key size (2048 or 4096 recommended)
     * @return generated key pair
     * @throws NoSuchAlgorithmException if RSA is not available
     */
    public KeyPair generateKeyPair(int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(keySize, new SecureRandom());
        return keyGen.generateKeyPair();
    }
    
    /**
     * Converts bytes to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
    
    /**
     * Converts hex string to bytes.
     */
    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
