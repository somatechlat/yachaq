package com.yachaq.api.deletion;

import com.yachaq.core.domain.SecureDeletionCertificate;
import com.yachaq.core.domain.SecureDeletionCertificate.DeletionMethod;
import com.yachaq.core.domain.SecureDeletionCertificate.DeletionStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Secure Deletion Service.
 * Tests domain logic without Spring context.
 * 
 * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
 * For any data deletion request using crypto-shred, the encryption keys
 * must be destroyed and subsequent decryption attempts must fail.
 * 
 * **Validates: Requirements 222.1, 222.2**
 */
class SecureDeletionPropertyTest {

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.1**
     * 
     * For any deletion certificate created, it must contain all required fields
     * including resource type, resource ID, deletion method, and timestamp.
     */
    @Property(tries = 100)
    void property19_certificateCreation_containsAllRequiredFields(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validDeletionMethods") DeletionMethod deletionMethod,
            @ForAll("validScopeDescriptions") String scopeDescription,
            @ForAll("validReasons") String reason) {
        
        UUID resourceId = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();

        // Create certificate
        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                deletionMethod,
                scopeDescription,
                reason,
                requestedBy
        );

        // Property 19: Certificate must contain all required fields
        assertThat(certificate.getResourceType()).isEqualTo(resourceType);
        assertThat(certificate.getResourceId()).isEqualTo(resourceId);
        assertThat(certificate.getDeletionMethod()).isEqualTo(deletionMethod);
        assertThat(certificate.getScopeDescription()).isEqualTo(scopeDescription);
        assertThat(certificate.getDeletionReason()).isEqualTo(reason);
        assertThat(certificate.getRequestedBy()).isEqualTo(requestedBy);
        assertThat(certificate.getInitiatedAt()).isNotNull();
        assertThat(certificate.getCreatedAt()).isNotNull();
        assertThat(certificate.getCertificateHash()).isNotNull().hasSize(64);
        assertThat(certificate.getStatus()).isEqualTo(DeletionStatus.INITIATED);
    }

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.2**
     * 
     * For any crypto-shred deletion, marking the key as destroyed must
     * update the certificate status and set keyDestroyed to true.
     */
    @Property(tries = 100)
    void property19_keyDestruction_updatesStatusCorrectly(
            @ForAll("validResourceTypes") String resourceType) {
        
        UUID resourceId = UUID.randomUUID();

        // Create certificate with CRYPTO_SHRED method
        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.CRYPTO_SHRED,
                "Test deletion",
                "Test reason",
                UUID.randomUUID()
        );

        // Initially key is not destroyed
        assertThat(certificate.isKeyDestroyed()).isFalse();
        assertThat(certificate.getStatus()).isEqualTo(DeletionStatus.INITIATED);

        // Destroy key
        certificate.markKeyDestroyed();

        // Property 19: Key must be marked as destroyed
        assertThat(certificate.isKeyDestroyed()).isTrue();
        
        // For CRYPTO_SHRED, key destruction completes the deletion
        assertThat(certificate.isComplete()).isTrue();
        assertThat(certificate.getCompletedAt()).isNotNull();
    }

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.2**
     * 
     * For any deletion using BOTH method, completion requires both
     * key destruction AND storage deletion/overwrite.
     */
    @Property(tries = 100)
    void property19_bothMethod_requiresKeyAndStorageDeletion(
            @ForAll("validResourceTypes") String resourceType) {
        
        UUID resourceId = UUID.randomUUID();

        // Create certificate with BOTH method
        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.BOTH,
                "Complete deletion",
                "Test reason",
                UUID.randomUUID()
        );

        // Initially not complete
        assertThat(certificate.isComplete()).isFalse();

        // Destroy key only - not complete yet
        certificate.markKeyDestroyed();
        assertThat(certificate.isComplete()).isFalse();

        // Delete storage - still not complete (need overwrite)
        certificate.markStorageDeleted();
        assertThat(certificate.isComplete()).isFalse();

        // Overwrite storage - now complete
        certificate.markStorageOverwritten();
        assertThat(certificate.isComplete()).isTrue();
        assertThat(certificate.getCompletedAt()).isNotNull();
    }

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.1**
     * 
     * For any certificate, the hash must be deterministic and verifiable.
     */
    @Property(tries = 100)
    void property19_certificateHash_isDeterministicAndVerifiable(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validDeletionMethods") DeletionMethod deletionMethod) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                deletionMethod,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        // Hash must be valid SHA-256 (64 hex chars)
        assertThat(certificate.getCertificateHash())
                .isNotNull()
                .hasSize(64)
                .matches("[0-9a-f]+");

        // Integrity verification must pass
        assertThat(certificate.verifyIntegrity()).isTrue();
    }

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.1**
     * 
     * For any certificate state change, the hash must be updated.
     */
    @Property(tries = 100)
    void property19_stateChanges_updateHash(
            @ForAll("validResourceTypes") String resourceType) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.BOTH,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        String initialHash = certificate.getCertificateHash();

        // Destroy key - hash should change
        certificate.markKeyDestroyed();
        String afterKeyDestroyed = certificate.getCertificateHash();
        assertThat(afterKeyDestroyed).isNotEqualTo(initialHash);

        // Delete storage - hash should change again
        certificate.markStorageDeleted();
        String afterStorageDeleted = certificate.getCertificateHash();
        assertThat(afterStorageDeleted).isNotEqualTo(afterKeyDestroyed);

        // Overwrite storage - hash should change again
        certificate.markStorageOverwritten();
        String afterOverwrite = certificate.getCertificateHash();
        assertThat(afterOverwrite).isNotEqualTo(afterStorageDeleted);

        // Integrity must still verify
        assertThat(certificate.verifyIntegrity()).isTrue();
    }

    /**
     * **Feature: yachaq-platform, Property 19: Secure Deletion Verification**
     * **Validates: Requirements 222.3**
     * 
     * For OVERWRITE method, completion requires storage deletion AND overwrite.
     */
    @Property(tries = 100)
    void property19_overwriteMethod_requiresStorageDeletionAndOverwrite(
            @ForAll("validResourceTypes") String resourceType) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.OVERWRITE,
                "Overwrite deletion",
                "Test reason",
                UUID.randomUUID()
        );

        // Initially not complete
        assertThat(certificate.isComplete()).isFalse();

        // Delete storage only - not complete yet
        certificate.markStorageDeleted();
        assertThat(certificate.isComplete()).isFalse();

        // Overwrite storage - now complete
        certificate.markStorageOverwritten();
        assertThat(certificate.isComplete()).isTrue();
    }

    /**
     * Property: Verification fails for incomplete certificates.
     */
    @Property(tries = 100)
    void verificationFailsForIncompleteCertificates(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validDeletionMethods") DeletionMethod deletionMethod) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                deletionMethod,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        // Incomplete certificate cannot be verified
        assertThat(certificate.isComplete()).isFalse();
        
        // Attempting to mark as verified should fail
        assertThatThrownBy(() -> certificate.markVerified())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    /**
     * Property: Completed certificates can be verified.
     */
    @Property(tries = 100)
    void completedCertificatesCanBeVerified(
            @ForAll("validResourceTypes") String resourceType) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.CRYPTO_SHRED,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        // Complete the deletion
        certificate.markKeyDestroyed();
        assertThat(certificate.isComplete()).isTrue();

        // Now verification should succeed
        certificate.markVerified();
        assertThat(certificate.getStatus()).isEqualTo(DeletionStatus.VERIFIED);
        assertThat(certificate.getVerifiedAt()).isNotNull();
    }

    /**
     * Property: Failed certificates record failure reason.
     */
    @Property(tries = 100)
    void failedCertificatesRecordReason(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll("validReasons") String failureReason) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.CRYPTO_SHRED,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        // Mark as failed
        certificate.markFailed(failureReason);

        assertThat(certificate.getStatus()).isEqualTo(DeletionStatus.FAILED);
        assertThat(certificate.getFailureReason()).isEqualTo(failureReason);
    }

    /**
     * Property: Storage locations can be recorded.
     */
    @Property(tries = 100)
    void storageLocationsCanBeRecorded(
            @ForAll("validResourceTypes") String resourceType,
            @ForAll @Size(min = 1, max = 5) List<@AlphaChars @StringLength(min = 5, max = 50) String> locations) {
        
        UUID resourceId = UUID.randomUUID();

        SecureDeletionCertificate certificate = SecureDeletionCertificate.create(
                resourceType,
                resourceId,
                DeletionMethod.OVERWRITE,
                "Test scope",
                "Test reason",
                UUID.randomUUID()
        );

        String locationsStr = String.join(",", locations);
        certificate.setStorageLocations(locationsStr);

        assertThat(certificate.getStorageLocations()).isEqualTo(locationsStr);
        // Hash should be updated
        assertThat(certificate.verifyIntegrity()).isTrue();
    }

    // Arbitraries
    @Provide
    Arbitrary<String> validResourceTypes() {
        return Arbitraries.of(
                "CONSENT_CONTRACT",
                "TIME_CAPSULE",
                "USER_DATA",
                "AUDIT_RECEIPT",
                "QUERY_PLAN",
                "ODS_ENTRY",
                "ODX_ENTRY"
        );
    }

    @Provide
    Arbitrary<DeletionMethod> validDeletionMethods() {
        return Arbitraries.of(DeletionMethod.values());
    }

    @Provide
    Arbitrary<String> validScopeDescriptions() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(10)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> validReasons() {
        return Arbitraries.of(
                "User requested deletion",
                "Consent revoked",
                "TTL expired",
                "Legal requirement",
                "Data retention policy",
                "Security incident"
        );
    }
}
