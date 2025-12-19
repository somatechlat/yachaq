package com.yachaq.api.graphql;

import com.yachaq.api.settlement.DSBalance;
import com.yachaq.api.settlement.SettlementService;
import com.yachaq.api.token.YCTokenService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.ConsentContractRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GraphQL Subscription Resolver.
 * 
 * Requirements: 28.7
 * - Real-time updates via subscriptions
 * 
 * Uses polling with change detection for real-time updates.
 * Polls database every 2 seconds and emits only when changes are detected.
 */
@Controller
public class SubscriptionResolver {

    private final SettlementService settlementService;
    private final YCTokenService ycTokenService;
    private final ConsentContractRepository consentContractRepository;
    private final AuditReceiptRepository auditReceiptRepository;

    public SubscriptionResolver(
            SettlementService settlementService,
            YCTokenService ycTokenService,
            ConsentContractRepository consentContractRepository,
            AuditReceiptRepository auditReceiptRepository) {
        this.settlementService = settlementService;
        this.ycTokenService = ycTokenService;
        this.consentContractRepository = consentContractRepository;
        this.auditReceiptRepository = auditReceiptRepository;
    }

    /**
     * Subscribe to consent updates for a DS.
     * Polls for new/updated consent contracts every 2 seconds.
     */
    @SubscriptionMapping
    public Flux<ConsentContract> consentUpdated(@Argument String dsId) {
        UUID uuid = UUID.fromString(dsId);
        AtomicReference<Instant> lastCheck = new AtomicReference<>(Instant.now());
        AtomicReference<Integer> lastCount = new AtomicReference<>(0);
        
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> {
                    // Find all contracts for this DS
                    List<ConsentContract> contracts = consentContractRepository.findByDsId(uuid);
                    int currentCount = contracts.size();
                    int previousCount = lastCount.getAndSet(currentCount);
                    
                    // Emit new contracts (simple change detection based on count)
                    if (currentCount > previousCount) {
                        // Return the newest contracts (those created after last check)
                        Instant checkTime = lastCheck.get();
                        lastCheck.set(Instant.now());
                        
                        List<ConsentContract> newContracts = contracts.stream()
                                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(checkTime))
                                .toList();
                        
                        return Flux.fromIterable(newContracts);
                    }
                    return Flux.empty();
                });
    }

    /**
     * Subscribe to audit events for a DS.
     * Polls for new audit receipts every 2 seconds.
     */
    @SubscriptionMapping
    public Flux<AuditReceipt> auditEvent(@Argument String dsId) {
        UUID uuid = UUID.fromString(dsId);
        AtomicReference<Instant> lastCheck = new AtomicReference<>(Instant.now());
        
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> {
                    Instant checkTime = lastCheck.get();
                    lastCheck.set(Instant.now());
                    
                    // Find receipts created since last check for this actor using pageable query
                    List<AuditReceipt> newReceipts = auditReceiptRepository
                            .findByActorIdOrderByTimestampDesc(uuid, org.springframework.data.domain.PageRequest.of(0, 100))
                            .getContent()
                            .stream()
                            .filter(r -> r.getTimestamp() != null && r.getTimestamp().isAfter(checkTime))
                            .toList();
                    
                    return Flux.fromIterable(newReceipts);
                });
    }

    /**
     * Subscribe to balance updates for a DS.
     * Polls balance every 5 seconds with real data from settlement service.
     */
    @SubscriptionMapping
    public Flux<QueryResolver.Balance> balanceUpdated(@Argument String dsId) {
        UUID uuid = UUID.fromString(dsId);
        
        // Poll balance every 5 seconds with real data
        return Flux.interval(Duration.ofSeconds(5))
            .map(tick -> {
                DSBalance dsBalance = settlementService.getOrCreateBalance(uuid);
                BigDecimal ycBalance = ycTokenService.getBalance(uuid);
                
                return new QueryResolver.Balance(
                    uuid,
                    dsBalance.getAvailableBalance(),
                    dsBalance.getPendingBalance(),
                    dsBalance.getTotalEarned(),
                    dsBalance.getTotalPaidOut(),
                    dsBalance.getCurrency(),
                    ycBalance
                );
            });
    }
}
