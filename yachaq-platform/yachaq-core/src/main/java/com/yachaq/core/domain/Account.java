package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Account - Base entity for all account types in the YACHAQ platform.
 * 
 * Supports multiple account types:
 * - DS-IND: Individual Data Sovereign
 * - DS-COMP: Company Data Sovereign (fleet management)
 * - DS-ORG: Organization Data Sovereign
 * - RQ-COM: Commercial Requester
 * - RQ-AR: Academic/Research Requester
 * - RQ-NGO: Non-Profit/Foundation Requester
 * 
 * Validates: Requirements 225.1, 225.2, 225.3, 226.1, 226.2, 227.1, 227.2
 */
@Entity
@Table(name = "accounts", indexes = {
    @Index(name = "idx_account_email", columnList = "email"),
    @Index(name = "idx_account_type", columnList = "account_type"),
    @Index(name = "idx_account_status", columnList = "status")
})
public class Account {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "organization_name")
    private String organizationName;

    @Column(name = "kyb_verified")
    private boolean kybVerified;

    @Column(name = "kyb_verification_hash")
    private String kybVerificationHash;

    @Column(name = "max_devices")
    private Integer maxDevices;

    @Column(name = "governance_constraints")
    private String governanceConstraints;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Account types supported by the platform.
     */
    public enum AccountType {
        // Data Sovereign types
        DS_IND,   // Individual Data Sovereign
        DS_COMP,  // Company Data Sovereign (fleet management)
        DS_ORG,   // Organization Data Sovereign
        
        // Requester types
        RQ_COM,   // Commercial Requester
        RQ_AR,    // Academic/Research Requester
        RQ_NGO    // Non-Profit/Foundation Requester
    }

    public enum AccountStatus {
        PENDING,      // Awaiting verification
        ACTIVE,       // Fully active
        SUSPENDED,    // Temporarily suspended
        BANNED        // Permanently banned
    }

    protected Account() {}

    private Account(AccountType accountType) {
        this.id = UUID.randomUUID();
        this.accountType = accountType;
        this.status = AccountStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.maxDevices = getDefaultMaxDevices(accountType);
    }


    /**
     * Returns default max devices based on account type.
     * DS-IND: 5 devices, DS-COMP/DS-ORG: 100 devices (fleet management)
     */
    private static int getDefaultMaxDevices(AccountType accountType) {
        return switch (accountType) {
            case DS_IND -> 5;
            case DS_COMP, DS_ORG -> 100;
            case RQ_COM, RQ_AR, RQ_NGO -> 10;
        };
    }

    /**
     * Creates a new Individual Data Sovereign account.
     */
    public static Account createDSIndividual(String email, String displayName) {
        Account account = new Account(AccountType.DS_IND);
        account.email = email;
        account.displayName = displayName;
        return account;
    }

    /**
     * Creates a new Company Data Sovereign account.
     * Requirement 225.1: Support company DS accounts with fleet management.
     */
    public static Account createDSCompany(String email, String displayName, String organizationName) {
        Account account = new Account(AccountType.DS_COMP);
        account.email = email;
        account.displayName = displayName;
        account.organizationName = organizationName;
        return account;
    }

    /**
     * Creates a new Organization Data Sovereign account.
     * Requirement 225.2: Support organization DS accounts.
     */
    public static Account createDSOrganization(String email, String displayName, String organizationName) {
        Account account = new Account(AccountType.DS_ORG);
        account.email = email;
        account.displayName = displayName;
        account.organizationName = organizationName;
        return account;
    }

    /**
     * Creates a new Commercial Requester account.
     */
    public static Account createRequesterCommercial(String email, String displayName, String organizationName) {
        Account account = new Account(AccountType.RQ_COM);
        account.email = email;
        account.displayName = displayName;
        account.organizationName = organizationName;
        return account;
    }

    /**
     * Creates a new Academic/Research Requester account.
     * Requirement 226.1: Support academic/research requester workflows.
     */
    public static Account createRequesterAcademic(String email, String displayName, String organizationName) {
        Account account = new Account(AccountType.RQ_AR);
        account.email = email;
        account.displayName = displayName;
        account.organizationName = organizationName;
        return account;
    }

    /**
     * Creates a new NGO/Foundation Requester account.
     * Requirement 227.1: Support NGO/Foundation governance constraints.
     */
    public static Account createRequesterNGO(String email, String displayName, String organizationName, String governanceConstraints) {
        Account account = new Account(AccountType.RQ_NGO);
        account.email = email;
        account.displayName = displayName;
        account.organizationName = organizationName;
        account.governanceConstraints = governanceConstraints;
        return account;
    }

    /**
     * Activates the account after verification.
     */
    public void activate() {
        if (this.status != AccountStatus.PENDING) {
            throw new IllegalStateException("Can only activate PENDING accounts");
        }
        this.status = AccountStatus.ACTIVE;
        this.verifiedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Completes KYB verification for company/organization accounts.
     * Requirement 225.3: KYB verification for DS-COMP/DS-ORG.
     */
    public void completeKYBVerification(String verificationHash) {
        if (!isEnterpriseAccount()) {
            throw new IllegalStateException("KYB verification only applies to enterprise accounts");
        }
        this.kybVerified = true;
        this.kybVerificationHash = verificationHash;
        this.updatedAt = Instant.now();
    }

    /**
     * Suspends the account.
     */
    public void suspend() {
        if (this.status == AccountStatus.BANNED) {
            throw new IllegalStateException("Cannot suspend BANNED accounts");
        }
        this.status = AccountStatus.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    /**
     * Bans the account permanently.
     */
    public void ban() {
        this.status = AccountStatus.BANNED;
        this.updatedAt = Instant.now();
    }

    /**
     * Reactivates a suspended account.
     */
    public void reactivate() {
        if (this.status != AccountStatus.SUSPENDED) {
            throw new IllegalStateException("Can only reactivate SUSPENDED accounts");
        }
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if this is a Data Sovereign account.
     */
    public boolean isDataSovereign() {
        return accountType == AccountType.DS_IND || 
               accountType == AccountType.DS_COMP || 
               accountType == AccountType.DS_ORG;
    }

    /**
     * Checks if this is a Requester account.
     */
    public boolean isRequester() {
        return accountType == AccountType.RQ_COM || 
               accountType == AccountType.RQ_AR || 
               accountType == AccountType.RQ_NGO;
    }

    /**
     * Checks if this is an enterprise account (company or organization).
     */
    public boolean isEnterpriseAccount() {
        return accountType == AccountType.DS_COMP || 
               accountType == AccountType.DS_ORG;
    }

    /**
     * Checks if the account is active.
     */
    public boolean isActive() {
        return this.status == AccountStatus.ACTIVE;
    }

    /**
     * Checks if the account can perform actions.
     */
    public boolean canPerformActions() {
        return this.status == AccountStatus.ACTIVE;
    }

    /**
     * Updates the max devices limit (for fleet management).
     * Requirement 225.2: Fleet management for multiple devices.
     */
    public void setMaxDevices(int maxDevices) {
        if (maxDevices < 1) {
            throw new IllegalArgumentException("Max devices must be at least 1");
        }
        this.maxDevices = maxDevices;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public AccountType getAccountType() { return accountType; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public AccountStatus getStatus() { return status; }
    public String getOrganizationName() { return organizationName; }
    public boolean isKybVerified() { return kybVerified; }
    public String getKybVerificationHash() { return kybVerificationHash; }
    public Integer getMaxDevices() { return maxDevices; }
    public String getGovernanceConstraints() { return governanceConstraints; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }

    // Setters for mutable fields
    public void setEmail(String email) {
        this.email = email;
        this.updatedAt = Instant.now();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void setGovernanceConstraints(String governanceConstraints) {
        this.governanceConstraints = governanceConstraints;
        this.updatedAt = Instant.now();
    }
}
