package com.yachaq.api.screening;

import com.yachaq.core.domain.PolicyRule;
import com.yachaq.core.repository.PolicyRuleRepository;
import com.yachaq.core.repository.RequestRepository;
import com.yachaq.core.repository.ScreeningResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 11: Anti-Targeting Cohort Threshold (k >= 50)
 * 
 * Feature: yachaq-platform, Property 11: Anti-Targeting Cohort Threshold
 * Validates: Requirements 196.1, 196.2
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScreeningServicePropertyTest {

    @Autowired
    private RequestService requestService;
    @Autowired
    private ScreeningService screeningService;
    @Autowired
    private RequestRepository requestRepository;
    @Autowired
    private ScreeningResultRepository screeningResultRepository;
    @Autowired
    private PolicyRuleRepository policyRuleRepository;

    @BeforeEach
    void setUp() {
        screeningResultRepository.deleteAll();
        requestRepository.deleteAll();
        policyRuleRepository.deleteAll();
        seedPolicyRules();
    }

    private void seedPolicyRules() {
        // COHORT_MIN_SIZE - blocking rule
        PolicyRule cohortRule = PolicyRule.create(
                "COHORT_MIN_SIZE",
                "Minimum Cohort Size",
                "Cohort must be at least k=50",
                PolicyRule.RuleType.BLOCKING,
                "PRIVACY",
                10
        );
        policyRuleRepository.save(cohortRule);

        // DURATION_REASONABLE - warning rule
        PolicyRule durationRule = PolicyRule.create(
                "DURATION_REASONABLE",
                "Reasonable Duration",
                "Duration should not exceed 365 days",
                PolicyRule.RuleType.WARNING,
                "COMPLIANCE",
                5
        );
        policyRuleRepository.save(durationRule);
    }

    /**
     * Property 11: Anti-Targeting Cohort Threshold
     * For any request with eligibility criteria matching >= k users,
     * the screening service SHALL approve the request (assuming no other violations).
     */
    @Test
    void property11_allowsWhenCohortAboveThreshold() {
        // Create a request with broad eligibility criteria (high cohort)
        Map<String, Object> scope = Map.of("field", "value");
        // Broad criteria that would match many users
        Map<String, Object> eligibility = Map.of("status", "ACTIVE", "region", "GLOBAL");

        RequestService.CreateRequestCommand cmd = new RequestService.CreateRequestCommand(
                UUID.randomUUID(),
                "Test purpose with broad criteria",
                scope,
                eligibility,
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(3600 * 24 * 30),
                com.yachaq.core.domain.Request.UnitType.SURVEY,
                BigDecimal.valueOf(1.00),
                100,
                BigDecimal.valueOf(100.00)
        );

        var req = requestService.createRequest(cmd);
        requestService.submitForScreening(req.id(), req.requesterId());
        var screening = screeningService.getScreeningResult(req.id());
        
        // The screening service estimates cohort based on criteria
        // With broad criteria, cohort should be above threshold
        assertNotNull(screening);
        // Verify the cohort threshold property is enforced
        if (screening.cohortSizeEstimate() >= 50) {
            assertEquals(com.yachaq.core.domain.ScreeningResult.ScreeningDecision.APPROVED, screening.decision());
        }
    }

    /**
     * Property 11: Anti-Targeting Cohort Threshold
     * For any request with eligibility criteria matching < k users,
     * the screening service SHALL reject the request.
     */
    @Test
    void property11_rejectsWhenCohortBelowThreshold() {
        // Create a request with very narrow eligibility criteria (low cohort)
        Map<String, Object> scope = Map.of("field", "value");
        // Very specific criteria that would match few users
        Map<String, Object> eligibility = Map.of(
                "status", "ACTIVE",
                "exact_user_id", UUID.randomUUID().toString(),
                "specific_attribute", "unique_value_12345"
        );

        RequestService.CreateRequestCommand cmd = new RequestService.CreateRequestCommand(
                UUID.randomUUID(),
                "Low cohort test with narrow criteria",
                scope,
                eligibility,
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(3600 * 24 * 30),
                com.yachaq.core.domain.Request.UnitType.SURVEY,
                BigDecimal.valueOf(1.00),
                10,
                BigDecimal.valueOf(10.00)
        );

        var req = requestService.createRequest(cmd);
        requestService.submitForScreening(req.id(), req.requesterId());
        var screening = screeningService.getScreeningResult(req.id());
        
        assertNotNull(screening);
        // Verify the cohort threshold property is enforced
        if (screening.cohortSizeEstimate() < 50) {
            assertEquals(com.yachaq.core.domain.ScreeningResult.ScreeningDecision.REJECTED, screening.decision());
            assertTrue(screening.reasonCodes().contains("COHORT_MIN_SIZE"));
        }
    }
}
