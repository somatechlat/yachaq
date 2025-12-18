package com.yachaq.api.consent;

import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.DSProfile.DSAccountType;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.DSProfileRepository;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Consent Engine.
 * Uses example-based testing with generated data due to Spring context requirements.
 * 
 * **Feature: yachaq-platform, Property 1: Consent Contract Creation Completeness**
 * **Validates: Requirements 3.1**
 * 
 * **Feature: yachaq-platform, Property 2: Revocation SLA Enforcement**
 * **Validates: Requirements 3.4, 197.1**
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentServicePropertyTest {

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ConsentContractRepository consentRepository;

    @Autowired
    private AuditReceiptRepository auditRepository;

    @Autowired
    private DSProfileRepository dsProfileRepository;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        consentRepository.deleteAll();
        // Note: DS profiles are cleaned up per iteration to avoid FK constraint issues
    }


    /**
     * **Feature: yachaq-platform, Property 1: Consent Contract Creation Completeness**
     * 
     * *For any* valid consent grant request with DS, requester, scope, purpose, 
     * duration, and price, creating a consent contract should result in a contract 
     * object containing all specified fields and an associated audit receipt.
     * 
     * **Validates: Requirements 3.1**
     */
    @org.junit.jupiter.api.Test
    void property1_consentCreation_containsAllFieldsAndAuditReceipt() {
        // Run 100 iterations with random data
        for (int i = 0; i < 100; i++) {
            ConsentService.ConsentRequest request = generateValidConsentRequest();
            
            // Create consent
            ConsentService.ConsentResult result = consentService.createConsent(request);
            
            // Verify result contains all required fields
            assertNotNull(result.contractId(), "Contract ID must not be null");
            assertNotNull(result.status(), "Status must not be null");
            assertNotNull(result.blockchainAnchorHash(), "Blockchain anchor hash must not be null");
            assertNotNull(result.auditReceiptId(), "Audit receipt ID must not be null");
            assertNotNull(result.createdAt(), "Created timestamp must not be null");
            
            // Verify contract was persisted with all fields
            ConsentContract contract = consentRepository.findById(result.contractId()).orElseThrow();
            assertEquals(request.dsId(), contract.getDsId(), "DS ID must match");
            assertEquals(request.requesterId(), contract.getRequesterId(), "Requester ID must match");
            assertEquals(request.requestId(), contract.getRequestId(), "Request ID must match");
            assertEquals(request.scopeHash(), contract.getScopeHash(), "Scope hash must match");
            assertEquals(request.purposeHash(), contract.getPurposeHash(), "Purpose hash must match");
            assertEquals(request.durationStart(), contract.getDurationStart(), "Duration start must match");
            assertEquals(request.durationEnd(), contract.getDurationEnd(), "Duration end must match");
            assertEquals(0, request.compensationAmount().compareTo(contract.getCompensationAmount()), 
                    "Compensation amount must match");
            assertEquals(ConsentContract.ConsentStatus.ACTIVE, contract.getStatus(), "Status must be ACTIVE");
            
            // Verify audit receipt was created
            assertTrue(auditRepository.findById(result.auditReceiptId()).isPresent(), 
                    "Audit receipt must exist");
            
            // Clean up for next iteration (only consent-related data, not DS profiles)
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    /**
     * **Feature: yachaq-platform, Property 2: Revocation SLA Enforcement**
     * 
     * *For any* consent revocation request, all future access attempts using that 
     * consent must be blocked within 60 seconds of the revocation timestamp.
     * 
     * **Validates: Requirements 3.4, 197.1**
     */
    @org.junit.jupiter.api.Test
    void property2_revocationSLA_blocksAccessWithin60Seconds() {
        // Run 100 iterations with random data
        for (int i = 0; i < 100; i++) {
            ConsentService.ConsentRequest request = generateValidConsentRequest();
            
            // Create consent first
            ConsentService.ConsentResult createResult = consentService.createConsent(request);
            UUID contractId = createResult.contractId();
            
            // Verify access is permitted before revocation
            assertTrue(consentService.evaluateAccess(contractId, "test-fields-hash"),
                    "Access should be permitted before revocation");
            
            // Revoke consent
            ConsentService.RevocationResult revokeResult = consentService.revokeConsent(contractId, request.dsId());
            
            // Verify SLA compliance
            assertTrue(revokeResult.slaCompliant(), 
                    "Revocation must complete within 60 seconds SLA");
            assertTrue(revokeResult.processingTimeMs() < 60000, 
                    "Processing time must be under 60 seconds");
            
            // Verify access is blocked after revocation
            assertFalse(consentService.evaluateAccess(contractId, "test-fields-hash"),
                    "Access must be blocked after revocation");
            
            // Verify contract status is REVOKED
            ConsentContract contract = consentRepository.findById(contractId).orElseThrow();
            assertEquals(ConsentContract.ConsentStatus.REVOKED, contract.getStatus(),
                    "Contract status must be REVOKED");
            assertNotNull(contract.getRevokedAt(), "Revoked timestamp must be set");
            
            // Verify audit receipt was created for revocation
            assertTrue(auditRepository.findById(revokeResult.auditReceiptId()).isPresent(),
                    "Revocation audit receipt must exist");
            
            // Clean up for next iteration (only consent-related data, not DS profiles)
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    /**
     * Generates a valid consent request with random data.
     * Creates a DS profile in the database to satisfy foreign key constraints.
     */
    private ConsentService.ConsentRequest generateValidConsentRequest() {
        // Create DS profile first to satisfy foreign key constraint
        DSProfile profile = new DSProfile("test-ds-" + UUID.randomUUID(), DSAccountType.DS_IND);
        profile = dsProfileRepository.save(profile);
        UUID dsId = profile.getId();
        
        UUID requesterId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String scopeHash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String purposeHash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        
        long daysUntilStart = (long) (Math.random() * 30) + 1;
        long durationDays = (long) (Math.random() * 365) + 1;
        
        Instant start = Instant.now().plus(daysUntilStart, ChronoUnit.DAYS);
        Instant end = start.plus(durationDays, ChronoUnit.DAYS);
        
        BigDecimal amount = BigDecimal.valueOf(Math.random() * 1000 + 0.01)
                .setScale(4, java.math.RoundingMode.HALF_UP);
        
        return new ConsentService.ConsentRequest(
                dsId, requesterId, requestId, scopeHash, purposeHash, start, end, amount
        );
    }
}
