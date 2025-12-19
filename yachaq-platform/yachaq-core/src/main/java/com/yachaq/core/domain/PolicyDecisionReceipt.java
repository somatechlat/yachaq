package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entity for policy decision receipts - audit trail for policy evaluations.
 * Replaces raw SQL in FailClosedPolicyService and PrivacyGovernorService.
 */
@Entity
@Table(name = "policy_decision_receipts", indexes = {
    @Index(name = "idx_pdr_requester", columnList = "requester_id"),
    @Index(name = "idx_pdr_campaign", columnList = "campaign_id"),
    @Index(name = "idx_pdr_created", columnList = "created_at")
})
public class PolicyDecisionReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "decision_type", nullable = false)
    private String decisionType;

    @NotNull
    @Column(nullable = false)
    private String decision;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "requester_id")
    private UUID requesterId;

    @Column(name = "reason_codes", columnDefinition = "TEXT")
    private String reasonCodes;

    @NotNull
    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PolicyDecisionReceipt() {}

    public static PolicyDecisionReceipt create(String decisionType, String decision, UUID campaignId,
                                                UUID requesterId, List<String> reasonCodes, String policyVersion) {
        PolicyDecisionReceipt receipt = new PolicyDecisionReceipt();
        receipt.decisionType = decisionType;
        receipt.decision = decision;
        receipt.campaignId = campaignId;
        receipt.requesterId = requesterId;
        receipt.reasonCodes = reasonCodes != null ? String.join(",", reasonCodes) : "";
        receipt.policyVersion = policyVersion;
        receipt.createdAt = Instant.now();
        return receipt;
    }

    // Getters
    public UUID getId() { return id; }
    public String getDecisionType() { return decisionType; }
    public String getDecision() { return decision; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getRequesterId() { return requesterId; }
    public String getReasonCodes() { return reasonCodes; }
    public String getPolicyVersion() { return policyVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
