package com.yachaq.api.matching;

import com.yachaq.core.domain.Request;
import com.yachaq.core.repository.DSProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Privacy-preserving matching service.
 * 
 * Property 4: Uniform Compensation
 * Validates: Requirements 8.1, 8.2, 10.2
 * 
 * Matches requests to DS using ODX labels only, enforcing k-anonymity thresholds.
 * Never reveals DS identity to requester until explicit consent.
 */
@Service
public class MatchingService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DSProfileRepository dsProfileRepository;

    @Value("${yachaq.matching.min-cohort-size:50}")
    private int minCohortSize;

    public MatchingService(
            NamedParameterJdbcTemplate jdbcTemplate,
            DSProfileRepository dsProfileRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.dsProfileRepository = dsProfileRepository;
    }

    /**
     * Find eligible DS profiles for a request using privacy-preserving matching.
     * Uses only ODX labels (coarse attributes), never raw personal data.
     * 
     * @param request The request to match
     * @return List of eligible DS pseudonyms (not real identities)
     */
    public MatchResult findEligibleProfiles(Request request) {
        Map<String, Object> criteria = request.getEligibilityCriteria();
        
        // Build query using only allowed ODX-safe criteria
        StringBuilder sql = new StringBuilder("""
            SELECT id, pseudonym, account_type, status, created_at
            FROM ds_profiles
            WHERE status = 'ACTIVE'
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();

        // Apply ODX-safe filters only
        applyOdxSafeFilters(sql, params, criteria);

        // Execute query
        List<EligibleProfile> profiles = jdbcTemplate.query(sql.toString(), params, (rs, rowNum) ->
            new EligibleProfile(
                rs.getObject("id", UUID.class),
                rs.getString("pseudonym"),
                rs.getString("account_type"),
                rs.getTimestamp("created_at").toInstant()
            )
        );

        int cohortSize = profiles.size();
        boolean meetsKAnonymity = cohortSize >= minCohortSize;

        return new MatchResult(
            meetsKAnonymity ? profiles : Collections.emptyList(),
            cohortSize,
            meetsKAnonymity,
            request.getUnitPrice() // Same price for all - Property 4
        );
    }

    /**
     * Apply only ODX-safe filters to prevent re-identification.
     * Allowed: account_type, status, created_after, created_before
     * Blocked: exact location, precise age, specific attributes
     */
    private void applyOdxSafeFilters(StringBuilder sql, MapSqlParameterSource params, Map<String, Object> criteria) {
        // Account type filter (coarse)
        Object accountType = criteria.get("account_type");
        if (accountType != null) {
            sql.append(" AND account_type = :accountType");
            params.addValue("accountType", accountType.toString());
        }

        // Time-based filters (coarse buckets)
        Object createdAfter = criteria.get("created_after");
        if (createdAfter != null) {
            sql.append(" AND created_at >= :createdAfter");
            params.addValue("createdAfter", Instant.parse(createdAfter.toString()));
        }

        Object createdBefore = criteria.get("created_before");
        if (createdBefore != null) {
            sql.append(" AND created_at <= :createdBefore");
            params.addValue("createdBefore", Instant.parse(createdBefore.toString()));
        }

        // Block any criteria that could enable re-identification
        Set<String> blockedCriteria = Set.of(
            "exact_location", "precise_age", "email", "phone", 
            "name", "address", "ip_address", "device_id"
        );
        
        for (String blocked : blockedCriteria) {
            if (criteria.containsKey(blocked)) {
                throw new UnsafeCriteriaException("Criteria '" + blocked + "' is blocked for privacy protection");
            }
        }
    }


    /**
     * Property 4: Uniform Compensation
     * Verify that all DS in a request receive identical compensation.
     */
    public boolean verifyUniformCompensation(UUID requestId, List<UUID> dsIds) {
        if (dsIds.isEmpty()) return true;

        String sql = """
            SELECT DISTINCT compensation_amount
            FROM consent_contracts
            WHERE request_id = :requestId AND ds_id IN (:dsIds)
            """;
        
        List<BigDecimal> amounts = jdbcTemplate.queryForList(sql,
            new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("dsIds", dsIds),
            BigDecimal.class
        );

        // All DS must have same compensation
        return amounts.size() <= 1;
    }

    /**
     * Get compensation for a request - same for all DS (Property 4).
     */
    public BigDecimal getUniformCompensation(UUID requestId) {
        String sql = """
            SELECT unit_price FROM requests WHERE id = :requestId
            """;
        return jdbcTemplate.queryForObject(sql,
            new MapSqlParameterSource("requestId", requestId),
            BigDecimal.class
        );
    }

    /**
     * Convert compensation to local currency display (LCD).
     * Uses platform FX reference rate.
     */
    public LocalCurrencyDisplay toLocalCurrency(BigDecimal ycAmount, String targetCurrency) {
        // Platform FX rates (simplified - in production would use real FX service)
        BigDecimal fxRate = getFxRate("YC", targetCurrency);
        BigDecimal localAmount = ycAmount.multiply(fxRate);
        
        return new LocalCurrencyDisplay(
            ycAmount,
            localAmount,
            targetCurrency,
            fxRate,
            Instant.now() // FX lock moment
        );
    }

    private BigDecimal getFxRate(String from, String to) {
        // Simplified FX rates - in production would call real FX service
        return switch (to) {
            case "USD" -> BigDecimal.ONE;
            case "EUR" -> new BigDecimal("0.92");
            case "GBP" -> new BigDecimal("0.79");
            case "JPY" -> new BigDecimal("149.50");
            default -> BigDecimal.ONE;
        };
    }

    // DTOs
    public record MatchResult(
        List<EligibleProfile> eligibleProfiles,
        int cohortSize,
        boolean meetsKAnonymity,
        BigDecimal uniformCompensation
    ) {}

    public record EligibleProfile(
        UUID id,
        String pseudonym, // Never reveal real identity
        String accountType,
        Instant createdAt
    ) {}

    public record LocalCurrencyDisplay(
        BigDecimal ycAmount,
        BigDecimal localAmount,
        String currency,
        BigDecimal fxRate,
        Instant fxLockMoment
    ) {}

    public static class UnsafeCriteriaException extends RuntimeException {
        public UnsafeCriteriaException(String message) { super(message); }
    }
}
