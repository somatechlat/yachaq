package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
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
