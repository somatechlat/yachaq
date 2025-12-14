package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Policy rule for screening engine.
 * Rules are loaded from database and evaluated against requests.
 * 
 * Validates: Requirements 6.1, 6.2
 */
@Entity
@Table(name = "policy_rules", indexes = {
    @Index(name = "idx_policy_rule_type", columnList = "rule_type"),
    @Index(name = "idx_policy_rule_category", columnList = "rule_category"),
    @Index(name = "idx_policy_rule_active", columnList = "is_active")
})
public class PolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "rule_code", nullable = false, unique = true)
    private String ruleCode;

    @NotNull
    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @NotNull
    @Column(name = "rule_description", nullable = false, columnDefinition = "TEXT")
    private String ruleDescription;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    @NotNull
    @Column(name = "rule_category", nullable = false)
    private String ruleCategory;

    @NotNull
    @Column(name = "rule_expression", nullable = false, columnDefinition = "TEXT")
    private String ruleExpression;

    @NotNull
    @Column(nullable = false)
    private Integer severity;

    @NotNull
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @NotNull
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PolicyRule() {}

    // Getters
    public UUID getId() { return id; }
    public String getRuleCode() { return ruleCode; }
    public String getRuleName() { return ruleName; }
    public String getRuleDescription() { return ruleDescription; }
    public RuleType getRuleType() { return ruleType; }
    public String getRuleCategory() { return ruleCategory; }
    public String getRuleExpression() { return ruleExpression; }
    public Integer getSeverity() { return severity; }
    public Boolean getIsActive() { return isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isBlocking() {
        return ruleType == RuleType.BLOCKING;
    }

    public enum RuleType {
        BLOCKING,   // Causes immediate rejection
        WARNING,    // Adds to risk score, may trigger manual review
        INFO        // Informational only
    }
}
