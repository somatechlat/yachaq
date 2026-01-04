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
 * Implements the real-time subscription operations for the YACHAQ GraphQL API.
 * This class maps fields in the GraphQL `Subscription` type to reactive streams (Flux)
 * of events.
 *
 * <p>The current implementation uses a polling mechanism to simulate real-time updates.
 * This approach is suitable for demonstrating functionality but may be replaced by a
 * true event-driven architecture (e.g., using Kafka listeners) in a production environment.
 * </p>
 *
 * <p>Requirements: 28.7 - Real-time updates via subscriptions</p>
 */
@Controller
public class SubscriptionResolver {

    private final SettlementService settlementService;
    private final YCTokenService ycTokenService;
    private final ConsentContractRepository consentContractRepository;
    private final AuditReceiptRepository auditReceiptRepository;

    /**
     * Constructs a new SubscriptionResolver with the necessary service and repository dependencies.
     *
     * @param settlementService Service for managing financial balances.
     * @param ycTokenService Service for managing YC utility tokens.
     * @param consentContractRepository Repository for accessing consent contracts.
     * @param auditReceiptRepository Repository for accessing audit receipts.
     */
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
     * Resolves the 'consentUpdated' subscription. Pushes updates when new consent
     * contracts are created for a Data Subject.
     *
     * <p><b>Implementation Note:</b> This subscription polls the database every 2 seconds.
     * It detects a change by counting the number of contracts. An event is published only
     * when the count increases, sending the newly detected contracts.</p>
     *
     * @param dsId The UUID of the Data Subject to monitor.
     * @return A {@link Flux} that emits {@link ConsentContract} objects when new consents are detected.
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
     * Resolves the 'auditEvent' subscription. Pushes new audit receipts for a Data Subject.
     *
     * <p><b>Implementation Note:</b> This subscription polls the database every 2 seconds.
     * It publishes any audit receipts with a timestamp later than the last check.</p>
     *
     * @param dsId The UUID of the Data Subject (actor) to monitor.
     * @return A {@link Flux} that emits new {@link AuditReceipt} objects as they are created.
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
     * Resolves the 'balanceUpdated' subscription. Periodically pushes the current balance
     * of a Data Subject.
     *
     * <p><b>Implementation Note:</b> This subscription polls the balance services every 5 seconds
     * and emits the full, current balance on every interval, regardless of whether it has changed.</p>
     *
     * @param dsId The UUID of the Data Subject to monitor.
     * @return A {@link Flux} that emits {@link QueryResolver.Balance} objects periodically.
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
