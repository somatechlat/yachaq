package com.yachaq.api.graphql;

import com.yachaq.api.consent.ConsentService;
import com.yachaq.api.query.QueryOrchestratorService;
import com.yachaq.api.settlement.PayoutInstruction;
import com.yachaq.api.settlement.PayoutService;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.TimeCapsule;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GraphQL Mutation Resolver.
 * 
 * Requirements: 28.1
 * - Unified schema for mutations
 */
@Controller
public class MutationResolver {

    private final ConsentService consentService;
    private final PayoutService payoutService;
    private final QueryOrchestratorService queryService;

    public MutationResolver(
            ConsentService consentService,
            PayoutService payoutService,
            QueryOrchestratorService queryService) {
        this.consentService = consentService;
        this.payoutService = payoutService;
        this.queryService = queryService;
    }

    // Consent mutations
    @MutationMapping
    public ConsentResult grantConsent(@Argument GrantConsentInput input) {
        ConsentService.ConsentRequest request = new ConsentService.ConsentRequest(
            UUID.fromString(input.dsId()),
            UUID.fromString(input.requesterId()),
            UUID.fromString(input.requestId()),
            input.scopeHash(),
            input.purposeHash(),
            Instant.parse(input.durationStart()),
            Instant.parse(input.durationEnd()),
            BigDecimal.valueOf(input.compensationAmount())
        );
        
        ConsentService.ConsentResult result = consentService.createConsent(request);
        
        return new ConsentResult(
            result.contractId(),
            UUID.fromString(input.dsId()),
            UUID.fromString(input.requesterId()),
            result.status().name(),
            result.auditReceiptId(),
            result.createdAt().toString()
        );
    }

    @MutationMapping
    public RevocationResult revokeConsent(@Argument String id, @Argument String dsId) {
        ConsentService.RevocationResult result = consentService.revokeConsent(
            UUID.fromString(id),
            UUID.fromString(dsId)
        );
        
        return new RevocationResult(
            result.contractId(),
            result.revokedAt().toString(),
            result.auditReceiptId(),
            0 // tokensInvalidated - not tracked in current implementation
        );
    }

    // Payout mutations
    @MutationMapping
    public PayoutResult requestPayout(@Argument PayoutInput input) {
        try {
            PayoutInstruction instruction = payoutService.createPayoutInstruction(
                UUID.fromString(input.dsId()),
                BigDecimal.valueOf(input.amount()),
                PayoutService.PayoutMethod.valueOf(input.method()),
                input.destination()
            );
            
            return new PayoutResult(
                instruction.getId(),
                UUID.fromString(input.dsId()),
                instruction.getAmount().doubleValue(),
                instruction.getStatus().name(),
                "Payout request submitted"
            );
        } catch (PayoutService.InsufficientBalanceException e) {
            return new PayoutResult(
                null,
                UUID.fromString(input.dsId()),
                input.amount(),
                "FAILED",
                e.getMessage()
            );
        }
    }

    // Query mutations
    @MutationMapping
    public QueryPlan createQueryPlan(@Argument CreateQueryPlanInput input) {
        return queryService.createQueryPlan(
            UUID.fromString(input.requesterId()),
            UUID.fromString(input.consentContractId()),
            input.scope(),
            input.transforms(),
            input.ttlMinutes()
        );
    }

    @MutationMapping
    public DispatchResult dispatchQuery(@Argument DispatchQueryInput input) {
        Set<UUID> deviceIds = input.eligibleDeviceIds().stream()
            .map(UUID::fromString)
            .collect(Collectors.toSet());
        
        QueryOrchestratorService.DispatchResult result = queryService.dispatchQuery(
            UUID.fromString(input.planId()),
            deviceIds,
            Duration.ofSeconds(input.timeoutSeconds())
        );
        
        return new DispatchResult(
            result.queryId(),
            result.dispatchedCount(),
            result.status()
        );
    }

    @MutationMapping
    public TimeCapsule createTimeCapsule(@Argument CreateCapsuleInput input) {
        return queryService.createTimeCapsule(
            UUID.fromString(input.queryId()),
            input.ttlMinutes()
        );
    }

    // Input records
    public record GrantConsentInput(
        String dsId,
        String requesterId,
        String requestId,
        String scopeHash,
        String purposeHash,
        String durationStart,
        String durationEnd,
        double compensationAmount
    ) {}

    public record PayoutInput(
        String dsId,
        double amount,
        String method,
        String destination
    ) {}

    public record CreateQueryPlanInput(
        String requesterId,
        String consentContractId,
        String scope,
        List<String> transforms,
        int ttlMinutes
    ) {}

    public record DispatchQueryInput(
        String planId,
        List<String> eligibleDeviceIds,
        int timeoutSeconds
    ) {}

    public record CreateCapsuleInput(
        String queryId,
        int ttlMinutes
    ) {}

    // Result records
    public record ConsentResult(
        UUID contractId,
        UUID dsId,
        UUID requesterId,
        String status,
        UUID auditReceiptId,
        String createdAt
    ) {}

    public record RevocationResult(
        UUID contractId,
        String revokedAt,
        UUID auditReceiptId,
        int tokensInvalidated
    ) {}

    public record PayoutResult(
        UUID payoutId,
        UUID dsId,
        double amount,
        String status,
        String message
    ) {}

    public record DispatchResult(
        UUID queryId,
        int dispatchedCount,
        String status
    ) {}
}
