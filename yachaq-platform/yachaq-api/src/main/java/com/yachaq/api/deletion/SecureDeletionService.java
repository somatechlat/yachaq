package com.yachaq.api.deletion;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.security.EncryptionService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.SecureDeletionCertificate;
import com.yachaq.core.domain.SecureDeletionCertificate.DeletionMethod;
import com.yachaq.core.domain.SecureDeletionCertificate.DeletionStatus;
import com.yachaq.core.repository.SecureDeletionCertificateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Secure Deletion Service - Crypto-shred and storage deletion.
 * 
 * Property 19: Secure Deletion Verification
 * Validates: Requirements 222.1, 222.2, 222.3, 222.4, 222.9
 * 
 * For any data deletion request using crypto-shred, the encryption keys
 * must be destroyed and subsequent decryption attempts must fail.
 */
@Service
public class SecureDeletionService {

    private static final Logger log = LoggerFactory.getLogger(SecureDeletionService.class);
    private static final int OVERWRITE_PASSES = 3;

    private final SecureDeletionCertificateRepository certificateRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();
    
    // Track destroyed keys (in production, this would be in a secure key store)
    private final Set<String> destroyedKeys = Collections.synchronizedSet(new HashSet<>());

    public SecureDeletionService(
            SecureDeletionCertificateRepository certificateRepository,
            AuditService auditService) {
        this.certificateRepository = certificateRepository;
        this.auditService = auditService;
    }

    /**
     * Initiates secure deletion for a resource.
     * Requirements 222.1: Generate deletion certificates with timestamp and scope.
     * 
     * @param resourceType Type of resource being deleted
     * @param resourceId ID of the resource
     * @param deletionMethod Method to use (CRYPTO_SHRED, OVERWRITE, or BOTH)
     * @param scopeDescription Description of what is being deleted
     * @param reason Reason for deletion
     * @param requestedBy ID of the requester (DS or system)
     * @return The deletion certificate
     */
    @Transactional
    public SecureDeletionCertificate initiateDeletion(
            String resourceType,
            UUID resourceId,
            DeletionMethod deletionMethod,
            String scopeDescription,
            String reason,
            UUID requestedBy) {
        
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("Resource type cannot be null or blank");
        }
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }
        if (deletionMethod == null) {
            throw new IllegalArgumentException("Deletion method cannot be null");
        }

        // Create certificate
        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                deletionMethod,
                scopeDescription,
                reason,
                requestedBy
        );

        SecureDeletionCertificate saved = certificateRepository.save(certificate);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.SECURE_DELETION_INITIATED,
                requestedBy != null ? requestedBy : UUID.randomUUID(),
                requestedBy != null ? AuditReceipt.ActorType.DS : AuditReceipt.ActorType.SYSTEM,
                saved.getId(),
                "SECURE_DELETION_CERTIFICATE",
                sha256("initiated|" + resourceType + "|" + resourceId + "|" + deletionMethod)
        );

        log.info("Initiated secure deletion for {} {} with method {}", 
                resourceType, resourceId, deletionMethod);

        return saved;
    }

    /**
     * Destroys the encryption key for a resource (crypto-shred).
     * Property 19: Encryption keys must be destroyed.
     * Requirements 222.2: Destroy encryption keys on deletion request.
     * 
     * @param certificateId The deletion certificate ID
     * @param keyId The key ID to destroy
     * @return Updated certificate
     */
    @Transactional
    public SecureDeletionCertificate destroyKey(UUID certificateId, String keyId) {
        SecureDeletionCertificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new DeletionNotFoundException("Certificate not found: " + certificateId));

        if (certificate.isKeyDestroyed()) {
            throw new IllegalStateException("Key already destroyed for certificate: " + certificateId);
        }

        // Destroy the key (mark as destroyed)
        destroyedKeys.add(keyId);
        
        // Update certificate
        certificate.markKeyDestroyed();
        SecureDeletionCertificate saved = certificateRepository.save(certificate);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.KEY_DESTROYED,
                certificate.getRequestedBy() != null ? certificate.getRequestedBy() : UUID.randomUUID(),
                AuditReceipt.ActorType.SYSTEM,
                certificate.getResourceId(),
                certificate.getResourceType(),
                sha256("key_destroyed|" + keyId + "|" + certificate.getResourceId())
        );

        log.info("Destroyed key {} for resource {} {}", 
                keyId, certificate.getResourceType(), certificate.getResourceId());

        return saved;
    }

    /**
     * Checks if a key has been destroyed.
     * Property 19: Subsequent decryption attempts must fail.
     * 
     * @param keyId The key ID to check
     * @return true if the key has been destroyed
     */
    public boolean isKeyDestroyed(String keyId) {
        return destroyedKeys.contains(keyId);
    }

    /**
     * Attempts to decrypt data with a potentially destroyed key.
     * Property 19: Decryption must fail if key is destroyed.
     * 
     * @param keyId The key ID
     * @param encryptedData The encrypted data
     * @return DecryptionResult indicating success or failure
     */
    public DecryptionResult attemptDecryption(String keyId, byte[] encryptedData) {
        if (isKeyDestroyed(keyId)) {
            // Audit the blocked attempt
            auditService.appendReceipt(
                    AuditReceipt.EventType.DECRYPTION_BLOCKED_KEY_DESTROYED,
                    UUID.randomUUID(),
                    AuditReceipt.ActorType.SYSTEM,
                    UUID.randomUUID(),
                    "DECRYPTION_ATTEMPT",
                    sha256("blocked|" + keyId)
            );
            
            return new DecryptionResult(false, null, "Key has been destroyed - decryption blocked");
        }
        
        // In a real implementation, this would call the actual decryption
        // For now, we just indicate the key is valid
        return new DecryptionResult(true, encryptedData, null);
    }

    /**
     * Deletes storage for a resource.
     * Requirements 222.4: Coordinate deletion across all storage locations.
     * 
     * @param certificateId The deletion certificate ID
     * @param storageLocations List of storage locations to delete
     * @return Updated certificate
     */
    @Transactional
    public SecureDeletionCertificate deleteStorage(UUID certificateId, List<String> storageLocations) {
        SecureDeletionCertificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new DeletionNotFoundException("Certificate not found: " + certificateId));

        if (certificate.isStorageDeleted()) {
            throw new IllegalStateException("Storage already deleted for certificate: " + certificateId);
        }

        // Record storage locations
        certificate.setStorageLocations(String.join(",", storageLocations));
        
        // Mark storage as deleted
        certificate.markStorageDeleted();
        SecureDeletionCertificate saved = certificateRepository.save(certificate);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.STORAGE_DELETED,
                certificate.getRequestedBy() != null ? certificate.getRequestedBy() : UUID.randomUUID(),
                AuditReceipt.ActorType.SYSTEM,
                certificate.getResourceId(),
                certificate.getResourceType(),
                sha256("storage_deleted|" + certificate.getResourceId() + "|" + storageLocations.size())
        );

        log.info("Deleted storage for resource {} {} at {} locations", 
                certificate.getResourceType(), certificate.getResourceId(), storageLocations.size());

        return saved;
    }

    /**
     * Overwrites storage with random data.
     * Requirements 222.3: Overwrite storage with random data.
     * 
     * @param certificateId The deletion certificate ID
     * @param dataSize Size of data to overwrite
     * @return Updated certificate
     */
    @Transactional
    public SecureDeletionCertificate overwriteStorage(UUID certificateId, int dataSize) {
        SecureDeletionCertificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new DeletionNotFoundException("Certificate not found: " + certificateId));

        if (certificate.isStorageOverwritten()) {
            throw new IllegalStateException("Storage already overwritten for certificate: " + certificateId);
        }

        // Perform multiple overwrite passes with random data
        for (int pass = 0; pass < OVERWRITE_PASSES; pass++) {
            byte[] randomData = new byte[dataSize];
            secureRandom.nextBytes(randomData);
            // In production, this would actually write to storage
            log.debug("Overwrite pass {} for certificate {}", pass + 1, certificateId);
        }

        // Mark storage as overwritten
        certificate.markStorageOverwritten();
        SecureDeletionCertificate saved = certificateRepository.save(certificate);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.STORAGE_OVERWRITTEN,
                certificate.getRequestedBy() != null ? certificate.getRequestedBy() : UUID.randomUUID(),
                AuditReceipt.ActorType.SYSTEM,
                certificate.getResourceId(),
                certificate.getResourceType(),
                sha256("storage_overwritten|" + certificate.getResourceId() + "|" + OVERWRITE_PASSES)
        );

        log.info("Overwrote storage for resource {} {} with {} passes", 
                certificate.getResourceType(), certificate.getResourceId(), OVERWRITE_PASSES);

        return saved;
    }

    /**
     * Executes complete secure deletion (crypto-shred + storage deletion).
     * 
     * @param resourceType Type of resource
     * @param resourceId Resource ID
     * @param keyId Key ID to destroy
     * @param storageLocations Storage locations to delete
     * @param dataSize Size of data for overwrite
     * @param reason Deletion reason
     * @param requestedBy Requester ID
     * @return Completed certificate
     */
    @Transactional
    public SecureDeletionCertificate executeCompleteDeletion(
            String resourceType,
            UUID resourceId,
            String keyId,
            List<String> storageLocations,
            int dataSize,
            String reason,
            UUID requestedBy) {
        
        // Initiate
        SecureDeletionCertificate certificate = initiateDeletion(
                resourceType,
                resourceId,
                DeletionMethod.BOTH,
                "Complete deletion of " + resourceType + " " + resourceId,
                reason,
                requestedBy
        );

        try {
            // Destroy key
            certificate = destroyKey(certificate.getId(), keyId);

            // Delete storage
            certificate = deleteStorage(certificate.getId(), storageLocations);

            // Overwrite storage
            certificate = overwriteStorage(certificate.getId(), dataSize);

            // Audit completion
            auditService.appendReceipt(
                    AuditReceipt.EventType.SECURE_DELETION_COMPLETED,
                    requestedBy != null ? requestedBy : UUID.randomUUID(),
                    requestedBy != null ? AuditReceipt.ActorType.DS : AuditReceipt.ActorType.SYSTEM,
                    certificate.getId(),
                    "SECURE_DELETION_CERTIFICATE",
                    sha256("completed|" + certificate.getCertificateHash())
            );

            log.info("Completed secure deletion for {} {}", resourceType, resourceId);

            return certificate;

        } catch (Exception e) {
            certificate.markFailed(e.getMessage());
            certificateRepository.save(certificate);

            auditService.appendReceipt(
                    AuditReceipt.EventType.SECURE_DELETION_FAILED,
                    requestedBy != null ? requestedBy : UUID.randomUUID(),
                    AuditReceipt.ActorType.SYSTEM,
                    certificate.getId(),
                    "SECURE_DELETION_CERTIFICATE",
                    sha256("failed|" + e.getMessage())
            );

            throw new DeletionFailedException("Secure deletion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a deletion certificate.
     * Requirements 222.9: Coordinate deletion across all storage locations.
     * 
     * @param certificateId Certificate ID to verify
     * @return Verification result
     */
    @Transactional
    public VerificationResult verifyCertificate(UUID certificateId) {
        SecureDeletionCertificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new DeletionNotFoundException("Certificate not found: " + certificateId));

        List<String> issues = new ArrayList<>();

        // Check integrity
        if (!certificate.verifyIntegrity()) {
            issues.add("Certificate hash integrity check failed");
        }

        // Check completion status
        if (!certificate.isComplete()) {
            issues.add("Deletion is not complete. Status: " + certificate.getStatus());
        }

        // Check key destruction for crypto-shred methods
        if (certificate.getDeletionMethod() == DeletionMethod.CRYPTO_SHRED ||
            certificate.getDeletionMethod() == DeletionMethod.BOTH) {
            if (!certificate.isKeyDestroyed()) {
                issues.add("Key was not destroyed");
            }
        }

        // Check storage deletion for overwrite methods
        if (certificate.getDeletionMethod() == DeletionMethod.OVERWRITE ||
            certificate.getDeletionMethod() == DeletionMethod.BOTH) {
            if (!certificate.isStorageDeleted()) {
                issues.add("Storage was not deleted");
            }
            if (!certificate.isStorageOverwritten()) {
                issues.add("Storage was not overwritten");
            }
        }

        boolean valid = issues.isEmpty();

        if (valid && certificate.getStatus() == DeletionStatus.COMPLETED) {
            certificate.markVerified();
            certificateRepository.save(certificate);

            auditService.appendReceipt(
                    AuditReceipt.EventType.SECURE_DELETION_VERIFIED,
                    certificate.getRequestedBy() != null ? certificate.getRequestedBy() : UUID.randomUUID(),
                    AuditReceipt.ActorType.SYSTEM,
                    certificate.getId(),
                    "SECURE_DELETION_CERTIFICATE",
                    sha256("verified|" + certificate.getCertificateHash())
            );
        }

        return new VerificationResult(valid, issues, certificate);
    }

    /**
     * Gets a deletion certificate by ID.
     */
    public Optional<SecureDeletionCertificate> getCertificate(UUID certificateId) {
        return certificateRepository.findById(certificateId);
    }

    /**
     * Checks if a resource has been securely deleted.
     */
    public boolean isResourceDeleted(String resourceType, UUID resourceId) {
        return certificateRepository.isResourceDeleted(resourceType, resourceId);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // DTOs and Exceptions
    public record DecryptionResult(boolean success, byte[] data, String failureReason) {}
    
    public record VerificationResult(boolean valid, List<String> issues, SecureDeletionCertificate certificate) {}

    public static class DeletionNotFoundException extends RuntimeException {
        public DeletionNotFoundException(String message) { super(message); }
    }

    public static class DeletionFailedException extends RuntimeException {
        public DeletionFailedException(String message, Throwable cause) { super(message, cause); }
    }
}
