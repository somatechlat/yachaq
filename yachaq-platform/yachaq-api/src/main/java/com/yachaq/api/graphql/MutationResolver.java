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
 * Implements the write operations (mutations) for the YACHAQ GraphQL API.
 * This class maps the fields in the GraphQL `Mutation` type to the corresponding
 * service-layer methods that change system state.
 *
 * <p>Requirements: 28.1 - Unified schema for mutations</p>
 */
@Controller
public class MutationResolver {

    private final ConsentService consentService;
    private final PayoutService payoutService;
    private final QueryOrchestratorService queryService;

    /**
     * Constructs a new MutationResolver with the necessary service dependencies.
     *
     * @param consentService Service for creating and revoking consent contracts.
     * @param payoutService Service for processing financial payouts.
     * @param queryService Service for creating query plans and time capsules.
     */
    public MutationResolver(
            ConsentService consentService,
            PayoutService payoutService,
            QueryOrchestratorService queryService) {
        this.consentService = consentService;
        this.payoutService = payoutService;
        this.queryService = queryService;
    }

    /**
     * Resolves the 'grantConsent' mutation. Creates a new consent contract based on user input.
     *
     * @param input The input data for creating the consent contract.
     * @return A {@link ConsentResult} summarizing the outcome of the operation.
     */
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

    /**
     * Resolves the 'revokeConsent' mutation. Revokes an existing, active consent contract.
     *
     * @param id The UUID of the consent contract to revoke.
     * @param dsId The UUID of the Data Subject initiating the revocation, for authorization.
     * @return A {@link RevocationResult} summarizing the outcome.
     */
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

    /**
     * Resolves the 'requestPayout' mutation. Initiates a financial payout for a Data Subject.
     *
     * @param input The input data for the payout request.
     * @return A {@link PayoutResult} indicating the status of the request.
     *         Handles insufficient balance errors gracefully by returning a FAILED status.
     */
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

    /**
     * Resolves the 'createQueryPlan' mutation. Creates a new query plan from a consent contract.
     *
     * @param input The input data for creating the query plan.
     * @return The newly created {@link QueryPlan}.
     */
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

    /**
     * Resolves the 'dispatchQuery' mutation. Dispatches a query to eligible devices based on a plan.
     *
     * @param input The input data for the dispatch operation.
     * @return A {@link DispatchResult} summarizing the outcome of the dispatch.
     */
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

    /**
     * Resolves the 'createTimeCapsule' mutation. Creates a time capsule for deferred data delivery.
     *
     * @param input The input data for creating the time capsule.
     * @return The newly created {@link TimeCapsule}.
     */
    @MutationMapping
    public TimeCapsule createTimeCapsule(@Argument CreateCapsuleInput input) {
        return queryService.createTimeCapsule(
            UUID.fromString(input.queryId()),
            input.ttlMinutes()
        );
    }

    /**
     * DTO for the 'grantConsent' mutation input.
     * @param dsId The Data Subject's unique identifier.
     * @param requesterId The Data Requester's unique identifier.
     * @param requestId The unique identifier of the original data request.
     * @param scopeHash A cryptographic hash of the requested data scope.
     * @param purposeHash A cryptographic hash of the stated purpose.
     * @param durationStart The ISO 8601 timestamp for when the consent becomes active.
     * @param durationEnd The ISO 8601 timestamp for when the consent expires.
     * @param compensationAmount The financial compensation offered for this consent.
     */
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

    /**
     * DTO for the 'requestPayout' mutation input.
     * @param dsId The Data Subject's unique identifier.
     * @param amount The amount to be paid out.
     * @param method The desired payout method (e.g., 'BANK_TRANSFER').
     * @param destination The destination for the funds (e.g., account number).
     */
    public record PayoutInput(
        String dsId,
        double amount,
        String method,
        String destination
    ) {}

    /**

     * DTO for the 'createQueryPlan' mutation input.
     * @param requesterId The Data Requester's unique identifier.
     * @param consentContractId The ID of the consent contract authorizing this plan.
     * @param scope A detailed description or definition of the data to be queried.
     * @param transforms A list of transformations to be applied to the data.
     * @param ttlMinutes The Time-To-Live for the query plan in minutes.
     */
    public record CreateQueryPlanInput(
        String requesterId,
        String consentContractId,
        String scope,
        List<String> transforms,
        int ttlMinutes
    ) {}

    /**
     * DTO for the 'dispatchQuery' mutation input.
     * @param planId The ID of the query plan to be executed.
     * @param eligibleDeviceIds A list of specific device IDs to dispatch the query to.
     * @param timeoutSeconds The maximum time in seconds to wait for a response from devices.
     */
    public record DispatchQueryInput(
        String planId,
        List<String> eligibleDeviceIds,
        int timeoutSeconds
    ) {}

    /**
     * DTO for the 'createTimeCapsule' mutation input.
     * @param queryId The ID of the query this capsule is associated with.
     * @param ttlMinutes The Time-To-Live for the capsule in minutes.
     */
    public record CreateCapsuleInput(
        String queryId,
        int ttlMinutes
    ) {}

    /**
     * DTO representing the result of a 'grantConsent' mutation.
     * @param contractId The ID of the newly created consent contract.
     * @param dsId The ID of the Data Subject.
     * @param requesterId The ID of the Data Requester.
     * @param status The initial status of the new contract.
     * @param auditReceiptId The ID of the audit receipt generated for this event.
     * @param createdAt The timestamp of the consent creation.
     */
    public record ConsentResult(
        UUID contractId,
        UUID dsId,
        UUID requesterId,
        String status,
        UUID auditReceiptId,
        String createdAt
    ) {}

    /**
     * DTO representing the result of a 'revokeConsent' mutation.
     * @param contractId The ID of the contract that was revoked.
     * @param revokedAt The timestamp of the revocation event.
     * @param auditReceiptId The ID of the audit receipt generated for this event.
     * @param tokensInvalidated The number of associated YC Tokens that were invalidated.
     */
    public record RevocationResult(
        UUID contractId,
        String revokedAt,
        UUID auditReceiptId,
        int tokensInvalidated
    ) {}

    /**
     * DTO representing the result of a 'requestPayout' mutation.
     * @param payoutId The ID of the newly created payout instruction.
     * @param dsId The ID of the Data Subject.
     * @param amount The requested payout amount.
     * @param status The initial status of the payout.
     * @param message A message providing additional details about the result.
     */
    public record PayoutResult(
        UUID payoutId,
        UUID dsId,
        double amount,
        String status,
        String message
    ) {}

    /**
     * DTO representing the result of a 'dispatchQuery' mutation.
     * @param queryId The unique identifier for this specific query execution.
     * @param dispatchedCount The number of devices the query was successfully dispatched to.
     * @param status The status of the dispatch operation.
     */
    public record DispatchResult(
        UUID queryId,
        int dispatchedCount,
        String status
    ) {}
}
