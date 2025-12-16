package com.yachaq.api.account;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.Account;
import com.yachaq.core.domain.Account.AccountStatus;
import com.yachaq.core.domain.Account.AccountType;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Account Service - Manages all account types and their lifecycle.
 * 
 * Supports:
 * - DS-IND, DS-COMP, DS-ORG (Data Sovereign accounts)
 * - RQ-COM, RQ-AR, RQ-NGO (Requester accounts)
 * 
 * Validates: Requirements 225.1, 225.2, 225.3, 226.1, 226.2, 227.1, 227.2
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository, AuditService auditService) {
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /**
     * Creates a new Individual Data Sovereign account.
     */
    @Transactional
    public Account createDSIndividual(String email, String displayName) {
        validateEmailNotExists(email);
        Account account = Account.createDSIndividual(email, displayName);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }

    /**
     * Creates a new Company Data Sovereign account.
     * Requirement 225.1: Support company DS accounts with fleet management.
     */
    @Transactional
    public Account createDSCompany(String email, String displayName, String organizationName) {
        validateEmailNotExists(email);
        if (organizationName == null || organizationName.isBlank()) {
            throw new IllegalArgumentException("Organization name is required for DS-COMP accounts");
        }
        Account account = Account.createDSCompany(email, displayName, organizationName);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }

    /**
     * Creates a new Organization Data Sovereign account.
     * Requirement 225.2: Support organization DS accounts.
     */
    @Transactional
    public Account createDSOrganization(String email, String displayName, String organizationName) {
        validateEmailNotExists(email);
        if (organizationName == null || organizationName.isBlank()) {
            throw new IllegalArgumentException("Organization name is required for DS-ORG accounts");
        }
        Account account = Account.createDSOrganization(email, displayName, organizationName);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }

    /**
     * Creates a new Commercial Requester account.
     */
    @Transactional
    public Account createRequesterCommercial(String email, String displayName, String organizationName) {
        validateEmailNotExists(email);
        Account account = Account.createRequesterCommercial(email, displayName, organizationName);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }

    /**
     * Creates a new Academic/Research Requester account.
     * Requirement 226.1: Support academic/research requester workflows.
     */
    @Transactional
    public Account createRequesterAcademic(String email, String displayName, String organizationName) {
        validateEmailNotExists(email);
        Account account = Account.createRequesterAcademic(email, displayName, organizationName);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }

    /**
     * Creates a new NGO/Foundation Requester account.
     * Requirement 227.1: Support NGO/Foundation governance constraints.
     */
    @Transactional
    public Account createRequesterNGO(String email, String displayName, String organizationName, String governanceConstraints) {
        validateEmailNotExists(email);
        if (governanceConstraints == null || governanceConstraints.isBlank()) {
            throw new IllegalArgumentException("Governance constraints are required for RQ-NGO accounts");
        }
        Account account = Account.createRequesterNGO(email, displayName, organizationName, governanceConstraints);
        account = accountRepository.save(account);
        auditAccountCreation(account);
        return account;
    }


    /**
     * Completes KYB verification for enterprise accounts.
     * Requirement 225.3: KYB verification for DS-COMP/DS-ORG.
     */
    @Transactional
    public Account completeKYBVerification(UUID accountId, String verificationHash) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        if (!account.isEnterpriseAccount()) {
            throw new InvalidAccountTypeException("KYB verification only applies to DS-COMP and DS-ORG accounts");
        }
        
        account.completeKYBVerification(verificationHash);
        account = accountRepository.save(account);
        
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                accountId,
                AuditReceipt.ActorType.SYSTEM,
                accountId,
                "Account",
                "kyb_verified:" + verificationHash.substring(0, 8));
        
        return account;
    }

    /**
     * Activates an account after verification.
     */
    @Transactional
    public Account activateAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        // Enterprise accounts require KYB verification before activation
        if (account.isEnterpriseAccount() && !account.isKybVerified()) {
            throw new IllegalStateException("Enterprise accounts require KYB verification before activation");
        }
        
        account.activate();
        account = accountRepository.save(account);
        
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                accountId,
                AuditReceipt.ActorType.SYSTEM,
                accountId,
                "Account",
                "status:ACTIVE");
        
        return account;
    }

    /**
     * Suspends an account.
     */
    @Transactional
    public Account suspendAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        account.suspend();
        account = accountRepository.save(account);
        
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                accountId,
                AuditReceipt.ActorType.SYSTEM,
                accountId,
                "Account",
                "status:SUSPENDED");
        
        return account;
    }

    /**
     * Bans an account permanently.
     */
    @Transactional
    public Account banAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        account.ban();
        account = accountRepository.save(account);
        
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                accountId,
                AuditReceipt.ActorType.SYSTEM,
                accountId,
                "Account",
                "status:BANNED");
        
        return account;
    }

    /**
     * Updates fleet device limit for enterprise accounts.
     * Requirement 225.2: Fleet management for multiple devices.
     */
    @Transactional
    public Account updateFleetLimit(UUID accountId, int maxDevices) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        if (!account.isEnterpriseAccount()) {
            throw new InvalidAccountTypeException("Fleet management only applies to DS-COMP and DS-ORG accounts");
        }
        
        account.setMaxDevices(maxDevices);
        account = accountRepository.save(account);
        
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                accountId,
                AuditReceipt.ActorType.SYSTEM,
                accountId,
                "Account",
                "max_devices:" + maxDevices);
        
        return account;
    }

    /**
     * Checks if an account can add more devices (fleet management).
     */
    public boolean canAddDevice(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));
        
        long currentDevices = accountRepository.countDevicesByAccountId(accountId);
        return currentDevices < account.getMaxDevices();
    }

    /**
     * Gets an account by ID.
     */
    public Optional<Account> getAccount(UUID accountId) {
        return accountRepository.findById(accountId);
    }

    /**
     * Gets an account by email.
     */
    public Optional<Account> getAccountByEmail(String email) {
        return accountRepository.findByEmail(email);
    }

    /**
     * Gets all accounts by type.
     */
    public List<Account> getAccountsByType(AccountType accountType) {
        return accountRepository.findByAccountType(accountType);
    }

    /**
     * Gets all Data Sovereign accounts.
     */
    public List<Account> getAllDataSovereigns() {
        return accountRepository.findAllDataSovereigns();
    }

    /**
     * Gets all Requester accounts.
     */
    public List<Account> getAllRequesters() {
        return accountRepository.findAllRequesters();
    }

    /**
     * Gets enterprise accounts pending KYB verification.
     */
    public List<Account> getEnterpriseAccountsPendingKYB() {
        return accountRepository.findEnterpriseAccountsPendingKYB();
    }

    private void validateEmailNotExists(String email) {
        if (accountRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email already registered: " + email);
        }
    }

    private void auditAccountCreation(Account account) {
        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_CREATED,
                account.getId(),
                AuditReceipt.ActorType.DS,
                account.getId(),
                "Account",
                "type:" + account.getAccountType().name());
    }
}
