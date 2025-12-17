package com.yachaq.node.contract;

import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Represents a consent contract draft before signing.
 * Requirement 314.1: Include labels, time window, output mode, identity, price, escrow, TTL.
 * Requirement 314.2: Create Contract.buildDraft(request, userChoices) method.
 */
public record ContractDraft(
        String id,
        String requestId,
        String requesterId,
        String dsNodeId,
        Set<String> selectedLabels,
        TimeWindow timeWindow,
        OutputMode outputMode,
        IdentityReveal identityReveal,
        CompensationTerms compensation,
        String escrowId,
        Instant ttl,
        ObligationTerms obligations,
        String nonce,
        Instant createdAt,
        Map<String, Object> metadata
) {
    
    public ContractDraft {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(requestId, "Request ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");
        Objects.requireNonNull(selectedLabels, "Selected labels cannot be null");
        Objects.requireNonNull(outputMode, "Output mode cannot be null");
        Objects.requireNonNull(identityReveal, "Identity reveal cannot be null");
        Objects.requireNonNull(compensation, "Compensation cannot be null");
        Objects.requireNonNull(ttl, "TTL cannot be null");
        Objects.requireNonNull(nonce, "Nonce cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        
        selectedLabels = Set.copyOf(selectedLabels);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Checks if the draft has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(ttl);
    }

    /**
     * Checks if identity is revealed.
     */
    public boolean isIdentityRevealed() {
        return identityReveal.reveal();
    }

    /**
     * Gets the canonical bytes for signing.
     */
    public byte[] getCanonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append("|");
        sb.append(requestId).append("|");
        sb.append(requesterId).append("|");
        sb.append(dsNodeId).append("|");
        sb.append(String.join(",", new TreeSet<>(selectedLabels))).append("|");
        if (timeWindow != null) {
            sb.append(timeWindow.start()).append("-").append(timeWindow.end());
        }
        sb.append("|");
        sb.append(outputMode).append("|");
        sb.append(identityReveal.reveal()).append("|");
        sb.append(compensation.amount()).append("|");
        sb.append(compensation.currency()).append("|");
        sb.append(escrowId != null ? escrowId : "").append("|");
        sb.append(ttl).append("|");
        sb.append(nonce);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Time window for data access.
     */
    public record TimeWindow(Instant start, Instant end) {
        public TimeWindow {
            Objects.requireNonNull(start, "Start cannot be null");
            Objects.requireNonNull(end, "End cannot be null");
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Start must be before end");
            }
        }
    }

    /**
     * Identity reveal settings.
     */
    public record IdentityReveal(
            boolean reveal,
            String revealLevel,
            Set<String> revealedFields
    ) {
        public static IdentityReveal anonymous() {
            return new IdentityReveal(false, "NONE", Set.of());
        }
        
        public static IdentityReveal revealed(String level, Set<String> fields) {
            return new IdentityReveal(true, level, fields);
        }
    }

    /**
     * Compensation terms.
     */
    public record CompensationTerms(
            BigDecimal amount,
            String currency,
            BigDecimal platformFee,
            BigDecimal netAmount
    ) {
        public CompensationTerms {
            Objects.requireNonNull(amount, "Amount cannot be null");
            Objects.requireNonNull(currency, "Currency cannot be null");
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Amount cannot be negative");
            }
        }
        
        public static CompensationTerms of(BigDecimal amount, String currency) {
            BigDecimal fee = amount.multiply(new BigDecimal("0.10")); // 10% platform fee
            BigDecimal net = amount.subtract(fee);
            return new CompensationTerms(amount, currency, fee, net);
        }
    }

    /**
     * Data handling obligation terms.
     * Requirement 314.2: Include obligations in contract draft.
     */
    public record ObligationTerms(
            int retentionDays,
            RetentionPolicy retentionPolicy,
            Set<String> usageRestrictions,
            DeletionRequirement deletionRequirement
    ) {
        public enum RetentionPolicy {
            DELETE_AFTER_USE,
            DELETE_AFTER_PERIOD,
            DELETE_ON_REVOCATION,
            DELETE_ON_REQUEST
        }
        
        public enum DeletionRequirement {
            CRYPTO_SHRED,
            OVERWRITE,
            BOTH
        }
        
        public static ObligationTerms standard() {
            return new ObligationTerms(
                    30,
                    RetentionPolicy.DELETE_AFTER_PERIOD,
                    Set.of("NO_RESALE", "NO_PROFILING"),
                    DeletionRequirement.CRYPTO_SHRED
            );
        }
    }
}
