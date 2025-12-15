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
 * GraphQL Query Resolver.
 * 
 * Requirements: 28.1
 * - Unified schema for queries
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

    // Consent queries
    @QueryMapping
    public ConsentContract consent(@Argument String id) {
        return consentService.getContract(UUID.fromString(id));
    }

    @QueryMapping
    public List<ConsentContract> consents(@Argument String dsId, @Argument Boolean activeOnly) {
        UUID uuid = UUID.fromString(dsId);
        return Boolean.TRUE.equals(activeOnly) 
            ? consentService.getActiveContracts(uuid)
            : consentService.getAllContracts(uuid);
    }

    // Audit queries
    @QueryMapping
    public AuditReceipt auditReceipt(@Argument String id) {
        return auditService.getReceipt(UUID.fromString(id));
    }

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

    @QueryMapping
    public List<AuditReceipt> auditReceiptsByResource(@Argument String resourceId) {
        return auditService.getReceiptsByResource(UUID.fromString(resourceId));
    }

    // Wallet queries
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

    @QueryMapping
    public List<PayoutInstruction> payoutHistory(@Argument String dsId) {
        return payoutService.getPayoutHistory(UUID.fromString(dsId));
    }

    @QueryMapping
    public List<YCToken> ycTransactions(@Argument String dsId) {
        return ycTokenService.getTokensByHolder(UUID.fromString(dsId));
    }

    // Query orchestrator
    @QueryMapping
    public QueryPlan queryPlan(@Argument String id) {
        return queryService.getQueryPlan(UUID.fromString(id));
    }

    @QueryMapping
    public TimeCapsule timeCapsule(@Argument String id) {
        return queryService.getTimeCapsule(UUID.fromString(id));
    }

    // Matching
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

    // DTOs
    public record AuditReceiptPage(
        List<AuditReceipt> content,
        long totalElements,
        int totalPages,
        int page,
        int size
    ) {}

    public record Balance(
        UUID dsId,
        BigDecimal availableBalance,
        BigDecimal pendingBalance,
        BigDecimal totalEarned,
        BigDecimal totalPaidOut,
        String currency,
        BigDecimal ycBalance
    ) {}

    public record MatchingStats(
        UUID requestId,
        int totalEligible,
        int totalMatched,
        int cohortSize,
        boolean kAnonymityMet
    ) {}
}
