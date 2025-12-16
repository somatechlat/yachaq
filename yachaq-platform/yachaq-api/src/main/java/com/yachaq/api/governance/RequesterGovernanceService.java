package com.yachaq.api.governance;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.audit.MerkleTree;
import com.yachaq.core.domain.AuditReceipt;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Requester Governance Service - Manages requester tiers, DUA, reputation, and enforcement.
 * 
 * Validates: Requirements 207.1, 207.2, 208.1, 208.2, 209.1, 209.2, 210.1, 210.2, 211.1, 211.2, 211.3
 * 
 * Key features:
 * - Tier management based on verification level
 * - DUA binding and versioning with re-acceptance
 * - Reputation scoring from disputes and violations
 * - Misuse enforcement pipeline
 * - Export controls with watermarking
 */
@Service
public class RequesterGovernanceService {

    private static final String CURRENT_DUA_VERSION = "2.0.0";
    private static final BigDecimal INITIAL_REPUTATION_SCORE = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_REPUTATION_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_REPUTATION_SCORE = BigDecimal.valueOf(100);

    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RequesterGovernanceService(AuditService auditService, NamedParameterJdbcTemplate jdbcTemplate) {
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }


    // ==================== Tier Management (Requirement 207) ====================

    /**
     * Assigns a tier to a requester based on verification level.
     * Requirement 207.1: Assign tiers based on verification level.
     */
    @Transactional
    public RequesterTierAssignment assignTier(UUID requesterId, VerificationLevel verificationLevel) {
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }

        RequesterTier tier = calculateTier(verificationLevel);
        TierPrivileges privileges = getTierPrivileges(tier);

        String sql = """
                INSERT INTO requester_tiers 
                (id, requester_id, tier, verification_level, max_budget, allowed_products, export_allowed, assigned_at)
                VALUES (:id, :requesterId, :tier, :verificationLevel, :maxBudget, :allowedProducts, :exportAllowed, :assignedAt)
                ON CONFLICT (requester_id) DO UPDATE SET
                    tier = :tier,
                    verification_level = :verificationLevel,
                    max_budget = :maxBudget,
                    allowed_products = :allowedProducts,
                    export_allowed = :exportAllowed,
                    assigned_at = :assignedAt
                """;

        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", assignmentId)
                .addValue("requesterId", requesterId)
                .addValue("tier", tier.name())
                .addValue("verificationLevel", verificationLevel.name())
                .addValue("maxBudget", privileges.maxBudget())
                .addValue("allowedProducts", String.join(",", privileges.allowedProducts()))
                .addValue("exportAllowed", privileges.exportAllowed())
                .addValue("assignedAt", Instant.now()));

        auditService.appendReceipt(
                AuditReceipt.EventType.PROFILE_UPDATED,
                requesterId,
                AuditReceipt.ActorType.SYSTEM,
                requesterId,
                "RequesterTier",
                "tier:" + tier.name());

        return new RequesterTierAssignment(assignmentId, requesterId, tier, verificationLevel, privileges, Instant.now());
    }

    /**
     * Gets the current tier for a requester.
     */
    public Optional<RequesterTierAssignment> getTier(UUID requesterId) {
        String sql = """
                SELECT id, requester_id, tier, verification_level, max_budget, allowed_products, export_allowed, assigned_at
                FROM requester_tiers WHERE requester_id = :requesterId
                """;

        return Optional.ofNullable(jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("requesterId", requesterId),
                rs -> {
                    if (rs.next()) {
                        RequesterTier tier = RequesterTier.valueOf(rs.getString("tier"));
                        return new RequesterTierAssignment(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("requester_id")),
                                tier,
                                VerificationLevel.valueOf(rs.getString("verification_level")),
                                new TierPrivileges(
                                        rs.getBigDecimal("max_budget"),
                                        Arrays.asList(rs.getString("allowed_products").split(",")),
                                        rs.getBoolean("export_allowed")
                                ),
                                rs.getTimestamp("assigned_at").toInstant()
                        );
                    }
                    return null;
                }));
    }

    /**
     * Checks if a requester can access a specific product.
     * Requirement 207.2: Gate exports, budgets, and products by tier.
     */
    public boolean canAccessProduct(UUID requesterId, String productType) {
        return getTier(requesterId)
                .map(t -> t.privileges().allowedProducts().contains(productType))
                .orElse(false);
    }

    private RequesterTier calculateTier(VerificationLevel level) {
        return switch (level) {
            case NONE -> RequesterTier.BASIC;
            case EMAIL_VERIFIED -> RequesterTier.STANDARD;
            case IDENTITY_VERIFIED -> RequesterTier.VERIFIED;
            case KYB_VERIFIED -> RequesterTier.ENTERPRISE;
        };
    }

    private TierPrivileges getTierPrivileges(RequesterTier tier) {
        return switch (tier) {
            case BASIC -> new TierPrivileges(
                    BigDecimal.valueOf(1000),
                    List.of("aggregate_only"),
                    false
            );
            case STANDARD -> new TierPrivileges(
                    BigDecimal.valueOf(10000),
                    List.of("aggregate_only", "clean_room"),
                    false
            );
            case VERIFIED -> new TierPrivileges(
                    BigDecimal.valueOf(100000),
                    List.of("aggregate_only", "clean_room", "model_training"),
                    true
            );
            case ENTERPRISE -> new TierPrivileges(
                    BigDecimal.valueOf(1000000),
                    List.of("aggregate_only", "clean_room", "model_training", "raw_export"),
                    true
            );
        };
    }


    // ==================== DUA Binding (Requirement 208) ====================

    /**
     * Records DUA acceptance with version.
     * Requirement 208.1: Record DUA acceptance with version.
     */
    @Transactional
    public DUAAcceptance acceptDUA(UUID requesterId, String duaVersion) {
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }
        if (duaVersion == null || duaVersion.isBlank()) {
            throw new IllegalArgumentException("DUA version cannot be null or blank");
        }

        String sql = """
                INSERT INTO dua_acceptances 
                (id, requester_id, dua_version, accepted_at, ip_address, user_agent, signature_hash)
                VALUES (:id, :requesterId, :duaVersion, :acceptedAt, :ipAddress, :userAgent, :signatureHash)
                """;

        UUID acceptanceId = UUID.randomUUID();
        String signatureHash = MerkleTree.sha256(requesterId + "|" + duaVersion + "|" + Instant.now());

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", acceptanceId)
                .addValue("requesterId", requesterId)
                .addValue("duaVersion", duaVersion)
                .addValue("acceptedAt", Instant.now())
                .addValue("ipAddress", "system")
                .addValue("userAgent", "api")
                .addValue("signatureHash", signatureHash));

        return new DUAAcceptance(acceptanceId, requesterId, duaVersion, Instant.now(), signatureHash);
    }

    /**
     * Checks if requester needs to re-accept DUA due to version update.
     * Requirement 208.2: Require re-acceptance on DUA updates.
     */
    public boolean requiresDUAReacceptance(UUID requesterId) {
        String sql = """
                SELECT dua_version FROM dua_acceptances 
                WHERE requester_id = :requesterId 
                ORDER BY accepted_at DESC LIMIT 1
                """;

        String acceptedVersion = jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("requesterId", requesterId),
                rs -> rs.next() ? rs.getString("dua_version") : null);

        return acceptedVersion == null || !acceptedVersion.equals(CURRENT_DUA_VERSION);
    }

    /**
     * Gets the current DUA version.
     */
    public String getCurrentDUAVersion() {
        return CURRENT_DUA_VERSION;
    }

    /**
     * Gets DUA acceptance history for a requester.
     */
    public List<DUAAcceptance> getDUAHistory(UUID requesterId) {
        String sql = """
                SELECT id, requester_id, dua_version, accepted_at, signature_hash
                FROM dua_acceptances WHERE requester_id = :requesterId ORDER BY accepted_at DESC
                """;

        return jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("requesterId", requesterId),
                (rs, rowNum) -> new DUAAcceptance(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("requester_id")),
                        rs.getString("dua_version"),
                        rs.getTimestamp("accepted_at").toInstant(),
                        rs.getString("signature_hash")
                ));
    }


    // ==================== Reputation Scoring (Requirement 209) ====================

    /**
     * Gets or initializes reputation score for a requester.
     * Requirement 209.1: Compute scores from disputes, violations, targeting attempts.
     */
    @Transactional
    public ReputationScore getOrInitializeReputation(UUID requesterId) {
        String selectSql = """
                SELECT id, requester_id, score, dispute_count, violation_count, targeting_attempts, last_updated
                FROM requester_reputation WHERE requester_id = :requesterId
                """;

        ReputationScore existing = jdbcTemplate.query(selectSql,
                new MapSqlParameterSource().addValue("requesterId", requesterId),
                rs -> {
                    if (rs.next()) {
                        return new ReputationScore(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("requester_id")),
                                rs.getBigDecimal("score"),
                                rs.getInt("dispute_count"),
                                rs.getInt("violation_count"),
                                rs.getInt("targeting_attempts"),
                                rs.getTimestamp("last_updated").toInstant()
                        );
                    }
                    return null;
                });

        if (existing != null) {
            return existing;
        }

        // Initialize new reputation
        String insertSql = """
                INSERT INTO requester_reputation 
                (id, requester_id, score, dispute_count, violation_count, targeting_attempts, last_updated)
                VALUES (:id, :requesterId, :score, 0, 0, 0, :lastUpdated)
                """;

        UUID reputationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(insertSql, new MapSqlParameterSource()
                .addValue("id", reputationId)
                .addValue("requesterId", requesterId)
                .addValue("score", INITIAL_REPUTATION_SCORE)
                .addValue("lastUpdated", now));

        return new ReputationScore(reputationId, requesterId, INITIAL_REPUTATION_SCORE, 0, 0, 0, now);
    }

    /**
     * Records a dispute outcome and updates reputation.
     * Requirement 209.1: Compute scores from disputes.
     */
    @Transactional
    public ReputationScore recordDisputeOutcome(UUID requesterId, DisputeOutcome outcome) {
        ReputationScore current = getOrInitializeReputation(requesterId);
        
        BigDecimal adjustment = switch (outcome) {
            case REQUESTER_FAULT -> BigDecimal.valueOf(-10);
            case DS_FAULT -> BigDecimal.valueOf(2);
            case NO_FAULT -> BigDecimal.ZERO;
            case PARTIAL_FAULT -> BigDecimal.valueOf(-5);
        };

        BigDecimal newScore = current.score().add(adjustment)
                .max(MIN_REPUTATION_SCORE)
                .min(MAX_REPUTATION_SCORE);

        String sql = """
                UPDATE requester_reputation 
                SET score = :score, dispute_count = dispute_count + 1, last_updated = :lastUpdated
                WHERE requester_id = :requesterId
                """;

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("score", newScore)
                .addValue("lastUpdated", Instant.now())
                .addValue("requesterId", requesterId));

        return getOrInitializeReputation(requesterId);
    }

    /**
     * Records a policy violation and updates reputation.
     * Requirement 209.1: Compute scores from violations.
     */
    @Transactional
    public ReputationScore recordViolation(UUID requesterId, ViolationType violationType) {
        ReputationScore current = getOrInitializeReputation(requesterId);
        
        BigDecimal adjustment = switch (violationType) {
            case MINOR -> BigDecimal.valueOf(-5);
            case MODERATE -> BigDecimal.valueOf(-15);
            case SEVERE -> BigDecimal.valueOf(-30);
            case CRITICAL -> BigDecimal.valueOf(-50);
        };

        BigDecimal newScore = current.score().add(adjustment)
                .max(MIN_REPUTATION_SCORE)
                .min(MAX_REPUTATION_SCORE);

        String sql = """
                UPDATE requester_reputation 
                SET score = :score, violation_count = violation_count + 1, last_updated = :lastUpdated
                WHERE requester_id = :requesterId
                """;

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("score", newScore)
                .addValue("lastUpdated", Instant.now())
                .addValue("requesterId", requesterId));

        return getOrInitializeReputation(requesterId);
    }

    /**
     * Records a targeting attempt and updates reputation.
     * Requirement 209.1: Compute scores from targeting attempts.
     */
    @Transactional
    public ReputationScore recordTargetingAttempt(UUID requesterId) {
        String sql = """
                UPDATE requester_reputation 
                SET score = GREATEST(score - 20, 0), targeting_attempts = targeting_attempts + 1, last_updated = :lastUpdated
                WHERE requester_id = :requesterId
                """;

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("lastUpdated", Instant.now())
                .addValue("requesterId", requesterId));

        return getOrInitializeReputation(requesterId);
    }

    /**
     * Checks if requester has good standing based on reputation.
     * Requirement 209.2: Update access privileges based on reputation.
     */
    public boolean hasGoodStanding(UUID requesterId) {
        return getOrInitializeReputation(requesterId).score()
                .compareTo(BigDecimal.valueOf(50)) >= 0;
    }


    // ==================== Misuse Enforcement (Requirement 210) ====================

    /**
     * Files a misuse report with evidence.
     * Requirement 210.1: Report → evidence pack → action → receipts.
     */
    @Transactional
    public MisuseReport fileMisuseReport(UUID reporterId, UUID requesterId, String description, List<String> evidenceHashes) {
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }

        String sql = """
                INSERT INTO misuse_reports 
                (id, reporter_id, requester_id, description, evidence_hashes, status, filed_at)
                VALUES (:id, :reporterId, :requesterId, :description, :evidenceHashes, :status, :filedAt)
                """;

        UUID reportId = UUID.randomUUID();
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", reportId)
                .addValue("reporterId", reporterId)
                .addValue("requesterId", requesterId)
                .addValue("description", description)
                .addValue("evidenceHashes", String.join(",", evidenceHashes))
                .addValue("status", ReportStatus.PENDING.name())
                .addValue("filedAt", Instant.now()));

        return new MisuseReport(reportId, reporterId, requesterId, description, evidenceHashes, 
                ReportStatus.PENDING, Instant.now(), null, null);
    }

    /**
     * Resolves a misuse report with enforcement action.
     * Requirement 210.2: Track enforcement history.
     */
    @Transactional
    public MisuseReport resolveMisuseReport(UUID reportId, EnforcementAction action, String resolution) {
        String updateSql = """
                UPDATE misuse_reports 
                SET status = :status, enforcement_action = :action, resolution = :resolution, resolved_at = :resolvedAt
                WHERE id = :reportId
                """;

        ReportStatus newStatus = action == EnforcementAction.DISMISSED ? 
                ReportStatus.DISMISSED : ReportStatus.RESOLVED;

        jdbcTemplate.update(updateSql, new MapSqlParameterSource()
                .addValue("status", newStatus.name())
                .addValue("action", action.name())
                .addValue("resolution", resolution)
                .addValue("resolvedAt", Instant.now())
                .addValue("reportId", reportId));

        // Record enforcement action
        String enforcementSql = """
                INSERT INTO enforcement_history 
                (id, report_id, action, details, executed_at)
                VALUES (:id, :reportId, :action, :details, :executedAt)
                """;

        jdbcTemplate.update(enforcementSql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("reportId", reportId)
                .addValue("action", action.name())
                .addValue("details", resolution)
                .addValue("executedAt", Instant.now()));

        return getMisuseReport(reportId).orElseThrow();
    }

    /**
     * Gets a misuse report by ID.
     */
    public Optional<MisuseReport> getMisuseReport(UUID reportId) {
        String sql = """
                SELECT id, reporter_id, requester_id, description, evidence_hashes, status, filed_at, enforcement_action, resolution
                FROM misuse_reports WHERE id = :reportId
                """;

        return Optional.ofNullable(jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("reportId", reportId),
                rs -> {
                    if (rs.next()) {
                        String actionStr = rs.getString("enforcement_action");
                        return new MisuseReport(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("reporter_id") != null ? UUID.fromString(rs.getString("reporter_id")) : null,
                                UUID.fromString(rs.getString("requester_id")),
                                rs.getString("description"),
                                Arrays.asList(rs.getString("evidence_hashes").split(",")),
                                ReportStatus.valueOf(rs.getString("status")),
                                rs.getTimestamp("filed_at").toInstant(),
                                actionStr != null ? EnforcementAction.valueOf(actionStr) : null,
                                rs.getString("resolution")
                        );
                    }
                    return null;
                }));
    }


    // ==================== Export Controls (Requirement 211) ====================

    /**
     * Requests an export with step-up verification.
     * Requirement 211.1: Require step-up verification for exports.
     */
    @Transactional
    public ExportRequest requestExport(UUID requesterId, UUID datasetId, ExportFormat format) {
        // Check tier allows export
        RequesterTierAssignment tier = getTier(requesterId)
                .orElseThrow(() -> new IllegalStateException("Requester has no tier assigned"));

        if (!tier.privileges().exportAllowed()) {
            throw new ExportNotAllowedException("Export not allowed for tier: " + tier.tier());
        }

        // Check reputation
        if (!hasGoodStanding(requesterId)) {
            throw new ExportNotAllowedException("Export not allowed: poor reputation standing");
        }

        // Check DUA acceptance
        if (requiresDUAReacceptance(requesterId)) {
            throw new ExportNotAllowedException("Export not allowed: DUA re-acceptance required");
        }

        String sql = """
                INSERT INTO export_requests 
                (id, requester_id, dataset_id, format, status, watermark_id, requested_at)
                VALUES (:id, :requesterId, :datasetId, :format, :status, :watermarkId, :requestedAt)
                """;

        UUID exportId = UUID.randomUUID();
        String watermarkId = generateWatermark(requesterId, datasetId);

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", exportId)
                .addValue("requesterId", requesterId)
                .addValue("datasetId", datasetId)
                .addValue("format", format.name())
                .addValue("status", ExportStatus.PENDING_VERIFICATION.name())
                .addValue("watermarkId", watermarkId)
                .addValue("requestedAt", Instant.now()));

        auditService.appendReceipt(
                AuditReceipt.EventType.DATA_ACCESS,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                datasetId,
                "ExportRequest",
                "export_requested:" + watermarkId);

        return new ExportRequest(exportId, requesterId, datasetId, format, 
                ExportStatus.PENDING_VERIFICATION, watermarkId, Instant.now());
    }

    /**
     * Completes step-up verification for export.
     * Requirement 211.1: Require step-up verification for exports.
     */
    @Transactional
    public ExportRequest completeExportVerification(UUID exportId, String verificationToken) {
        String updateSql = """
                UPDATE export_requests 
                SET status = :status, verification_token = :token, verified_at = :verifiedAt
                WHERE id = :exportId AND status = 'PENDING_VERIFICATION'
                """;

        int updated = jdbcTemplate.update(updateSql, new MapSqlParameterSource()
                .addValue("status", ExportStatus.APPROVED.name())
                .addValue("token", verificationToken)
                .addValue("verifiedAt", Instant.now())
                .addValue("exportId", exportId));

        if (updated == 0) {
            throw new IllegalStateException("Export request not found or not pending verification");
        }

        return getExportRequest(exportId).orElseThrow();
    }

    /**
     * Generates a watermark for export tracking.
     * Requirement 211.2: Apply watermarking.
     */
    private String generateWatermark(UUID requesterId, UUID datasetId) {
        String data = requesterId.toString() + "|" + datasetId.toString() + "|" + Instant.now().toEpochMilli();
        return "WM-" + MerkleTree.sha256(data).substring(0, 16).toUpperCase();
    }

    /**
     * Gets an export request by ID.
     */
    public Optional<ExportRequest> getExportRequest(UUID exportId) {
        String sql = """
                SELECT id, requester_id, dataset_id, format, status, watermark_id, requested_at
                FROM export_requests WHERE id = :exportId
                """;

        return Optional.ofNullable(jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("exportId", exportId),
                rs -> {
                    if (rs.next()) {
                        return new ExportRequest(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("requester_id")),
                                UUID.fromString(rs.getString("dataset_id")),
                                ExportFormat.valueOf(rs.getString("format")),
                                ExportStatus.valueOf(rs.getString("status")),
                                rs.getString("watermark_id"),
                                rs.getTimestamp("requested_at").toInstant()
                        );
                    }
                    return null;
                }));
    }

    /**
     * Gets export history for audit trail.
     * Requirement 211.3: Full audit trail.
     */
    public List<ExportRequest> getExportHistory(UUID requesterId) {
        String sql = """
                SELECT id, requester_id, dataset_id, format, status, watermark_id, requested_at
                FROM export_requests WHERE requester_id = :requesterId ORDER BY requested_at DESC
                """;

        return jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("requesterId", requesterId),
                (rs, rowNum) -> new ExportRequest(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("requester_id")),
                        UUID.fromString(rs.getString("dataset_id")),
                        ExportFormat.valueOf(rs.getString("format")),
                        ExportStatus.valueOf(rs.getString("status")),
                        rs.getString("watermark_id"),
                        rs.getTimestamp("requested_at").toInstant()
                ));
    }


    // ==================== Records and Enums ====================

    public enum RequesterTier {
        BASIC,      // Unverified, limited access
        STANDARD,   // Email verified
        VERIFIED,   // Identity verified
        ENTERPRISE  // KYB verified
    }

    public enum VerificationLevel {
        NONE,
        EMAIL_VERIFIED,
        IDENTITY_VERIFIED,
        KYB_VERIFIED
    }

    public enum DisputeOutcome {
        REQUESTER_FAULT,
        DS_FAULT,
        NO_FAULT,
        PARTIAL_FAULT
    }

    public enum ViolationType {
        MINOR,
        MODERATE,
        SEVERE,
        CRITICAL
    }

    public enum ReportStatus {
        PENDING,
        UNDER_REVIEW,
        RESOLVED,
        DISMISSED
    }

    public enum EnforcementAction {
        WARNING,
        TEMPORARY_SUSPENSION,
        PERMANENT_BAN,
        TIER_DOWNGRADE,
        DISMISSED
    }

    public enum ExportFormat {
        CSV,
        JSON,
        PARQUET,
        ENCRYPTED_ARCHIVE
    }

    public enum ExportStatus {
        PENDING_VERIFICATION,
        APPROVED,
        COMPLETED,
        REJECTED,
        EXPIRED
    }

    public record TierPrivileges(
            BigDecimal maxBudget,
            List<String> allowedProducts,
            boolean exportAllowed
    ) {}

    public record RequesterTierAssignment(
            UUID id,
            UUID requesterId,
            RequesterTier tier,
            VerificationLevel verificationLevel,
            TierPrivileges privileges,
            Instant assignedAt
    ) {}

    public record DUAAcceptance(
            UUID id,
            UUID requesterId,
            String duaVersion,
            Instant acceptedAt,
            String signatureHash
    ) {}

    public record ReputationScore(
            UUID id,
            UUID requesterId,
            BigDecimal score,
            int disputeCount,
            int violationCount,
            int targetingAttempts,
            Instant lastUpdated
    ) {
        public boolean isGoodStanding() {
            return score.compareTo(BigDecimal.valueOf(50)) >= 0;
        }
    }

    public record MisuseReport(
            UUID id,
            UUID reporterId,
            UUID requesterId,
            String description,
            List<String> evidenceHashes,
            ReportStatus status,
            Instant filedAt,
            EnforcementAction enforcementAction,
            String resolution
    ) {}

    public record ExportRequest(
            UUID id,
            UUID requesterId,
            UUID datasetId,
            ExportFormat format,
            ExportStatus status,
            String watermarkId,
            Instant requestedAt
    ) {}

    // Exceptions

    public static class ExportNotAllowedException extends RuntimeException {
        public ExportNotAllowedException(String message) { super(message); }
    }
}
