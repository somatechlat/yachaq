package com.yachaq.api.screening;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.PolicyRule;
import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.ScreeningResult;
import com.yachaq.core.domain.ScreeningResult.ScreenedBy;
import com.yachaq.core.domain.ScreeningResult.ScreeningDecision;
import com.yachaq.core.repository.PolicyRuleRepository;
import com.yachaq.core.repository.RequestRepository;
import com.yachaq.core.repository.ScreeningResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Service
public class ScreeningService {

    private final RequestRepository requestRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PolicyRuleRepository policyRuleRepository;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

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
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.requestRepository = requestRepository;
        this.screeningResultRepository = screeningResultRepository;
        this.policyRuleRepository = policyRuleRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
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

    private boolean isEscrowFunded(Request request) {
        if (request.getEscrowId() == null) {
            return false;
        }
        String sql = """
                SELECT funded_amount, locked_amount, status
                FROM escrow_accounts
                WHERE id = :id
                """;
        Map<String, Object> row = jdbcTemplate.query(sql,
                new MapSqlParameterSource("id", request.getEscrowId()),
                rs -> rs.next() ? Map.of(
                        "funded", rs.getBigDecimal("funded_amount"),
                        "locked", rs.getBigDecimal("locked_amount"),
                        "status", rs.getString("status")
                ) : null);
        if (row == null) return false;
        BigDecimal funded = (BigDecimal) row.get("funded");
        String status = (String) row.get("status");
        BigDecimal required = request.calculateRequiredEscrow();
        return funded.compareTo(required) >= 0 && (status.equals("FUNDED") || status.equals("LOCKED"));
    }

    private boolean requesterStatusNotActive(UUID requesterId) {
        String sql = "SELECT status FROM requesters WHERE id = :id";
        String status = jdbcTemplate.query(sql, new MapSqlParameterSource("id", requesterId),
                rs -> rs.next() ? rs.getString("status") : null);
        return status == null || !"ACTIVE".equalsIgnoreCase(status);
    }

    private boolean requesterAccountTypeNotAllowed(UUID requesterId) {
        // Using tier as proxy for allowed types; block banned/suspended.
        String sql = "SELECT tier, status FROM requesters WHERE id = :id";
        Map<String, Object> row = jdbcTemplate.query(sql, new MapSqlParameterSource("id", requesterId),
                rs -> rs.next() ? Map.of(
                        "tier", rs.getString("tier"),
                        "status", rs.getString("status")
                ) : null);
        if (row == null) return true;
        String status = (String) row.get("status");
        return !"ACTIVE".equalsIgnoreCase(status);
    }

    private int estimateCohortSize(Request request) {
        // Allowed filters: account_type, status, created_after, created_before
        Map<String, Object> criteria = request.getEligibilityCriteria();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ds_profiles WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        Object accountType = criteria.get("account_type");
        if (accountType != null) {
            sql.append(" AND account_type = :accountType");
            params.addValue("accountType", accountType.toString());
        }

        Object status = criteria.get("status");
        if (status != null) {
            sql.append(" AND status = :status");
            params.addValue("status", status.toString());
        }

        Object createdAfter = criteria.get("created_after");
        if (createdAfter != null) {
            sql.append(" AND created_at >= :createdAfter");
            params.addValue("createdAfter", java.time.Instant.parse(createdAfter.toString()));
        }

        Object createdBefore = criteria.get("created_before");
        if (createdBefore != null) {
            sql.append(" AND created_at <= :createdBefore");
            params.addValue("createdBefore", java.time.Instant.parse(createdBefore.toString()));
        }

        Integer count = jdbcTemplate.queryForObject(sql.toString(), params, Integer.class);
        return count != null ? count : 0;
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
