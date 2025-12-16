package com.yachaq.api.account;

import com.yachaq.core.domain.Account;
import com.yachaq.core.domain.Account.AccountStatus;
import com.yachaq.core.domain.Account.AccountType;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

/**
 * Property-based tests for Extended Account Types.
 * 
 * **Feature: yachaq-platform, Task 35: Extended Account Types**
 * Tests DS-IND, DS-COMP, DS-ORG, RQ-COM, RQ-AR, RQ-NGO account types.
 * 
 * **Validates: Requirements 225.1, 225.2, 225.3, 226.1, 226.2, 227.1, 227.2**
 */
class ExtendedAccountTypesPropertyTest {

    // ==================== DS Account Type Properties ====================

    /**
     * Property: DS-IND accounts are correctly created as individual Data Sovereigns.
     * **Validates: Requirements 225.1**
     */
    @Property(tries = 100)
    void dsIndividual_createdCorrectly(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName) {
        
        String fullEmail = email + "@test.com";
        Account account = Account.createDSIndividual(fullEmail, displayName);

        assert account.getId() != null : "Account ID should be generated";
        assert account.getAccountType() == AccountType.DS_IND : 
                "Account type should be DS_IND";
        assert account.getEmail().equals(fullEmail) : 
                "Email should match";
        assert account.getDisplayName().equals(displayName) : 
                "Display name should match";
        assert account.getStatus() == AccountStatus.PENDING : 
                "Initial status should be PENDING";
        assert account.isDataSovereign() : 
                "DS-IND should be identified as Data Sovereign";
        assert !account.isRequester() : 
                "DS-IND should NOT be identified as Requester";
        assert !account.isEnterpriseAccount() : 
                "DS-IND should NOT be enterprise account";
        assert account.getMaxDevices() == 5 : 
                "DS-IND default max devices should be 5";
    }

    /**
     * Property: DS-COMP accounts support fleet management with higher device limits.
     * **Validates: Requirements 225.1, 225.2**
     */
    @Property(tries = 100)
    void dsCompany_supportsFleetManagement(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName,
            @ForAll @AlphaChars @StringLength(min = 2, max = 100) String orgName) {
        
        String fullEmail = email + "@company.com";
        Account account = Account.createDSCompany(fullEmail, displayName, orgName);

        assert account.getAccountType() == AccountType.DS_COMP : 
                "Account type should be DS_COMP";
        assert account.getOrganizationName().equals(orgName) : 
                "Organization name should match";
        assert account.isDataSovereign() : 
                "DS-COMP should be identified as Data Sovereign";
        assert account.isEnterpriseAccount() : 
                "DS-COMP should be enterprise account";
        assert account.getMaxDevices() == 100 : 
                "DS-COMP default max devices should be 100 (fleet management)";
        assert !account.isKybVerified() : 
                "New DS-COMP should not be KYB verified";
    }

    /**
     * Property: DS-ORG accounts support organization data sovereignty.
     * **Validates: Requirements 225.2**
     */
    @Property(tries = 100)
    void dsOrganization_createdCorrectly(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName,
            @ForAll @AlphaChars @StringLength(min = 2, max = 100) String orgName) {
        
        String fullEmail = email + "@org.com";
        Account account = Account.createDSOrganization(fullEmail, displayName, orgName);

        assert account.getAccountType() == AccountType.DS_ORG : 
                "Account type should be DS_ORG";
        assert account.getOrganizationName().equals(orgName) : 
                "Organization name should match";
        assert account.isDataSovereign() : 
                "DS-ORG should be identified as Data Sovereign";
        assert account.isEnterpriseAccount() : 
                "DS-ORG should be enterprise account";
        assert account.getMaxDevices() == 100 : 
                "DS-ORG default max devices should be 100";
    }

    // ==================== KYB Verification Properties ====================

    /**
     * Property: Enterprise accounts (DS-COMP, DS-ORG) can complete KYB verification.
     * **Validates: Requirements 225.3**
     */
    @Property(tries = 100)
    void enterpriseAccounts_canCompleteKYBVerification(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 64, max = 64) String verificationHash) {
        
        Account account = Account.createDSCompany(email + "@company.com", "Test", "TestCorp");
        
        assert !account.isKybVerified() : "Should not be KYB verified initially";
        
        account.completeKYBVerification(verificationHash);
        
        assert account.isKybVerified() : "Should be KYB verified after completion";
        assert account.getKybVerificationHash().equals(verificationHash) : 
                "Verification hash should match";
    }

    /**
     * Property: Non-enterprise accounts cannot complete KYB verification.
     * **Validates: Requirements 225.3**
     */
    @Property(tries = 50)
    void nonEnterpriseAccounts_cannotCompleteKYB(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email) {
        
        Account account = Account.createDSIndividual(email + "@test.com", "Test");
        
        try {
            account.completeKYBVerification("somehash");
            assert false : "Should throw exception for non-enterprise account";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("enterprise") : 
                    "Exception should mention enterprise accounts";
        }
    }

    // ==================== Requester Account Type Properties ====================

    /**
     * Property: RQ-COM accounts are correctly created as commercial requesters.
     */
    @Property(tries = 100)
    void rqCommercial_createdCorrectly(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName,
            @ForAll @AlphaChars @StringLength(min = 2, max = 100) String orgName) {
        
        String fullEmail = email + "@business.com";
        Account account = Account.createRequesterCommercial(fullEmail, displayName, orgName);

        assert account.getAccountType() == AccountType.RQ_COM : 
                "Account type should be RQ_COM";
        assert account.isRequester() : 
                "RQ-COM should be identified as Requester";
        assert !account.isDataSovereign() : 
                "RQ-COM should NOT be identified as Data Sovereign";
        assert account.getMaxDevices() == 10 : 
                "RQ-COM default max devices should be 10";
    }

    /**
     * Property: RQ-AR accounts support academic/research workflows.
     * **Validates: Requirements 226.1, 226.2**
     */
    @Property(tries = 100)
    void rqAcademic_supportsResearchWorkflows(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName,
            @ForAll @AlphaChars @StringLength(min = 2, max = 100) String orgName) {
        
        String fullEmail = email + "@university.edu";
        Account account = Account.createRequesterAcademic(fullEmail, displayName, orgName);

        assert account.getAccountType() == AccountType.RQ_AR : 
                "Account type should be RQ_AR";
        assert account.isRequester() : 
                "RQ-AR should be identified as Requester";
        assert account.getOrganizationName().equals(orgName) : 
                "Organization name should match";
    }

    /**
     * Property: RQ-NGO accounts enforce governance constraints.
     * **Validates: Requirements 227.1, 227.2**
     */
    @Property(tries = 100)
    void rqNGO_enforcesGovernanceConstraints(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String displayName,
            @ForAll @AlphaChars @StringLength(min = 2, max = 100) String orgName,
            @ForAll @AlphaChars @StringLength(min = 10, max = 200) String constraints) {
        
        String fullEmail = email + "@ngo.org";
        Account account = Account.createRequesterNGO(fullEmail, displayName, orgName, constraints);

        assert account.getAccountType() == AccountType.RQ_NGO : 
                "Account type should be RQ_NGO";
        assert account.isRequester() : 
                "RQ-NGO should be identified as Requester";
        assert account.getGovernanceConstraints().equals(constraints) : 
                "Governance constraints should match";
    }


    // ==================== Account Lifecycle Properties ====================

    /**
     * Property: Accounts start in PENDING status and can be activated.
     */
    @Property(tries = 100)
    void accountLifecycle_pendingToActive(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email) {
        
        Account account = Account.createDSIndividual(email + "@test.com", "Test");
        
        assert account.getStatus() == AccountStatus.PENDING : 
                "Initial status should be PENDING";
        assert !account.isActive() : 
                "PENDING account should not be active";
        
        account.activate();
        
        assert account.getStatus() == AccountStatus.ACTIVE : 
                "Status should be ACTIVE after activation";
        assert account.isActive() : 
                "Account should be active";
        assert account.canPerformActions() : 
                "Active account should be able to perform actions";
        assert account.getVerifiedAt() != null : 
                "Verified timestamp should be set";
    }

    /**
     * Property: Active accounts can be suspended and reactivated.
     */
    @Property(tries = 100)
    void accountLifecycle_suspendAndReactivate(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email) {
        
        Account account = Account.createDSIndividual(email + "@test.com", "Test");
        account.activate();
        
        account.suspend();
        
        assert account.getStatus() == AccountStatus.SUSPENDED : 
                "Status should be SUSPENDED";
        assert !account.isActive() : 
                "Suspended account should not be active";
        assert !account.canPerformActions() : 
                "Suspended account should not perform actions";
        
        account.reactivate();
        
        assert account.getStatus() == AccountStatus.ACTIVE : 
                "Status should be ACTIVE after reactivation";
        assert account.isActive() : 
                "Reactivated account should be active";
    }

    /**
     * Property: Banned accounts cannot be reactivated.
     */
    @Property(tries = 50)
    void accountLifecycle_bannedIsPermanent(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email) {
        
        Account account = Account.createDSIndividual(email + "@test.com", "Test");
        account.activate();
        account.ban();
        
        assert account.getStatus() == AccountStatus.BANNED : 
                "Status should be BANNED";
        assert !account.isActive() : 
                "Banned account should not be active";
        
        try {
            account.suspend();
            assert false : "Should not be able to suspend banned account";
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    // ==================== Fleet Management Properties ====================

    /**
     * Property: Fleet device limits can be updated for enterprise accounts.
     * **Validates: Requirements 225.2**
     */
    @Property(tries = 100)
    void fleetManagement_deviceLimitsUpdatable(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @IntRange(min = 1, max = 1000) int newLimit) {
        
        Account account = Account.createDSCompany(email + "@company.com", "Test", "TestCorp");
        
        account.setMaxDevices(newLimit);
        
        assert account.getMaxDevices() == newLimit : 
                "Max devices should be updated to " + newLimit;
    }

    /**
     * Property: Device limit must be at least 1.
     */
    @Property(tries = 50)
    void fleetManagement_deviceLimitMinimum(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email,
            @ForAll @IntRange(min = -100, max = 0) int invalidLimit) {
        
        Account account = Account.createDSCompany(email + "@company.com", "Test", "TestCorp");
        
        try {
            account.setMaxDevices(invalidLimit);
            assert false : "Should reject device limit < 1";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("at least 1") : 
                    "Exception should mention minimum limit";
        }
    }

    // ==================== Account Type Classification Properties ====================

    /**
     * Property: All DS account types are correctly classified as Data Sovereigns.
     */
    @Property(tries = 50)
    void classification_allDSTypesAreDataSovereigns() {
        Account dsInd = Account.createDSIndividual("ind@test.com", "Ind");
        Account dsComp = Account.createDSCompany("comp@test.com", "Comp", "Corp");
        Account dsOrg = Account.createDSOrganization("org@test.com", "Org", "OrgName");
        
        assert dsInd.isDataSovereign() : "DS-IND should be Data Sovereign";
        assert dsComp.isDataSovereign() : "DS-COMP should be Data Sovereign";
        assert dsOrg.isDataSovereign() : "DS-ORG should be Data Sovereign";
        
        assert !dsInd.isRequester() : "DS-IND should not be Requester";
        assert !dsComp.isRequester() : "DS-COMP should not be Requester";
        assert !dsOrg.isRequester() : "DS-ORG should not be Requester";
    }

    /**
     * Property: All RQ account types are correctly classified as Requesters.
     */
    @Property(tries = 50)
    void classification_allRQTypesAreRequesters() {
        Account rqCom = Account.createRequesterCommercial("com@test.com", "Com", "Corp");
        Account rqAr = Account.createRequesterAcademic("ar@test.com", "AR", "Uni");
        Account rqNgo = Account.createRequesterNGO("ngo@test.com", "NGO", "Foundation", "constraints");
        
        assert rqCom.isRequester() : "RQ-COM should be Requester";
        assert rqAr.isRequester() : "RQ-AR should be Requester";
        assert rqNgo.isRequester() : "RQ-NGO should be Requester";
        
        assert !rqCom.isDataSovereign() : "RQ-COM should not be Data Sovereign";
        assert !rqAr.isDataSovereign() : "RQ-AR should not be Data Sovereign";
        assert !rqNgo.isDataSovereign() : "RQ-NGO should not be Data Sovereign";
    }

    /**
     * Property: Only DS-COMP and DS-ORG are enterprise accounts.
     */
    @Property(tries = 50)
    void classification_onlyCompAndOrgAreEnterprise() {
        Account dsInd = Account.createDSIndividual("ind@test.com", "Ind");
        Account dsComp = Account.createDSCompany("comp@test.com", "Comp", "Corp");
        Account dsOrg = Account.createDSOrganization("org@test.com", "Org", "OrgName");
        Account rqCom = Account.createRequesterCommercial("rq@test.com", "RQ", "Corp");
        
        assert !dsInd.isEnterpriseAccount() : "DS-IND should not be enterprise";
        assert dsComp.isEnterpriseAccount() : "DS-COMP should be enterprise";
        assert dsOrg.isEnterpriseAccount() : "DS-ORG should be enterprise";
        assert !rqCom.isEnterpriseAccount() : "RQ-COM should not be enterprise";
    }

    // ==================== Unique ID Properties ====================

    /**
     * Property: Each account gets a unique UUID.
     */
    @Property(tries = 100)
    void uniqueIds_eachAccountGetsUniqueId(
            @ForAll @IntRange(min = 2, max = 10) int count) {
        
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        
        for (int i = 0; i < count; i++) {
            Account account = Account.createDSIndividual("user" + i + "@test.com", "User" + i);
            ids.add(account.getId());
        }
        
        assert ids.size() == count : 
                "All accounts should have unique IDs. Expected " + count + ", got " + ids.size();
    }

    /**
     * Property: Account timestamps are set correctly on creation.
     */
    @Property(tries = 50)
    void timestamps_setCorrectlyOnCreation(
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String email) {
        
        java.time.Instant before = java.time.Instant.now();
        Account account = Account.createDSIndividual(email + "@test.com", "Test");
        java.time.Instant after = java.time.Instant.now();
        
        assert account.getCreatedAt() != null : "Created timestamp should be set";
        assert account.getUpdatedAt() != null : "Updated timestamp should be set";
        assert !account.getCreatedAt().isBefore(before) : 
                "Created timestamp should not be before test start";
        assert !account.getCreatedAt().isAfter(after) : 
                "Created timestamp should not be after test end";
    }
}
