package com.yachaq.api.query;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.QueryPlan.PlanStatus;
import com.yachaq.core.domain.QueryPlan.VerificationResult;
import com.yachaq.core.repository.QueryPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Service for Query Plan signing and verification.
 * Implements cryptographic signing of query plans with platform key.
 * 
 * Property 15: Query Plan Signature Verification
 * Validates: Requirements 216.1, 216.2, 216.3, 216.4, 216.5, 216.6, 216.8
 * 
 * For any query plan created by the platform, signing the plan and then
 * verifying the signature must succeed; and for any tampered plan,
 * verification must fail.
 */
@Service
public class QueryPlanSecurityService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanSecurityService.class);
    private static final String ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final long DEFAULT_TTL_HOURS = 24;

    private final QueryPlanRepository queryPlanRepository;
    private final AuditService auditService;
    private final KeyPair platformKeyPair;
    private final String keyId;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryPlanSecurityService(
            QueryPlanRepository queryPlanRepository,
            AuditService auditService,
            @Value("${yachaq.security.platform-key-id:platform-key-v1}") String keyId) {
        this.queryPlanRepository = queryPlanRepository;
        this.auditService = auditService;
        this.keyId = keyId;
        this.platformKeyPair = generateKeyPair();
    }

    /**
     * Constructor for testing with provided key pair.
     */
    public QueryPlanSecurityService(
            QueryPlanRepository queryPlanRepository,
            AuditService auditService,
            KeyPair keyPair,
            String keyId) {
        this.queryPlanRepository = queryPlanRepository;
        this.auditService = auditService;
        this.platformKeyPair = keyPair;
        this.keyId = keyId;
    }


    /**
     * Creates and signs a new query plan.
     * Requirements 216.1, 216.3, 216.4, 216.5: Sign with platform key,
     * include scope, transforms, compensation, and TTL.
     * 
     * @param requestId Associated request ID
     * @param consentContractId Associated consent contract ID
     * @param scopeHash Hash of permitted fields/scope
     * @param allowedTransforms JSON array of allowed transforms
     * @param outputRestrictions JSON array of output restrictions
     * @param compensation Compensation amount for this query
     * @param ttlHours Time-to-live in hours (null for default)
     * @return Signed query plan
     */
    @Transactional
    public QueryPlan createAndSignPlan(
            UUID requestId,
            UUID consentContractId,
            String scopeHash,
            String allowedTransforms,
            String outputRestrictions,
            BigDecimal compensation,
            Long ttlHours) {
        
        if (requestId == null) {
            throw new IllegalArgumentException("Request ID cannot be null");
        }
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }
        if (scopeHash == null || scopeHash.isBlank()) {
            throw new IllegalArgumentException("Scope hash cannot be null or blank");
        }
        if (compensation == null || compensation.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Compensation must be non-negative");
        }

        QueryPlan plan = new QueryPlan();
        plan.setRequestId(requestId);
        plan.setConsentContractId(consentContractId);
        plan.setScopeHash(scopeHash);
        plan.setAllowedTransforms(allowedTransforms);
        plan.setOutputRestrictions(outputRestrictions);
        plan.setCompensation(compensation);
        
        long ttl = ttlHours != null ? ttlHours : DEFAULT_TTL_HOURS;
        plan.setTtl(Instant.now().plus(ttl, ChronoUnit.HOURS));

        try {
            plan.sign(platformKeyPair.getPrivate(), keyId);
            QueryPlan savedPlan = queryPlanRepository.save(plan);

            // Generate audit receipt
            auditService.appendReceipt(
                    AuditReceipt.EventType.QUERY_PLAN_SIGNED,
                    requestId,
                    AuditReceipt.ActorType.SYSTEM,
                    savedPlan.getId(),
                    "QUERY_PLAN",
                    sha256("plan_id=" + savedPlan.getId() + 
                           ",scope_hash=" + scopeHash +
                           ",key_id=" + keyId)
            );

            log.info("Created and signed query plan {} for request {}", 
                    savedPlan.getId(), requestId);

            return savedPlan;

        } catch (SignatureException e) {
            log.error("Failed to sign query plan for request {}", requestId, e);
            throw new QueryPlanSigningException("Failed to sign query plan: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a query plan signature.
     * Requirements 216.2, 216.6, 216.8: Verify signature before execution,
     * reject tampered or expired plans.
     * 
     * @param planId Query plan ID to verify
     * @return Verification result
     */
    @Transactional
    public VerificationResult verifyPlan(UUID planId) {
        QueryPlan plan = queryPlanRepository.findById(planId)
                .orElseThrow(() -> new QueryPlanNotFoundException("Query plan not found: " + planId));

        return verifyPlan(plan);
    }

    /**
     * Verifies a query plan signature.
     * 
     * @param plan Query plan to verify
     * @return Verification result
     */
    @Transactional
    public VerificationResult verifyPlan(QueryPlan plan) {
        if (plan == null) {
            return new VerificationResult(false, "Query plan cannot be null");
        }

        VerificationResult result = plan.verify(platformKeyPair.getPublic());

        // Generate audit receipt
        AuditReceipt.EventType eventType = result.isValid() 
                ? AuditReceipt.EventType.QUERY_PLAN_VERIFIED 
                : AuditReceipt.EventType.QUERY_PLAN_REJECTED;

        auditService.appendReceipt(
                eventType,
                plan.getRequestId(),
                AuditReceipt.ActorType.SYSTEM,
                plan.getId(),
                "QUERY_PLAN",
                sha256("plan_id=" + plan.getId() + 
                       ",verified=" + result.isValid() +
                       ",reason=" + (result.failureReason() != null ? result.failureReason() : ""))
        );

        if (result.isValid()) {
            log.info("Query plan {} verified successfully", plan.getId());
        } else {
            log.warn("Query plan {} verification failed: {}", plan.getId(), result.failureReason());
            plan.reject(result.failureReason());
            queryPlanRepository.save(plan);
        }

        return result;
    }

    /**
     * Verifies a query plan on device before execution.
     * This method is called by devices to verify the plan they received.
     * 
     * @param plan Query plan received by device
     * @param publicKeyBase64 Platform's public key in Base64 format
     * @return Verification result
     */
    public VerificationResult verifyPlanOnDevice(QueryPlan plan, String publicKeyBase64) {
        if (plan == null) {
            return new VerificationResult(false, "Query plan cannot be null");
        }
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            return new VerificationResult(false, "Public key cannot be null or blank");
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            return plan.verify(publicKey);

        } catch (Exception e) {
            log.error("Failed to verify plan on device", e);
            return new VerificationResult(false, "Verification error: " + e.getMessage());
        }
    }

    /**
     * Gets the platform's public key in Base64 format.
     * Devices use this to verify query plan signatures.
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(
                platformKeyPair.getPublic().getEncoded());
    }

    /**
     * Dispatches a verified plan to device.
     * 
     * @param planId Query plan ID
     * @return Dispatched plan
     */
    @Transactional
    public QueryPlan dispatchPlan(UUID planId) {
        QueryPlan plan = queryPlanRepository.findById(planId)
                .orElseThrow(() -> new QueryPlanNotFoundException("Query plan not found: " + planId));

        if (!plan.isValidForExecution()) {
            throw new QueryPlanInvalidException(
                    "Plan is not valid for execution. Status: " + plan.getStatus() + 
                    ", Expired: " + plan.isExpired());
        }

        plan.markDispatched();
        QueryPlan savedPlan = queryPlanRepository.save(plan);

        log.info("Dispatched query plan {} to device", planId);

        return savedPlan;
    }

    /**
     * Marks a plan as executed after successful device execution.
     * 
     * @param planId Query plan ID
     * @return Executed plan
     */
    @Transactional
    public QueryPlan markPlanExecuted(UUID planId) {
        QueryPlan plan = queryPlanRepository.findById(planId)
                .orElseThrow(() -> new QueryPlanNotFoundException("Query plan not found: " + planId));

        plan.markExecuted();
        QueryPlan savedPlan = queryPlanRepository.save(plan);

        log.info("Marked query plan {} as executed", planId);

        return savedPlan;
    }

    /**
     * Expires all plans past their TTL.
     * 
     * @return Number of plans expired
     */
    @Transactional
    public int expireOldPlans() {
        var expiredPlans = queryPlanRepository.findExpiredPlans(Instant.now());
        int count = 0;
        
        for (QueryPlan plan : expiredPlans) {
            plan.setStatus(PlanStatus.EXPIRED);
            queryPlanRepository.save(plan);
            count++;
        }

        if (count > 0) {
            log.info("Expired {} query plans", count);
        }

        return count;
    }

    /**
     * Generates a new RSA key pair for signing.
     * In production, this would load from secure key storage.
     */
    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(KEY_SIZE, new SecureRandom());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate key pair", e);
        }
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

    // Exceptions
    public static class QueryPlanNotFoundException extends RuntimeException {
        public QueryPlanNotFoundException(String message) { super(message); }
    }

    public static class QueryPlanSigningException extends RuntimeException {
        public QueryPlanSigningException(String message, Throwable cause) { super(message, cause); }
    }

    public static class QueryPlanInvalidException extends RuntimeException {
        public QueryPlanInvalidException(String message) { super(message); }
    }
}
