package com.yachaq.node.safety;

import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.ObligationTerms;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Safety & Sensitivity Gate for detecting high-risk data combinations.
 * Requirement 320.1: Detect combinations like health + minors + location.
 * Requirement 320.2: Force clean-room outputs, coarse geo/time, higher privacy floors.
 * Requirement 320.3: Display warnings for high-risk requests.
 */
public class SensitivityGate {

    // Sensitive label categories
    private static final Set<String> HEALTH_LABELS = Set.of(
            "domain:health", "domain:health:*",
            "health:condition", "health:medication", "health:diagnosis",
            "health:mental", "health:reproductive", "health:genetic"
    );

    private static final Set<String> MINOR_LABELS = Set.of(
            "demographic:minor", "demographic:child", "demographic:teen",
            "age:under_18", "age:under_13", "privacy:minor"
    );

    private static final Set<String> LOCATION_LABELS = Set.of(
            "domain:location", "geo:precise", "geo:home", "geo:work",
            "location:realtime", "location:history", "location:frequent"
    );

    private static final Set<String> FINANCIAL_LABELS = Set.of(
            "domain:financial", "financial:income", "financial:debt",
            "financial:credit", "financial:transaction"
    );

    private static final Set<String> BIOMETRIC_LABELS = Set.of(
            "biometric:face", "biometric:fingerprint", "biometric:voice",
            "biometric:gait", "biometric:iris"
    );

    private static final Set<String> COMMUNICATION_LABELS = Set.of(
            "domain:communication", "communication:content", "communication:metadata",
            "communication:contacts", "communication:private"
    );

    // Risk thresholds
    private static final int HIGH_RISK_THRESHOLD = 3;
    private static final int CRITICAL_RISK_THRESHOLD = 5;

    private final SensitivityConfig config;

    public SensitivityGate() {
        this(SensitivityConfig.defaultConfig());
    }

    public SensitivityGate(SensitivityConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
    }

    /**
     * Evaluates a request for sensitivity risks.
     * Requirement 320.1: Detect combinations like health + minors + location.
     */
    public SensitivityAssessment assess(DataRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        Set<String> allLabels = new HashSet<>(request.requiredLabels());
        allLabels.addAll(request.optionalLabels());

        List<RiskFactor> riskFactors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check individual sensitive categories
        boolean hasHealth = containsAny(allLabels, HEALTH_LABELS);
        boolean hasMinor = containsAny(allLabels, MINOR_LABELS);
        boolean hasLocation = containsAny(allLabels, LOCATION_LABELS);
        boolean hasFinancial = containsAny(allLabels, FINANCIAL_LABELS);
        boolean hasBiometric = containsAny(allLabels, BIOMETRIC_LABELS);
        boolean hasCommunication = containsAny(allLabels, COMMUNICATION_LABELS);

        // Add individual risk factors
        if (hasHealth) {
            riskFactors.add(new RiskFactor(RiskCategory.HEALTH_DATA, 2, 
                    "Request includes health-related data"));
        }
        if (hasMinor) {
            riskFactors.add(new RiskFactor(RiskCategory.MINOR_DATA, 3, 
                    "Request may involve data from minors"));
        }
        if (hasLocation) {
            riskFactors.add(new RiskFactor(RiskCategory.LOCATION_DATA, 2, 
                    "Request includes location data"));
        }
        if (hasFinancial) {
            riskFactors.add(new RiskFactor(RiskCategory.FINANCIAL_DATA, 2, 
                    "Request includes financial data"));
        }
        if (hasBiometric) {
            riskFactors.add(new RiskFactor(RiskCategory.BIOMETRIC_DATA, 3, 
                    "Request includes biometric data"));
        }
        if (hasCommunication) {
            riskFactors.add(new RiskFactor(RiskCategory.COMMUNICATION_DATA, 2, 
                    "Request includes communication data"));
        }

        // Check dangerous combinations
        // Requirement 320.1: Detect combinations like health + minors + location
        if (hasHealth && hasMinor) {
            riskFactors.add(new RiskFactor(RiskCategory.HEALTH_MINOR_COMBO, 4, 
                    "CRITICAL: Health data combined with minor data"));
            warnings.add("This request combines health data with data that may involve minors. " +
                    "Extra protections will be enforced.");
        }

        if (hasHealth && hasLocation) {
            riskFactors.add(new RiskFactor(RiskCategory.HEALTH_LOCATION_COMBO, 3, 
                    "Health data combined with location data increases re-identification risk"));
            warnings.add("Health data combined with location data may enable re-identification.");
        }

        if (hasMinor && hasLocation) {
            riskFactors.add(new RiskFactor(RiskCategory.MINOR_LOCATION_COMBO, 4, 
                    "CRITICAL: Location data combined with minor data"));
            warnings.add("Location data combined with minor data requires maximum protection.");
        }

        // Triple combination - highest risk
        if (hasHealth && hasMinor && hasLocation) {
            riskFactors.add(new RiskFactor(RiskCategory.HEALTH_MINOR_LOCATION_COMBO, 5, 
                    "CRITICAL: Health + Minor + Location combination detected"));
            warnings.add("CRITICAL: This request combines health, minor, and location data. " +
                    "Clean-room output mode will be enforced.");
        }

        if (hasBiometric && hasMinor) {
            riskFactors.add(new RiskFactor(RiskCategory.BIOMETRIC_MINOR_COMBO, 5, 
                    "CRITICAL: Biometric data combined with minor data"));
            warnings.add("Biometric data from minors requires maximum protection.");
        }

        // Check output mode risk
        if (request.outputMode() == OutputMode.RAW_EXPORT) {
            riskFactors.add(new RiskFactor(RiskCategory.RAW_EXPORT, 2, 
                    "Raw export mode requested"));
        }

        // Calculate total risk score
        int totalRiskScore = riskFactors.stream()
                .mapToInt(RiskFactor::score)
                .sum();

        // Determine risk level
        RiskLevel riskLevel = determineRiskLevel(totalRiskScore);

        // Determine required protections
        Set<RequiredProtection> requiredProtections = determineProtections(riskFactors, riskLevel);

        return new SensitivityAssessment(
                riskLevel,
                totalRiskScore,
                riskFactors,
                warnings,
                requiredProtections,
                !requiredProtections.isEmpty()
        );
    }


    /**
     * Applies forced defaults to a contract draft based on sensitivity assessment.
     * Requirement 320.2: Force clean-room outputs, coarse geo/time, higher privacy floors.
     */
    public ContractDraft applyForcedDefaults(ContractDraft draft, SensitivityAssessment assessment) {
        Objects.requireNonNull(draft, "Draft cannot be null");
        Objects.requireNonNull(assessment, "Assessment cannot be null");

        if (!assessment.requiresIntervention()) {
            return draft;
        }

        OutputMode forcedOutputMode = draft.outputMode();
        Set<String> filteredLabels = new HashSet<>(draft.selectedLabels());
        ObligationTerms forcedObligations = draft.obligations();
        Map<String, Object> metadata = new HashMap<>(draft.metadata());

        // Apply required protections
        for (RequiredProtection protection : assessment.requiredProtections()) {
            switch (protection) {
                case CLEAN_ROOM_ONLY -> {
                    forcedOutputMode = OutputMode.CLEAN_ROOM;
                    metadata.put("forced_clean_room", true);
                }
                case AGGREGATE_ONLY -> {
                    if (forcedOutputMode != OutputMode.CLEAN_ROOM) {
                        forcedOutputMode = OutputMode.AGGREGATE_ONLY;
                    }
                    metadata.put("forced_aggregate", true);
                }
                case COARSE_GEO -> {
                    // Remove precise geo labels, keep only coarse
                    filteredLabels = filteredLabels.stream()
                            .filter(l -> !l.contains("geo:precise") && !l.contains("location:realtime"))
                            .collect(Collectors.toSet());
                    metadata.put("forced_coarse_geo", true);
                }
                case COARSE_TIME -> {
                    // Remove precise time labels
                    filteredLabels = filteredLabels.stream()
                            .filter(l -> !l.contains("time:precise") && !l.contains("time:realtime"))
                            .collect(Collectors.toSet());
                    metadata.put("forced_coarse_time", true);
                }
                case HIGHER_PRIVACY_FLOOR -> {
                    metadata.put("privacy_floor", "cleanroom");
                    metadata.put("forced_privacy_floor", true);
                }
                case SHORTER_RETENTION -> {
                    forcedObligations = new ObligationTerms(
                            Math.min(7, forcedObligations != null ? forcedObligations.retentionDays() : 30),
                            ObligationTerms.RetentionPolicy.DELETE_AFTER_USE,
                            forcedObligations != null ? forcedObligations.usageRestrictions() : Set.of(),
                            ObligationTerms.DeletionRequirement.BOTH
                    );
                    metadata.put("forced_short_retention", true);
                }
                case NO_EXPORT -> {
                    if (forcedOutputMode == OutputMode.EXPORT_ALLOWED || 
                        forcedOutputMode == OutputMode.RAW_EXPORT) {
                        forcedOutputMode = OutputMode.CLEAN_ROOM;
                    }
                    metadata.put("forced_no_export", true);
                }
                case ADDITIONAL_CONSENT -> {
                    metadata.put("requires_additional_consent", true);
                }
            }
        }

        // Add warnings to metadata
        metadata.put("sensitivity_warnings", assessment.warnings());
        metadata.put("risk_level", assessment.riskLevel().name());
        metadata.put("risk_score", assessment.totalRiskScore());

        // Build new draft with forced defaults
        return new ContractDraft(
                draft.id(),
                draft.requestId(),
                draft.requesterId(),
                draft.dsNodeId(),
                filteredLabels,
                draft.timeWindow(),
                forcedOutputMode,
                draft.identityReveal(),
                draft.compensation(),
                draft.escrowId(),
                draft.ttl(),
                forcedObligations != null ? forcedObligations : draft.obligations(),
                draft.nonce(),
                draft.createdAt(),
                metadata
        );
    }

    /**
     * Generates consent warnings for display to the user.
     * Requirement 320.3: Display warnings for high-risk requests.
     */
    public List<ConsentWarning> generateWarnings(SensitivityAssessment assessment) {
        List<ConsentWarning> warnings = new ArrayList<>();

        for (RiskFactor factor : assessment.riskFactors()) {
            ConsentWarning.Severity severity = switch (factor.score()) {
                case 5 -> ConsentWarning.Severity.CRITICAL;
                case 4 -> ConsentWarning.Severity.HIGH;
                case 3 -> ConsentWarning.Severity.MEDIUM;
                default -> ConsentWarning.Severity.LOW;
            };

            warnings.add(new ConsentWarning(
                    factor.category().name(),
                    factor.description(),
                    severity,
                    getRecommendation(factor.category())
            ));
        }

        // Add protection warnings
        for (RequiredProtection protection : assessment.requiredProtections()) {
            warnings.add(new ConsentWarning(
                    "PROTECTION_ENFORCED",
                    "Protection enforced: " + protection.getDescription(),
                    ConsentWarning.Severity.INFO,
                    "This protection has been automatically applied for your safety."
            ));
        }

        return warnings;
    }

    /**
     * Checks if a request should be blocked entirely.
     */
    public boolean shouldBlock(SensitivityAssessment assessment) {
        // Block if risk score exceeds critical threshold and config allows blocking
        if (assessment.totalRiskScore() >= CRITICAL_RISK_THRESHOLD && config.blockCriticalRisk()) {
            return true;
        }

        // Block specific dangerous combinations if configured
        for (RiskFactor factor : assessment.riskFactors()) {
            if (factor.category() == RiskCategory.HEALTH_MINOR_LOCATION_COMBO && 
                config.blockHealthMinorLocation()) {
                return true;
            }
            if (factor.category() == RiskCategory.BIOMETRIC_MINOR_COMBO && 
                config.blockBiometricMinor()) {
                return true;
            }
        }

        return false;
    }

    private boolean containsAny(Set<String> labels, Set<String> patterns) {
        for (String label : labels) {
            for (String pattern : patterns) {
                if (pattern.endsWith("*")) {
                    if (label.startsWith(pattern.substring(0, pattern.length() - 1))) {
                        return true;
                    }
                } else if (label.equals(pattern) || label.startsWith(pattern + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    private RiskLevel determineRiskLevel(int totalScore) {
        if (totalScore >= CRITICAL_RISK_THRESHOLD) {
            return RiskLevel.CRITICAL;
        } else if (totalScore >= HIGH_RISK_THRESHOLD) {
            return RiskLevel.HIGH;
        } else if (totalScore >= 2) {
            return RiskLevel.MEDIUM;
        } else if (totalScore >= 1) {
            return RiskLevel.LOW;
        }
        return RiskLevel.NONE;
    }

    private Set<RequiredProtection> determineProtections(List<RiskFactor> factors, RiskLevel level) {
        Set<RequiredProtection> protections = EnumSet.noneOf(RequiredProtection.class);

        // Critical level always requires clean room and no export
        if (level == RiskLevel.CRITICAL) {
            protections.add(RequiredProtection.CLEAN_ROOM_ONLY);
            protections.add(RequiredProtection.NO_EXPORT);
            protections.add(RequiredProtection.SHORTER_RETENTION);
            protections.add(RequiredProtection.ADDITIONAL_CONSENT);
        }

        // High level requires aggregate only and coarse data
        if (level == RiskLevel.HIGH || level == RiskLevel.CRITICAL) {
            protections.add(RequiredProtection.COARSE_GEO);
            protections.add(RequiredProtection.COARSE_TIME);
            protections.add(RequiredProtection.HIGHER_PRIVACY_FLOOR);
        }

        // Check specific combinations
        for (RiskFactor factor : factors) {
            switch (factor.category()) {
                case HEALTH_MINOR_COMBO, HEALTH_MINOR_LOCATION_COMBO, BIOMETRIC_MINOR_COMBO -> {
                    protections.add(RequiredProtection.CLEAN_ROOM_ONLY);
                    protections.add(RequiredProtection.NO_EXPORT);
                    protections.add(RequiredProtection.ADDITIONAL_CONSENT);
                }
                case MINOR_LOCATION_COMBO -> {
                    protections.add(RequiredProtection.COARSE_GEO);
                    protections.add(RequiredProtection.NO_EXPORT);
                }
                case HEALTH_LOCATION_COMBO -> {
                    protections.add(RequiredProtection.COARSE_GEO);
                    protections.add(RequiredProtection.AGGREGATE_ONLY);
                }
                default -> {}
            }
        }

        return protections;
    }

    private String getRecommendation(RiskCategory category) {
        return switch (category) {
            case HEALTH_DATA -> "Consider limiting health data sharing to aggregate statistics only.";
            case MINOR_DATA -> "Data involving minors requires extra caution. Consider declining.";
            case LOCATION_DATA -> "Location data can reveal sensitive patterns. Use coarse location only.";
            case FINANCIAL_DATA -> "Financial data should only be shared with verified organizations.";
            case BIOMETRIC_DATA -> "Biometric data is highly sensitive and cannot be changed if compromised.";
            case COMMUNICATION_DATA -> "Communication data may reveal private conversations.";
            case HEALTH_MINOR_COMBO -> "CRITICAL: This combination requires maximum protection.";
            case HEALTH_LOCATION_COMBO -> "This combination increases re-identification risk.";
            case MINOR_LOCATION_COMBO -> "CRITICAL: Never share precise location data involving minors.";
            case HEALTH_MINOR_LOCATION_COMBO -> "CRITICAL: This is the highest risk combination. Consider declining.";
            case BIOMETRIC_MINOR_COMBO -> "CRITICAL: Biometric data from minors should never be exported.";
            case RAW_EXPORT -> "Raw export allows full data access. Consider aggregate mode instead.";
        };
    }


    // ==================== Enums and Records ====================

    /**
     * Risk levels for sensitivity assessment.
     */
    public enum RiskLevel {
        NONE(0),
        LOW(1),
        MEDIUM(2),
        HIGH(3),
        CRITICAL(4);

        private final int level;

        RiskLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public boolean isAtLeast(RiskLevel other) {
            return this.level >= other.level;
        }
    }

    /**
     * Categories of risk factors.
     */
    public enum RiskCategory {
        HEALTH_DATA,
        MINOR_DATA,
        LOCATION_DATA,
        FINANCIAL_DATA,
        BIOMETRIC_DATA,
        COMMUNICATION_DATA,
        HEALTH_MINOR_COMBO,
        HEALTH_LOCATION_COMBO,
        MINOR_LOCATION_COMBO,
        HEALTH_MINOR_LOCATION_COMBO,
        BIOMETRIC_MINOR_COMBO,
        RAW_EXPORT
    }

    /**
     * Required protections based on risk assessment.
     */
    public enum RequiredProtection {
        CLEAN_ROOM_ONLY("Output restricted to clean room viewing only"),
        AGGREGATE_ONLY("Only aggregated results allowed"),
        COARSE_GEO("Precise location data removed, only coarse geo allowed"),
        COARSE_TIME("Precise timestamps removed, only coarse time buckets allowed"),
        HIGHER_PRIVACY_FLOOR("Privacy floor elevated to maximum"),
        SHORTER_RETENTION("Data retention period reduced"),
        NO_EXPORT("Data export disabled"),
        ADDITIONAL_CONSENT("Additional explicit consent required");

        private final String description;

        RequiredProtection(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Individual risk factor.
     */
    public record RiskFactor(
            RiskCategory category,
            int score,
            String description
    ) {
        public RiskFactor {
            Objects.requireNonNull(category, "Category cannot be null");
            Objects.requireNonNull(description, "Description cannot be null");
            if (score < 0 || score > 5) {
                throw new IllegalArgumentException("Score must be between 0 and 5");
            }
        }
    }

    /**
     * Complete sensitivity assessment result.
     */
    public record SensitivityAssessment(
            RiskLevel riskLevel,
            int totalRiskScore,
            List<RiskFactor> riskFactors,
            List<String> warnings,
            Set<RequiredProtection> requiredProtections,
            boolean requiresIntervention
    ) {
        public SensitivityAssessment {
            Objects.requireNonNull(riskLevel, "Risk level cannot be null");
            riskFactors = List.copyOf(riskFactors);
            warnings = List.copyOf(warnings);
            requiredProtections = Set.copyOf(requiredProtections);
        }

        public boolean isCritical() {
            return riskLevel == RiskLevel.CRITICAL;
        }

        public boolean isHighRisk() {
            return riskLevel.isAtLeast(RiskLevel.HIGH);
        }
    }

    /**
     * Consent warning for display to user.
     */
    public record ConsentWarning(
            String code,
            String message,
            Severity severity,
            String recommendation
    ) {
        public enum Severity {
            INFO, LOW, MEDIUM, HIGH, CRITICAL
        }
    }

    /**
     * Configuration for sensitivity gate behavior.
     */
    public record SensitivityConfig(
            boolean blockCriticalRisk,
            boolean blockHealthMinorLocation,
            boolean blockBiometricMinor,
            boolean enforceCleanRoomForHighRisk,
            int maxAllowedRiskScore
    ) {
        public static SensitivityConfig defaultConfig() {
            return new SensitivityConfig(
                    false,  // Don't block by default, just enforce protections
                    false,  // Don't block, but enforce clean room
                    false,  // Don't block, but enforce clean room
                    true,   // Enforce clean room for high risk
                    10      // Max allowed risk score
            );
        }

        public static SensitivityConfig strictConfig() {
            return new SensitivityConfig(
                    true,   // Block critical risk
                    true,   // Block health + minor + location
                    true,   // Block biometric + minor
                    true,   // Enforce clean room
                    5       // Lower max risk score
            );
        }
    }
}
