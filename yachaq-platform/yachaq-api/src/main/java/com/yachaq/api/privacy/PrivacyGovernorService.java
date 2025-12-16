package com.yachaq.api.privacy;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.audit.MerkleTree;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.PrivacyRiskBudget;
import com.yachaq.core.domain.PrivacyRiskBudget.PRBStatus;
import com.yachaq.core.repository.PrivacyRiskBudgetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Privacy Governor Service - Enforces Privacy Risk Budget (PRB), k-min cohorts, and linkage defense.
 * 
 * Property 26: K-Min Cohort Enforcement
 * *For any* discovery query with eligibility criteria, if the estimated cohort size 
 * is below k-min threshold (k < 50), the query must be blocked.
 * 
 * Property 27: Linkage Rate Limiting
 * *For any* sequence of queries from the same requester with high similarity, 
 * the system must enforce rate limits after threshold is exceeded.
 * 
 * Property 28: PRB Allocation and Lock
 * *For any* campaign quoted, a Privacy Risk Budget must be allocated;
 * upon acceptance, the PRB must be locked and immutable.
 * 
 * Validates: Requirements 204.1, 204.2, 204.3, 204.4, 202.1, 202.2, 203.1, 203.2
 */
@Service
public class PrivacyGovernorService {

    private static final int DEFAULT_K_MIN = 50;
    private static final int DEFAULT_LINKAGE_WINDOW_HOURS = 24;
    private static final int DEFAULT_LINKAGE_MAX_SIMILAR_QUERIES = 10;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8;
    private static final String DEFAULT_RULESET_VERSION = "1.0.0";

    private final PrivacyRiskBudgetRepository prbRepository;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    private final int kMin;
    private final int linkageWindowHours;
    private final int linkageMaxSimilarQueries;
    private final double similarityThreshold;
    private final String rulesetVersion;

    public PrivacyGovernorService(
            PrivacyRiskBudgetRepository prbRepository,
            AuditService auditService,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this(prbRepository, auditService, jdbcTemplate, 
             DEFAULT_K_MIN, DEFAULT_LINKAGE_WINDOW_HOURS, 
             DEFAULT_LINKAGE_MAX_SIMILAR_QUERIES, DEFAULT_SIMILARITY_THRESHOLD,
             DEFAULT_RULESET_VERSION);
    }

    public PrivacyGovernorService(
            PrivacyRiskBudgetRepository prbRepository,
            AuditService auditService,
            NamedParameterJdbcTemplate jdbcTemplate,
            @Value("${yachaq.privacy.k-min:50}") int kMin,
            @Value("${yachaq.privacy.linkage-window-hours:24}") int linkageWindowHours,
            @Value("${yachaq.privacy.linkage-max-similar:10}") int linkageMaxSimilarQueries,
            @Value("${yachaq.privacy.similarity-threshold:0.8}") double similarityThreshold,
            @Value("${yachaq.privacy.ruleset-version:1.0.0}") String rulesetVersion) {
        this.prbRepository = prbRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.kMin = kMin > 0 ? kMin : DEFAULT_K_MIN;
        this.linkageWindowHours = linkageWindowHours > 0 ? linkageWindowHours : DEFAULT_LINKAGE_WINDOW_HOURS;
        this.linkageMaxSimilarQueries = linkageMaxSimilarQueries > 0 ? linkageMaxSimilarQueries : DEFAULT_LINKAGE_MAX_SIMILAR_QUERIES;
        this.similarityThreshold = similarityThreshold > 0 ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD;
        this.rulesetVersion = rulesetVersion != null ? rulesetVersion : DEFAULT_RULESET_VERSION;
    }

    // ==================== PRB Allocation and Lock ====================

    /**
     * Allocates a Privacy Risk Budget for a campaign at quote time.
     * Property 28: PRB Allocation and Lock - allocation at quote time.
     * Validates: Requirements 204.1
     * 
     * @param campaignId The campaign ID
     * @param riskProfile Risk profile used to calculate budget
     * @return The allocated PRB
     */
    @Transactional
    public PrivacyRiskBudget allocatePRB(UUID campaignId, RiskProfile riskProfile) {
        if (campaignId == null) {
            throw new IllegalArgumentException("Campaign ID cannot be null");
        }
        if (prbRepository.existsByCampaignId(campaignId)) {
            throw new PRBAlreadyExistsException("PRB already exists for campaign: " + campaignId);
        }

        BigDecimal budget = calculateBudget(riskProfile);
        PrivacyRiskBudget prb = PrivacyRiskBudget.allocate(campaignId, budget, rulesetVersion);
        PrivacyRiskBudget saved = prbRepository.save(prb);

        // Generate audit receipt
        auditService.appendReceipt(
                AuditReceipt.EventType.PRB_ALLOCATED,
                campaignId,
                AuditReceipt.ActorType.SYSTEM,
                saved.getId(),
                "PrivacyRiskBudget",
                computePRBHash(saved)
        );

        return saved;
    }

    /**
     * Locks the PRB at campaign acceptance.
     * Property 28: PRB Allocation and Lock - lock at acceptance.
     * Validates: Requirements 204.2
     * 
     * @param campaignId The campaign ID
     * @return The locked PRB
     */
    @Transactional
    public PrivacyRiskBudget lockPRB(UUID campaignId) {
        PrivacyRiskBudget prb = prbRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new PRBNotFoundException("PRB not found for campaign: " + campaignId));

        prb.lock();
        PrivacyRiskBudget saved = prbRepository.save(prb);

        auditService.appendReceipt(
                AuditReceipt.EventType.PRB_LOCKED,
                campaignId,
                AuditReceipt.ActorType.SYSTEM,
                saved.getId(),
                "PrivacyRiskBudget",
                computePRBHash(saved)
        );

        return saved;
    }

    /**
     * Consumes PRB for a transform/export operation.
     * Property 28: PRB consumption tracking.
     * Validates: Requirements 204.3, 204.4
     * 
     * @param campaignId The campaign ID
     * @param transformId The transform being executed
     * @param riskCost The privacy risk cost
     * @return The updated PRB
     */
    @Transactional
    public PrivacyRiskBudget consumePRB(UUID campaignId, String transformId, BigDecimal riskCost) {
        PrivacyRiskBudget prb = prbRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new PRBNotFoundException("PRB not found for campaign: " + campaignId));

        prb.consume(riskCost);
        PrivacyRiskBudget saved = prbRepository.save(prb);

        // Log consumption
        logPRBConsumption(saved.getId(), transformId, "TRANSFORM", riskCost, saved.getRemaining());

        auditService.appendReceipt(
                AuditReceipt.EventType.PRB_CONSUMED,
                campaignId,
                AuditReceipt.ActorType.SYSTEM,
                saved.getId(),
                "PrivacyRiskBudget",
                computePRBHash(saved)
        );

        return saved;
    }

    /**
     * Checks if PRB can accommodate a given risk cost.
     * Validates: Requirements 204.4
     */
    public boolean canConsumePRB(UUID campaignId, BigDecimal riskCost) {
        return prbRepository.findByCampaignId(campaignId)
                .map(prb -> prb.canConsume(riskCost))
                .orElse(false);
    }

    /**
     * Gets the PRB for a campaign.
     */
    public Optional<PrivacyRiskBudget> getPRB(UUID campaignId) {
        return prbRepository.findByCampaignId(campaignId);
    }

    // ==================== K-Min Cohort Enforcement ====================

    /**
     * Checks if eligibility criteria meet k-min cohort threshold.
     * Property 26: K-Min Cohort Enforcement
     * Validates: Requirements 202.1, 202.2
     * 
     * @param criteria The eligibility criteria
     * @return CohortCheckResult with size and pass/fail status
     */
    @Transactional
    public CohortCheckResult checkCohort(Map<String, Object> criteria) {
        return checkCohort(criteria, kMin);
    }

    /**
     * Checks if eligibility criteria meet custom k-min threshold.
     * Property 26: K-Min Cohort Enforcement
     * Validates: Requirements 202.1, 202.2
     * 
     * @param criteria The eligibility criteria
     * @param customKMin Custom k-min threshold
     * @return CohortCheckResult with size and pass/fail status
     */
    @Transactional
    public CohortCheckResult checkCohort(Map<String, Object> criteria, int customKMin) {
        String criteriaHash = computeCriteriaHash(criteria);
        
        // Check cache first
        Optional<CohortCheckResult> cached = getCachedCohortSize(criteriaHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Compute cohort size
        int cohortSize = estimateCohortSize(criteria);
        boolean meetsThreshold = cohortSize >= customKMin;

        CohortCheckResult result = new CohortCheckResult(
                criteriaHash,
                cohortSize,
                customKMin,
                meetsThreshold,
                meetsThreshold ? null : "Cohort size " + cohortSize + " < k-min " + customKMin
        );

        // Cache the result
        cacheCohortSize(result);

        // Log policy decision if blocked
        if (!meetsThreshold) {
            logPolicyDecision("COHORT_BLOCK", "DENY", null, null, 
                    List.of("K_MIN_VIOLATION"), rulesetVersion);
        }

        return result;
    }

    /**
     * Estimates cohort size from eligibility criteria.
     * Uses only allowed filters: account_type, status, created_after, created_before
     */
    private int estimateCohortSize(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            // Return total DS count
            String sql = "SELECT COUNT(*) FROM ds_profiles WHERE status = 'active'";
            Integer count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
            return count != null ? count : 0;
        }

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ds_profiles WHERE status = 'active'");
        MapSqlParameterSource params = new MapSqlParameterSource();

        Object accountType = criteria.get("account_type");
        if (accountType != null) {
            sql.append(" AND account_type = :accountType");
            params.addValue("accountType", accountType.toString());
        }

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

        Integer count = jdbcTemplate.queryForObject(sql.toString(), params, Integer.class);
        return count != null ? count : 0;
    }

    // ==================== Linkage Rate Limiting ====================

    /**
     * Detects and enforces linkage rate limits for repeated similar queries.
     * Property 27: Linkage Rate Limiting
     * Validates: Requirements 203.1, 203.2
     * 
     * @param requesterId The requester ID
     * @param queryHistory Recent query history for similarity analysis
     * @return LinkageCheckResult with pass/fail status
     */
    @Transactional
    public LinkageCheckResult detectLinkage(UUID requesterId, List<QueryRecord> queryHistory) {
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }
        if (queryHistory == null || queryHistory.isEmpty()) {
            return new LinkageCheckResult(requesterId, false, 0, 0.0, null);
        }

        Instant windowStart = Instant.now().minus(Duration.ofHours(linkageWindowHours));
        Instant windowEnd = Instant.now();

        // Get recent queries in window
        List<QueryRecord> recentQueries = queryHistory.stream()
                .filter(q -> q.timestamp().isAfter(windowStart))
                .toList();

        if (recentQueries.size() < 2) {
            return new LinkageCheckResult(requesterId, false, recentQueries.size(), 0.0, null);
        }

        // Compute similarity between consecutive queries
        double maxSimilarity = 0.0;
        int similarCount = 0;

        for (int i = 1; i < recentQueries.size(); i++) {
            double similarity = computeQuerySimilarity(
                    recentQueries.get(i - 1).criteriaHash(),
                    recentQueries.get(i).criteriaHash()
            );
            maxSimilarity = Math.max(maxSimilarity, similarity);
            if (similarity >= similarityThreshold) {
                similarCount++;
            }
        }

        boolean blocked = similarCount >= linkageMaxSimilarQueries;

        if (blocked) {
            // Record the block
            recordLinkageBlock(requesterId, recentQueries.get(recentQueries.size() - 1).criteriaHash(),
                    maxSimilarity, similarCount, windowStart, windowEnd);

            logPolicyDecision("LINKAGE_BLOCK", "DENY", null, requesterId,
                    List.of("LINKAGE_RATE_EXCEEDED"), rulesetVersion);
        }

        return new LinkageCheckResult(
                requesterId,
                blocked,
                similarCount,
                maxSimilarity,
                blocked ? "Linkage rate limit exceeded: " + similarCount + " similar queries" : null
        );
    }

    /**
     * Computes similarity between two query criteria hashes.
     * Simple implementation using hash comparison; can be enhanced with more sophisticated methods.
     */
    private double computeQuerySimilarity(String hash1, String hash2) {
        if (hash1 == null || hash2 == null) {
            return 0.0;
        }
        if (hash1.equals(hash2)) {
            return 1.0;
        }
        // Simple character-level similarity for demonstration
        int matches = 0;
        int minLen = Math.min(hash1.length(), hash2.length());
        for (int i = 0; i < minLen; i++) {
            if (hash1.charAt(i) == hash2.charAt(i)) {
                matches++;
            }
        }
        return (double) matches / Math.max(hash1.length(), hash2.length());
    }

    // ==================== Helper Methods ====================

    private BigDecimal calculateBudget(RiskProfile riskProfile) {
        if (riskProfile == null) {
            return BigDecimal.valueOf(10.0); // Default budget
        }
        // Budget calculation based on risk profile
        BigDecimal base = BigDecimal.valueOf(10.0);
        BigDecimal riskMultiplier = BigDecimal.valueOf(1.0 + riskProfile.riskLevel() * 0.5);
        return base.multiply(riskMultiplier);
    }

    private String computePRBHash(PrivacyRiskBudget prb) {
        String data = String.join("|",
                prb.getId().toString(),
                prb.getCampaignId().toString(),
                prb.getAllocated().toPlainString(),
                prb.getConsumed().toPlainString(),
                prb.getStatus().name()
        );
        return MerkleTree.sha256(data);
    }

    private String computeCriteriaHash(Map<String, Object> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return MerkleTree.sha256("EMPTY_CRITERIA");
        }
        TreeMap<String, Object> sorted = new TreeMap<>(criteria);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(";");
        }
        return MerkleTree.sha256(sb.toString());
    }

    private void logPRBConsumption(UUID prbId, String transformId, String operationType, 
                                    BigDecimal riskCost, BigDecimal remainingAfter) {
        String sql = """
                INSERT INTO prb_consumption_logs 
                (id, prb_id, transform_id, operation_type, risk_cost, remaining_after, consumed_at, details_hash)
                VALUES (:id, :prbId, :transformId, :operationType, :riskCost, :remainingAfter, :consumedAt, :detailsHash)
                """;
        
        String detailsHash = MerkleTree.sha256(prbId + "|" + transformId + "|" + riskCost);
        
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("prbId", prbId)
                .addValue("transformId", transformId)
                .addValue("operationType", operationType)
                .addValue("riskCost", riskCost)
                .addValue("remainingAfter", remainingAfter)
                .addValue("consumedAt", Instant.now())
                .addValue("detailsHash", detailsHash));
    }

    private Optional<CohortCheckResult> getCachedCohortSize(String criteriaHash) {
        String sql = """
                SELECT estimated_size, k_min_threshold, meets_threshold 
                FROM cohort_size_cache 
                WHERE criteria_hash = :hash AND expires_at > :now
                """;
        
        return Optional.ofNullable(jdbcTemplate.query(sql, 
                new MapSqlParameterSource()
                        .addValue("hash", criteriaHash)
                        .addValue("now", Instant.now()),
                rs -> {
                    if (rs.next()) {
                        return new CohortCheckResult(
                                criteriaHash,
                                rs.getInt("estimated_size"),
                                rs.getInt("k_min_threshold"),
                                rs.getBoolean("meets_threshold"),
                                null
                        );
                    }
                    return null;
                }));
    }

    private void cacheCohortSize(CohortCheckResult result) {
        String sql = """
                INSERT INTO cohort_size_cache 
                (id, criteria_hash, estimated_size, k_min_threshold, meets_threshold, computed_at, expires_at, computation_method)
                VALUES (:id, :hash, :size, :kMin, :meets, :computedAt, :expiresAt, :method)
                ON CONFLICT (criteria_hash) DO UPDATE SET
                    estimated_size = :size,
                    meets_threshold = :meets,
                    computed_at = :computedAt,
                    expires_at = :expiresAt
                """;
        
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("hash", result.criteriaHash())
                .addValue("size", result.cohortSize())
                .addValue("kMin", result.kMinThreshold())
                .addValue("meets", result.meetsThreshold())
                .addValue("computedAt", Instant.now())
                .addValue("expiresAt", Instant.now().plus(Duration.ofHours(1)))
                .addValue("method", "EXACT"));
    }

    private void recordLinkageBlock(UUID requesterId, String queryHash, double similarity, 
                                     int queryCount, Instant windowStart, Instant windowEnd) {
        String sql = """
                INSERT INTO linkage_rate_limits 
                (id, requester_id, query_hash, similarity_score, query_count, first_query_at, last_query_at, 
                 window_start, window_end, blocked, blocked_at)
                VALUES (:id, :requesterId, :queryHash, :similarity, :queryCount, :firstQuery, :lastQuery,
                        :windowStart, :windowEnd, TRUE, :blockedAt)
                """;
        
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("requesterId", requesterId)
                .addValue("queryHash", queryHash)
                .addValue("similarity", similarity)
                .addValue("queryCount", queryCount)
                .addValue("firstQuery", windowStart)
                .addValue("lastQuery", Instant.now())
                .addValue("windowStart", windowStart)
                .addValue("windowEnd", windowEnd)
                .addValue("blockedAt", Instant.now()));
    }

    private void logPolicyDecision(String decisionType, String decision, UUID campaignId, 
                                    UUID requesterId, List<String> reasonCodes, String policyVersion) {
        String sql = """
                INSERT INTO policy_decision_receipts 
                (id, decision_type, decision, campaign_id, requester_id, reason_codes, policy_version, created_at)
                VALUES (:id, :decisionType, :decision, :campaignId, :requesterId, :reasonCodes, :policyVersion, :createdAt)
                """;
        
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("decisionType", decisionType)
                .addValue("decision", decision)
                .addValue("campaignId", campaignId)
                .addValue("requesterId", requesterId)
                .addValue("reasonCodes", String.join(",", reasonCodes))
                .addValue("policyVersion", policyVersion)
                .addValue("createdAt", Instant.now()));
    }

    public int getKMin() {
        return kMin;
    }

    // ==================== Records and Exceptions ====================

    public record RiskProfile(
            double riskLevel,
            String category,
            boolean sensitiveData,
            int estimatedQueries
    ) {}

    public record CohortCheckResult(
            String criteriaHash,
            int cohortSize,
            int kMinThreshold,
            boolean meetsThreshold,
            String blockReason
    ) {
        public boolean isBlocked() {
            return !meetsThreshold;
        }
    }

    public record LinkageCheckResult(
            UUID requesterId,
            boolean blocked,
            int similarQueryCount,
            double maxSimilarity,
            String blockReason
    ) {}

    public record QueryRecord(
            String criteriaHash,
            Instant timestamp
    ) {}

    public static class PRBNotFoundException extends RuntimeException {
        public PRBNotFoundException(String message) {
            super(message);
        }
    }

    public static class PRBAlreadyExistsException extends RuntimeException {
        public PRBAlreadyExistsException(String message) {
            super(message);
        }
    }
}
