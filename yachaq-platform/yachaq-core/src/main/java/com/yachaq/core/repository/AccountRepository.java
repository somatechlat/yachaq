package com.yachaq.core.repository;

import com.yachaq.core.domain.Account;
import com.yachaq.core.domain.Account.AccountStatus;
import com.yachaq.core.domain.Account.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Account entities.
 * 
 * Validates: Requirements 225.1, 225.2, 225.3, 226.1, 226.2, 227.1, 227.2
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Finds an account by email.
     */
    Optional<Account> findByEmail(String email);

    /**
     * Checks if an email is already registered.
     */
    boolean existsByEmail(String email);

    /**
     * Finds accounts by type.
     */
    List<Account> findByAccountType(AccountType accountType);

    /**
     * Finds accounts by status.
     */
    List<Account> findByStatus(AccountStatus status);

    /**
     * Finds accounts by type and status.
     */
    List<Account> findByAccountTypeAndStatus(AccountType accountType, AccountStatus status);

    /**
     * Finds all Data Sovereign accounts.
     */
    @Query("SELECT a FROM Account a WHERE a.accountType IN ('DS_IND', 'DS_COMP', 'DS_ORG')")
    List<Account> findAllDataSovereigns();

    /**
     * Finds all Requester accounts.
     */
    @Query("SELECT a FROM Account a WHERE a.accountType IN ('RQ_COM', 'RQ_AR', 'RQ_NGO')")
    List<Account> findAllRequesters();

    /**
     * Finds enterprise accounts (DS-COMP, DS-ORG) that need KYB verification.
     */
    @Query("SELECT a FROM Account a WHERE a.accountType IN ('DS_COMP', 'DS_ORG') AND a.kybVerified = false")
    List<Account> findEnterpriseAccountsPendingKYB();

    /**
     * Counts devices for an account (used for fleet management limits).
     */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.dsId = :accountId")
    long countDevicesByAccountId(@Param("accountId") UUID accountId);
}
