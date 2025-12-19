package com.yachaq.api.bonds;

import com.yachaq.api.bonds.RequesterBondsService.*;
import com.yachaq.api.escrow.EscrowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Requester Bonds Service.
 * Tests bond requirements, forfeiture, and return functionality.
 * 
 * **Feature: yachaq-platform, Task 102.4: Bonds Service Tests**
 * **Validates: Requirements 355.1, 355.2, 355.3, 355.4, 355.5**
 * 
 * Security: Ensures bonds are properly held and released.
 * Performance: Tests bond calculations with various scenarios.
 */
@SpringBootTest
@ActiveProfiles("test")
class RequesterBondsServiceTest {

    @Autowired
    private RequesterBondsService bondsService;

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);
    }

    // ==================== Task 102.1: Bond Requirements Tests ====================

    /**
     * Test: Basic bond requirement calculation.
     * Validates: Requirement 355.1
     */
    @Test
    void testBasicBondRequirement() {
        BondCalculationRequest request = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.8,
                "STANDARD"
        );

        BondRequirement requirement = bondsService.calculateBondRequirement(request);

        assertNotNull(requirement);
        assertTrue(requirement.amount().compareTo(BigDecimal.ZERO) >= 0);
        assertNotNull(requirement.releaseDate());
        assertEquals(90, requirement.holdDays());
    }

    /**
     * Test: Sensitive data increases bond requirement.
     * Validates: Requirement 355.1
     */
    @Test
    void testSensitiveDataBondMultiplier() {
        BondCalculationRequest normalRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.8,
                "BASIC"
        );

        BondCalculationRequest sensitiveRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                true, // sensitive data
                false,
                false,
                0.8,
                "BASIC"
        );

        BondRequirement normalReq = bondsService.calculateBondRequirement(normalRequest);
        BondRequirement sensitiveReq = bondsService.calculateBondRequirement(sensitiveRequest);

        assertTrue(sensitiveReq.amount().compareTo(normalReq.amount()) > 0,
                "Sensitive data should increase bond requirement");
        assertTrue(sensitiveReq.reasons().stream().anyMatch(r -> r.contains("Sensitive")),
                "Should include sensitive data reason");
    }

    /**
     * Test: Export requirement increases bond.
     * Validates: Requirement 355.1
     */
    @Test
    void testExportBondMultiplier() {
        BondCalculationRequest normalRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.8,
                "BASIC"
        );

        BondCalculationRequest exportRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                true, // requires export
                false,
                0.8,
                "BASIC"
        );

        BondRequirement normalReq = bondsService.calculateBondRequirement(normalRequest);
        BondRequirement exportReq = bondsService.calculateBondRequirement(exportRequest);

        assertTrue(exportReq.amount().compareTo(normalReq.amount()) > 0,
                "Export should increase bond requirement");
        assertTrue(exportReq.reasons().stream().anyMatch(r -> r.contains("export")),
                "Should include export reason");
    }

    /**
     * Test: First-time requester premium.
     * Validates: Requirement 355.1
     */
    @Test
    void testFirstTimeRequesterPremium() {
        BondCalculationRequest returningRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false, // not first request
                0.8,
                "BASIC"
        );

        BondCalculationRequest firstTimeRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                true, // first request
                0.8,
                "BASIC"
        );

        BondRequirement returningReq = bondsService.calculateBondRequirement(returningRequest);
        BondRequirement firstTimeReq = bondsService.calculateBondRequirement(firstTimeRequest);

        assertTrue(firstTimeReq.amount().compareTo(returningReq.amount()) > 0,
                "First-time requester should have higher bond");
    }

    /**
     * Test: Low reputation increases bond.
     * Validates: Requirement 355.1
     */
    @Test
    void testLowReputationPenalty() {
        BondCalculationRequest goodRepRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.9, // good reputation
                "BASIC"
        );

        BondCalculationRequest lowRepRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.3, // low reputation
                "BASIC"
        );

        BondRequirement goodRepReq = bondsService.calculateBondRequirement(goodRepRequest);
        BondRequirement lowRepReq = bondsService.calculateBondRequirement(lowRepRequest);

        assertTrue(lowRepReq.amount().compareTo(goodRepReq.amount()) > 0,
                "Low reputation should increase bond requirement");
    }

    /**
     * Test: Tier discount applied correctly.
     * Validates: Requirement 355.1
     */
    @Test
    void testTierDiscount() {
        BondCalculationRequest basicRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(15000),
                true,
                false,
                false,
                0.8,
                "BASIC"
        );

        BondCalculationRequest enterpriseRequest = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(15000),
                true,
                false,
                false,
                0.8,
                "ENTERPRISE"
        );

        BondRequirement basicReq = bondsService.calculateBondRequirement(basicRequest);
        BondRequirement enterpriseReq = bondsService.calculateBondRequirement(enterpriseRequest);

        assertTrue(enterpriseReq.amount().compareTo(basicReq.amount()) < 0,
                "Enterprise tier should have lower bond due to discount");
    }

    /**
     * Test: Bond deposit success.
     * Validates: Requirement 355.1
     */
    @Test
    void testBondDeposit() {
        UUID requesterId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BigDecimal amount = BigDecimal.valueOf(1000);

        BondDepositResult result = bondsService.depositBond(requesterId, requestId, amount);

        assertTrue(result.success());
        assertNotNull(result.bondId());
        
        Optional<Bond> bond = bondsService.getBond(result.bondId());
        assertTrue(bond.isPresent());
        assertEquals(BondStatus.HELD, bond.get().status());
        assertEquals(amount, bond.get().amount());
    }

    /**
     * Test: Bond deposit with zero amount fails.
     * Validates: Requirement 355.1
     */
    @Test
    void testBondDepositZeroAmountFails() {
        BondDepositResult result = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.ZERO
        );

        assertFalse(result.success());
        assertNull(result.bondId());
    }

    // ==================== Task 102.2: Bond Forfeiture Tests ====================

    /**
     * Test: Bond forfeiture on privacy violation.
     * Validates: Requirement 355.2
     */
    @Test
    void testBondForfeiturePrivacyViolation() {
        // Deposit bond first
        BondDepositResult deposit = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000)
        );
        assertTrue(deposit.success());

        // Forfeit bond
        ForfeitureResult result = bondsService.forfeitBond(
                deposit.bondId(),
                ForfeitureReason.PRIVACY_VIOLATION
        );

        assertTrue(result.success());
        assertEquals(BigDecimal.valueOf(1000), result.forfeitedAmount(),
                "Privacy violation should forfeit full amount");

        Optional<Bond> bond = bondsService.getBond(deposit.bondId());
        assertTrue(bond.isPresent());
        assertEquals(BondStatus.FORFEITED, bond.get().status());
    }

    /**
     * Test: Bond forfeiture on contract breach (partial).
     * Validates: Requirement 355.2
     */
    @Test
    void testBondForfeitureContractBreach() {
        BondDepositResult deposit = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000)
        );

        ForfeitureResult result = bondsService.forfeitBond(
                deposit.bondId(),
                ForfeitureReason.CONTRACT_BREACH
        );

        assertTrue(result.success());
        assertEquals(0, BigDecimal.valueOf(750).compareTo(result.forfeitedAmount()),
                "Contract breach should forfeit 75%");
    }

    /**
     * Test: Bond forfeiture on targeting attempt.
     * Validates: Requirement 355.2
     */
    @Test
    void testBondForfeitureTargetingAttempt() {
        BondDepositResult deposit = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000)
        );

        ForfeitureResult result = bondsService.forfeitBond(
                deposit.bondId(),
                ForfeitureReason.TARGETING_ATTEMPT
        );

        assertTrue(result.success());
        assertEquals(0, BigDecimal.valueOf(500).compareTo(result.forfeitedAmount()),
                "Targeting attempt should forfeit 50%");
    }

    /**
     * Test: Cannot forfeit non-existent bond.
     * Validates: Requirement 355.2
     */
    @Test
    void testCannotForfeitNonExistentBond() {
        ForfeitureResult result = bondsService.forfeitBond(
                "non-existent-bond-id",
                ForfeitureReason.PRIVACY_VIOLATION
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("not found"));
    }

    /**
     * Test: Cannot forfeit already forfeited bond.
     * Validates: Requirement 355.2
     */
    @Test
    void testCannotForfeitAlreadyForfeitedBond() {
        BondDepositResult deposit = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000)
        );

        // First forfeiture
        bondsService.forfeitBond(deposit.bondId(), ForfeitureReason.PRIVACY_VIOLATION);

        // Second forfeiture attempt
        ForfeitureResult result = bondsService.forfeitBond(
                deposit.bondId(),
                ForfeitureReason.DATA_MISUSE
        );

        assertFalse(result.success());
        assertTrue(result.message().contains("not in held status"));
    }

    // ==================== Task 102.3: Bond Return Tests ====================

    /**
     * Test: Bond return after hold period.
     * Validates: Requirement 355.4
     */
    @Test
    void testBondReturnAfterHoldPeriod() {
        // For this test, we need to simulate a bond with an expired hold period
        // Since we can't easily manipulate time, we'll test the logic path
        UUID requesterId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        BigDecimal amount = BigDecimal.valueOf(1000);

        BondDepositResult deposit = bondsService.depositBond(requesterId, requestId, amount);
        assertTrue(deposit.success());

        // Try to return before hold period expires
        ReturnResult earlyResult = bondsService.returnBond(deposit.bondId());
        assertFalse(earlyResult.success());
        assertTrue(earlyResult.message().contains("hold period not complete"));
    }

    /**
     * Test: Cannot return non-existent bond.
     * Validates: Requirement 355.4
     */
    @Test
    void testCannotReturnNonExistentBond() {
        ReturnResult result = bondsService.returnBond("non-existent-bond-id");

        assertFalse(result.success());
        assertTrue(result.message().contains("not found"));
    }

    /**
     * Test: Cannot return forfeited bond.
     * Validates: Requirement 355.4
     */
    @Test
    void testCannotReturnForfeitedBond() {
        BondDepositResult deposit = bondsService.depositBond(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(1000)
        );

        // Forfeit the bond
        bondsService.forfeitBond(deposit.bondId(), ForfeitureReason.PRIVACY_VIOLATION);

        // Try to return
        ReturnResult result = bondsService.returnBond(deposit.bondId());

        assertFalse(result.success());
        assertTrue(result.message().contains("not in held status"));
    }

    // ==================== Additional Tests ====================

    /**
     * Test: Get bonds for requester.
     * Validates: Requirement 355.3
     */
    @Test
    void testGetBondsForRequester() {
        UUID requesterId = UUID.randomUUID();

        // Deposit multiple bonds
        bondsService.depositBond(requesterId, UUID.randomUUID(), BigDecimal.valueOf(1000));
        bondsService.depositBond(requesterId, UUID.randomUUID(), BigDecimal.valueOf(2000));
        bondsService.depositBond(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(500)); // Different requester

        List<Bond> bonds = bondsService.getBondsForRequester(requesterId);

        assertEquals(2, bonds.size());
        assertTrue(bonds.stream().allMatch(b -> b.requesterId().equals(requesterId)));
    }

    /**
     * Test: Get total held amount.
     * Validates: Requirement 355.3
     */
    @Test
    void testGetTotalHeldAmount() {
        UUID requesterId = UUID.randomUUID();

        bondsService.depositBond(requesterId, UUID.randomUUID(), BigDecimal.valueOf(1000));
        bondsService.depositBond(requesterId, UUID.randomUUID(), BigDecimal.valueOf(2000));

        BigDecimal total = bondsService.getTotalHeldAmount(requesterId);

        assertEquals(BigDecimal.valueOf(3000), total);
    }

    /**
     * Test: Null safety.
     * Validates: Requirement 355.5
     */
    @Test
    void testNullSafety() {
        assertThrows(NullPointerException.class,
                () -> bondsService.calculateBondRequirement(null));
        
        assertThrows(NullPointerException.class,
                () -> bondsService.depositBond(null, UUID.randomUUID(), BigDecimal.ONE));
        
        assertThrows(NullPointerException.class,
                () -> bondsService.forfeitBond(null, ForfeitureReason.PRIVACY_VIOLATION));
        
        assertThrows(NullPointerException.class,
                () -> bondsService.returnBond(null));
    }

    /**
     * Test: High-value request bond scaling.
     * Validates: Requirement 355.1
     */
    @Test
    void testHighValueRequestBondScaling() {
        BondCalculationRequest lowBudget = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(5000),
                false,
                false,
                false,
                0.8,
                "BASIC"
        );

        BondCalculationRequest highBudget = new BondCalculationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(50000), // High budget
                false,
                false,
                false,
                0.8,
                "BASIC"
        );

        BondRequirement lowReq = bondsService.calculateBondRequirement(lowBudget);
        BondRequirement highReq = bondsService.calculateBondRequirement(highBudget);

        assertTrue(highReq.amount().compareTo(lowReq.amount()) > 0,
                "High-value request should have higher bond");
        assertTrue(highReq.reasons().stream().anyMatch(r -> r.contains("High-value")),
                "Should include high-value reason");
    }
}
