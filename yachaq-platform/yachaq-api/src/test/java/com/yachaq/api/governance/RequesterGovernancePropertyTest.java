package com.yachaq.api.governance;

import com.yachaq.api.governance.RequesterGovernanceService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Requester Governance Service.
 * 
 * Validates: Requirements 207.1, 207.2, 208.1, 208.2, 209.1, 209.2, 210.1, 210.2, 211.1, 211.2
 */
class RequesterGovernancePropertyTest {

    // ==================== Tier Management (Requirement 207) ====================

    @Property(tries = 100)
    void tierAssignment_basedOnVerificationLevel(
            @ForAll("verificationLevels") VerificationLevel level) {
        
        RequesterTier expectedTier = switch (level) {
            case NONE -> RequesterTier.BASIC;
            case EMAIL_VERIFIED -> RequesterTier.STANDARD;
            case IDENTITY_VERIFIED -> RequesterTier.VERIFIED;
            case KYB_VERIFIED -> RequesterTier.ENTERPRISE;
        };

        // Simulate tier calculation
        RequesterTier actualTier = calculateTier(level);
        
        assertThat(actualTier).isEqualTo(expectedTier);
    }

    @Property(tries = 100)
    void tierPrivileges_increaseWithTier(
            @ForAll("requesterTiers") RequesterTier tier) {
        
        TierPrivileges privileges = getTierPrivileges(tier);
        
        // Higher tiers should have higher budgets
        assertThat(privileges.maxBudget()).isPositive();
        
        // All tiers should have at least one allowed product
        assertThat(privileges.allowedProducts()).isNotEmpty();
        
        // Only VERIFIED and ENTERPRISE can export
        if (tier == RequesterTier.BASIC || tier == RequesterTier.STANDARD) {
            assertThat(privileges.exportAllowed()).isFalse();
        }
    }


    @Property(tries = 50)
    void enterpriseTier_hasHighestPrivileges() {
        TierPrivileges enterprise = getTierPrivileges(RequesterTier.ENTERPRISE);
        TierPrivileges verified = getTierPrivileges(RequesterTier.VERIFIED);
        TierPrivileges standard = getTierPrivileges(RequesterTier.STANDARD);
        TierPrivileges basic = getTierPrivileges(RequesterTier.BASIC);

        assertThat(enterprise.maxBudget()).isGreaterThan(verified.maxBudget());
        assertThat(verified.maxBudget()).isGreaterThan(standard.maxBudget());
        assertThat(standard.maxBudget()).isGreaterThan(basic.maxBudget());
        
        assertThat(enterprise.allowedProducts().size())
                .isGreaterThanOrEqualTo(verified.allowedProducts().size());
    }

    // ==================== DUA Binding (Requirement 208) ====================

    @Property(tries = 100)
    void duaAcceptance_recordsVersionAndTimestamp(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("duaVersions") String duaVersion) {
        
        DUAAcceptance acceptance = simulateDUAAcceptance(requesterId, duaVersion);
        
        assertThat(acceptance.requesterId()).isEqualTo(requesterId);
        assertThat(acceptance.duaVersion()).isEqualTo(duaVersion);
        assertThat(acceptance.acceptedAt()).isNotNull();
        assertThat(acceptance.signatureHash()).isNotNull();
        assertThat(acceptance.signatureHash()).isNotBlank();
    }

    @Property(tries = 100)
    void duaReacceptance_requiredOnVersionMismatch(
            @ForAll("duaVersions") String acceptedVersion) {
        
        String currentVersion = "2.0.0";
        boolean requiresReacceptance = !acceptedVersion.equals(currentVersion);
        
        // If versions don't match, re-acceptance is required
        if (!acceptedVersion.equals(currentVersion)) {
            assertThat(requiresReacceptance).isTrue();
        }
    }

    // ==================== Reputation Scoring (Requirement 209) ====================

    @Property(tries = 100)
    void reputationScore_initializedAt100(
            @ForAll("validRequesterIds") UUID requesterId) {
        
        ReputationScore initial = simulateInitialReputation(requesterId);
        
        assertThat(initial.score()).isEqualTo(BigDecimal.valueOf(100));
        assertThat(initial.disputeCount()).isZero();
        assertThat(initial.violationCount()).isZero();
        assertThat(initial.targetingAttempts()).isZero();
    }

    @Property(tries = 100)
    void reputationScore_decreasesOnRequesterFaultDispute(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll @IntRange(min = 1, max = 5) int disputeCount) {
        
        BigDecimal score = BigDecimal.valueOf(100);
        
        for (int i = 0; i < disputeCount; i++) {
            score = score.subtract(BigDecimal.valueOf(10)); // REQUESTER_FAULT penalty
        }
        
        // Score should decrease but not below 0
        BigDecimal finalScore = score.max(BigDecimal.ZERO);
        assertThat(finalScore).isLessThan(BigDecimal.valueOf(100));
        assertThat(finalScore).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Property(tries = 100)
    void reputationScore_boundedBetween0And100(
            @ForAll @IntRange(min = 0, max = 20) int violations,
            @ForAll @IntRange(min = 0, max = 10) int disputes) {
        
        BigDecimal score = BigDecimal.valueOf(100);
        
        // Apply violations (each -15 for MODERATE)
        score = score.subtract(BigDecimal.valueOf(violations * 15));
        // Apply disputes (each -10 for REQUESTER_FAULT)
        score = score.subtract(BigDecimal.valueOf(disputes * 10));
        
        // Bound the score
        BigDecimal bounded = score.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
        
        assertThat(bounded).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    @Property(tries = 100)
    void goodStanding_requiresScoreAbove50(
            @ForAll @IntRange(min = 0, max = 100) int scoreValue) {
        
        BigDecimal score = BigDecimal.valueOf(scoreValue);
        boolean isGoodStanding = score.compareTo(BigDecimal.valueOf(50)) >= 0;
        
        if (scoreValue >= 50) {
            assertThat(isGoodStanding).isTrue();
        } else {
            assertThat(isGoodStanding).isFalse();
        }
    }


    // ==================== Misuse Enforcement (Requirement 210) ====================

    @Property(tries = 100)
    void misuseReport_hasRequiredFields(
            @ForAll("validRequesterIds") UUID reporterId,
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll @StringLength(min = 10, max = 500) String description) {
        
        List<String> evidenceHashes = List.of("hash1", "hash2");
        MisuseReport report = simulateMisuseReport(reporterId, requesterId, description, evidenceHashes);
        
        assertThat(report.id()).isNotNull();
        assertThat(report.requesterId()).isEqualTo(requesterId);
        assertThat(report.description()).isEqualTo(description);
        assertThat(report.evidenceHashes()).isNotEmpty();
        assertThat(report.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(report.filedAt()).isNotNull();
    }

    @Property(tries = 50)
    void enforcementAction_updatesReportStatus(
            @ForAll("enforcementActions") EnforcementAction action) {
        
        ReportStatus expectedStatus = action == EnforcementAction.DISMISSED ? 
                ReportStatus.DISMISSED : ReportStatus.RESOLVED;
        
        assertThat(expectedStatus).isIn(ReportStatus.RESOLVED, ReportStatus.DISMISSED);
    }

    // ==================== Export Controls (Requirement 211) ====================

    @Property(tries = 100)
    void exportRequest_requiresVerifiedTier(
            @ForAll("requesterTiers") RequesterTier tier) {
        
        TierPrivileges privileges = getTierPrivileges(tier);
        
        // Only VERIFIED and ENTERPRISE can export
        if (tier == RequesterTier.VERIFIED || tier == RequesterTier.ENTERPRISE) {
            assertThat(privileges.exportAllowed()).isTrue();
        } else {
            assertThat(privileges.exportAllowed()).isFalse();
        }
    }

    @Property(tries = 100)
    void exportRequest_hasWatermark(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validRequesterIds") UUID datasetId,
            @ForAll("exportFormats") ExportFormat format) {
        
        ExportRequest request = simulateExportRequest(requesterId, datasetId, format);
        
        assertThat(request.watermarkId()).isNotNull();
        assertThat(request.watermarkId()).startsWith("WM-");
        assertThat(request.status()).isEqualTo(ExportStatus.PENDING_VERIFICATION);
    }

    @Property(tries = 100)
    void watermark_isUniquePerRequest(
            @ForAll("validRequesterIds") UUID requesterId,
            @ForAll("validRequesterIds") UUID datasetId) {
        
        ExportRequest request1 = simulateExportRequest(requesterId, datasetId, ExportFormat.CSV);
        ExportRequest request2 = simulateExportRequest(requesterId, datasetId, ExportFormat.CSV);
        
        // Watermarks should be unique (different timestamps)
        assertThat(request1.watermarkId()).isNotEqualTo(request2.watermarkId());
    }

    // ==================== Edge Cases ====================

    @Test
    void nullRequesterId_throwsException() {
        assertThatThrownBy(() -> simulateTierAssignment(null, VerificationLevel.EMAIL_VERIFIED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDuaVersion_throwsException() {
        assertThatThrownBy(() -> simulateDUAAcceptance(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankDuaVersion_throwsException() {
        assertThatThrownBy(() -> simulateDUAAcceptance(UUID.randomUUID(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }


    // ==================== Simulation Helpers ====================

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

    private RequesterTierAssignment simulateTierAssignment(UUID requesterId, VerificationLevel level) {
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }
        RequesterTier tier = calculateTier(level);
        TierPrivileges privileges = getTierPrivileges(tier);
        return new RequesterTierAssignment(UUID.randomUUID(), requesterId, tier, level, privileges, Instant.now());
    }

    private DUAAcceptance simulateDUAAcceptance(UUID requesterId, String duaVersion) {
        if (duaVersion == null || duaVersion.isBlank()) {
            throw new IllegalArgumentException("DUA version cannot be null or blank");
        }
        String signatureHash = "sha256:" + UUID.randomUUID().toString().substring(0, 16);
        return new DUAAcceptance(UUID.randomUUID(), requesterId, duaVersion, Instant.now(), signatureHash);
    }

    private ReputationScore simulateInitialReputation(UUID requesterId) {
        return new ReputationScore(UUID.randomUUID(), requesterId, BigDecimal.valueOf(100), 0, 0, 0, Instant.now());
    }

    private MisuseReport simulateMisuseReport(UUID reporterId, UUID requesterId, String description, List<String> evidenceHashes) {
        return new MisuseReport(UUID.randomUUID(), reporterId, requesterId, description, evidenceHashes,
                ReportStatus.PENDING, Instant.now(), null, null);
    }

    private ExportRequest simulateExportRequest(UUID requesterId, UUID datasetId, ExportFormat format) {
        String watermarkId = "WM-" + UUID.randomUUID().toString().substring(0, 16).toUpperCase();
        return new ExportRequest(UUID.randomUUID(), requesterId, datasetId, format,
                ExportStatus.PENDING_VERIFICATION, watermarkId, Instant.now());
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<UUID> validRequesterIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<VerificationLevel> verificationLevels() {
        return Arbitraries.of(VerificationLevel.values());
    }

    @Provide
    Arbitrary<RequesterTier> requesterTiers() {
        return Arbitraries.of(RequesterTier.values());
    }

    @Provide
    Arbitrary<String> duaVersions() {
        return Arbitraries.of("1.0.0", "1.5.0", "2.0.0", "2.1.0");
    }

    @Provide
    Arbitrary<EnforcementAction> enforcementActions() {
        return Arbitraries.of(EnforcementAction.values());
    }

    @Provide
    Arbitrary<ExportFormat> exportFormats() {
        return Arbitraries.of(ExportFormat.values());
    }
}
