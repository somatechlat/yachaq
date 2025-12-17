package com.yachaq.node.contract;

import com.yachaq.node.contract.ContractDraft.*;
import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.OutputMode;
import com.yachaq.node.key.KeyManagementService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Builder for creating consent contract drafts.
 * Requirement 314.1: Create Contract.buildDraft(request, userChoices) method.
 * Requirement 314.2: Include labels, time window, output mode, identity, price, escrow, TTL.
 */
public class ContractBuilder {

    private static final int DEFAULT_TTL_SECONDS = 3600; // 1 hour
    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final String dsNodeId;
    private final NonceGenerator nonceGenerator;

    public ContractBuilder(String dsNodeId) {
        this(dsNodeId, new SecureNonceGenerator());
    }

    public ContractBuilder(String dsNodeId, NonceGenerator nonceGenerator) {
        this.dsNodeId = Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");
        this.nonceGenerator = Objects.requireNonNull(nonceGenerator, "Nonce generator cannot be null");
    }

    /**
     * Builds a contract draft from a request and user choices.
     * Requirement 314.1: Create Contract.buildDraft(request, userChoices) method.
     */
    public ContractDraft buildDraft(DataRequest request, UserChoices choices) {
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(choices, "User choices cannot be null");

        // Validate choices against request constraints
        validateChoices(request, choices);

        // Generate unique nonce for replay protection
        String nonce = nonceGenerator.generate();

        // Calculate compensation
        CompensationTerms compensation = calculateCompensation(request, choices);

        // Build time window
        TimeWindow timeWindow = buildTimeWindow(request, choices);

        // Build identity reveal settings
        IdentityReveal identityReveal = buildIdentityReveal(choices);

        // Build obligation terms
        ObligationTerms obligations = buildObligations(request, choices);

        // Calculate TTL
        Instant ttl = Instant.now().plusSeconds(
                choices.ttlSeconds() > 0 ? choices.ttlSeconds() : DEFAULT_TTL_SECONDS
        );

        return new ContractDraft(
                UUID.randomUUID().toString(),
                request.id(),
                request.requesterId(),
                dsNodeId,
                choices.selectedLabels(),
                timeWindow,
                choices.outputMode() != null ? choices.outputMode() : request.outputMode(),
                identityReveal,
                compensation,
                request.compensation() != null ? request.compensation().escrowId() : null,
                ttl,
                obligations,
                nonce,
                Instant.now(),
                choices.metadata()
        );
    }

    /**
     * Validates user choices against request constraints.
     */
    private void validateChoices(DataRequest request, UserChoices choices) {
        // Selected labels must be subset of request labels
        Set<String> allowedLabels = new HashSet<>(request.requiredLabels());
        allowedLabels.addAll(request.optionalLabels());
        
        for (String label : choices.selectedLabels()) {
            if (!allowedLabels.contains(label)) {
                throw new IllegalArgumentException("Label not allowed by request: " + label);
            }
        }

        // Must include all required labels
        for (String required : request.requiredLabels()) {
            if (!choices.selectedLabels().contains(required)) {
                throw new IllegalArgumentException("Missing required label: " + required);
            }
        }

        // Output mode must be compatible
        if (choices.outputMode() != null && 
            !isOutputModeCompatible(request.outputMode(), choices.outputMode())) {
            throw new IllegalArgumentException("Output mode not compatible with request");
        }
    }

    /**
     * Checks if user's output mode is compatible with request's output mode.
     */
    private boolean isOutputModeCompatible(OutputMode requestMode, OutputMode userMode) {
        // User can only choose more restrictive modes
        return switch (requestMode) {
            case RAW_EXPORT -> true; // Any mode allowed
            case EXPORT_ALLOWED -> userMode != OutputMode.RAW_EXPORT;
            case CLEAN_ROOM -> userMode == OutputMode.CLEAN_ROOM || userMode == OutputMode.AGGREGATE_ONLY;
            case AGGREGATE_ONLY -> userMode == OutputMode.AGGREGATE_ONLY;
        };
    }

    /**
     * Calculates compensation based on request and choices.
     */
    private CompensationTerms calculateCompensation(DataRequest request, UserChoices choices) {
        if (request.compensation() == null) {
            return CompensationTerms.of(BigDecimal.ZERO, "USD");
        }

        BigDecimal baseAmount = BigDecimal.valueOf(request.compensation().amount());
        
        // Adjust based on selected labels (more labels = more compensation)
        int selectedCount = choices.selectedLabels().size();
        int totalCount = request.requiredLabels().size() + request.optionalLabels().size();
        
        if (totalCount > 0 && selectedCount > request.requiredLabels().size()) {
            // Bonus for optional labels
            double bonus = (double)(selectedCount - request.requiredLabels().size()) / 
                          (totalCount - request.requiredLabels().size()) * 0.2;
            baseAmount = baseAmount.multiply(BigDecimal.valueOf(1 + bonus));
        }

        return CompensationTerms.of(baseAmount, request.compensation().currency());
    }

    /**
     * Builds time window from request and choices.
     */
    private TimeWindow buildTimeWindow(DataRequest request, UserChoices choices) {
        if (choices.timeWindow() != null) {
            return choices.timeWindow();
        }
        if (request.timeWindow() != null) {
            return new TimeWindow(request.timeWindow().start(), request.timeWindow().end());
        }
        // Default: 30 days from now
        Instant start = Instant.now();
        Instant end = start.plusSeconds(30L * 24 * 60 * 60);
        return new TimeWindow(start, end);
    }

    /**
     * Builds identity reveal settings from choices.
     */
    private IdentityReveal buildIdentityReveal(UserChoices choices) {
        if (!choices.revealIdentity()) {
            return IdentityReveal.anonymous();
        }
        return IdentityReveal.revealed(
                choices.identityRevealLevel() != null ? choices.identityRevealLevel() : "BASIC",
                choices.revealedFields() != null ? choices.revealedFields() : Set.of()
        );
    }

    /**
     * Builds obligation terms from request and choices.
     */
    private ObligationTerms buildObligations(DataRequest request, UserChoices choices) {
        int retentionDays = choices.retentionDays() > 0 ? 
                choices.retentionDays() : DEFAULT_RETENTION_DAYS;
        
        ObligationTerms.RetentionPolicy policy = choices.retentionPolicy() != null ?
                choices.retentionPolicy() : ObligationTerms.RetentionPolicy.DELETE_AFTER_PERIOD;
        
        Set<String> restrictions = choices.usageRestrictions() != null ?
                choices.usageRestrictions() : Set.of("NO_RESALE", "NO_PROFILING");
        
        ObligationTerms.DeletionRequirement deletion = choices.deletionRequirement() != null ?
                choices.deletionRequirement() : ObligationTerms.DeletionRequirement.CRYPTO_SHRED;

        return new ObligationTerms(retentionDays, policy, restrictions, deletion);
    }

    /**
     * User choices for contract building.
     */
    public record UserChoices(
            Set<String> selectedLabels,
            TimeWindow timeWindow,
            OutputMode outputMode,
            boolean revealIdentity,
            String identityRevealLevel,
            Set<String> revealedFields,
            int retentionDays,
            ObligationTerms.RetentionPolicy retentionPolicy,
            Set<String> usageRestrictions,
            ObligationTerms.DeletionRequirement deletionRequirement,
            int ttlSeconds,
            Map<String, Object> metadata
    ) {
        public UserChoices {
            Objects.requireNonNull(selectedLabels, "Selected labels cannot be null");
            selectedLabels = Set.copyOf(selectedLabels);
            revealedFields = revealedFields != null ? Set.copyOf(revealedFields) : Set.of();
            usageRestrictions = usageRestrictions != null ? Set.copyOf(usageRestrictions) : Set.of();
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Set<String> selectedLabels = new HashSet<>();
            private TimeWindow timeWindow;
            private OutputMode outputMode;
            private boolean revealIdentity = false;
            private String identityRevealLevel;
            private Set<String> revealedFields;
            private int retentionDays = 30;
            private ObligationTerms.RetentionPolicy retentionPolicy;
            private Set<String> usageRestrictions;
            private ObligationTerms.DeletionRequirement deletionRequirement;
            private int ttlSeconds = 3600;
            private Map<String, Object> metadata = new HashMap<>();

            public Builder selectedLabels(Set<String> labels) { 
                this.selectedLabels = new HashSet<>(labels); 
                return this; 
            }
            public Builder addLabel(String label) { 
                this.selectedLabels.add(label); 
                return this; 
            }
            public Builder timeWindow(TimeWindow window) { 
                this.timeWindow = window; 
                return this; 
            }
            public Builder outputMode(OutputMode mode) { 
                this.outputMode = mode; 
                return this; 
            }
            public Builder revealIdentity(boolean reveal) { 
                this.revealIdentity = reveal; 
                return this; 
            }
            public Builder identityRevealLevel(String level) { 
                this.identityRevealLevel = level; 
                return this; 
            }
            public Builder revealedFields(Set<String> fields) { 
                this.revealedFields = fields; 
                return this; 
            }
            public Builder retentionDays(int days) { 
                this.retentionDays = days; 
                return this; 
            }
            public Builder retentionPolicy(ObligationTerms.RetentionPolicy policy) { 
                this.retentionPolicy = policy; 
                return this; 
            }
            public Builder usageRestrictions(Set<String> restrictions) { 
                this.usageRestrictions = restrictions; 
                return this; 
            }
            public Builder deletionRequirement(ObligationTerms.DeletionRequirement req) { 
                this.deletionRequirement = req; 
                return this; 
            }
            public Builder ttlSeconds(int seconds) { 
                this.ttlSeconds = seconds; 
                return this; 
            }
            public Builder metadata(Map<String, Object> metadata) { 
                this.metadata = new HashMap<>(metadata); 
                return this; 
            }

            public UserChoices build() {
                return new UserChoices(
                        selectedLabels, timeWindow, outputMode, revealIdentity,
                        identityRevealLevel, revealedFields, retentionDays,
                        retentionPolicy, usageRestrictions, deletionRequirement,
                        ttlSeconds, metadata
                );
            }
        }
    }

    /**
     * Interface for nonce generation.
     */
    public interface NonceGenerator {
        String generate();
    }

    /**
     * Secure nonce generator using SecureRandom.
     */
    public static class SecureNonceGenerator implements NonceGenerator {
        private final SecureRandom random = new SecureRandom();

        @Override
        public String generate() {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }
}
