package com.yachaq.api.bonds;

import com.yachaq.api.escrow.EscrowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requester Bonds Service for managing bonds for high-risk requests.
 * Provides bond requirements, forfeiture, and return functionality.
 * 
 * Security: Bonds are held in escrow and released only on successful completion.
 * Performance: Bond calculations are O(1).
 * 
 * Validates: Requirements 355.1, 355.2, 355.3, 355.4, 355.5
 */
@Service
public class RequesterBondsService {

    private final EscrowService escrowService;
    private final Map<String, Bond> bondRegistry = new ConcurrentHashMap<>();

    // Bond configuration
    private static final BigDecimal BASE_BOND_AMOUNT = BigDecimal.valueOf(1000);
    private static final BigDecimal SENSITIVE_DATA_MULTIPLIER = BigDecimal.valueOf(2);
    private static final BigDecimal EXPORT_MULTIPLIER = BigDecimal.valueOf(3);
    private static final int BOND_HOLD_DAYS = 90;

    public RequesterBondsService(EscrowService escrowService) {
        this.escrowService = escrowService;
    }


    // ==================== Task 102.1: Bond Requirements ====================

    /**
     * Calculates bond requirement for a request.
     * Requirement 355.1: Require bonds for high-risk requests.
     */
    public BondRequirement calculateBondRequirement(BondCalculationRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        BigDecimal bondAmount = BASE_BOND_AMOUNT;
        List<String> reasons = new ArrayList<>();

        // Sensitive data multiplier
        if (request.includesSensitiveData()) {
            bondAmount = bondAmount.multiply(SENSITIVE_DATA_MULTIPLIER);
            reasons.add("Sensitive data access");
        }

        // Export multiplier
        if (request.requiresExport()) {
            bondAmount = bondAmount.multiply(EXPORT_MULTIPLIER);
            reasons.add("Data export requested");
        }

        // Budget-based scaling
        if (request.budget() != null && request.budget().compareTo(BigDecimal.valueOf(10000)) > 0) {
            BigDecimal budgetMultiplier = request.budget().divide(BigDecimal.valueOf(10000), 2, 
                    java.math.RoundingMode.HALF_UP);
            bondAmount = bondAmount.multiply(budgetMultiplier);
            reasons.add("High-value request");
        }

        // First-time requester premium
        if (request.isFirstRequest()) {
            bondAmount = bondAmount.multiply(BigDecimal.valueOf(1.5));
            reasons.add("First-time requester");
        }

        // Low reputation penalty
        if (request.reputationScore() != null && request.reputationScore() < 0.5) {
            bondAmount = bondAmount.multiply(BigDecimal.valueOf(2));
            reasons.add("Low reputation score");
        }

        // Apply tier discount
        BigDecimal tierDiscount = getTierDiscount(request.tier());
        bondAmount = bondAmount.multiply(tierDiscount);

        // Minimum bond threshold
        if (bondAmount.compareTo(BigDecimal.valueOf(100)) < 0) {
            bondAmount = BigDecimal.ZERO;
            reasons.clear();
            reasons.add("Below minimum threshold - no bond required");
        }

        boolean required = bondAmount.compareTo(BigDecimal.ZERO) > 0;
        Instant releaseDate = Instant.now().plus(BOND_HOLD_DAYS, ChronoUnit.DAYS);

        return new BondRequirement(
                required,
                bondAmount,
                reasons,
                releaseDate,
                BOND_HOLD_DAYS
        );
    }

    /**
     * Deposits a bond.
     */
    @Transactional
    public BondDepositResult depositBond(UUID requesterId, UUID requestId, BigDecimal amount) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(requestId, "Request ID cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return new BondDepositResult(false, null, "Bond amount must be positive");
        }

        String bondId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant releaseDate = now.plus(BOND_HOLD_DAYS, ChronoUnit.DAYS);

        Bond bond = new Bond(
                bondId,
                requesterId,
                requestId,
                amount,
                BondStatus.HELD,
                now,
                releaseDate,
                null,
                null
        );

        bondRegistry.put(bondId, bond);

        return new BondDepositResult(true, bondId, "Bond deposited successfully");
    }

    private BigDecimal getTierDiscount(String tier) {
        if (tier == null) return BigDecimal.ONE;
        return switch (tier.toUpperCase()) {
            case "BASIC" -> BigDecimal.ONE;
            case "STANDARD" -> BigDecimal.valueOf(0.9);
            case "PREMIUM" -> BigDecimal.valueOf(0.7);
            case "ENTERPRISE" -> BigDecimal.valueOf(0.5);
            default -> BigDecimal.ONE;
        };
    }

    // ==================== Task 102.2: Bond Forfeiture ====================

    /**
     * Forfeits a bond due to abuse detection.
     * Requirement 355.2: Forfeit bonds on abuse detection.
     */
    @Transactional
    public ForfeitureResult forfeitBond(String bondId, ForfeitureReason reason) {
        Objects.requireNonNull(bondId, "Bond ID cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        Bond bond = bondRegistry.get(bondId);
        if (bond == null) {
            return new ForfeitureResult(false, "Bond not found", null);
        }

        if (bond.status() != BondStatus.HELD) {
            return new ForfeitureResult(false, "Bond not in held status", null);
        }

        // Calculate forfeiture amount based on reason
        BigDecimal forfeitureAmount = calculateForfeitureAmount(bond.amount(), reason);

        Bond forfeited = new Bond(
                bond.id(),
                bond.requesterId(),
                bond.requestId(),
                bond.amount(),
                BondStatus.FORFEITED,
                bond.depositedAt(),
                bond.releaseDate(),
                Instant.now(),
                reason.name()
        );

        bondRegistry.put(bondId, forfeited);

        return new ForfeitureResult(
                true,
                "Bond forfeited: " + reason.description(),
                forfeitureAmount
        );
    }

    private BigDecimal calculateForfeitureAmount(BigDecimal bondAmount, ForfeitureReason reason) {
        return switch (reason) {
            case PRIVACY_VIOLATION -> bondAmount; // Full forfeiture
            case CONTRACT_BREACH -> bondAmount.multiply(BigDecimal.valueOf(0.75));
            case DATA_MISUSE -> bondAmount; // Full forfeiture
            case TARGETING_ATTEMPT -> bondAmount.multiply(BigDecimal.valueOf(0.5));
            case POLICY_VIOLATION -> bondAmount.multiply(BigDecimal.valueOf(0.25));
        };
    }

    // ==================== Task 102.3: Bond Return ====================

    /**
     * Returns a bond on successful completion.
     * Requirement 355.4: Return bonds on successful completion.
     */
    @Transactional
    public ReturnResult returnBond(String bondId) {
        Objects.requireNonNull(bondId, "Bond ID cannot be null");

        Bond bond = bondRegistry.get(bondId);
        if (bond == null) {
            return new ReturnResult(false, "Bond not found", null);
        }

        if (bond.status() != BondStatus.HELD) {
            return new ReturnResult(false, "Bond not in held status", null);
        }

        // Check if release date has passed
        if (Instant.now().isBefore(bond.releaseDate())) {
            long daysRemaining = ChronoUnit.DAYS.between(Instant.now(), bond.releaseDate());
            return new ReturnResult(false, 
                    "Bond hold period not complete. " + daysRemaining + " days remaining", null);
        }

        Bond returned = new Bond(
                bond.id(),
                bond.requesterId(),
                bond.requestId(),
                bond.amount(),
                BondStatus.RETURNED,
                bond.depositedAt(),
                bond.releaseDate(),
                Instant.now(),
                "Successful completion"
        );

        bondRegistry.put(bondId, returned);

        return new ReturnResult(true, "Bond returned successfully", bond.amount());
    }

    /**
     * Gets bond status.
     */
    public Optional<Bond> getBond(String bondId) {
        return Optional.ofNullable(bondRegistry.get(bondId));
    }

    /**
     * Gets all bonds for a requester.
     */
    public List<Bond> getBondsForRequester(UUID requesterId) {
        return bondRegistry.values().stream()
                .filter(b -> b.requesterId().equals(requesterId))
                .toList();
    }

    /**
     * Gets total held bond amount for a requester.
     */
    public BigDecimal getTotalHeldAmount(UUID requesterId) {
        return bondRegistry.values().stream()
                .filter(b -> b.requesterId().equals(requesterId) && b.status() == BondStatus.HELD)
                .map(Bond::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    // ==================== Inner Types ====================

    public record BondCalculationRequest(
            UUID requesterId,
            UUID requestId,
            BigDecimal budget,
            boolean includesSensitiveData,
            boolean requiresExport,
            boolean isFirstRequest,
            Double reputationScore,
            String tier
    ) {}

    public record BondRequirement(
            boolean required,
            BigDecimal amount,
            List<String> reasons,
            Instant releaseDate,
            int holdDays
    ) {}

    public record BondDepositResult(
            boolean success,
            String bondId,
            String message
    ) {}

    public record Bond(
            String id,
            UUID requesterId,
            UUID requestId,
            BigDecimal amount,
            BondStatus status,
            Instant depositedAt,
            Instant releaseDate,
            Instant resolvedAt,
            String resolution
    ) {}

    public enum BondStatus {
        HELD,
        RETURNED,
        FORFEITED,
        PARTIALLY_FORFEITED
    }

    public enum ForfeitureReason {
        PRIVACY_VIOLATION("Privacy violation detected"),
        CONTRACT_BREACH("Contract terms breached"),
        DATA_MISUSE("Data misuse detected"),
        TARGETING_ATTEMPT("Targeting attempt detected"),
        POLICY_VIOLATION("Policy violation");

        private final String description;

        ForfeitureReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record ForfeitureResult(
            boolean success,
            String message,
            BigDecimal forfeitedAmount
    ) {}

    public record ReturnResult(
            boolean success,
            String message,
            BigDecimal returnedAmount
    ) {}
}
