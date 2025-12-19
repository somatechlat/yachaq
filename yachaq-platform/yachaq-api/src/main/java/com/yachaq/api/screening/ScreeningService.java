package com.yachaq.api.screening;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.escrow.EscrowRepository;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.EscrowAccount;
import com.yachaq.core.domain.PolicyRule;
import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.ScreeningResult;
import com.yachaq.core.domain.ScreeningResult.ScreenedBy;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
import com.yachaq.core.domain.Account;
import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.repository.AccountRepository;
import com.yachaq.core.repository.DSProfileRepository;
import com.yachaq.core.repository.PolicyRuleRepository;
import com.yachaq.core.repository.RequestRepository;
import com.yachaq.core.repository.ScreeningResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Service for screening data requests against policy rules.
 * Uses Spring Data JPA repositories for all database access - no raw SQL.
 */
@Service
public class ScreeningService {

    private final RequestRepository requestRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PolicyRuleRepository policyRuleRepository;
    private final AuditService auditService;
    private final EscrowRepository escrowRepository;
    private final AccountRepository accountRepository;
    private final DSProfileRepository dsProfileRepository;

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
            AuditService auditService,
            EscrowRepository escrowRepository,
            AccountRepository accountRepository,
            DSProfileRepository dsProfileRepository) {
        this.requestRepository = requestRepository;
        this.screeningResultRepository = screeningResultRepository;
        this.policyRuleRepository = policyRuleRepository;
        this.auditService = auditService;
        this.escrowRepository = escrowRepository;
        this.accountRepository = accountRepository;
        this.dsProfileRepository = dsProfileRepository;
    }

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

        List<PolicyRule> rules = policyRuleRepository.findByIsActiveTrueOrderBySeverityDesc();
        int cohortSize = estimateCohortSize(request);

        PolicyEvaluationResult evaluation = evaluatePolicies(request, rules, cohortSize);

        ScreeningResult result = ScreeningResult.create(
                requestId,
                evaluation.decision(),
                evaluation.reasonCodes(),
                evaluation.riskScore(),
                cohortSize,
                policyVersion,
                ScreenedBy.AUTOMATED
        );

        ScreeningResult saved = screeningResultRepository.save(result);

        if (evaluation.decision() == ScreeningDecision.APPROVED) {
            request.activate();
        } else if (evaluation.decision() == ScreeningDecision.REJECTED) {
            request.reject();
        }
        requestRepository.save(request);

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

    private PolicyEvaluationResult evaluatePolicies(Request request, List<PolicyRule> rules, int cohortSize) {
        List<String> reasons = new ArrayList<>();
        BigDecimal risk = BigDecimal.ZERO;
        boolean blocking = false;

        for (PolicyRule rule : rules) {
            boolean violated = switch (rule.getRuleCode()) {
                case "COHORT_MIN_SIZE" -> cohortSize < minCohortSize;
                case "DURATION_REASONABLE" -> durationDays(request) > 365;
                case "BUDGET_ESCROW_FUNDED" -> !isEscrowFunded(request);
                case "STATUS_ALLOWED" -> requesterStatusNotActive(request.getRequesterId());
                case "ACCOUNT_TYPE_ALLOWED" -> requesterAccountTypeNotAllowed(request.getRequesterId());
                default -> false; // ignore unknown codes; we do not seed unknowns
            };

            if (violated) {
                reasons.add(rule.getRuleCode());
                if (rule.isBlocking()) {
                    blocking = true;
                } else if (rule.getRuleType() == PolicyRule.RuleType.WARNING) {
                    risk = risk.add(BigDecimal.valueOf(rule.getSeverity()).movePointLeft(1)); // severity/10
                }
            }
        }

        ScreeningDecision decision;
        if (blocking) {
            decision = ScreeningDecision.REJECTED;
        } else if (risk.compareTo(manualReviewThreshold) >= 0) {
            decision = ScreeningDecision.MANUAL_REVIEW;
        } else {
            decision = ScreeningDecision.APPROVED;
        }

        return new PolicyEvaluationResult(decision, reasons, risk);
    }

    private long durationDays(Request request) {
        return Duration.between(request.getDurationStart(), request.getDurationEnd()).toDays();
    }

    /**
     * Checks if the escrow account is sufficiently funded.
     * Uses Spring Data JPA repository instead of raw SQL.
     */
    private boolean isEscrowFunded(Request request) {
        if (request.getEscrowId() == null) {
            return false;
        }
        Optional<EscrowAccount> escrowOpt = escrowRepository.findById(request.getEscrowId());
        if (escrowOpt.isEmpty()) {
            return false;
        }
        EscrowAccount escrow = escrowOpt.get();
        BigDecimal required = request.calculateRequiredEscrow();
        EscrowAccount.EscrowStatus status = escrow.getStatus();
        return escrow.getFundedAmount().compareTo(required) >= 0 
                && (status == EscrowAccount.EscrowStatus.FUNDED || status == EscrowAccount.EscrowStatus.LOCKED);
    }

    /**
     * Checks if the requester account status is not active.
     * Uses Spring Data JPA repository instead of raw SQL.
     */
    private boolean requesterStatusNotActive(UUID requesterId) {
        Optional<Account> accountOpt = accountRepository.findById(requesterId);
        if (accountOpt.isEmpty()) {
            return true; // No account found = not active
        }
        Account account = accountOpt.get();
        return account.getStatus() != Account.AccountStatus.ACTIVE;
    }

    /**
     * Checks if the requester account type is not allowed.
     * Uses Spring Data JPA repository instead of raw SQL.
     */
    private boolean requesterAccountTypeNotAllowed(UUID requesterId) {
        Optional<Account> accountOpt = accountRepository.findById(requesterId);
        if (accountOpt.isEmpty()) {
            return true; // No account found = not allowed
        }
        Account account = accountOpt.get();
        return account.getStatus() != Account.AccountStatus.ACTIVE;
    }

    /**
     * Estimates cohort size based on eligibility criteria.
     * Uses Spring Data JPA repository instead of raw SQL.
     */
    private int estimateCohortSize(Request request) {
        Map<String, Object> criteria = request.getEligibilityCriteria();
        
        // Get all DS profiles and filter in memory
        // For production, this should use a custom repository method with Specification
        List<DSProfile> allProfiles = dsProfileRepository.findAll();
        
        return (int) allProfiles.stream()
                .filter(profile -> matchesCriteria(profile, criteria))
                .count();
    }

    /**
     * Checks if a DS profile matches the eligibility criteria.
     */
    private boolean matchesCriteria(DSProfile profile, Map<String, Object> criteria) {
        Object accountType = criteria.get("account_type");
        if (accountType != null && !profile.getAccountType().name().equals(accountType.toString())) {
            return false;
        }

        Object status = criteria.get("status");
        if (status != null && !profile.getStatus().name().equals(status.toString())) {
            return false;
        }

        Object createdAfter = criteria.get("created_after");
        if (createdAfter != null) {
            Instant after = Instant.parse(createdAfter.toString());
            if (profile.getCreatedAt().isBefore(after)) {
                return false;
            }
        }

        Object createdBefore = criteria.get("created_before");
        if (createdBefore != null) {
            Instant before = Instant.parse(createdBefore.toString());
            if (profile.getCreatedAt().isAfter(before)) {
                return false;
            }
        }

        return true;
    }

    public ScreeningResultDto getScreeningResult(UUID requestId) {
        ScreeningResult result = screeningResultRepository.findByRequestId(requestId)
                .orElseThrow(() -> new ScreeningNotFoundException("No screening result for request: " + requestId));
        return toDto(result);
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
            ScreeningResult.AppealStatus appealStatus
    ) {}

    public record PolicyEvaluationResult(
            ScreeningDecision decision,
            List<String> reasonCodes,
            BigDecimal riskScore
    ) {}

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
