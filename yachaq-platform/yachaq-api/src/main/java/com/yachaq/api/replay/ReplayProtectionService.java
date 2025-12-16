package com.yachaq.api.replay;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.NonceRegistry;
import com.yachaq.core.domain.NonceRegistry.NonceStatus;
import com.yachaq.core.domain.TimeCapsule;
import com.yachaq.core.repository.NonceRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for capsule replay protection using nonce registry.
 * 
 * Property 16: Capsule Replay Protection
 * Validates: Requirements 218.1, 218.2, 218.3, 218.4
 * 
 * For any time capsule created with a nonce, accessing the capsule
 * with the same nonce a second time must be rejected.
 */
@Service
public class ReplayProtectionService {

    private static final Logger log = LoggerFactory.getLogger(ReplayProtectionService.class);
    private static final int NONCE_LENGTH = 32;

    private final NonceRegistryRepository nonceRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom;

    public ReplayProtectionService(
            NonceRegistryRepository nonceRepository,
            AuditService auditService) {
        this.nonceRepository = nonceRepository;
        this.auditService = auditService;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a unique nonce for a capsule.
     * Requirements 218.1: Generate unique nonces for capsules.
     * 
     * @return Cryptographically secure random nonce
     */
    public String generateNonce() {
        byte[] nonceBytes = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonceBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    }


    /**
     * Registers a nonce for a capsule.
     * Requirements 218.4: Store nonces with TTL matching capsule TTL.
     * 
     * @param capsule The time capsule to register nonce for
     * @return Registered nonce entry
     */
    @Transactional
    public NonceRegistry registerNonce(TimeCapsule capsule) {
        if (capsule == null) {
            throw new IllegalArgumentException("Capsule cannot be null");
        }
        if (capsule.getNonce() == null || capsule.getNonce().isBlank()) {
            throw new IllegalArgumentException("Capsule nonce cannot be null or blank");
        }

        // Check if nonce already exists (should not happen with proper UUID generation)
        if (nonceRepository.existsByNonce(capsule.getNonce())) {
            throw new NonceAlreadyExistsException(
                    "Nonce already registered: " + capsule.getNonce());
        }

        NonceRegistry registry = NonceRegistry.create(
                capsule.getNonce(),
                capsule.getId(),
                capsule.getTtl()
        );

        NonceRegistry saved = nonceRepository.save(registry);

        // Generate audit receipt
        auditService.appendReceipt(
                AuditReceipt.EventType.NONCE_REGISTERED,
                capsule.getRequestId(),
                AuditReceipt.ActorType.SYSTEM,
                capsule.getId(),
                "TIME_CAPSULE",
                sha256("nonce=" + capsule.getNonce() + 
                       ",capsule_id=" + capsule.getId() +
                       ",expires_at=" + capsule.getTtl())
        );

        log.info("Registered nonce for capsule {}", capsule.getId());

        return saved;
    }

    /**
     * Registers a nonce with explicit parameters.
     * 
     * @param nonce Nonce value
     * @param capsuleId Capsule ID
     * @param expiresAt Expiration time
     * @return Registered nonce entry
     */
    @Transactional
    public NonceRegistry registerNonce(String nonce, UUID capsuleId, Instant expiresAt) {
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("Nonce cannot be null or blank");
        }
        if (capsuleId == null) {
            throw new IllegalArgumentException("Capsule ID cannot be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiration time cannot be null");
        }

        if (nonceRepository.existsByNonce(nonce)) {
            throw new NonceAlreadyExistsException("Nonce already registered: " + nonce);
        }

        NonceRegistry registry = NonceRegistry.create(nonce, capsuleId, expiresAt);
        return nonceRepository.save(registry);
    }

    /**
     * Validates a nonce for capsule access.
     * Requirements 218.2: Validate nonce on capsule access.
     * 
     * Property 16: Accessing with same nonce twice must be rejected.
     * 
     * @param nonce Nonce to validate
     * @return Validation result
     */
    @Transactional
    public ValidationResult validateNonce(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return new ValidationResult(false, "Nonce cannot be null or blank", null);
        }

        Optional<NonceRegistry> registryOpt = nonceRepository.findByNonce(nonce);

        if (registryOpt.isEmpty()) {
            log.warn("Nonce not found: {}", nonce);
            return new ValidationResult(false, "Nonce not found", null);
        }

        NonceRegistry registry = registryOpt.get();

        // Property 16: Check for replay attack
        if (registry.isReplayAttempt()) {
            log.warn("Replay attack detected for nonce: {}", nonce);
            
            // Generate audit receipt for replay attempt
            auditService.appendReceipt(
                    AuditReceipt.EventType.CAPSULE_REPLAY_REJECTED,
                    registry.getCapsuleId(),
                    AuditReceipt.ActorType.SYSTEM,
                    registry.getCapsuleId(),
                    "TIME_CAPSULE",
                    sha256("nonce=" + nonce + 
                           ",capsule_id=" + registry.getCapsuleId() +
                           ",original_use=" + registry.getUsedAt())
            );

            return new ValidationResult(false, "Replay attack detected - nonce already used", registry);
        }

        // Check if nonce has expired
        if (registry.isExpired()) {
            registry.markExpired();
            nonceRepository.save(registry);
            log.warn("Nonce expired: {}", nonce);
            return new ValidationResult(false, "Nonce has expired", registry);
        }

        // Mark nonce as used
        registry.markUsed();
        nonceRepository.save(registry);

        // Generate audit receipt for successful validation
        auditService.appendReceipt(
                AuditReceipt.EventType.NONCE_VALIDATED,
                registry.getCapsuleId(),
                AuditReceipt.ActorType.SYSTEM,
                registry.getCapsuleId(),
                "TIME_CAPSULE",
                sha256("nonce=" + nonce + 
                       ",capsule_id=" + registry.getCapsuleId() +
                       ",validated_at=" + registry.getUsedAt())
        );

        log.info("Nonce validated successfully for capsule {}", registry.getCapsuleId());

        return new ValidationResult(true, null, registry);
    }

    /**
     * Invalidates a nonce (marks as used without access).
     * Used for administrative purposes or security incidents.
     * 
     * @param nonce Nonce to invalidate
     */
    @Transactional
    public void invalidateNonce(String nonce) {
        NonceRegistry registry = nonceRepository.findByNonce(nonce)
                .orElseThrow(() -> new NonceNotFoundException("Nonce not found: " + nonce));

        if (registry.getStatus() == NonceStatus.ACTIVE) {
            registry.markUsed();
            nonceRepository.save(registry);
            log.info("Nonce invalidated: {}", nonce);
        }
    }

    /**
     * Expires all nonces past their TTL.
     * Requirements 218.4: Nonces expire with capsule TTL.
     * 
     * @return Number of nonces expired
     */
    @Transactional
    public int expireOldNonces() {
        var expiredNonces = nonceRepository.findExpiredNonces(Instant.now());
        int count = 0;

        for (NonceRegistry registry : expiredNonces) {
            registry.markExpired();
            nonceRepository.save(registry);
            count++;
        }

        if (count > 0) {
            log.info("Expired {} nonces", count);
        }

        return count;
    }

    /**
     * Gets the status of a nonce.
     * 
     * @param nonce Nonce to check
     * @return Nonce status or empty if not found
     */
    public Optional<NonceStatus> getNonceStatus(String nonce) {
        return nonceRepository.findByNonce(nonce)
                .map(NonceRegistry::getStatus);
    }

    /**
     * Checks if a nonce exists and is valid.
     * 
     * @param nonce Nonce to check
     * @return true if nonce exists and is valid for use
     */
    public boolean isNonceValid(String nonce) {
        return nonceRepository.findByNonce(nonce)
                .map(NonceRegistry::isValid)
                .orElse(false);
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

    /**
     * Result of nonce validation.
     */
    public record ValidationResult(
            boolean valid,
            String failureReason,
            NonceRegistry registry
    ) {
        public boolean isValid() { return valid; }
    }

    // Exceptions
    public static class NonceNotFoundException extends RuntimeException {
        public NonceNotFoundException(String message) { super(message); }
    }

    public static class NonceAlreadyExistsException extends RuntimeException {
        public NonceAlreadyExistsException(String message) { super(message); }
    }

    public static class ReplayAttackException extends RuntimeException {
        public ReplayAttackException(String message) { super(message); }
    }
}
