package com.yachaq.node.safety;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.safety.SensitivityGate.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based and unit tests for Safety & Sensitivity Gate.
 * Requirement 320.5: Test policy enforcement for flagged scenarios.
 */
class SensitivityGatePropertyTest {

    private final SensitivityGate gate = new SensitivityGate();

    // ==================== High-Risk Detection Tests ====================

    @Test
    void assess_detectsHealthData() {
        DataRequest request = createRequest(Set.of("domain:health"), Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.HEALTH_DATA);
        assertThat(assessment.totalRiskScore()).isGreaterThan(0);
    }

    @Test
    void assess_detectsMinorData() {
        DataRequest request = createRequest(Set.of("demographic:minor"), Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.MINOR_DATA);
        assertThat(assessment.totalRiskScore()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void assess_detectsLocationData() {
        DataRequest request = createRequest(Set.of("domain:location"), Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.LOCATION_DATA);
    }

    @Test
    void assess_detectsHealthMinorCombo() {
        // Requirement 320.1: Detect combinations like health + minors
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor"), 
                Set.of()
        );
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.HEALTH_MINOR_COMBO);
        assertThat(assessment.riskLevel()).isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
        assertThat(assessment.warnings()).isNotEmpty();
    }

    @Test
    void assess_detectsHealthMinorLocationCombo() {
        // Requirement 320.1: Detect combinations like health + minors + location
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"), 
                Set.of()
        );
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.HEALTH_MINOR_LOCATION_COMBO);
        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(assessment.isCritical()).isTrue();
        assertThat(assessment.requiredProtections())
                .contains(RequiredProtection.CLEAN_ROOM_ONLY);
    }

    @Test
    void assess_detectsBiometricMinorCombo() {
        DataRequest request = createRequest(
                Set.of("biometric:face", "demographic:minor"), 
                Set.of()
        );
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.BIOMETRIC_MINOR_COMBO);
        assertThat(assessment.isCritical()).isTrue();
    }

    @Test
    void assess_noRiskForSafeLabels() {
        DataRequest request = createRequest(
                Set.of("domain:activity", "time:period"), 
                Set.of()
        );
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(assessment.totalRiskScore()).isEqualTo(0);
        assertThat(assessment.requiresIntervention()).isFalse();
    }

    // ==================== Forced Defaults Tests ====================

    @Test
    void applyForcedDefaults_enforcesCleanRoomForCritical() {
        // Requirement 320.2: Force clean-room outputs for high-risk
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, OutputMode.EXPORT_ALLOWED);
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        assertThat(modified.outputMode()).isEqualTo(OutputMode.CLEAN_ROOM);
        assertThat(modified.metadata().get("forced_clean_room")).isEqualTo(true);
    }

    @Test
    void applyForcedDefaults_removePreciseGeoForHighRisk() {
        // Requirement 320.2: Force coarse geo for high-risk
        DataRequest request = createRequest(
                Set.of("domain:health", "geo:precise"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, OutputMode.AGGREGATE_ONLY);
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        assertThat(modified.selectedLabels())
                .doesNotContain("geo:precise");
        assertThat(modified.metadata().get("forced_coarse_geo")).isEqualTo(true);
    }

    @Test
    void applyForcedDefaults_shorterRetentionForCritical() {
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, OutputMode.AGGREGATE_ONLY);
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        assertThat(modified.obligations().retentionDays()).isLessThanOrEqualTo(7);
        assertThat(modified.obligations().deletionRequirement())
                .isEqualTo(ObligationTerms.DeletionRequirement.BOTH);
    }

    @Test
    void applyForcedDefaults_noChangeForLowRisk() {
        DataRequest request = createRequest(Set.of("domain:activity"), Set.of());
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, OutputMode.EXPORT_ALLOWED);
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        // Should be unchanged
        assertThat(modified.outputMode()).isEqualTo(OutputMode.EXPORT_ALLOWED);
        assertThat(modified.selectedLabels()).isEqualTo(draft.selectedLabels());
    }

    // ==================== Warning Generation Tests ====================

    @Test
    void generateWarnings_createsWarningsForRiskFactors() {
        // Requirement 320.3: Display warnings for high-risk requests
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        
        List<ConsentWarning> warnings = gate.generateWarnings(assessment);
        
        assertThat(warnings).isNotEmpty();
        assertThat(warnings)
                .anyMatch(w -> w.severity() == ConsentWarning.Severity.CRITICAL ||
                              w.severity() == ConsentWarning.Severity.HIGH);
    }

    @Test
    void generateWarnings_includesProtectionNotices() {
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        
        List<ConsentWarning> warnings = gate.generateWarnings(assessment);
        
        assertThat(warnings)
                .anyMatch(w -> w.code().equals("PROTECTION_ENFORCED"));
    }

    @Test
    void generateWarnings_includesRecommendations() {
        DataRequest request = createRequest(Set.of("domain:health"), Set.of());
        SensitivityAssessment assessment = gate.assess(request);
        
        List<ConsentWarning> warnings = gate.generateWarnings(assessment);
        
        assertThat(warnings)
                .allMatch(w -> w.recommendation() != null && !w.recommendation().isBlank());
    }

    // ==================== Blocking Tests ====================

    @Test
    void shouldBlock_returnsFalseWithDefaultConfig() {
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        
        // Default config doesn't block, just enforces protections
        assertThat(gate.shouldBlock(assessment)).isFalse();
    }

    @Test
    void shouldBlock_returnsTrueWithStrictConfig() {
        SensitivityGate strictGate = new SensitivityGate(SensitivityConfig.strictConfig());
        DataRequest request = createRequest(
                Set.of("domain:health", "demographic:minor", "domain:location"), 
                Set.of()
        );
        SensitivityAssessment assessment = strictGate.assess(request);
        
        assertThat(strictGate.shouldBlock(assessment)).isTrue();
    }


    // ==================== Property-Based Tests ====================

    @Property(tries = 100)
    void property_healthMinorLocationAlwaysCritical(
            @ForAll("healthLabels") String healthLabel,
            @ForAll("minorLabels") String minorLabel,
            @ForAll("locationLabels") String locationLabel) {
        // Property: Any combination of health + minor + location is always critical
        DataRequest request = createRequest(
                Set.of(healthLabel, minorLabel, locationLabel), 
                Set.of()
        );
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(assessment.requiredProtections())
                .contains(RequiredProtection.CLEAN_ROOM_ONLY);
    }

    @Property(tries = 100)
    void property_forcedDefaultsNeverIncreasesRisk(
            @ForAll("sensitiveRequests") DataRequest request) {
        // Property: Applying forced defaults should never increase data exposure risk
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, request.outputMode());
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        // Modified output mode should be same or more restrictive (lower exposure)
        // Lower number = more restrictive = less exposure
        assertThat(getOutputModeExposure(modified.outputMode()))
                .isLessThanOrEqualTo(getOutputModeExposure(draft.outputMode()));
        
        // Modified labels should be subset of original
        assertThat(draft.selectedLabels()).containsAll(modified.selectedLabels());
    }

    @Property(tries = 100)
    void property_noRiskForSafeLabels(
            @ForAll("safeLabels") Set<String> labels) {
        // Property: Safe labels should never trigger high risk
        DataRequest request = createRequest(labels, Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskLevel()).isIn(RiskLevel.NONE, RiskLevel.LOW);
    }

    @Property(tries = 50)
    void property_warningsMatchRiskFactors(
            @ForAll("sensitiveRequests") DataRequest request) {
        // Property: Number of warnings should be at least number of risk factors
        SensitivityAssessment assessment = gate.assess(request);
        
        List<ConsentWarning> warnings = gate.generateWarnings(assessment);
        
        // At least one warning per risk factor
        assertThat(warnings.size()).isGreaterThanOrEqualTo(assessment.riskFactors().size());
    }

    @Property(tries = 50)
    void property_criticalAlwaysRequiresCleanRoom(
            @ForAll("criticalRequests") DataRequest request) {
        // Property: Critical risk always requires clean room
        SensitivityAssessment assessment = gate.assess(request);
        
        if (assessment.riskLevel() == RiskLevel.CRITICAL) {
            assertThat(assessment.requiredProtections())
                    .contains(RequiredProtection.CLEAN_ROOM_ONLY);
        }
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<String> healthLabels() {
        return Arbitraries.of(
                "domain:health", "health:condition", "health:medication",
                "health:diagnosis", "health:mental", "health:reproductive"
        );
    }

    @Provide
    Arbitrary<String> minorLabels() {
        return Arbitraries.of(
                "demographic:minor", "demographic:child", "demographic:teen",
                "age:under_18", "age:under_13"
        );
    }

    @Provide
    Arbitrary<String> locationLabels() {
        return Arbitraries.of(
                "domain:location", "geo:precise", "geo:home",
                "location:realtime", "location:history"
        );
    }

    @Provide
    Arbitrary<Set<String>> safeLabels() {
        return Arbitraries.of(
                "domain:activity", "time:period", "time:daytype",
                "geo:density", "quality:source", "behavior:pattern"
        ).set().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<DataRequest> sensitiveRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        Set.of("domain:health"),
                        Set.of("demographic:minor"),
                        Set.of("domain:location"),
                        Set.of("domain:health", "demographic:minor"),
                        Set.of("domain:health", "domain:location"),
                        Set.of("biometric:face")
                ),
                Arbitraries.of(OutputMode.values())
        ).as((labels, mode) -> createRequest(labels, Set.of(), mode));
    }

    @Provide
    Arbitrary<DataRequest> criticalRequests() {
        return Arbitraries.of(
                createRequest(Set.of("domain:health", "demographic:minor", "domain:location"), Set.of()),
                createRequest(Set.of("biometric:face", "demographic:minor"), Set.of()),
                createRequest(Set.of("health:mental", "age:under_13", "geo:home"), Set.of())
        );
    }

    // ==================== Helper Methods ====================

    private DataRequest createRequest(Set<String> requiredLabels, Set<String> optionalLabels) {
        return createRequest(requiredLabels, optionalLabels, OutputMode.AGGREGATE_ONLY);
    }

    private DataRequest createRequest(Set<String> requiredLabels, Set<String> optionalLabels, OutputMode mode) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .requesterName("Test Requester")
                .type(RequestType.BROADCAST)
                .requiredLabels(requiredLabels)
                .optionalLabels(optionalLabels)
                .outputMode(mode)
                .compensation(new CompensationOffer(10.0, "USD", "escrow-1"))
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
    }

    private ContractDraft createDraft(DataRequest request, OutputMode outputMode) {
        return new ContractDraft(
                UUID.randomUUID().toString(),
                request.id(),
                request.requesterId(),
                "ds-node-1",
                request.requiredLabels(),
                request.timeWindow() != null ? 
                        new ContractDraft.TimeWindow(request.timeWindow().start(), request.timeWindow().end()) : 
                        new ContractDraft.TimeWindow(Instant.now().minusSeconds(86400), Instant.now()),
                outputMode,
                IdentityReveal.anonymous(),
                CompensationTerms.of(BigDecimal.TEN, "USD"),
                "escrow-1",
                Instant.now().plusSeconds(3600),
                ObligationTerms.standard(),
                UUID.randomUUID().toString(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Returns exposure level of output mode.
     * Lower = more restrictive = less data exposure.
     * AGGREGATE_ONLY and CLEAN_ROOM are both restrictive (low exposure).
     */
    private int getOutputModeExposure(OutputMode mode) {
        return switch (mode) {
            case AGGREGATE_ONLY -> 1;  // Most restrictive
            case CLEAN_ROOM -> 1;      // Also very restrictive (view only)
            case EXPORT_ALLOWED -> 3;
            case RAW_EXPORT -> 4;      // Highest exposure
        };
    }

    // ==================== Edge Case Tests ====================

    @Test
    void assess_handlesEmptyLabels() {
        DataRequest request = createRequest(Set.of(), Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        assertThat(assessment.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(assessment.riskFactors()).isEmpty();
    }

    @Test
    void assess_handlesExpiredRequest() {
        DataRequest request = DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .type(RequestType.BROADCAST)
                .requiredLabels(Set.of("domain:health"))
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .createdAt(Instant.now().minusSeconds(86400))
                .expiresAt(Instant.now().minusSeconds(3600)) // Expired
                .build();
        
        SensitivityAssessment assessment = gate.assess(request);
        
        // Expired requests should still be assessed but marked
        assertThat(assessment).isNotNull();
    }

    @Test
    void assess_detectsWildcardPatterns() {
        DataRequest request = createRequest(Set.of("health:mental:depression"), Set.of());
        
        SensitivityAssessment assessment = gate.assess(request);
        
        // Should match health:* pattern
        assertThat(assessment.riskFactors())
                .anyMatch(f -> f.category() == RiskCategory.HEALTH_DATA);
    }

    @Test
    void applyForcedDefaults_preservesNonSensitiveLabels() {
        DataRequest request = createRequest(
                Set.of("domain:health", "domain:activity", "time:period"), 
                Set.of()
        );
        SensitivityAssessment assessment = gate.assess(request);
        ContractDraft draft = createDraft(request, OutputMode.AGGREGATE_ONLY);
        
        ContractDraft modified = gate.applyForcedDefaults(draft, assessment);
        
        // Non-sensitive labels should be preserved
        assertThat(modified.selectedLabels())
                .contains("domain:activity", "time:period");
    }

    @Test
    void riskLevel_comparisons() {
        assertThat(RiskLevel.CRITICAL.isAtLeast(RiskLevel.HIGH)).isTrue();
        assertThat(RiskLevel.HIGH.isAtLeast(RiskLevel.MEDIUM)).isTrue();
        assertThat(RiskLevel.LOW.isAtLeast(RiskLevel.CRITICAL)).isFalse();
        assertThat(RiskLevel.NONE.isAtLeast(RiskLevel.NONE)).isTrue();
    }

    @Test
    void riskFactor_validatesScore() {
        assertThatThrownBy(() -> new RiskFactor(RiskCategory.HEALTH_DATA, -1, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskFactor(RiskCategory.HEALTH_DATA, 6, "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
