package com.yachaq.api.screening;

import com.yachaq.core.repository.RequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 11: Anti-Targeting Cohort Threshold (k >= 50)
 */
@SpringBootTest
@ActiveProfiles("test")
class ScreeningServicePropertyTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private RequestService requestService;
    @Autowired
    private ScreeningService screeningService;
    @Autowired
    private RequestRepository requestRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM screening_results", new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM requests", new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM ds_profiles", new MapSqlParameterSource());
        jdbcTemplate.update("DELETE FROM policy_rules", new MapSqlParameterSource());
        seedPolicyRules();
        seedDsProfiles(80); // enough to be above k=50
    }

    private void seedPolicyRules() {
        String sql = """
                INSERT INTO policy_rules (id, rule_code, rule_name, rule_description, rule_type, rule_category, severity, is_active, created_at, updated_at)
                VALUES (:id, :code, :name, :desc, :type, :category, :severity, true, NOW(), NOW())
                """;
        // COHORT_MIN_SIZE - blocking rule
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("code", "COHORT_MIN_SIZE")
                .addValue("name", "Minimum Cohort Size")
                .addValue("desc", "Cohort must be at least k=50")
                .addValue("type", "BLOCKING")
                .addValue("category", "PRIVACY")
                .addValue("severity", 10));
        // DURATION_REASONABLE - warning rule
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("code", "DURATION_REASONABLE")
                .addValue("name", "Reasonable Duration")
                .addValue("desc", "Duration should not exceed 365 days")
                .addValue("type", "WARNING")
                .addValue("category", "COMPLIANCE")
                .addValue("severity", 5));
    }

    @Test
    void property11_allowsWhenCohortAboveThreshold() {
        Map<String, Object> scope = Map.of("field", "value");
        Map<String, Object> eligibility = Map.of("status", "ACTIVE");

        RequestService.CreateRequestCommand cmd = new RequestService.CreateRequestCommand(
                UUID.randomUUID(),
                "Test purpose",
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
        assertEquals(com.yachaq.core.domain.ScreeningResult.ScreeningDecision.APPROVED, screening.decision());
        assertTrue(screening.cohortSizeEstimate() >= 50);
    }

    @Test
    void property11_rejectsWhenCohortBelowThreshold() {
        jdbcTemplate.update("DELETE FROM ds_profiles", new MapSqlParameterSource());
        seedDsProfiles(20); // below k

        Map<String, Object> scope = Map.of("field", "value");
        Map<String, Object> eligibility = Map.of("status", "ACTIVE");

        RequestService.CreateRequestCommand cmd = new RequestService.CreateRequestCommand(
                UUID.randomUUID(),
                "Low cohort test",
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
        assertEquals(com.yachaq.core.domain.ScreeningResult.ScreeningDecision.REJECTED, screening.decision());
        assertTrue(screening.cohortSizeEstimate() < 50);
    }

    private void seedDsProfiles(int count) {
        String sql = """
                INSERT INTO ds_profiles (id, pseudonym, status, account_type, created_at, version)
                VALUES (:id, :pseudonym, 'ACTIVE', 'DS_IND', NOW(), 0)
                """;
        for (int i = 0; i < count; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("id", UUID.randomUUID());
            params.put("pseudonym", "user_" + i);
            jdbcTemplate.update(sql, new MapSqlParameterSource(params));
        }
    }
}
