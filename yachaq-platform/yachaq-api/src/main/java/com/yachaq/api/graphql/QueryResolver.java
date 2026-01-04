package com.yachaq.api.graphql;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.consent.ConsentService;
import com.yachaq.api.matching.MatchingService;
import com.yachaq.api.query.QueryOrchestratorService;
import com.yachaq.api.settlement.DSBalance;
import com.yachaq.api.settlement.PayoutInstruction;
import com.yachaq.api.settlement.PayoutService;
import com.yachaq.api.settlement.SettlementService;
import com.yachaq.api.token.YCToken;
import com.yachaq.api.token.YCTokenService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.TimeCapsule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Implements the read-only operations (queries) for the YACHAQ GraphQL API.
 * This class maps the fields in the GraphQL `Query` type to the corresponding
 * service-layer methods.
 *
 * <p>Requirements: 28.1 - Unified schema for queries</p>
 */
@Controller
public class QueryResolver {

    private final ConsentService consentService;
    private final AuditService auditService;
    private final SettlementService settlementService;
    private final PayoutService payoutService;
    private final YCTokenService ycTokenService;
    private final QueryOrchestratorService queryService;
    private final MatchingService matchingService;

    /**
     * Constructs a new QueryResolver with the necessary service dependencies.
     *
     * @param consentService Service for managing consent contracts.
     * @param auditService Service for accessing audit trail data.
     * @param settlementService Service for managing financial balances.
     * @param payoutService Service for handling payout history.
     * @param ycTokenService Service for managing YC utility tokens.
     * @param queryService Service for orchestrating queries and time capsules.
     * @param matchingService Service for accessing matching engine statistics.
     */
    public QueryResolver(
            ConsentService consentService,
            AuditService auditService,
            SettlementService settlementService,
            PayoutService payoutService,
            YCTokenService ycTokenService,
            QueryOrchestratorService queryService,
            MatchingService matchingService) {
        this.consentService = consentService;
        this.auditService = auditService;
        this.settlementService = settlementService;
        this.payoutService = payoutService;
        this.ycTokenService = ycTokenService;
        this.queryService = queryService;
        this.matchingService = matchingService;
    }

    /**
     * Resolves the 'consent' query. Fetches a single consent contract by its ID.
     *
     * @param id The UUID of the consent contract as a string.
     * @return The {@link ConsentContract} if found, otherwise null.
     */
    @QueryMapping
    public ConsentContract consent(@Argument String id) {
        return consentService.getContract(UUID.fromString(id));
    }

    /**
     * Resolves the 'consents' query. Fetches all consent contracts for a Data Subject.
     *
     * @param dsId The UUID of the Data Subject as a string.
     * @param activeOnly If true, filters for only active contracts.
     * @return A list of {@link ConsentContract} objects.
     */
    @QueryMapping
    public List<ConsentContract> consents(@Argument String dsId, @Argument Boolean activeOnly) {
        UUID uuid = UUID.fromString(dsId);
        return Boolean.TRUE.equals(activeOnly)
            ? consentService.getActiveContracts(uuid)
            : consentService.getAllContracts(uuid);
    }

    /**
     * Resolves the 'auditReceipt' query. Fetches a single audit receipt by its ID.
     *
     * @param id The UUID of the audit receipt as a string.
     * @return The {@link AuditReceipt} if found, otherwise null.
     */
    @QueryMapping
    public AuditReceipt auditReceipt(@Argument String id) {
        return auditService.getReceipt(UUID.fromString(id));
    }

    /**
     * Resolves the 'auditReceipts' query. Fetches a paginated list of audit receipts for a Data Subject.
     *
     * @param dsId The UUID of the Data Subject (actor) as a string.
     * @param page The page number to retrieve (0-indexed).
     * @param size The number of items per page.
     * @return An {@link AuditReceiptPage} containing the requested receipts and pagination details.
     */
    @QueryMapping
    public AuditReceiptPage auditReceipts(@Argument String dsId, @Argument Integer page, @Argument Integer size) {
        Page<AuditReceipt> receipts = auditService.getReceiptsByActor(
            UUID.fromString(dsId),
            PageRequest.of(page != null ? page : 0, size != null ? size : 20)
        );
        return new AuditReceiptPage(
            receipts.getContent(),
            receipts.getTotalElements(),
            receipts.getTotalPages(),
            receipts.getNumber(),
            receipts.getSize()
        );
    }

    /**
     * Resolves the 'auditReceiptsByResource' query. Fetches all audit receipts related to a specific resource.
     *
     * @param resourceId The UUID of the resource (e.g., a ConsentContract) as a string.
     * @return A list of {@link AuditReceipt} objects.
     */
    @QueryMapping
    public List<AuditReceipt> auditReceiptsByResource(@Argument String resourceId) {
        return auditService.getReceiptsByResource(UUID.fromString(resourceId));
    }

    /**
     * Resolves the 'balance' query. Fetches the financial and token balances for a Data Subject.
     *
     * @param dsId The UUID of the Data Subject as a string.
     * @return A {@link Balance} object summarizing the user's balances.
     */
    @QueryMapping
    public Balance balance(@Argument String dsId) {
        UUID uuid = UUID.fromString(dsId);
        DSBalance dsBalance = settlementService.getOrCreateBalance(uuid);
        BigDecimal ycBalance = ycTokenService.getBalance(uuid);

        return new Balance(
            uuid,
            dsBalance.getAvailableBalance(),
            dsBalance.getPendingBalance(),
            dsBalance.getTotalEarned(),
            dsBalance.getTotalPaidOut(),
            dsBalance.getCurrency(),
            ycBalance
        );
    }

    /**
     * Resolves the 'payoutHistory' query. Fetches the history of payout instructions for a Data Subject.
     *
     * @param dsId The UUID of the Data Subject as a string.
     * @return A list of {@link PayoutInstruction} objects.
     */
    @QueryMapping
    public List<PayoutInstruction> payoutHistory(@Argument String dsId) {
        return payoutService.getPayoutHistory(UUID.fromString(dsId));
    }

    /**
     * Resolves the 'ycTransactions' query. Fetches the YC Token transaction history for a Data Subject.
     *
     * @param dsId The UUID of the Data Subject as a string.
     * @return A list of {@link YCToken} transactions.
     */
    @QueryMapping
    public List<YCToken> ycTransactions(@Argument String dsId) {
        return ycTokenService.getTokensByHolder(UUID.fromString(dsId));
    }

    /**
     * Resolves the 'queryPlan' query. Fetches a single query plan by its ID.
     *
     * @param id The UUID of the query plan as a string.
     * @return The {@link QueryPlan} if found, otherwise null.
     */
    @QueryMapping
    public QueryPlan queryPlan(@Argument String id) {
        return queryService.getQueryPlan(UUID.fromString(id));
    }

    /**
     * Resolves the 'timeCapsule' query. Fetches a single time capsule by its ID.
     *
     * @param id The UUID of the time capsule as a string.
     * @return The {@link TimeCapsule} if found, otherwise null.
     */
    @QueryMapping
    public TimeCapsule timeCapsule(@Argument String id) {
        return queryService.getTimeCapsule(UUID.fromString(id));
    }

    /**
     * Resolves the 'matchingStats' query. Fetches matching engine statistics for a data request.
     *
     * @param requestId The UUID of the data request as a string.
     * @return A {@link MatchingStats} object with the latest statistics.
     */
    @QueryMapping
    public MatchingStats matchingStats(@Argument String requestId) {
        MatchingService.MatchingStats stats = matchingService.getMatchingStats(UUID.fromString(requestId));
        return new MatchingStats(
            UUID.fromString(requestId),
            stats.totalEligible(),
            stats.totalMatched(),
            stats.cohortSize(),
            stats.kAnonymityMet()
        );
    }

    /**
     * A DTO representing a paginated response for audit receipts.
     * @param content The list of audit receipts for the current page.
     * @param totalElements The total number of audit receipts across all pages.
     * @param totalPages The total number of pages available.
     * @param page The current page number (0-indexed).
     * @param size The number of items per page.
     */
    public record AuditReceiptPage(
        List<AuditReceipt> content,
        long totalElements,
        int totalPages,
        int page,
        int size
    ) {}

    /**
     * A DTO representing the combined financial and token balances for a Data Subject.
     * @param dsId The Data Subject's unique identifier.
     * @param availableBalance The amount of money available for immediate payout.
     * @param pendingBalance The amount of money earned but not yet cleared for payout.
     * @param totalEarned The total amount of money ever earned by the Data Subject.
     * @param totalPaidOut The total amount of money ever paid out to the Data Subject.
     * @param currency The currency for all financial values (e.g., 'USD').
     * @param ycBalance The current balance of YC Tokens.
     */
    public record Balance(
        UUID dsId,
        BigDecimal availableBalance,
        BigDecimal pendingBalance,
        BigDecimal totalEarned,
        BigDecimal totalPaidOut,
        String currency,
        BigDecimal ycBalance
    ) {}

    /**
     * A DTO representing statistics from the matching engine for a given data request.
     * @param requestId The ID of the data request being analyzed.
     * @param totalEligible The total number of Data Subjects eligible for the request.
     * @param totalMatched The number of Data Subjects who have consented and matched the query criteria.
     * @param cohortSize The size of the resulting data cohort.
     * @param kAnonymityMet Indicates whether the specified k-anonymity threshold was met.
     */
    public record MatchingStats(
        UUID requestId,
        int totalEligible,
        int totalMatched,
        int cohortSize,
        boolean kAnonymityMet
    ) {}
}
