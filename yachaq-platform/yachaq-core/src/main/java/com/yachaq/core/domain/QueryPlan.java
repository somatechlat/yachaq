package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Query Plan - Signed execution plan specifying what may be computed/accessed.
 * 
 * Property 15: Query Plan Signature Verification
 * Validates: Requirements 216.1, 216.2
 * 
 * For any query plan created by the platform, signing the plan and then
 * verifying the signature must succeed; and for any tampered plan,
 * verification must fail.
 */
@Entity
@Table(name = "query_plans")
public class QueryPlan {

    @Id
    private UUID id;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "consent_contract_id", nullable = false)
    private UUID consentContractId;

    @Column(name = "scope_hash", nullable = false)
    private String scopeHash;

    @Column(name = "allowed_transforms", columnDefinition = "TEXT")
    private String allowedTransforms; // JSON array of allowed transforms

    @Column(name = "output_restrictions", columnDefinition = "TEXT")
    private String outputRestrictions; // JSON array of restrictions

    /**
     * JSON array of permitted fields from consent contract.
     * Property 17: Field-Level Access Enforcement
     * Validates: Requirements 219.1, 219.2
     */
    @Column(name = "permitted_fields", columnDefinition = "TEXT")
    private String permittedFields;

    @Column(name = "compensation", nullable = false, precision = 19, scale = 4)
    private BigDecimal compensation;

    @Column(name = "ttl", nullable = false)
    private Instant ttl;

    @Column(name = "signature", nullable = false, length = 512)
    private String signature;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt;

    @Column(name = "signing_key_id", nullable = false)
    private String signingKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    public enum PlanStatus {
        PENDING,
        SIGNED,
        DISPATCHED,
        EXECUTED,
        EXPIRED,
        REJECTED
    }

    public QueryPlan() {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.status = PlanStatus.PENDING;
    }

    // Check if plan has expired
    public boolean isExpired() {
        return Instant.now().isAfter(ttl);
    }

    // Check if plan is valid for execution
    public boolean isValidForExecution() {
        return status == PlanStatus.SIGNED && !isExpired();
    }

    /**
     * Generates the canonical payload for signing.
     * Includes: scope, transforms, compensation, TTL, requestId, consentContractId
     * Requirements 216.3, 216.4, 216.5
     */
    public String getSignablePayload() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(id != null ? id.toString() : "");
        sb.append("|requestId=").append(requestId != null ? requestId.toString() : "");
        sb.append("|consentContractId=").append(consentContractId != null ? consentContractId.toString() : "");
        sb.append("|scopeHash=").append(scopeHash != null ? scopeHash : "");
        sb.append("|allowedTransforms=").append(allowedTransforms != null ? allowedTransforms : "");
        sb.append("|outputRestrictions=").append(outputRestrictions != null ? outputRestrictions : "");
        sb.append("|permittedFields=").append(permittedFields != null ? permittedFields : "");
        sb.append("|compensation=").append(compensation != null ? compensation.toPlainString() : "");
        sb.append("|ttl=").append(ttl != null ? ttl.toString() : "");
        return sb.toString();
    }

    /**
     * Signs the query plan with the provided private key.
     * Requirements 216.1: Sign query plans with platform key
     * 
     * @param privateKey Platform's private key for signing
     * @param keyId Identifier for the signing key
     * @throws SignatureException if signing fails
     */
    public void sign(PrivateKey privateKey, String keyId) throws SignatureException {
        if (privateKey == null) {
            throw new IllegalArgumentException("Private key cannot be null");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (status == PlanStatus.SIGNED) {
            throw new IllegalStateException("Query plan is already signed");
        }

        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(getSignablePayload().getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();
            
            this.signature = Base64.getEncoder().encodeToString(signatureBytes);
            this.signingKeyId = keyId;
            this.signedAt = Instant.now();
            this.status = PlanStatus.SIGNED;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SignatureException("Failed to sign query plan: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies the query plan signature with the provided public key.
     * Requirements 216.2, 216.6, 216.8: Verify signature before execution
     * 
     * @param publicKey Platform's public key for verification
     * @return VerificationResult containing success status and any failure reason
     */
    public VerificationResult verify(PublicKey publicKey) {
        if (publicKey == null) {
            return new VerificationResult(false, "Public key cannot be null");
        }
        if (signature == null || signature.isBlank()) {
            return new VerificationResult(false, "Query plan is not signed");
        }
        if (isExpired()) {
            return new VerificationResult(false, "Query plan has expired");
        }

        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(getSignablePayload().getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = Base64.getDecoder().decode(signature);
            
            boolean valid = sig.verify(signatureBytes);
            if (valid) {
                return new VerificationResult(true, null);
            } else {
                return new VerificationResult(false, "Signature verification failed - possible tampering");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | java.security.SignatureException e) {
            return new VerificationResult(false, "Verification error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return new VerificationResult(false, "Invalid signature format: " + e.getMessage());
        }
    }

    /**
     * Marks the plan as rejected (failed verification).
     */
    public void reject(String reason) {
        this.status = PlanStatus.REJECTED;
    }

    /**
     * Marks the plan as executed.
     */
    public void markExecuted() {
        if (status != PlanStatus.SIGNED && status != PlanStatus.DISPATCHED) {
            throw new IllegalStateException("Cannot execute plan in status: " + status);
        }
        this.status = PlanStatus.EXECUTED;
        this.executedAt = Instant.now();
    }

    /**
     * Marks the plan as dispatched to device.
     */
    public void markDispatched() {
        if (status != PlanStatus.SIGNED) {
            throw new IllegalStateException("Cannot dispatch plan in status: " + status);
        }
        this.status = PlanStatus.DISPATCHED;
    }

    /**
     * Result of signature verification.
     */
    public record VerificationResult(boolean valid, String failureReason) {
        public boolean isValid() { return valid; }
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public UUID getConsentContractId() { return consentContractId; }
    public void setConsentContractId(UUID consentContractId) { this.consentContractId = consentContractId; }

    public String getScopeHash() { return scopeHash; }
    public void setScopeHash(String scopeHash) { this.scopeHash = scopeHash; }

    public String getAllowedTransforms() { return allowedTransforms; }
    public void setAllowedTransforms(String allowedTransforms) { this.allowedTransforms = allowedTransforms; }

    public String getOutputRestrictions() { return outputRestrictions; }
    public void setOutputRestrictions(String outputRestrictions) { this.outputRestrictions = outputRestrictions; }

    public String getPermittedFields() { return permittedFields; }
    public void setPermittedFields(String permittedFields) { this.permittedFields = permittedFields; }

    public BigDecimal getCompensation() { return compensation; }
    public void setCompensation(BigDecimal compensation) { this.compensation = compensation; }

    public Instant getTtl() { return ttl; }
    public void setTtl(Instant ttl) { this.ttl = ttl; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }

    public String getSigningKeyId() { return signingKeyId; }
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
