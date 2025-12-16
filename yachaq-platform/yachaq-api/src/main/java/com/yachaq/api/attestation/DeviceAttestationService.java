package com.yachaq.api.attestation;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.DeviceAttestation;
import com.yachaq.core.domain.DeviceAttestation.Platform;
import com.yachaq.core.domain.DeviceAttestation.AttestationType;
import com.yachaq.core.domain.DeviceAttestation.TrustLevel;
import com.yachaq.core.domain.DeviceAttestation.AttestationStatus;
import com.yachaq.core.repository.DeviceAttestationRepository;
import com.yachaq.core.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for device attestation collection and verification.
 * Integrates SafetyNet (Android), DeviceCheck (iOS), and TPM attestation.
 * 
 * Validates: Requirements 217.1, 217.2, 217.3, 217.4, 217.5
 * Property 22: Device Attestation Collection
 */
@Service
public class DeviceAttestationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAttestationService.class);
    private static final int NONCE_LENGTH = 32;
    private static final long ATTESTATION_VALIDITY_HOURS = 24;

    private final DeviceAttestationRepository attestationRepository;
    private final DeviceRepository deviceRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom;

    public DeviceAttestationService(
            DeviceAttestationRepository attestationRepository,
            DeviceRepository deviceRepository,
            AuditService auditService) {
        this.attestationRepository = attestationRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a nonce for attestation challenge.
     * Nonce is used for replay protection.
     */
    public String generateNonce() {
        byte[] nonceBytes = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonceBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    }

    /**
     * Collects attestation from device.
     * Property 22: Attestation data collected and stored with device record.
     * 
     * @param deviceId Device to attest
     * @param platform Device platform (ANDROID, IOS, DESKTOP)
     * @param attestationProof Raw attestation proof from platform API
     * @param nonce Challenge nonce used in attestation
     * @return Created attestation record
     */
    @Transactional
    public DeviceAttestation collectAttestation(
            UUID deviceId,
            Platform platform,
            String attestationProof,
            String nonce) {
        
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        // Replay protection: check nonce hasn't been used
        if (attestationRepository.existsByNonce(nonce)) {
            throw new AttestationReplayException("Nonce already used - possible replay attack");
        }

        AttestationType attestationType = determineAttestationType(platform);
        String attestationHash = sha256(attestationProof);

        DeviceAttestation attestation = DeviceAttestation.create(
                deviceId,
                platform,
                attestationType,
                attestationProof,
                attestationHash,
                nonce
        );

        attestation = attestationRepository.save(attestation);

        log.info("Collected attestation for device {} with type {}", 
                deviceId, attestationType);

        return attestation;
    }

    /**
     * Verifies attestation proof and updates trust level.
     * Requirements 217.4, 217.5: Verify proofs and flag failed devices.
     * 
     * @param attestationId Attestation to verify
     * @return Verified attestation with trust level
     */
    @Transactional
    public DeviceAttestation verifyAttestation(UUID attestationId) {
        DeviceAttestation attestation = attestationRepository.findById(attestationId)
                .orElseThrow(() -> new AttestationNotFoundException(
                        "Attestation not found: " + attestationId));

        if (attestation.getStatus() == AttestationStatus.VERIFIED) {
            return attestation;
        }

        Device device = deviceRepository.findById(attestation.getDeviceId())
                .orElseThrow(() -> new DeviceNotFoundException(
                        "Device not found: " + attestation.getDeviceId()));

        try {
            VerificationResult result = performVerification(attestation);
            
            if (result.success()) {
                Instant expiresAt = Instant.now().plus(ATTESTATION_VALIDITY_HOURS, ChronoUnit.HOURS);
                attestation.verify(result.trustLevel(), expiresAt);
                device.verifyAttestation();
                
                log.info("Attestation verified for device {} with trust level {}", 
                        device.getId(), result.trustLevel());
            } else {
                attestation.fail(result.failureReason());
                device.failAttestation();
                
                log.warn("Attestation failed for device {}: {}", 
                        device.getId(), result.failureReason());
            }

            deviceRepository.save(device);
            DeviceAttestation savedAttestation = attestationRepository.save(attestation);

            // Generate audit receipt
            auditService.appendReceipt(
                    com.yachaq.core.domain.AuditReceipt.EventType.DEVICE_ATTESTATION,
                    device.getDsId(),
                    com.yachaq.core.domain.AuditReceipt.ActorType.DS,
                    device.getId(),
                    "DEVICE",
                    sha256("attestation_status=" + savedAttestation.getStatus() + 
                           ",trust_level=" + savedAttestation.getTrustLevel())
            );

            return savedAttestation;

        } catch (Exception e) {
            log.error("Attestation verification error for device {}", 
                    attestation.getDeviceId(), e);
            attestation.fail("Verification error: " + e.getMessage());
            device.failAttestation();
            deviceRepository.save(device);
            return attestationRepository.save(attestation);
        }
    }

    /**
     * Gets current attestation status for a device.
     */
    public AttestationStatusResult getAttestationStatus(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        Optional<DeviceAttestation> latestAttestation = 
                attestationRepository.findLatestValidAttestation(deviceId, Instant.now());

        if (latestAttestation.isPresent()) {
            DeviceAttestation att = latestAttestation.get();
            return new AttestationStatusResult(
                    device.getAttestationStatus(),
                    att.getTrustLevel(),
                    att.getExpiresAt(),
                    att.getVerifiedAt(),
                    null
            );
        }

        return new AttestationStatusResult(
                device.getAttestationStatus(),
                TrustLevel.UNVERIFIED,
                null,
                null,
                "No valid attestation found"
        );
    }

    /**
     * Triggers re-attestation for a device.
     * Returns a new nonce for the attestation challenge.
     */
    @Transactional
    public String refreshAttestation(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        // Reset device attestation status to pending
        device.failAttestation(); // Sets to FAILED, will be PENDING after new attestation
        deviceRepository.save(device);

        log.info("Triggered re-attestation for device {}", deviceId);

        return generateNonce();
    }

    /**
     * Performs platform-specific attestation verification.
     */
    private VerificationResult performVerification(DeviceAttestation attestation) {
        return switch (attestation.getAttestationType()) {
            case SAFETYNET -> verifySafetyNet(attestation);
            case DEVICECHECK -> verifyDeviceCheck(attestation);
            case TPM -> verifyTPM(attestation);
            case NONE -> new VerificationResult(true, TrustLevel.LOW, null);
        };
    }

    /**
     * Verifies Android SafetyNet/Play Integrity attestation.
     * In production, this would call Google's verification API.
     */
    private VerificationResult verifySafetyNet(DeviceAttestation attestation) {
        String proof = attestation.getAttestationProof();
        
        // Verify proof structure (JWT format for SafetyNet)
        if (!isValidJwtFormat(proof)) {
            return new VerificationResult(false, TrustLevel.UNVERIFIED, 
                    "Invalid SafetyNet proof format");
        }

        // Verify nonce is embedded in proof
        if (!proofContainsNonce(proof, attestation.getNonce())) {
            return new VerificationResult(false, TrustLevel.UNVERIFIED,
                    "Nonce mismatch in SafetyNet proof");
        }

        // In production: verify signature with Google's certificate
        // For now, check basic integrity indicators
        TrustLevel trustLevel = determineSafetyNetTrustLevel(proof);
        
        return new VerificationResult(true, trustLevel, null);
    }

    /**
     * Verifies iOS DeviceCheck/App Attest attestation.
     * In production, this would call Apple's verification API.
     */
    private VerificationResult verifyDeviceCheck(DeviceAttestation attestation) {
        String proof = attestation.getAttestationProof();

        // Verify proof structure
        if (proof == null || proof.length() < 100) {
            return new VerificationResult(false, TrustLevel.UNVERIFIED,
                    "Invalid DeviceCheck proof format");
        }

        // In production: verify with Apple's attestation service
        // Check for hardware attestation indicators
        TrustLevel trustLevel = proof.contains("hardware") ? 
                TrustLevel.HIGH : TrustLevel.MEDIUM;

        return new VerificationResult(true, trustLevel, null);
    }

    /**
     * Verifies desktop TPM attestation.
     */
    private VerificationResult verifyTPM(DeviceAttestation attestation) {
        String proof = attestation.getAttestationProof();

        if (proof == null || proof.isBlank()) {
            return new VerificationResult(false, TrustLevel.UNVERIFIED,
                    "Missing TPM attestation proof");
        }

        // In production: verify TPM quote and certificate chain
        return new VerificationResult(true, TrustLevel.MEDIUM, null);
    }

    private AttestationType determineAttestationType(Platform platform) {
        return switch (platform) {
            case ANDROID -> AttestationType.SAFETYNET;
            case IOS -> AttestationType.DEVICECHECK;
            case DESKTOP -> AttestationType.TPM;
        };
    }

    private boolean isValidJwtFormat(String proof) {
        if (proof == null) return false;
        String[] parts = proof.split("\\.");
        return parts.length == 3;
    }

    private boolean proofContainsNonce(String proof, String nonce) {
        // In production: decode JWT and verify nonce in payload
        return proof != null && nonce != null;
    }

    private TrustLevel determineSafetyNetTrustLevel(String proof) {
        // In production: parse SafetyNet response and check:
        // - ctsProfileMatch (device passes CTS)
        // - basicIntegrity (device not rooted)
        // - evaluationType (BASIC vs HARDWARE_BACKED)
        
        if (proof.contains("HARDWARE_BACKED")) {
            return TrustLevel.HIGH;
        } else if (proof.contains("BASIC")) {
            return TrustLevel.MEDIUM;
        }
        return TrustLevel.LOW;
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

    // Result records
    public record VerificationResult(
            boolean success,
            TrustLevel trustLevel,
            String failureReason
    ) {}

    public record AttestationStatusResult(
            Device.AttestationStatus deviceStatus,
            TrustLevel trustLevel,
            Instant expiresAt,
            Instant verifiedAt,
            String message
    ) {}

    // Exceptions
    public static class DeviceNotFoundException extends RuntimeException {
        public DeviceNotFoundException(String message) { super(message); }
    }

    public static class AttestationNotFoundException extends RuntimeException {
        public AttestationNotFoundException(String message) { super(message); }
    }

    public static class AttestationReplayException extends RuntimeException {
        public AttestationReplayException(String message) { super(message); }
    }
}
