package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Policy rule definition for screening.
 */
@Entity
@Table(name = "policy_rules", indexes = {
        @Index(name = "idx_policy_rule_active", columnList = "is_active"),
        @Index(name = "idx_policy_rule_category", columnList = "rule_category")
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

    public String getRuleCode() { return ruleCode; }
    public RuleType getRuleType() { return ruleType; }
    public Integer getSeverity() { return severity; }
    public Boolean getIsActive() { return isActive; }
    public String getRuleCategory() { return ruleCategory; }

    public boolean isBlocking() {
        return ruleType == RuleType.BLOCKING;
    }

    public enum RuleType {
        BLOCKING,
        WARNING,
        INFO
    }
}
