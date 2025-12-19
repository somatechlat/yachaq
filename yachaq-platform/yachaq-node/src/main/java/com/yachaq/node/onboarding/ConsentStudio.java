package com.yachaq.node.onboarding;

import com.yachaq.node.contract.ContractBuilder;
import com.yachaq.node.contract.ContractBuilder.UserChoices;
import com.yachaq.node.contract.ContractDraft;
import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.planvm.PlanVM;
import com.yachaq.node.planvm.QueryPlan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Consent Studio for Provider App UI.
 * Allows users to preview plans, edit scopes, and configure consent.
 * 
 * Validates: Requirements 344.1, 344.2, 344.3, 344.4, 344.5
 */
public class ConsentStudio {

    private final ContractBuilder contractBuilder;
    private final PlanPreviewService planPreviewService;
    private final PayoutCalculator payoutCalculator;

    public ConsentStudio(ContractBuilder contractBuilder, 
                         PlanPreviewService planPreviewService,
                         PayoutCalculator payoutCalculator) {
        this.contractBuilder = Objects.requireNonNull(contractBuilder, "ContractBuilder cannot be null");
        this.planPreviewService = Objects.requireNonNull(planPreviewService, "PlanPreviewService cannot be null");
        this.payoutCalculator = Objects.requireNonNull(payoutCalculator, "PayoutCalculator cannot be null");
    }

    /**
     * Gets the plan preview with privacy impact meter.
     * Requirement 344.1: Display plan preview with privacy impact meter.
     * 
     * @param request The data request
     * @param choices Current user choices
     * @return PlanPreview with privacy impact
     */
    public PlanPreview getPlanPreview(DataRequest request, UserChoices choices) {
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(choices, "Choices cannot be null");

        // Get human-readable plan description
        String planDescription = planPreviewService.describePlan(request, choices);
        
        // Calculate privacy impact
        PrivacyImpact privacyImpact = calculatePrivacyImpact(request, choices);
        
        // Get data categories being shared
        List<DataCategory> dataCategories = categorizeLabels(choices.selectedLabels());
        
        // Calculate estimated payout
        BigDecimal estimatedPayout = payoutCalculator.calculate(request, choices);

        return new PlanPreview(
                request.id(),
                planDescription,
                privacyImpact,
                dataCategories,
                choices.outputMode() != null ? choices.outputMode() : request.outputMode(),
                estimatedPayout,
                request.compensation() != null ? request.compensation().currency() : "USD",
                Instant.now()
        );
    }


    /**
     * Gets the scope editor view.
     * Requirement 344.2: Allow editing label families, time window, geo/time granularity, output mode.
     * 
     * @param request The data request
     * @param currentChoices Current user choices
     * @return ScopeEditorView with editable options
     */
    public ScopeEditorView getScopeEditor(DataRequest request, UserChoices currentChoices) {
        Objects.requireNonNull(request, "Request cannot be null");

        // Get available label families
        List<LabelFamily> labelFamilies = buildLabelFamilies(request);
        
        // Get available output modes (user can only choose more restrictive)
        List<OutputModeOption> outputModes = getAvailableOutputModes(request.outputMode());
        
        // Get time window options
        TimeWindowOptions timeOptions = buildTimeWindowOptions(request);
        
        // Get granularity options
        GranularityOptions granularityOptions = buildGranularityOptions();

        return new ScopeEditorView(
                request.id(),
                labelFamilies,
                outputModes,
                timeOptions,
                granularityOptions,
                currentChoices != null ? currentChoices.selectedLabels() : request.requiredLabels(),
                currentChoices != null ? currentChoices.outputMode() : request.outputMode()
        );
    }

    /**
     * Gets the identity reveal switch state.
     * Requirement 344.3: Show explicit switch with default OFF.
     * 
     * @param currentChoices Current user choices
     * @return IdentityRevealState with current state and options
     */
    public IdentityRevealState getIdentityRevealState(UserChoices currentChoices) {
        boolean isRevealed = currentChoices != null && currentChoices.revealIdentity();
        
        List<IdentityRevealOption> options = List.of(
                new IdentityRevealOption("ANONYMOUS", "Stay Anonymous", 
                        "Your identity will not be revealed to the requester", true),
                new IdentityRevealOption("BASIC", "Basic Identity", 
                        "Share your verified account status only", false),
                new IdentityRevealOption("FULL", "Full Identity", 
                        "Share your verified identity details", false)
        );

        return new IdentityRevealState(
                isRevealed,
                currentChoices != null ? currentChoices.identityRevealLevel() : "ANONYMOUS",
                options,
                "Identity reveal is OFF by default for your privacy protection"
        );
    }

    /**
     * Calculates how payout changes based on selections.
     * Requirement 344.4: Show how payout changes based on selections.
     * 
     * @param request The data request
     * @param baseChoices Base choices
     * @param modifiedChoices Modified choices
     * @return PayoutComparison showing the difference
     */
    public PayoutComparison comparePayouts(DataRequest request, 
                                           UserChoices baseChoices, 
                                           UserChoices modifiedChoices) {
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(baseChoices, "Base choices cannot be null");
        Objects.requireNonNull(modifiedChoices, "Modified choices cannot be null");

        BigDecimal basePayout = payoutCalculator.calculate(request, baseChoices);
        BigDecimal modifiedPayout = payoutCalculator.calculate(request, modifiedChoices);
        BigDecimal difference = modifiedPayout.subtract(basePayout);
        
        List<PayoutFactor> factors = analyzePayoutFactors(request, baseChoices, modifiedChoices);

        return new PayoutComparison(
                basePayout,
                modifiedPayout,
                difference,
                request.compensation() != null ? request.compensation().currency() : "USD",
                factors
        );
    }

    /**
     * Builds a contract draft from the current configuration.
     * 
     * @param request The data request
     * @param choices User choices
     * @return ContractDraft ready for signing
     */
    public ContractDraft buildContract(DataRequest request, UserChoices choices) {
        return contractBuilder.buildDraft(request, choices);
    }

    // ==================== Private Helper Methods ====================

    private PrivacyImpact calculatePrivacyImpact(DataRequest request, UserChoices choices) {
        int score = 0;
        List<String> factors = new ArrayList<>();
        
        // Factor: Number of labels
        int labelCount = choices.selectedLabels().size();
        if (labelCount > 5) {
            score += 30;
            factors.add("Sharing " + labelCount + " data categories");
        } else if (labelCount > 2) {
            score += 15;
            factors.add("Sharing " + labelCount + " data categories");
        } else {
            score += 5;
            factors.add("Sharing " + labelCount + " data categories");
        }
        
        // Factor: Sensitive labels
        long sensitiveCount = choices.selectedLabels().stream()
                .filter(this::isSensitiveLabel)
                .count();
        if (sensitiveCount > 0) {
            score += (int)(sensitiveCount * 15);
            factors.add(sensitiveCount + " sensitive data categories");
        }
        
        // Factor: Output mode
        OutputMode mode = choices.outputMode() != null ? choices.outputMode() : request.outputMode();
        switch (mode) {
            case RAW_EXPORT -> { score += 40; factors.add("Raw data export allowed"); }
            case EXPORT_ALLOWED -> { score += 25; factors.add("Data export allowed"); }
            case CLEAN_ROOM -> { score += 10; factors.add("Clean room access only"); }
            case AGGREGATE_ONLY -> { score += 5; factors.add("Aggregate results only"); }
        }
        
        // Factor: Identity reveal
        if (choices.revealIdentity()) {
            score += 20;
            factors.add("Identity will be revealed");
        }
        
        // Cap at 100
        score = Math.min(score, 100);
        
        PrivacyLevel level = score >= 70 ? PrivacyLevel.HIGH :
                            score >= 40 ? PrivacyLevel.MEDIUM : PrivacyLevel.LOW;

        return new PrivacyImpact(score, level, factors);
    }

    private boolean isSensitiveLabel(String label) {
        String lower = label.toLowerCase();
        return lower.contains("health") || lower.contains("finance") ||
               lower.contains("location") || lower.contains("biometric") ||
               lower.contains("medical") || lower.contains("genetic");
    }

    private List<DataCategory> categorizeLabels(Set<String> labels) {
        Map<String, List<String>> byNamespace = new HashMap<>();
        for (String label : labels) {
            String namespace = label.contains(":") ? label.split(":")[0] : "other";
            byNamespace.computeIfAbsent(namespace, k -> new ArrayList<>()).add(label);
        }
        
        return byNamespace.entrySet().stream()
                .map(e -> new DataCategory(
                        e.getKey(),
                        formatCategoryName(e.getKey()),
                        e.getValue(),
                        isSensitiveCategory(e.getKey())
                ))
                .toList();
    }

    private String formatCategoryName(String namespace) {
        return switch (namespace) {
            case "health" -> "Health & Fitness";
            case "media" -> "Media & Entertainment";
            case "finance" -> "Financial";
            case "location" -> "Location";
            case "communication" -> "Communication";
            default -> namespace.substring(0, 1).toUpperCase() + namespace.substring(1);
        };
    }

    private boolean isSensitiveCategory(String namespace) {
        return Set.of("health", "finance", "location", "biometric", "medical").contains(namespace);
    }

    private List<LabelFamily> buildLabelFamilies(DataRequest request) {
        Set<String> allLabels = new HashSet<>(request.requiredLabels());
        allLabels.addAll(request.optionalLabels());
        
        Map<String, List<LabelOption>> byFamily = new HashMap<>();
        for (String label : allLabels) {
            String family = label.contains(":") ? label.split(":")[0] : "other";
            boolean required = request.requiredLabels().contains(label);
            byFamily.computeIfAbsent(family, k -> new ArrayList<>())
                    .add(new LabelOption(label, formatLabelName(label), required));
        }
        
        return byFamily.entrySet().stream()
                .map(e -> new LabelFamily(e.getKey(), formatCategoryName(e.getKey()), e.getValue()))
                .toList();
    }

    private String formatLabelName(String label) {
        String name = label.contains(":") ? label.split(":")[1] : label;
        return Arrays.stream(name.split("_"))
                .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1))
                .reduce((a, b) -> a + " " + b)
                .orElse(name);
    }

    private List<OutputModeOption> getAvailableOutputModes(OutputMode requestMode) {
        List<OutputModeOption> options = new ArrayList<>();
        
        // User can only choose more restrictive modes
        options.add(new OutputModeOption(OutputMode.AGGREGATE_ONLY, "Aggregate Only", 
                "Only aggregated statistics will be shared", true));
        
        if (requestMode != OutputMode.AGGREGATE_ONLY) {
            options.add(new OutputModeOption(OutputMode.CLEAN_ROOM, "Clean Room", 
                    "Data viewable in secure environment only", true));
        }
        
        if (requestMode == OutputMode.EXPORT_ALLOWED || requestMode == OutputMode.RAW_EXPORT) {
            options.add(new OutputModeOption(OutputMode.EXPORT_ALLOWED, "Export Allowed", 
                    "Data can be exported by requester", true));
        }
        
        if (requestMode == OutputMode.RAW_EXPORT) {
            options.add(new OutputModeOption(OutputMode.RAW_EXPORT, "Raw Export", 
                    "Raw data can be exported", false));
        }
        
        return options;
    }

    private TimeWindowOptions buildTimeWindowOptions(DataRequest request) {
        Instant defaultStart = request.timeWindow() != null ? 
                request.timeWindow().start() : Instant.now().minusSeconds(30L * 24 * 60 * 60);
        Instant defaultEnd = request.timeWindow() != null ? 
                request.timeWindow().end() : Instant.now();
        
        return new TimeWindowOptions(defaultStart, defaultEnd, 
                List.of("Last 7 days", "Last 30 days", "Last 90 days", "Custom"));
    }

    private GranularityOptions buildGranularityOptions() {
        return new GranularityOptions(
                List.of("Day", "Week", "Month"),
                List.of("City", "Region", "Country", "None")
        );
    }

    private List<PayoutFactor> analyzePayoutFactors(DataRequest request, 
                                                     UserChoices base, 
                                                     UserChoices modified) {
        List<PayoutFactor> factors = new ArrayList<>();
        
        // Label count difference
        int baseLabelCount = base.selectedLabels().size();
        int modifiedLabelCount = modified.selectedLabels().size();
        if (baseLabelCount != modifiedLabelCount) {
            int diff = modifiedLabelCount - baseLabelCount;
            factors.add(new PayoutFactor(
                    "Data Categories",
                    diff > 0 ? "+" + diff + " categories" : diff + " categories",
                    diff > 0 ? BigDecimal.valueOf(diff * 0.5) : BigDecimal.valueOf(diff * 0.5)
            ));
        }
        
        // Output mode difference
        if (base.outputMode() != modified.outputMode()) {
            factors.add(new PayoutFactor(
                    "Output Mode",
                    "Changed to " + modified.outputMode(),
                    BigDecimal.ZERO // Simplified
            ));
        }
        
        // Identity reveal difference
        if (base.revealIdentity() != modified.revealIdentity()) {
            factors.add(new PayoutFactor(
                    "Identity Reveal",
                    modified.revealIdentity() ? "Enabled" : "Disabled",
                    modified.revealIdentity() ? BigDecimal.valueOf(1.0) : BigDecimal.valueOf(-1.0)
            ));
        }
        
        return factors;
    }


    // ==================== Inner Types ====================

    /**
     * Plan preview with privacy impact.
     */
    public record PlanPreview(
            String requestId,
            String planDescription,
            PrivacyImpact privacyImpact,
            List<DataCategory> dataCategories,
            OutputMode outputMode,
            BigDecimal estimatedPayout,
            String currency,
            Instant generatedAt
    ) {}

    /**
     * Privacy impact assessment.
     */
    public record PrivacyImpact(
            int score,
            PrivacyLevel level,
            List<String> factors
    ) {}

    /**
     * Privacy level indicator.
     */
    public enum PrivacyLevel {
        LOW("Low Impact", "Minimal data sharing"),
        MEDIUM("Medium Impact", "Moderate data sharing"),
        HIGH("High Impact", "Significant data sharing");

        private final String displayName;
        private final String description;

        PrivacyLevel(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Data category grouping.
     */
    public record DataCategory(
            String namespace,
            String displayName,
            List<String> labels,
            boolean sensitive
    ) {}

    /**
     * Scope editor view.
     */
    public record ScopeEditorView(
            String requestId,
            List<LabelFamily> labelFamilies,
            List<OutputModeOption> outputModes,
            TimeWindowOptions timeOptions,
            GranularityOptions granularityOptions,
            Set<String> currentSelectedLabels,
            OutputMode currentOutputMode
    ) {}

    /**
     * Label family grouping.
     */
    public record LabelFamily(
            String namespace,
            String displayName,
            List<LabelOption> labels
    ) {}

    /**
     * Individual label option.
     */
    public record LabelOption(
            String label,
            String displayName,
            boolean required
    ) {}

    /**
     * Output mode option.
     */
    public record OutputModeOption(
            OutputMode mode,
            String displayName,
            String description,
            boolean recommended
    ) {}

    /**
     * Time window options.
     */
    public record TimeWindowOptions(
            Instant defaultStart,
            Instant defaultEnd,
            List<String> presets
    ) {}

    /**
     * Granularity options.
     */
    public record GranularityOptions(
            List<String> timeGranularities,
            List<String> geoGranularities
    ) {}

    /**
     * Identity reveal state.
     */
    public record IdentityRevealState(
            boolean isRevealed,
            String currentLevel,
            List<IdentityRevealOption> options,
            String defaultOffMessage
    ) {}

    /**
     * Identity reveal option.
     */
    public record IdentityRevealOption(
            String level,
            String displayName,
            String description,
            boolean isDefault
    ) {}

    /**
     * Payout comparison.
     */
    public record PayoutComparison(
            BigDecimal basePayout,
            BigDecimal modifiedPayout,
            BigDecimal difference,
            String currency,
            List<PayoutFactor> factors
    ) {}

    /**
     * Payout factor.
     */
    public record PayoutFactor(
            String name,
            String change,
            BigDecimal impact
    ) {}

    /**
     * Interface for plan preview service.
     */
    public interface PlanPreviewService {
        String describePlan(DataRequest request, UserChoices choices);
    }

    /**
     * Interface for payout calculation.
     */
    public interface PayoutCalculator {
        BigDecimal calculate(DataRequest request, UserChoices choices);
    }

    /**
     * Default plan preview service.
     */
    public static class DefaultPlanPreviewService implements PlanPreviewService {
        @Override
        public String describePlan(DataRequest request, UserChoices choices) {
            StringBuilder sb = new StringBuilder();
            sb.append("This request will access ");
            sb.append(choices.selectedLabels().size());
            sb.append(" data categories from your device. ");
            
            OutputMode mode = choices.outputMode() != null ? choices.outputMode() : request.outputMode();
            sb.append("Data will be delivered in ");
            sb.append(switch (mode) {
                case AGGREGATE_ONLY -> "aggregate form only.";
                case CLEAN_ROOM -> "a secure clean room environment.";
                case EXPORT_ALLOWED -> "exportable format.";
                case RAW_EXPORT -> "raw format with full export capability.";
            });
            
            return sb.toString();
        }
    }

    /**
     * Default payout calculator.
     */
    public static class DefaultPayoutCalculator implements PayoutCalculator {
        @Override
        public BigDecimal calculate(DataRequest request, UserChoices choices) {
            if (request.compensation() == null) {
                return BigDecimal.ZERO;
            }
            
            BigDecimal base = BigDecimal.valueOf(request.compensation().amount());
            
            // Adjust based on label count
            int selectedCount = choices.selectedLabels().size();
            int requiredCount = request.requiredLabels().size();
            int optionalCount = request.optionalLabels().size();
            
            if (optionalCount > 0 && selectedCount > requiredCount) {
                double bonus = (double)(selectedCount - requiredCount) / optionalCount * 0.2;
                base = base.multiply(BigDecimal.valueOf(1 + bonus));
            }
            
            // Bonus for identity reveal
            if (choices.revealIdentity()) {
                base = base.multiply(BigDecimal.valueOf(1.1));
            }
            
            return base.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
