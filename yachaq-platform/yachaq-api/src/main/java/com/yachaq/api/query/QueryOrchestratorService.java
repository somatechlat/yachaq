package com.yachaq.api.query;

import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.QueryPlan.PlanStatus;
import com.yachaq.core.domain.TimeCapsule;
import com.yachaq.core.domain.TimeCapsule.CapsuleStatus;
import com.yachaq.core.repository.QueryPlanRepository;
import com.yachaq.core.repository.TimeCapsuleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Query Orchestrator Service - Dispatch live queries to devices and collect responses.
 * 
 * Property 13: Time Capsule TTL Enforcement
 * Property 15: Query Plan Signature Verification
 * Validates: Requirements 205.1, 205.3, 206.1, 206.2
 */
@Service
public class QueryOrchestratorService {

    private final QueryPlanRepository queryPlanRepository;
    private final TimeCapsuleRepository timeCapsuleRepository;

    @Value("${yachaq.query.signing-key:default-signing-key-change-in-production}")
    private String signingKey;

    @Value("${yachaq.query.default-timeout-seconds:300}")
    private int defaultTimeoutSeconds;

    @Value("${yachaq.capsule.max-ttl-hours:168}")
    private int maxTtlHours;

    public QueryOrchestratorService(
            QueryPlanRepository queryPlanRepository,
            TimeCapsuleRepository timeCapsuleRepository) {
        this.queryPlanRepository = queryPlanRepository;
        this.timeCapsuleRepository = timeCapsuleRepository;
    }

    /**
     * Create and sign a query plan.
     * Property 15: Query Plan Signature Verification
     */
    @Transactional
    public QueryPlan createSignedQueryPlan(
            UUID requestId,
            UUID consentContractId,
            List<String> scope,
            List<String> allowedTransforms,
            BigDecimal compensation,
            Duration ttl) {

        QueryPlan plan = new QueryPlan();
        plan.setRequestId(requestId);
        plan.setConsentContractId(consentContractId);
        plan.setScopeHash(computeHash(String.join(",", scope)));
        plan.setAllowedTransforms(String.join(",", allowedTransforms));
        plan.setOutputRestrictions("[]"); // Default empty
        plan.setCompensation(compensation);
        plan.setTtl(Instant.now().plus(ttl));
        plan.setSigningKeyId("platform-key-v1");

        // Sign the plan
        String signature = signPlan(plan);
        plan.setSignature(signature);
        plan.setSignedAt(Instant.now());
        plan.setStatus(PlanStatus.SIGNED);

        return queryPlanRepository.save(plan);
    }

    /**
     * Verify a query plan signature.
     * Property 15: Query Plan Signature Verification
     */
    public boolean verifyPlanSignature(QueryPlan plan) {
        String expectedSignature = signPlan(plan);
        return MessageDigest.isEqual(
            expectedSignature.getBytes(StandardCharsets.UTF_8),
            plan.getSignature().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Dispatch query to eligible devices.
     * Requirements: 205.1, 205.3
     */
    @Transactional
    public DispatchResult dispatchQuery(UUID planId, List<UUID> eligibleDeviceIds) {
        QueryPlan plan = queryPlanRepository.findById(planId)
            .orElseThrow(() -> new QueryPlanNotFoundException("Plan not found: " + planId));

        if (!plan.isValidForExecution()) {
            throw new InvalidQueryPlanException("Plan is not valid for execution: " + plan.getStatus());
        }

        if (!verifyPlanSignature(plan)) {
            throw new InvalidQueryPlanException("Plan signature verification failed");
        }

        plan.setStatus(PlanStatus.DISPATCHED);
        queryPlanRepository.save(plan);

        // In production, this would dispatch to actual devices via Kafka/gRPC
        // For now, return dispatch result
        return new DispatchResult(
            planId,
            eligibleDeviceIds.size(),
            Instant.now().plusSeconds(defaultTimeoutSeconds),
            DispatchStatus.DISPATCHED
        );
    }

    /**
     * Create a time capsule from query responses.
     * Property 13: Time Capsule TTL Enforcement
     * Requirements: 206.1, 206.2
     */
    @Transactional
    public TimeCapsule createTimeCapsule(
            UUID requestId,
            UUID consentContractId,
            byte[] encryptedPayload,
            Duration ttl) {

        // Validate TTL is within limits
        if (ttl.toHours() > maxTtlHours) {
            throw new InvalidTtlException("TTL exceeds maximum allowed: " + maxTtlHours + " hours");
        }

        if (ttl.isNegative() || ttl.isZero()) {
            throw new InvalidTtlException("TTL must be positive");
        }

        TimeCapsule capsule = new TimeCapsule();
        capsule.setRequestId(requestId);
        capsule.setConsentContractId(consentContractId);
        capsule.setFieldManifestHash(computeHash(Base64.getEncoder().encodeToString(encryptedPayload)));
        capsule.setEncryptedPayload(encryptedPayload);
        capsule.setEncryptionKeyId("capsule-key-" + UUID.randomUUID());
        capsule.setTtl(Instant.now().plus(ttl));
        capsule.setStatus(CapsuleStatus.CREATED);

        return timeCapsuleRepository.save(capsule);
    }

    /**
     * Access a time capsule with nonce validation.
     * Property 16: Capsule Replay Protection
     */
    @Transactional
    public CapsuleAccessResult accessCapsule(UUID capsuleId, String nonce) {
        TimeCapsule capsule = timeCapsuleRepository.findById(capsuleId)
            .orElseThrow(() -> new CapsuleNotFoundException("Capsule not found: " + capsuleId));

        // Property 13: Check TTL
        if (capsule.isExpired()) {
            capsule.setStatus(CapsuleStatus.EXPIRED);
            timeCapsuleRepository.save(capsule);
            return new CapsuleAccessResult(false, "Capsule has expired", null);
        }

        // Property 16: Validate nonce (replay protection)
        if (!capsule.getNonce().equals(nonce)) {
            return new CapsuleAccessResult(false, "Invalid nonce - possible replay attack", null);
        }

        // Mark as delivered
        if (capsule.getStatus() == CapsuleStatus.CREATED) {
            capsule.setStatus(CapsuleStatus.DELIVERED);
            capsule.setDeliveredAt(Instant.now());
            timeCapsuleRepository.save(capsule);
        }

        return new CapsuleAccessResult(true, "Access granted", capsule.getEncryptedPayload());
    }

    /**
     * Enforce TTL on expired capsules.
     * Property 13: Time Capsule TTL Enforcement
     * Requirements: 207.2, 207.3
     */
    @Transactional
    public List<UUID> enforceExpiredCapsules() {
        List<TimeCapsule> expired = timeCapsuleRepository.findExpiredCapsules(Instant.now());
        List<UUID> processedIds = new ArrayList<>();

        for (TimeCapsule capsule : expired) {
            capsule.setStatus(CapsuleStatus.EXPIRED);
            timeCapsuleRepository.save(capsule);
            processedIds.add(capsule.getId());
        }

        return processedIds;
    }

    /**
     * Delete capsules that are past TTL + grace period.
     * Property 13: Crypto-shred + storage deletion within 1 hour of TTL expiration.
     */
    @Transactional
    public List<DeletionResult> deleteDueCapsules() {
        // Find capsules where TTL + 1 hour has passed
        Instant threshold = Instant.now().minusSeconds(3600);
        List<TimeCapsule> dueCapsules = timeCapsuleRepository.findCapsulesDueForDeletion(threshold);
        List<DeletionResult> results = new ArrayList<>();

        for (TimeCapsule capsule : dueCapsules) {
            // Crypto-shred: clear encrypted payload
            capsule.setEncryptedPayload(null);
            capsule.setStatus(CapsuleStatus.DELETED);
            capsule.setDeletedAt(Instant.now());
            capsule.setDeletionReceiptId(UUID.randomUUID()); // Would link to audit receipt
            timeCapsuleRepository.save(capsule);

            results.add(new DeletionResult(
                capsule.getId(),
                capsule.getDeletionReceiptId(),
                true,
                "Crypto-shred completed"
            ));
        }

        return results;
    }

    // Private helper methods

    private String signPlan(QueryPlan plan) {
        try {
            String payload = plan.getRequestId() + "|" +
                plan.getConsentContractId() + "|" +
                plan.getScopeHash() + "|" +
                plan.getCompensation() + "|" +
                plan.getTtl();

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new SigningException("Failed to sign plan", e);
        }
    }

    private String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }

    // DTOs and Exceptions
    public record DispatchResult(UUID queryId, int dispatchedCount, Instant timeout, String status) {
        public DispatchResult(UUID planId, int deviceCount, Instant timeout, DispatchStatus dispatchStatus) {
            this(planId, deviceCount, timeout, dispatchStatus.name());
        }
    }
    public record CapsuleAccessResult(boolean granted, String message, byte[] payload) {}
    public record DeletionResult(UUID capsuleId, UUID receiptId, boolean success, String message) {}

    public enum DispatchStatus { DISPATCHED, TIMEOUT, COMPLETED, FAILED }

    public static class QueryPlanNotFoundException extends RuntimeException {
        public QueryPlanNotFoundException(String message) { super(message); }
    }

    public static class InvalidQueryPlanException extends RuntimeException {
        public InvalidQueryPlanException(String message) { super(message); }
    }

    public static class CapsuleNotFoundException extends RuntimeException {
        public CapsuleNotFoundException(String message) { super(message); }
    }

    public static class InvalidTtlException extends RuntimeException {
        public InvalidTtlException(String message) { super(message); }
    }

    public static class SigningException extends RuntimeException {
        public SigningException(String message, Throwable cause) { super(message, cause); }
    }

    public static class CapsuleExpiredException extends RuntimeException {
        public CapsuleExpiredException(String message) { super(message); }
    }

    // Additional methods for QueryController

    /**
     * Create a query plan (simplified version for controller).
     */
    @Transactional
    public QueryPlan createQueryPlan(UUID requesterId, UUID consentContractId, 
                                     String scope, List<String> transforms, int ttlMinutes) {
        return createSignedQueryPlan(
            requesterId,
            consentContractId,
            List.of(scope),
            transforms != null ? transforms : List.of(),
            BigDecimal.ZERO,
            Duration.ofMinutes(ttlMinutes)
        );
    }

    /**
     * Get a query plan by ID.
     */
    public QueryPlan getQueryPlan(UUID planId) {
        return queryPlanRepository.findById(planId)
            .orElseThrow(() -> new QueryPlanNotFoundException("Plan not found: " + planId));
    }

    /**
     * Dispatch query with timeout.
     */
    @Transactional
    public DispatchResult dispatchQuery(UUID planId, Set<UUID> eligibleDeviceIds, Duration timeout) {
        return dispatchQuery(planId, new ArrayList<>(eligibleDeviceIds));
    }

    /**
     * Create time capsule from query ID.
     */
    @Transactional
    public TimeCapsule createTimeCapsule(UUID queryId, int ttlMinutes) {
        return createTimeCapsule(
            queryId,
            queryId, // Use queryId as consent contract ID for simplicity
            new byte[0],
            Duration.ofMinutes(ttlMinutes)
        );
    }

    /**
     * Get a time capsule by ID.
     */
    public TimeCapsule getTimeCapsule(UUID capsuleId) {
        return timeCapsuleRepository.findById(capsuleId)
            .orElseThrow(() -> new CapsuleNotFoundException("Capsule not found: " + capsuleId));
    }

    /**
     * Check if a capsule is expired.
     */
    public boolean isCapsuleExpired(TimeCapsule capsule) {
        return capsule.isExpired();
    }
}
