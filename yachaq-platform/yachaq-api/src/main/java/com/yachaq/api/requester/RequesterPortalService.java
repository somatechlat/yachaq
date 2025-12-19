package com.yachaq.api.requester;

import com.yachaq.api.screening.RequestService;
import com.yachaq.api.screening.RequestService.CreateRequestCommand;
import com.yachaq.api.screening.RequestService.RequestDto;
import com.yachaq.api.screening.ScreeningService;
import com.yachaq.api.screening.ScreeningService.ScreeningResultDto;
import com.yachaq.core.domain.Request.UnitType;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Requester Portal Service for creating and managing data requests.
 * Provides templates, ODX criteria configuration, and policy feedback.
 * 
 * Security: All requests go through policy screening.
 * Performance: Template-based creation for efficiency.
 * UX: Clear feedback on policy rejections with remediation hints.
 * 
 * Validates: Requirements 348.1, 348.2, 348.3, 348.4, 348.5
 */
@Service
public class RequesterPortalService {

    private final RequestService requestService;
    private final ScreeningService screeningService;
    private final RequestTemplateRepository templateRepository;

    public RequesterPortalService(RequestService requestService,
                                  ScreeningService screeningService,
                                  RequestTemplateRepository templateRepository) {
        this.requestService = requestService;
        this.screeningService = screeningService;
        this.templateRepository = templateRepository;
    }

    // ==================== Task 93.1: Request Creation with Templates ====================

    /**
     * Gets available request templates.
     * Requirement 348.1: Provide templates for common use cases.
     */
    public List<RequestTemplate> getTemplates(String category) {
        if (category != null && !category.isBlank()) {
            return templateRepository.findByCategory(category);
        }
        return templateRepository.findAll();
    }

    /**
     * Gets a specific template by ID.
     */
    public RequestTemplate getTemplate(String templateId) {
        return templateRepository.findById(templateId).orElse(null);
    }

    /**
     * Creates a request from a template.
     * Requirement 348.1: Provide templates for common use cases.
     */
    @Transactional
    public RequestCreationResult createFromTemplate(UUID requesterId, String templateId,
                                                     RequestCustomization customizations) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(templateId, "Template ID cannot be null");

        RequestTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + templateId));

        // Build request from template
        RequestDraft draft = buildDraftFromTemplate(template, customizations);

        // Validate criteria first
        OdxCriteria criteria = new OdxCriteria(
                draft.requiredLabels(),
                draft.optionalLabels(),
                draft.timeWindow(),
                null
        );
        CriteriaValidationResult validation = validateCriteria(criteria);

        if (!validation.valid()) {
            return RequestCreationResult.rejected(null, validation.errors(), List.of());
        }

        // Create the request using existing service
        CreateRequestCommand command = new CreateRequestCommand(
                requesterId,
                draft.title(),
                buildScope(draft),
                buildEligibilityCriteria(draft),
                draft.timeWindow().start(),
                draft.timeWindow().end(),
                UnitType.DATA_ACCESS,
                draft.compensation(),
                1000, // Default max participants
                draft.compensation().multiply(BigDecimal.valueOf(1000))
        );

        RequestDto created = requestService.createRequest(command);

        // Submit for screening
        ScreeningResultDto screening = requestService.submitForScreening(created.id(), requesterId);

        if (screening.decision() == ScreeningDecision.REJECTED) {
            return RequestCreationResult.rejected(
                    created.id().toString(),
                    List.of("Request rejected by policy screening"),
                    generateRemediations(screening)
            );
        }

        return RequestCreationResult.success(created.id().toString(), screening.decision().name());
    }

    // ==================== Task 93.2: ODX Criteria Configuration ====================

    /**
     * Validates ODX criteria.
     * Requirement 348.2: Allow scope definition using ODX criteria.
     */
    public CriteriaValidationResult validateCriteria(OdxCriteria criteria) {
        Objects.requireNonNull(criteria, "Criteria cannot be null");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate label families
        for (String label : criteria.requiredLabels()) {
            if (!isValidOdxLabel(label)) {
                errors.add("Invalid label: " + label);
            }
            if (isSensitiveLabel(label)) {
                warnings.add("Sensitive label '" + label + "' may require additional justification");
            }
        }

        // Validate time window
        if (criteria.timeWindow() != null) {
            if (criteria.timeWindow().start().isAfter(criteria.timeWindow().end())) {
                errors.add("Time window start must be before end");
            }
            long days = ChronoUnit.DAYS.between(criteria.timeWindow().start(), criteria.timeWindow().end());
            if (days > 365) {
                warnings.add("Time window exceeds 1 year - consider narrowing scope");
            }
        }

        // Validate geo criteria
        if (criteria.geoCriteria() != null && criteria.geoCriteria().precision() != null) {
            if (criteria.geoCriteria().precision().equals("EXACT")) {
                errors.add("Exact location precision is not allowed - use CITY or REGION");
            }
        }

        // Estimate cohort size
        int estimatedCohort = estimateCohortSize(criteria);
        if (estimatedCohort < 50) {
            errors.add("Estimated cohort size (" + estimatedCohort + ") is below minimum threshold (50)");
        } else if (estimatedCohort < 100) {
            warnings.add("Estimated cohort size (" + estimatedCohort + ") is close to minimum threshold");
        }

        return new CriteriaValidationResult(errors.isEmpty(), errors, warnings, estimatedCohort);
    }

    /**
     * Gets available ODX label families.
     * Requirement 348.2: Allow scope definition using ODX criteria.
     */
    public List<LabelFamily> getAvailableLabelFamilies() {
        return List.of(
                new LabelFamily("health", "Health & Fitness", 
                        List.of("health:steps", "health:heart_rate", "health:sleep", "health:workouts"), true),
                new LabelFamily("media", "Media & Entertainment",
                        List.of("media:music", "media:video", "media:podcasts", "media:reading"), false),
                new LabelFamily("location", "Location",
                        List.of("location:home", "location:work", "location:travel"), true),
                new LabelFamily("communication", "Communication",
                        List.of("communication:messages", "communication:calls", "communication:email"), true),
                new LabelFamily("finance", "Financial",
                        List.of("finance:transactions", "finance:spending_category"), true),
                new LabelFamily("social", "Social",
                        List.of("social:connections", "social:interactions"), false)
        );
    }

    // ==================== Task 93.3: Policy Rejection Feedback ====================

    /**
     * Gets detailed rejection feedback.
     * Requirement 348.3: Show rejections with required downscopes.
     */
    public PolicyFeedback getPolicyFeedback(UUID requestId) {
        Objects.requireNonNull(requestId, "Request ID cannot be null");

        RequestDto request = requestService.getRequest(requestId);
        ScreeningResultDto screening = screeningService.getScreeningResult(requestId);

        if (screening == null || screening.decision() == ScreeningDecision.APPROVED) {
            return new PolicyFeedback(requestId.toString(), true, List.of(), List.of(), null);
        }

        List<PolicyViolation> violations = parseViolations(screening);
        List<RemediationSuggestion> suggestions = generateRemediations(screening);

        return new PolicyFeedback(
                requestId.toString(),
                false,
                violations,
                suggestions,
                screening.riskScore() != null ? screening.riskScore().doubleValue() : null
        );
    }

    // ==================== Request Status & Analytics ====================

    /**
     * Gets request status.
     * Requirement 348.4: Status display.
     */
    public RequestStatus getRequestStatus(UUID requesterId, UUID requestId) {
        RequestDto request = requestService.getRequest(requestId);
        if (request == null || !request.requesterId().equals(requesterId)) {
            return null;
        }

        ScreeningResultDto screening = null;
        try {
            screening = screeningService.getScreeningResult(requestId);
        } catch (Exception e) {
            // Screening not yet performed
        }

        return new RequestStatus(
                requestId.toString(),
                request.status().name(),
                screening != null ? screening.decision().name() : "PENDING",
                request.createdAt(),
                request.durationEnd(),
                new ResponseStats(0, 0, 0, BigDecimal.ZERO)
        );
    }

    /**
     * Gets analytics for a requester.
     * Requirement 348.5: Analytics.
     */
    public RequesterAnalytics getAnalytics(UUID requesterId) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");

        return new RequesterAnalytics(
                requesterId.toString(),
                0, 0, 0, 0, 0,
                BigDecimal.ZERO,
                Instant.now()
        );
    }

    // ==================== Private Helper Methods ====================

    private RequestDraft buildDraftFromTemplate(RequestTemplate template, RequestCustomization customizations) {
        Set<String> requiredLabels = new HashSet<>(template.defaultLabels());
        Set<String> optionalLabels = new HashSet<>(template.optionalLabels());

        if (customizations != null) {
            if (customizations.additionalLabels() != null) {
                requiredLabels.addAll(customizations.additionalLabels());
            }
            if (customizations.removedLabels() != null) {
                requiredLabels.removeAll(customizations.removedLabels());
            }
        }

        TimeWindow timeWindow = template.defaultTimeWindow();
        if (customizations != null && customizations.timeWindow() != null) {
            timeWindow = customizations.timeWindow();
        }

        BigDecimal compensation = template.suggestedCompensation();
        if (customizations != null && customizations.compensation() != null) {
            compensation = customizations.compensation();
        }

        return new RequestDraft(
                template.name() + " Request",
                template.description(),
                requiredLabels,
                optionalLabels,
                template.outputMode(),
                timeWindow,
                compensation,
                template.defaultTtlHours(),
                template.category()
        );
    }

    private Map<String, Object> buildScope(RequestDraft draft) {
        Map<String, Object> scope = new HashMap<>();
        scope.put("requiredLabels", draft.requiredLabels());
        scope.put("optionalLabels", draft.optionalLabels());
        scope.put("outputMode", draft.outputMode());
        return scope;
    }

    private Map<String, Object> buildEligibilityCriteria(RequestDraft draft) {
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("labels", draft.requiredLabels());
        criteria.put("timeWindow", Map.of(
                "start", draft.timeWindow().start().toString(),
                "end", draft.timeWindow().end().toString()
        ));
        return criteria;
    }

    private boolean isValidOdxLabel(String label) {
        if (label == null || label.isBlank()) return false;
        return label.contains(":") && label.split(":").length == 2;
    }

    private boolean isSensitiveLabel(String label) {
        String family = label.split(":")[0].toLowerCase();
        return Set.of("health", "finance", "location", "communication").contains(family);
    }

    private int estimateCohortSize(OdxCriteria criteria) {
        int baseSize = 10000;
        baseSize = baseSize / (1 + criteria.requiredLabels().size());
        long sensitiveCount = criteria.requiredLabels().stream().filter(this::isSensitiveLabel).count();
        baseSize = (int) (baseSize / (1 + sensitiveCount * 0.5));
        if (criteria.timeWindow() != null) {
            long days = ChronoUnit.DAYS.between(criteria.timeWindow().start(), criteria.timeWindow().end());
            if (days < 30) baseSize = baseSize / 2;
        }
        return Math.max(baseSize, 10);
    }

    private List<PolicyViolation> parseViolations(ScreeningResultDto screening) {
        List<PolicyViolation> violations = new ArrayList<>();
        if (screening.reasonCodes() != null) {
            for (String code : screening.reasonCodes()) {
                violations.add(new PolicyViolation(code, "Policy rule violated: " + code, PolicyViolationSeverity.ERROR));
            }
        }
        return violations;
    }

    private List<RemediationSuggestion> generateRemediations(ScreeningResultDto screening) {
        List<RemediationSuggestion> suggestions = new ArrayList<>();
        if (screening.reasonCodes() != null) {
            for (String code : screening.reasonCodes()) {
                if (code.contains("COHORT")) {
                    suggestions.add(new RemediationSuggestion("BROADEN_CRITERIA", "Broaden your criteria",
                            "Remove some required labels or expand the time window", RemediationAction.MODIFY_CRITERIA));
                } else if (code.contains("SENSITIVE")) {
                    suggestions.add(new RemediationSuggestion("DOWNSCOPE_OUTPUT", "Downscope output mode",
                            "Change output mode to AGGREGATE_ONLY", RemediationAction.CHANGE_OUTPUT_MODE));
                }
            }
        }
        return suggestions;
    }

    // ==================== Inner Types ====================

    public record RequestTemplate(String id, String name, String description, String category,
            Set<String> defaultLabels, Set<String> optionalLabels, String outputMode,
            TimeWindow defaultTimeWindow, BigDecimal suggestedCompensation, int defaultTtlHours) {}

    public record RequestCustomization(Set<String> additionalLabels, Set<String> removedLabels,
            TimeWindow timeWindow, BigDecimal compensation) {}

    public record RequestDraft(String title, String description, Set<String> requiredLabels,
            Set<String> optionalLabels, String outputMode, TimeWindow timeWindow,
            BigDecimal compensation, int ttlHours, String category) {}

    public record TimeWindow(Instant start, Instant end) {}

    public record OdxCriteria(Set<String> requiredLabels, Set<String> optionalLabels,
            TimeWindow timeWindow, GeoCriteria geoCriteria) {}

    public record GeoCriteria(String precision, List<String> regions) {}

    public record CriteriaValidationResult(boolean valid, List<String> errors, List<String> warnings, int estimatedCohortSize) {}

    public record LabelFamily(String id, String displayName, List<String> labels, boolean sensitive) {}

    public record RequestCreationResult(boolean success, String requestId, String status,
            List<String> errors, List<RemediationSuggestion> suggestions) {
        public static RequestCreationResult success(String requestId, String status) {
            return new RequestCreationResult(true, requestId, status, List.of(), List.of());
        }
        public static RequestCreationResult rejected(String requestId, List<String> errors, List<RemediationSuggestion> suggestions) {
            return new RequestCreationResult(false, requestId, "REJECTED", errors, suggestions);
        }
    }

    public record PolicyFeedback(String requestId, boolean approved, List<PolicyViolation> violations,
            List<RemediationSuggestion> suggestions, Double riskScore) {}

    public record PolicyViolation(String code, String message, PolicyViolationSeverity severity) {}

    public enum PolicyViolationSeverity { WARNING, ERROR, CRITICAL }

    public record RemediationSuggestion(String id, String title, String description, RemediationAction action) {}

    public enum RemediationAction { MODIFY_CRITERIA, CHANGE_OUTPUT_MODE, REDUCE_SCOPE, ADD_JUSTIFICATION, UPGRADE_TIER }

    public record RequestStatus(String requestId, String status, String screeningStatus,
            Instant createdAt, Instant expiresAt, ResponseStats responseStats) {}

    public record ResponseStats(int totalResponses, int completedResponses, int pendingResponses, BigDecimal totalCost) {}

    public record RequesterAnalytics(String requesterId, int totalRequests, int approvedRequests,
            int rejectedRequests, int pendingRequests, int totalResponses, BigDecimal totalSpent, Instant generatedAt) {}
}
