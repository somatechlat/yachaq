package com.yachaq.api.obligation;

import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.ConsentObligation;
import com.yachaq.core.domain.ConsentObligation.EnforcementLevel;
import com.yachaq.core.domain.ConsentObligation.ObligationStatus;
import com.yachaq.core.domain.ConsentObligation.ObligationType;
import com.yachaq.core.domain.ObligationViolation;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.ConsentObligationRepository;
import com.yachaq.core.repository.ObligationViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Consent Obligation Service.
 * 
 * **Feature: yachaq-platform, Property 23: Consent Obligation Specification**
 * *For any* consent contract granted, the contract must include data handling 
 * obligations specifying retention limits, usage restrictions, and deletion requirements.
 * **Validates: Requirements 223.1**
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentObligationPropertyTest {

    @Autowired
    private ConsentObligationService obligationService;

    @Autowired
    private ConsentContractRepository consentRepository;

    @Autowired
    private ConsentObligationRepository obligationRepository;

    @Autowired
    private ObligationViolationRepository violationRepository;

    @Autowired
    private AuditReceiptRepository auditRepository;

    private final Random random = new Random();

    @BeforeEach
    void setUp() {
        violationRepository.deleteAll();
        obligationRepository.deleteAll();
        auditRepository.deleteAll();
        consentRepository.deleteAll();
    }

    /**
     * **Feature: yachaq-platform, Property 23: Consent Obligation Specification**
     * 
     * *For any* consent contract granted, the contract must include data handling 
     * obligations specifying retention limits, usage restrictions, and deletion requirements.
     * 
     * **Validates: Requirements 223.1**
     */
    @Test
    void property23_consentContractMustIncludeAllRequiredObligations() {
        // Run 100 iterations with random data
        for (int i = 0; i < 100; i++) {
            // Create a consent contract
            ConsentContract contract = createTestContract();
            contract = consentRepository.save(contract);
            UUID contractId = contract.getId();

            // Generate random but valid obligation specification
            ConsentObligationService.ObligationSpecification spec = generateValidObligationSpec();

            // Create obligations for the contract
            ConsentObligationService.ObligationResult result = obligationService.createObligations(contractId, spec);

            // Property 23: Contract must include all required obligations
            assertTrue(result.success(), "Obligation creation must succeed");
            assertNotNull(result.obligationHash(), "Obligation hash must be generated");
            assertEquals(3, result.obligationIds().size(), "Must create exactly 3 required obligations");

            // Verify contract has all required obligation fields
            ConsentContract updatedContract = consentRepository.findById(contractId).orElseThrow();
            assertTrue(updatedContract.hasRequiredObligations(), 
                    "Contract must have all required obligation fields");
            
            // Verify retention limit is set
            assertNotNull(updatedContract.getRetentionDays(), "Retention days must be set");
            assertTrue(updatedContract.getRetentionDays() > 0, "Retention days must be positive");
            assertNotNull(updatedContract.getRetentionPolicy(), "Retention policy must be set");
            
            // Verify usage restrictions are set
            assertNotNull(updatedContract.getUsageRestrictions(), "Usage restrictions must be set");
            assertFalse(updatedContract.getUsageRestrictions().isBlank(), "Usage restrictions must not be blank");
            
            // Verify deletion requirements are set
            assertNotNull(updatedContract.getDeletionRequirements(), "Deletion requirements must be set");
            assertFalse(updatedContract.getDeletionRequirements().isBlank(), "Deletion requirements must not be blank");
            
            // Verify obligation hash for integrity
            assertNotNull(updatedContract.getObligationHash(), "Obligation hash must be set");
            assertEquals(64, updatedContract.getObligationHash().length(), "Obligation hash must be SHA-256 (64 hex chars)");

            // Verify obligation entities were created
            List<ConsentObligation> obligations = obligationRepository.findByConsentContractId(contractId);
            assertEquals(3, obligations.size(), "Must have 3 obligation entities");

            // Verify each required obligation type exists
            assertTrue(obligationRepository.hasRetentionLimit(contractId), 
                    "Must have RETENTION_LIMIT obligation");
            assertTrue(obligationRepository.hasUsageRestriction(contractId), 
                    "Must have USAGE_RESTRICTION obligation");
            assertTrue(obligationRepository.hasDeletionRequirement(contractId), 
                    "Must have DELETION_REQUIREMENT obligation");

            // Verify audit receipt was created
            assertNotNull(result.auditReceiptId(), "Audit receipt must be created");
            assertTrue(auditRepository.findById(result.auditReceiptId()).isPresent(), 
                    "Audit receipt must exist in database");

            // Clean up for next iteration
            violationRepository.deleteAll();
            obligationRepository.deleteAll();
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    /**
     * Property: Obligation validation must reject incomplete specifications.
     * 
     * *For any* obligation specification missing required fields, creation must fail.
     */
    @Test
    void property_incompleteObligationSpecificationMustBeRejected() {
        for (int i = 0; i < 50; i++) {
            ConsentContract contract = createTestContract();
            contract = consentRepository.save(contract);
            UUID contractId = contract.getId();

            // Test missing retention days
            assertThrows(ConsentObligationService.InvalidObligationException.class, () -> {
                obligationService.createObligations(contractId, new ConsentObligationService.ObligationSpecification(
                        null, // Missing retention days
                        "DELETE_AFTER_PERIOD",
                        EnforcementLevel.STRICT,
                        "[\"no_marketing\"]",
                        EnforcementLevel.MONITORED,
                        "{\"method\":\"crypto_shred\"}",
                        EnforcementLevel.STRICT
                ));
            }, "Must reject null retention days");

            // Test missing usage restrictions
            assertThrows(ConsentObligationService.InvalidObligationException.class, () -> {
                obligationService.createObligations(contractId, new ConsentObligationService.ObligationSpecification(
                        30,
                        "DELETE_AFTER_PERIOD",
                        EnforcementLevel.STRICT,
                        null, // Missing usage restrictions
                        EnforcementLevel.MONITORED,
                        "{\"method\":\"crypto_shred\"}",
                        EnforcementLevel.STRICT
                ));
            }, "Must reject null usage restrictions");

            // Test missing deletion requirements
            assertThrows(ConsentObligationService.InvalidObligationException.class, () -> {
                obligationService.createObligations(contractId, new ConsentObligationService.ObligationSpecification(
                        30,
                        "DELETE_AFTER_PERIOD",
                        EnforcementLevel.STRICT,
                        "[\"no_marketing\"]",
                        EnforcementLevel.MONITORED,
                        null, // Missing deletion requirements
                        EnforcementLevel.STRICT
                ));
            }, "Must reject null deletion requirements");

            // Clean up
            consentRepository.deleteAll();
        }
    }

    /**
     * Property: Violation detection must identify breaches of obligations.
     * 
     * *For any* active obligation that is breached, a violation must be detected and recorded.
     * **Validates: Requirements 223.3**
     */
    @Test
    void property_violationDetectionMustIdentifyBreaches() {
        for (int i = 0; i < 50; i++) {
            // Create contract with obligations
            ConsentContract contract = createTestContract();
            contract = consentRepository.save(contract);
            UUID contractId = contract.getId();

            int maxRetentionDays = random.nextInt(90) + 30; // 30-120 days
            ConsentObligationService.ObligationSpecification spec = new ConsentObligationService.ObligationSpecification(
                    maxRetentionDays,
                    "DELETE_AFTER_PERIOD",
                    EnforcementLevel.STRICT,
                    "[\"no_marketing\",\"no_third_party\"]",
                    EnforcementLevel.MONITORED,
                    "{\"method\":\"crypto_shred\",\"verify\":true}",
                    EnforcementLevel.STRICT
            );
            obligationService.createObligations(contractId, spec);

            // Simulate retention violation (data retained longer than allowed)
            int actualRetainedDays = maxRetentionDays + random.nextInt(30) + 1; // Exceeds limit
            ConsentObligationService.ViolationCheckContext context = new ConsentObligationService.ViolationCheckContext(
                    UUID.randomUUID(),
                    actualRetainedDays,
                    maxRetentionDays,
                    false,
                    null,
                    false,
                    false
            );

            List<ObligationViolation> violations = obligationService.detectViolations(contractId, context);

            // Verify violation was detected
            assertFalse(violations.isEmpty(), "Violation must be detected when retention exceeded");
            
            ObligationViolation violation = violations.get(0);
            assertEquals(ObligationViolation.ViolationType.RETENTION_EXCEEDED, violation.getViolationType(),
                    "Violation type must be RETENTION_EXCEEDED");
            assertNotNull(violation.getDescription(), "Violation must have description");
            assertNotNull(violation.getEvidenceHash(), "Violation must have evidence hash");
            assertNotNull(violation.getAuditReceiptId(), "Violation must have audit receipt");

            // Verify obligation status was updated
            List<ConsentObligation> obligations = obligationRepository.findByConsentContractId(contractId);
            boolean hasViolatedObligation = obligations.stream()
                    .anyMatch(o -> o.getStatus() == ObligationStatus.VIOLATED);
            assertTrue(hasViolatedObligation, "At least one obligation must be marked as violated");

            // Clean up
            violationRepository.deleteAll();
            obligationRepository.deleteAll();
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    /**
     * Property: Penalty enforcement must be recorded with audit trail.
     * 
     * *For any* violation with penalty applied, the penalty must be recorded and audited.
     * **Validates: Requirements 223.4**
     */
    @Test
    void property_penaltyEnforcementMustBeRecordedWithAudit() {
        for (int i = 0; i < 50; i++) {
            // Create contract with obligations
            ConsentContract contract = createTestContract();
            contract = consentRepository.save(contract);
            UUID contractId = contract.getId();

            ConsentObligationService.ObligationSpecification spec = generateValidObligationSpec();
            obligationService.createObligations(contractId, spec);

            // Create a violation
            ConsentObligationService.ViolationCheckContext context = new ConsentObligationService.ViolationCheckContext(
                    UUID.randomUUID(),
                    spec.retentionDays() + 10, // Exceeds limit
                    spec.retentionDays(),
                    false,
                    null,
                    false,
                    false
            );
            List<ObligationViolation> violations = obligationService.detectViolations(contractId, context);
            assertFalse(violations.isEmpty(), "Violation must be detected");

            UUID violationId = violations.get(0).getId();
            BigDecimal penaltyAmount = BigDecimal.valueOf(random.nextDouble() * 1000 + 10)
                    .setScale(4, java.math.RoundingMode.HALF_UP);

            // Apply penalty
            ConsentObligationService.PenaltyResult penaltyResult = obligationService.enforcePenalty(violationId, penaltyAmount);

            // Verify penalty was applied
            assertTrue(penaltyResult.success(), "Penalty application must succeed");
            assertEquals(penaltyAmount, penaltyResult.penaltyAmount(), "Penalty amount must match");
            assertNotNull(penaltyResult.auditReceiptId(), "Penalty must have audit receipt");

            // Verify violation record was updated
            ObligationViolation updatedViolation = violationRepository.findById(violationId).orElseThrow();
            assertTrue(updatedViolation.isPenaltyApplied(), "Penalty applied flag must be true");
            assertEquals(0, penaltyAmount.compareTo(updatedViolation.getPenaltyAmount()), 
                    "Penalty amount must be recorded");

            // Verify audit receipt exists
            assertTrue(auditRepository.findById(penaltyResult.auditReceiptId()).isPresent(),
                    "Penalty audit receipt must exist");

            // Verify penalty cannot be applied twice
            assertThrows(ConsentObligationService.PenaltyAlreadyAppliedException.class, () -> {
                obligationService.enforcePenalty(violationId, penaltyAmount);
            }, "Must reject duplicate penalty application");

            // Clean up
            violationRepository.deleteAll();
            obligationRepository.deleteAll();
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    /**
     * Property: Contract validation must correctly identify missing obligations.
     */
    @Test
    void property_contractValidationMustIdentifyMissingObligations() {
        for (int i = 0; i < 50; i++) {
            // Create contract WITHOUT obligations
            ConsentContract contract = createTestContract();
            contract = consentRepository.save(contract);
            UUID contractId = contract.getId();

            // Validation should fail - no obligations set
            assertFalse(obligationService.validateContractObligations(contractId),
                    "Validation must fail for contract without obligations");

            // Now add obligations
            ConsentObligationService.ObligationSpecification spec = generateValidObligationSpec();
            obligationService.createObligations(contractId, spec);

            // Validation should now pass
            assertTrue(obligationService.validateContractObligations(contractId),
                    "Validation must pass for contract with all required obligations");

            // Clean up
            obligationRepository.deleteAll();
            auditRepository.deleteAll();
            consentRepository.deleteAll();
        }
    }

    private ConsentContract createTestContract() {
        UUID dsId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String scopeHash = generateRandomHash();
        String purposeHash = generateRandomHash();
        
        Instant start = Instant.now().plus(random.nextInt(30) + 1, ChronoUnit.DAYS);
        Instant end = start.plus(random.nextInt(365) + 1, ChronoUnit.DAYS);
        BigDecimal amount = BigDecimal.valueOf(random.nextDouble() * 1000 + 0.01)
                .setScale(4, java.math.RoundingMode.HALF_UP);

        return ConsentContract.create(dsId, requesterId, requestId, scopeHash, purposeHash, start, end, amount);
    }

    private ConsentObligationService.ObligationSpecification generateValidObligationSpec() {
        int retentionDays = random.nextInt(365) + 1;
        String[] policies = {"DELETE_AFTER_USE", "DELETE_AFTER_PERIOD", "DELETE_ON_REVOCATION", "DELETE_ON_REQUEST"};
        String retentionPolicy = policies[random.nextInt(policies.length)];
        
        EnforcementLevel[] levels = EnforcementLevel.values();
        
        String[] usageRestrictions = {
            "[\"no_marketing\"]",
            "[\"no_third_party\",\"no_profiling\"]",
            "[\"research_only\",\"no_commercial\"]",
            "[\"internal_use_only\"]"
        };
        
        String[] deletionRequirements = {
            "{\"method\":\"crypto_shred\"}",
            "{\"method\":\"overwrite\",\"passes\":3}",
            "{\"method\":\"both\",\"verify\":true}",
            "{\"method\":\"crypto_shred\",\"notify\":true}"
        };

        return new ConsentObligationService.ObligationSpecification(
                retentionDays,
                retentionPolicy,
                levels[random.nextInt(levels.length)],
                usageRestrictions[random.nextInt(usageRestrictions.length)],
                levels[random.nextInt(levels.length)],
                deletionRequirements[random.nextInt(deletionRequirements.length)],
                levels[random.nextInt(levels.length)]
        );
    }

    private String generateRandomHash() {
        return UUID.randomUUID().toString().replace("-", "") + 
               UUID.randomUUID().toString().replace("-", "");
    }
}
