package com.yachaq.api.screening;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.PolicyRule;
import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.ScreeningResult;
import com.yachaq.core.domain.ScreeningResult.AppealStatus;
import com.yachaq.core.domain.ScreeningResult.ScreenedBy;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
import com.yachaq.core.repository.PolicyRuleRepository;
import com.yachaq.core.repository.RequestRepository;
import com.yachaq.core.repository.ScreeningResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Screening Engine - evaluates requests against policy rules from database.
 * 
 * Property 11: Anti-Targeting Cohort Threshold
 * Validates: Requirements 6.1, 6.2, 6.3, 196.1, 196.2
 */
@Service
public class ScreeningService {

    private final RequestRepository requestRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PolicyRuleRepository policyRuleRepository;
    private final AuditService auditService;

    @Value("${yachaq.screening.policy-version:1.0.0}")
    private String policyVersion;

    @Value("${yachaq.screening.min-cohort-size:50}")
    private int minCohortSize;

    @Value("${yachaq.screening.manual-review-threshold:0.5}")
    private BigDecimal manualReviewThreshold;

    public ScreeningService(
            RequestRepository requestRepository,
            ScreeningResultRepository screeningResultRepository,
            PolicyRuleRepository policyRuleRepository,
            AuditService auditService) {
        this.requestRepository = requestRepository;
        this.screeningResultRepository = screeningResultRepository;
        this.policyRuleRepository = policyRuleRepository;
        this.auditService = auditService;
    }

    /**
     * Screens a request against policy rules loaded from database.
     * Property 11: Anti-Targeting Cohort Threshold
     * Validates: Requirements 6.1, 6.2, 196.1
     */
    @Transactional
    public ScreeningResultDto screenRequest(UUID requestId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException("Request not found: " + requestId));

        if (request.getStatus() != Request.RequestStatus.SCREENING) {
            throw new InvalidScreeningStateException("Request must be in SCREENING status");
        }

        if (screeningResultRepository.existsByRequestId(requestId)) {
            throw new AlreadyScreenedException("Request has already been screened");
        }

        // Load active policy rules from database
        List<PolicyRule> activeRules = policyRuleRepository.findByIsActiveTrueOrderBySeverityDesc();
        
        // Run policy evaluation
        PolicyEvaluationResult evaluation = evaluatePolicies(request, activeRules);

        // Create screening result
        ScreeningResult result = ScreeningResult.create(
                requestId,
                evaluation.decision(),
                evaluation.reasonCodes(),
                evaluation.riskScore(),
                evaluation.cohortSizeEstimate(),
                policyVersion,
                ScreenedBy.AUTOMATED
        );

        ScreeningResult saved = screeningResultRepository.save(result);

        // Update request status based on decision
        if (evaluation.decision() == ScreeningDecision.APPROVED) {
            request.activate();
        } else if (evaluation.decision() == ScreeningDecision.REJECTED) {
            request.reject();
        }
        requestRepository.save(request);

        // Generate audit receipt
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                request.getRequesterId(),
                AuditReceipt.ActorType.SYSTEM,
                requestId,
                "Request",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    /**
     * Evaluates policy rules from database against a request.
     * Property 11: Anti-Targeting Cohort Threshold (k >= minCohortSize)
     */
    private PolicyEvaluationResult evaluatePolicies(Request request, List<PolicyRule> rules) {
        List<String> reasonCodes = new ArrayList<>();
        BigDecimal riskScore = BigDecimal.ZERO;
        boolean hasBlockingViolation = false;

        // Estimate cohort size based on eligibility criteria
        int cohortSizeEstimate = estimateCohortSize(request);

        // Evaluate each rule
        for (PolicyRule rule : rules) {
            RuleEvaluationResult ruleResult = evaluateRule(rule, request, cohortSizeEstimate);
            
            if (ruleResult.violated()) {
                reasonCodes.add(rule.getRuleCode());
                
                // Add severity-weighted risk contribution
                BigDecimal riskContribution = BigDecimal.valueOf(rule.getSeverity())
                        .divide(BigDecimal.TEN, 2, java.math.RoundingMode.HALF_UP);
                riskScore = riskScore.add(riskContribution);
                
                if (rule.isBlocking()) {
                    hasBlockingViolation = true;
                }
            }
        }

        // Cap risk score at 1.0
        if (riskScore.compareTo(BigDecimal.ONE) > 0) {
            riskScore = BigDecimal.ONE;
        }

        // Determine decision
        ScreeningDecision decision;
        if (hasBlockingViolation) {
            decision = ScreeningDecision.REJECTED;
        } else if (riskScore.compareTo(manualReviewThreshold) >= 0) {
            decision = ScreeningDecision.MANUAL_REVIEW;
        } else {
            decision = ScreeningDecision.APPROVED;
        }

        return new PolicyEvaluationResult(decision, reasonCodes, riskScore, cohortSizeEstimate);
    }

    /**
     * Evaluates a single rule against a request.
     */
    private RuleEvaluationResult evaluateRule(PolicyRule rule, Request request, int cohortSize) {
        return switch (rule.getRuleCode()) {
            case "COHORT_MIN_SIZE" -> new RuleEvaluationResult(cohortSize < minCohortSize);
            case "BUDGET_ESCROW_MATCH" -> evaluateBudgetRule(request);
            case "DURATION_REASONABLE" -> evaluateDurationRule(request);
            case "REIDENTIFICATION_RISK" -> evaluateReidentificationRule(request);
            case "SCOPE_SENSITIVE" -> evaluateSensitiveDataRule(request);
            default -> new RuleEvaluationResult(false); // Unknown rules don't trigger
        };
    }

    private RuleEvaluationResult evaluateBudgetRule(Request request) {
        BigDecimal requiredBudget = request.calculateRequiredEscrow();
        return new RuleEvaluationResult(request.getBudget().compareTo(requiredBudget) < 0);
    }

    private RuleEvaluationResult evaluateDurationRule(Request request) {
        long durationDays = java.time.Duration.between(
                request.getDurationStart(), request.getDurationEnd()).toDays();
        return new RuleEvaluationResult(durationDays > 365);
    }

    private RuleEvaluationResult evaluateReidentificationRule(Request request) {
        Map<String, Object> scope = request.getScope();
        
        // Direct identifiers = immediate violation
        Set<String> directIdentifiers = Set.of("name", "email", "phone", "ssn", "nationalId");
        boolean hasDirectIdentifier = scope.keySet().stream()
                .map(String::toLowerCase)
                .anyMatch(directIdentifiers::contains);
        
        if (hasDirectIdentifier) {
            return new RuleEvaluationResult(true);
        }

        // Quasi-identifiers - 3+ combined = violation
        Set<String> quasiIdentifiers = Set.of("birthdate", "zipcode", "gender", "occupation", "employer", "address");
        long quasiCount = scope.keySet().stream()
                .map(String::toLowerCase)
                .filter(quasiIdentifiers::contains)
                .count();
        
        return new RuleEvaluationResult(quasiCount >= 3);
    }

    private RuleEvaluationResult evaluateSensitiveDataRule(Request request) {
        Set<String> sensitiveCategories = Set.of(
                "health", "medical", "financial", "political", "religious",
                "sexual", "biometric", "genetic", "criminal"
        );
        boolean hasSensitive = request.getScope().keySet().stream()
                .map(String::toLowerCase)
                .anyMatch(sensitiveCategories::contains);
        return new RuleEvaluationResult(hasSensitive);
    }

    /**
     * Estimates cohort size. 
     * NOTE: This is a simplified estimation. In production, this should query
     * actual DS population statistics from the platform's ODX index aggregates.
     */
    private int estimateCohortSize(Request request) {
        Map<String, Object> criteria = request.getEligibilityCriteria();
        
        // If no criteria, assume large cohort
        if (criteria == null || criteria.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        // Count restrictive criteria - more criteria = smaller cohort
        // This is a conservative estimate that errs on the side of caution
        int criteriaCount = criteria.size();
        
        // Each criterion roughly halves the eligible population
        // Starting from a conservative base, apply exponential reduction
        // This ensures highly targeted requests get flagged for review
        int estimatedSize = (int) Math.pow(2, Math.max(0, 10 - criteriaCount));
        
        return Math.max(1, estimatedSize);
    }

    /**
     * Gets reason codes for a screening result.
     */
    public List<String> getReasonCodes(UUID screeningId) {
        ScreeningResult result = screeningResultRepository.findById(screeningId)
                .orElseThrow(() -> new ScreeningNotFoundException("Screening result not found: " + screeningId));
        return result.getReasonCodes();
    }

    /**
     * Submits an appeal for a rejected screening.
     */
    @Transactional
    public ScreeningResultDto submitAppeal(UUID screeningId, AppealRequest appealRequest) {
        ScreeningResult result = screeningResultRepository.findById(screeningId)
                .orElseThrow(() -> new ScreeningNotFoundException("Screening result not found: " + screeningId));

        result.submitAppeal();
        ScreeningResult saved = screeningResultRepository.save(result);

        // Log appeal submission with evidence hash
        Request request = requestRepository.findById(result.getRequestId())
                .orElseThrow(() -> new RequestNotFoundException("Request not found"));
        
        String evidenceHash = com.yachaq.api.audit.MerkleTree.sha256(
                appealRequest.evidence() != null ? appealRequest.evidence() : ""
        );
        
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                request.getRequesterId(),
                AuditReceipt.ActorType.REQUESTER,
                screeningId,
                "ScreeningAppeal",
                evidenceHash
        );

        return toDto(saved);
    }

    /**
     * Resolves an appeal (admin function).
     */
    @Transactional
    public ScreeningResultDto resolveAppeal(UUID screeningId, UUID reviewerId, boolean approved) {
        ScreeningResult result = screeningResultRepository.findById(screeningId)
                .orElseThrow(() -> new ScreeningNotFoundException("Screening result not found: " + screeningId));

        if (approved) {
            result.approveAppeal(reviewerId);
            Request request = requestRepository.findById(result.getRequestId())
                    .orElseThrow(() -> new RequestNotFoundException("Request not found"));
            request.activate();
            requestRepository.save(request);
        } else {
            result.rejectAppeal(reviewerId);
        }

        return toDto(screeningResultRepository.save(result));
    }

    /**
     * Gets screening result by request ID.
     */
    public ScreeningResultDto getScreeningResult(UUID requestId) {
        ScreeningResult result = screeningResultRepository.findByRequestId(requestId)
                .orElseThrow(() -> new ScreeningNotFoundException("No screening result for request: " + requestId));
        return toDto(result);
    }

    /**
     * Gets pending appeals.
     */
    public List<ScreeningResultDto> getPendingAppeals() {
        return screeningResultRepository.findByAppealStatusOrderByAppealSubmittedAtAsc(AppealStatus.PENDING)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private String computeDetailsHash(ScreeningResult result) {
        String data = String.join("|",
                result.getId().toString(),
                result.getDecision().name(),
                result.getRiskScore().toPlainString(),
                String.valueOf(result.getCohortSizeEstimate()),
                String.join(",", result.getReasonCodes())
        );
        return com.yachaq.api.audit.MerkleTree.sha256(data);
    }

    private ScreeningResultDto toDto(ScreeningResult result) {
        return new ScreeningResultDto(
                result.getId(),
                result.getRequestId(),
                result.getDecision(),
                result.getReasonCodes(),
                result.getRiskScore(),
                result.getCohortSizeEstimate(),
                result.getPolicyVersion(),
                result.getScreenedAt(),
                result.getScreenedBy(),
                result.getAppealStatus()
        );
    }

    // DTOs and Records
    public record ScreeningResultDto(
            UUID id,
            UUID requestId,
            ScreeningDecision decision,
            List<String> reasonCodes,
            BigDecimal riskScore,
            Integer cohortSizeEstimate,
            String policyVersion,
            java.time.Instant screenedAt,
            ScreenedBy screenedBy,
            AppealStatus appealStatus
    ) {}

    public record PolicyEvaluationResult(
            ScreeningDecision decision,
            List<String> reasonCodes,
            BigDecimal riskScore,
            int cohortSizeEstimate
    ) {}

    public record RuleEvaluationResult(boolean violated) {}

    public record AppealRequest(String evidence, String justification) {}

    // Exceptions
    public static class RequestNotFoundException extends RuntimeException {
        public RequestNotFoundException(String message) { super(message); }
    }

    public static class ScreeningNotFoundException extends RuntimeException {
        public ScreeningNotFoundException(String message) { super(message); }
    }

    public static class InvalidScreeningStateException extends RuntimeException {
        public InvalidScreeningStateException(String message) { super(message); }
    }

    public static class AlreadyScreenedException extends RuntimeException {
        public AlreadyScreenedException(String message) { super(message); }
    }
}
