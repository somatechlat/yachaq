package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for requester tier assignments.
 * Replaces raw SQL in RequesterGovernanceService.
 */
@Entity
@Table(name = "requester_tiers", indexes = {
    @Index(name = "idx_rt_requester", columnList = "requester_id")
})
public class RequesterTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_level", nullable = false)
    private VerificationLevel verificationLevel;

    @Column(name = "max_budget", precision = 19, scale = 4)
    private BigDecimal maxBudget;

    @Column(name = "allowed_products", columnDefinition = "TEXT")
    private String allowedProducts;

    @Column(name = "export_allowed")
    private boolean exportAllowed;

    @NotNull
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected RequesterTier() {}

    public static RequesterTier create(UUID requesterId, Tier tier, VerificationLevel verificationLevel,
                                        BigDecimal maxBudget, String allowedProducts, boolean exportAllowed) {
        RequesterTier rt = new RequesterTier();
        rt.requesterId = requesterId;
        rt.tier = tier;
        rt.verificationLevel = verificationLevel;
        rt.maxBudget = maxBudget;
        rt.allowedProducts = allowedProducts;
        rt.exportAllowed = exportAllowed;
        rt.assignedAt = Instant.now();
        return rt;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public Tier getTier() { return tier; }
    public VerificationLevel getVerificationLevel() { return verificationLevel; }
    public BigDecimal getMaxBudget() { return maxBudget; }
    public String getAllowedProducts() { return allowedProducts; }
    public boolean isExportAllowed() { return exportAllowed; }
    public Instant getAssignedAt() { return assignedAt; }

    public enum Tier {
        BASIC, STANDARD, PREMIUM, ENTERPRISE
    }

    public enum VerificationLevel {
        NONE, EMAIL, PHONE, KYC, KYB
    }
}
