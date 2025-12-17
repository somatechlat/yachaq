package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator Reputation and Abuse Prevention Service.
 * Requirement 324.1: Enforce rate limits on request submission.
 * Requirement 324.2: Compute reputation scores based on dispute outcomes.
 * Requirement 324.3: Collect node-side abuse signals without identity.
 * Requirement 324.4: Detect sybil attacks without accessing raw data.
 * Requirement 324.5: Apply stricter limits to lower-reputation requesters.
 */
@Service
public class ReputationAbuseService {

    // Rate limit windows
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final Duration HOURLY_WINDOW = Duration.ofHours(1);
    private static final Duration DAILY_WINDOW = Duration.ofHours(24);

    // Default rate limits (per window)
    private static final int DEFAULT_REQUESTS_PER_WINDOW = 10;
    private static final int DEFAULT_REQUESTS_PER_HOUR = 30;
    private static final int DEFAULT_REQUESTS_PER_DAY = 100;

    // Reputation score bounds
    private static final BigDecimal MIN_REPUTATION = BigDecimal.ZERO;
    private static final BigDecimal MAX_REPUTATION = BigDecimal.valueOf(100);
    private static final BigDecimal INITIAL_REPUTATION = BigDecimal.valueOf(50);

    // Reputation impact factors
    private static final BigDecimal DISPUTE_WON_IMPACT = BigDecimal.valueOf(2);
    private static final BigDecimal DISPUTE_LOST_IMPACT = BigDecimal.valueOf(-5);
    private static final BigDecimal ABUSE_SIGNAL_IMPACT = BigDecimal.valueOf(-1);
    private static final BigDecimal SUCCESSFUL_REQUEST_IMPACT = BigDecimal.valueOf(0.1);
    private static final BigDecimal TARGETING_ATTEMPT_IMPACT = BigDecimal.valueOf(-10);

    // Sybil detection thresholds
    private static final int SYBIL_PATTERN_THRESHOLD = 5;
    private static final double SYBIL_SIMILARITY_THRESHOLD = 0.8;

    // Rate limit storage (ephemeral)
    private final Map<UUID, RateLimitState> rateLimitStates;
    // Reputation storage
    private final Map<UUID, RequesterReputation> reputations;
    // Abuse signals (anonymized)
    private final Map<String, AbuseSignalAggregate> abuseSignals;
    // Sybil detection patterns
    private final Map<String, SybilPattern> sybilPatterns;

    private final AuditService auditService;

    @Value("${yachaq.reputation.decay-rate:0.01}")
    private double reputationDecayRate;

    public ReputationAbuseService(AuditService auditService) {
        this.auditService = auditService;
        this.rateLimitStates = new ConcurrentHashMap<>();
        this.reputations = new ConcurrentHashMap<>();
        this.abuseSignals = new ConcurrentHashMap<>();
        this.sybilPatterns = new ConcurrentHashMap<>();
    }

    // ==================== Rate Limiting (Requirement 324.1) ====================

    /**
     * Checks if a requester is within rate limits.
     * Requirement 324.1: Enforce rate limits on request submission.
     * Requirement 324.5: Apply stricter limits to lower-reputation requesters.
     */
    public RateLimitResult checkRateLimit(UUID requesterId) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");

        RateLimitState state = rateLimitStates.computeIfAbsent(
                requesterId, 
                id -> new RateLimitState(id, Instant.now())
        );

        // Get reputation-adjusted limits
        RequesterReputation reputation = getOrCreateReputation(requesterId);
        RateLimits limits = calculateAdjustedLimits(reputation);

        Instant now = Instant.now();

        // Clean up old entries
        state.cleanupOldEntries(now);

        // Check window limit
        int windowCount = state.getCountInWindow(now, RATE_LIMIT_WINDOW);
        if (windowCount >= limits.requestsPerWindow()) {
            return RateLimitResult.exceeded(
                    "Rate limit exceeded: " + windowCount + "/" + limits.requestsPerWindow() + " in 15 minutes",
                    state.getNextAllowedTime(RATE_LIMIT_WINDOW)
            );
        }

        // Check hourly limit
        int hourlyCount = state.getCountInWindow(now, HOURLY_WINDOW);
        if (hourlyCount >= limits.requestsPerHour()) {
            return RateLimitResult.exceeded(
                    "Hourly limit exceeded: " + hourlyCount + "/" + limits.requestsPerHour(),
                    state.getNextAllowedTime(HOURLY_WINDOW)
            );
        }

        // Check daily limit
        int dailyCount = state.getCountInWindow(now, DAILY_WINDOW);
        if (dailyCount >= limits.requestsPerDay()) {
            return RateLimitResult.exceeded(
                    "Daily limit exceeded: " + dailyCount + "/" + limits.requestsPerDay(),
                    state.getNextAllowedTime(DAILY_WINDOW)
            );
        }

        // Record this request
        state.recordRequest(now);

        return RateLimitResult.allowed(
                limits.requestsPerWindow() - windowCount - 1,
                limits.requestsPerHour() - hourlyCount - 1,
                limits.requestsPerDay() - dailyCount - 1
        );
    }

    /**
     * Calculates adjusted rate limits based on reputation.
     * Requirement 324.5: Apply stricter limits to lower-reputation requesters.
     */
    private RateLimits calculateAdjustedLimits(RequesterReputation reputation) {
        BigDecimal score = reputation.score();
        double multiplier = score.divide(BigDecimal.valueOf(50), 2, RoundingMode.HALF_UP).doubleValue();
        multiplier = Math.max(0.5, Math.min(2.0, multiplier)); // Clamp between 0.5x and 2x

        return new RateLimits(
                (int) (DEFAULT_REQUESTS_PER_WINDOW * multiplier),
                (int) (DEFAULT_REQUESTS_PER_HOUR * multiplier),
                (int) (DEFAULT_REQUESTS_PER_DAY * multiplier)
        );
    }


    // ==================== Reputation Scoring (Requirement 324.2) ====================

    /**
     * Gets or creates a reputation record for a requester.
     */
    public RequesterReputation getOrCreateReputation(UUID requesterId) {
        return reputations.computeIfAbsent(requesterId, id -> 
                new RequesterReputation(id, INITIAL_REPUTATION, Instant.now(), List.of())
        );
    }

    /**
     * Records a dispute outcome and updates reputation.
     * Requirement 324.2: Compute reputation scores based on dispute outcomes.
     */
    public RequesterReputation recordDisputeOutcome(UUID requesterId, DisputeOutcome outcome) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(outcome, "Outcome cannot be null");

        RequesterReputation current = getOrCreateReputation(requesterId);
        BigDecimal impact = switch (outcome) {
            case REQUESTER_WON -> DISPUTE_WON_IMPACT;
            case REQUESTER_LOST -> DISPUTE_LOST_IMPACT;
            case SETTLED -> BigDecimal.ZERO;
        };

        BigDecimal newScore = current.score().add(impact);
        newScore = clampReputation(newScore);

        ReputationEvent event = new ReputationEvent(
                ReputationEventType.DISPUTE_OUTCOME,
                impact,
                Instant.now(),
                "Dispute outcome: " + outcome
        );

        RequesterReputation updated = new RequesterReputation(
                requesterId,
                newScore,
                Instant.now(),
                appendEvent(current.history(), event)
        );
        reputations.put(requesterId, updated);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_SCREENED,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                requesterId,
                "ReputationUpdate",
                sha256(requesterId + "|" + outcome + "|" + newScore)
        );

        return updated;
    }

    /**
     * Records a successful request completion.
     */
    public RequesterReputation recordSuccessfulRequest(UUID requesterId) {
        RequesterReputation current = getOrCreateReputation(requesterId);
        BigDecimal newScore = clampReputation(current.score().add(SUCCESSFUL_REQUEST_IMPACT));

        ReputationEvent event = new ReputationEvent(
                ReputationEventType.SUCCESSFUL_REQUEST,
                SUCCESSFUL_REQUEST_IMPACT,
                Instant.now(),
                "Successful request completion"
        );

        RequesterReputation updated = new RequesterReputation(
                requesterId,
                newScore,
                Instant.now(),
                appendEvent(current.history(), event)
        );
        reputations.put(requesterId, updated);
        return updated;
    }

    /**
     * Records a targeting attempt (anti-targeting violation).
     */
    public RequesterReputation recordTargetingAttempt(UUID requesterId, String details) {
        RequesterReputation current = getOrCreateReputation(requesterId);
        BigDecimal newScore = clampReputation(current.score().add(TARGETING_ATTEMPT_IMPACT));

        ReputationEvent event = new ReputationEvent(
                ReputationEventType.TARGETING_ATTEMPT,
                TARGETING_ATTEMPT_IMPACT,
                Instant.now(),
                details
        );

        RequesterReputation updated = new RequesterReputation(
                requesterId,
                newScore,
                Instant.now(),
                appendEvent(current.history(), event)
        );
        reputations.put(requesterId, updated);

        // Audit
        auditService.appendReceipt(
                AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                requesterId,
                "TargetingAttempt",
                sha256(requesterId + "|targeting|" + details)
        );

        return updated;
    }

    private BigDecimal clampReputation(BigDecimal score) {
        if (score.compareTo(MIN_REPUTATION) < 0) return MIN_REPUTATION;
        if (score.compareTo(MAX_REPUTATION) > 0) return MAX_REPUTATION;
        return score;
    }

    private List<ReputationEvent> appendEvent(List<ReputationEvent> history, ReputationEvent event) {
        List<ReputationEvent> newHistory = new ArrayList<>(history);
        newHistory.add(event);
        // Keep only last 100 events
        if (newHistory.size() > 100) {
            newHistory = newHistory.subList(newHistory.size() - 100, newHistory.size());
        }
        return newHistory;
    }


    // ==================== Abuse Signal Aggregation (Requirement 324.3) ====================

    /**
     * Records an abuse signal from a node (anonymized).
     * Requirement 324.3: Collect node-side abuse signals without identity.
     */
    public void recordAbuseSignal(AbuseSignal signal) {
        Objects.requireNonNull(signal, "Signal cannot be null");

        // Anonymize the signal - hash the node identifier
        String anonymizedNodeId = sha256(signal.nodeEphemeralId());
        String signalKey = signal.requesterId() + "|" + signal.signalType();

        AbuseSignalAggregate aggregate = abuseSignals.computeIfAbsent(
                signalKey,
                k -> new AbuseSignalAggregate(signal.requesterId(), signal.signalType(), 
                        new AtomicInteger(0), new HashSet<>(), Instant.now())
        );

        // Only count unique nodes (prevent single node from spamming signals)
        if (aggregate.uniqueNodes().add(anonymizedNodeId)) {
            aggregate.count().incrementAndGet();

            // Update reputation if threshold reached
            if (aggregate.count().get() >= 5) {
                RequesterReputation current = getOrCreateReputation(signal.requesterId());
                BigDecimal newScore = clampReputation(current.score().add(ABUSE_SIGNAL_IMPACT));

                ReputationEvent event = new ReputationEvent(
                        ReputationEventType.ABUSE_SIGNAL,
                        ABUSE_SIGNAL_IMPACT,
                        Instant.now(),
                        "Abuse signal: " + signal.signalType()
                );

                RequesterReputation updated = new RequesterReputation(
                        signal.requesterId(),
                        newScore,
                        Instant.now(),
                        appendEvent(current.history(), event)
                );
                reputations.put(signal.requesterId(), updated);
            }
        }
    }

    /**
     * Gets aggregated abuse signals for a requester.
     */
    public List<AbuseSignalAggregate> getAbuseSignals(UUID requesterId) {
        return abuseSignals.values().stream()
                .filter(a -> a.requesterId().equals(requesterId))
                .toList();
    }

    // ==================== Sybil Detection (Requirement 324.4) ====================

    /**
     * Analyzes request patterns for sybil attack detection.
     * Requirement 324.4: Detect sybil attacks without accessing raw data.
     */
    public SybilAnalysisResult analyzeSybilPatterns(UUID requesterId, RequestPattern pattern) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(pattern, "Pattern cannot be null");

        // Create pattern fingerprint (no raw data, only behavioral patterns)
        String fingerprint = createPatternFingerprint(pattern);

        SybilPattern existing = sybilPatterns.get(fingerprint);
        if (existing == null) {
            // New pattern
            sybilPatterns.put(fingerprint, new SybilPattern(
                    fingerprint,
                    Set.of(requesterId),
                    pattern,
                    Instant.now()
            ));
            return SybilAnalysisResult.clean();
        }

        // Check if this requester already has this pattern
        if (existing.requesterIds().contains(requesterId)) {
            return SybilAnalysisResult.clean();
        }

        // Add requester to pattern
        Set<UUID> updatedRequesters = new HashSet<>(existing.requesterIds());
        updatedRequesters.add(requesterId);
        sybilPatterns.put(fingerprint, new SybilPattern(
                fingerprint,
                updatedRequesters,
                pattern,
                existing.firstSeen()
        ));

        // Check for sybil threshold
        if (updatedRequesters.size() >= SYBIL_PATTERN_THRESHOLD) {
            // Potential sybil attack detected
            auditService.appendReceipt(
                    AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
                    requesterId,
                    AuditReceipt.ActorType.SYSTEM,
                    requesterId,
                    "SybilDetection",
                    sha256(fingerprint + "|" + updatedRequesters.size())
            );

            return SybilAnalysisResult.suspicious(
                    updatedRequesters.size(),
                    "Pattern matches " + updatedRequesters.size() + " requesters",
                    fingerprint
            );
        }

        return SybilAnalysisResult.clean();
    }

    /**
     * Creates a behavioral fingerprint without raw data.
     */
    private String createPatternFingerprint(RequestPattern pattern) {
        // Hash behavioral characteristics, not raw data
        String characteristics = String.join("|",
                pattern.scopeCategories().stream().sorted().toList().toString(),
                String.valueOf(pattern.targetCohortSizeBucket()),
                String.valueOf(pattern.compensationBucket()),
                String.valueOf(pattern.durationBucket()),
                pattern.timeOfDayBucket()
        );
        return sha256(characteristics);
    }


    // ==================== Scheduled Cleanup ====================

    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupOldData() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));

        // Clean up old rate limit states
        rateLimitStates.entrySet().removeIf(e -> 
                e.getValue().getLastActivity().isBefore(cutoff));

        // Clean up old abuse signals
        abuseSignals.entrySet().removeIf(e -> 
                e.getValue().firstSeen().isBefore(cutoff));

        // Clean up old sybil patterns
        sybilPatterns.entrySet().removeIf(e -> 
                e.getValue().firstSeen().isBefore(cutoff));
    }

    // ==================== Utility Methods ====================

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Records and Enums ====================

    /**
     * Result of rate limit check.
     */
    public record RateLimitResult(
            boolean allowed,
            int remainingInWindow,
            int remainingInHour,
            int remainingInDay,
            String message,
            Instant retryAfter
    ) {
        public static RateLimitResult allowed(int window, int hour, int day) {
            return new RateLimitResult(true, window, hour, day, null, null);
        }

        public static RateLimitResult exceeded(String message, Instant retryAfter) {
            return new RateLimitResult(false, 0, 0, 0, message, retryAfter);
        }
    }

    /**
     * Rate limits configuration.
     */
    public record RateLimits(int requestsPerWindow, int requestsPerHour, int requestsPerDay) {}

    /**
     * Requester reputation record.
     */
    public record RequesterReputation(
            UUID requesterId,
            BigDecimal score,
            Instant lastUpdated,
            List<ReputationEvent> history
    ) {
        public RequesterReputation {
            history = history != null ? List.copyOf(history) : List.of();
        }

        public ReputationTier getTier() {
            if (score.compareTo(BigDecimal.valueOf(80)) >= 0) return ReputationTier.EXCELLENT;
            if (score.compareTo(BigDecimal.valueOf(60)) >= 0) return ReputationTier.GOOD;
            if (score.compareTo(BigDecimal.valueOf(40)) >= 0) return ReputationTier.NEUTRAL;
            if (score.compareTo(BigDecimal.valueOf(20)) >= 0) return ReputationTier.POOR;
            return ReputationTier.RESTRICTED;
        }
    }

    /**
     * Reputation tier.
     */
    public enum ReputationTier {
        EXCELLENT,  // 80-100: Full access, higher limits
        GOOD,       // 60-79: Standard access
        NEUTRAL,    // 40-59: Standard access, monitored
        POOR,       // 20-39: Reduced limits, enhanced monitoring
        RESTRICTED  // 0-19: Severely restricted, manual review required
    }

    /**
     * Reputation event.
     */
    public record ReputationEvent(
            ReputationEventType type,
            BigDecimal impact,
            Instant timestamp,
            String details
    ) {}

    /**
     * Reputation event types.
     */
    public enum ReputationEventType {
        DISPUTE_OUTCOME,
        SUCCESSFUL_REQUEST,
        ABUSE_SIGNAL,
        TARGETING_ATTEMPT,
        MANUAL_ADJUSTMENT
    }

    /**
     * Dispute outcome.
     */
    public enum DisputeOutcome {
        REQUESTER_WON,
        REQUESTER_LOST,
        SETTLED
    }


    /**
     * Abuse signal from a node.
     */
    public record AbuseSignal(
            UUID requesterId,
            String nodeEphemeralId,
            AbuseSignalType signalType,
            Instant timestamp,
            String details
    ) {}

    /**
     * Abuse signal types.
     */
    public enum AbuseSignalType {
        SPAM_REQUESTS,
        TARGETING_ATTEMPT,
        MISLEADING_PURPOSE,
        EXCESSIVE_SCOPE,
        SUSPICIOUS_PATTERN
    }

    /**
     * Aggregated abuse signals.
     */
    public record AbuseSignalAggregate(
            UUID requesterId,
            AbuseSignalType signalType,
            AtomicInteger count,
            Set<String> uniqueNodes,
            Instant firstSeen
    ) {
        public AbuseSignalAggregate {
            uniqueNodes = uniqueNodes != null ? new HashSet<>(uniqueNodes) : new HashSet<>();
        }
    }

    /**
     * Request pattern for sybil detection.
     */
    public record RequestPattern(
            Set<String> scopeCategories,
            int targetCohortSizeBucket,
            int compensationBucket,
            int durationBucket,
            String timeOfDayBucket
    ) {
        public RequestPattern {
            scopeCategories = scopeCategories != null ? Set.copyOf(scopeCategories) : Set.of();
        }
    }

    /**
     * Sybil pattern record.
     */
    public record SybilPattern(
            String fingerprint,
            Set<UUID> requesterIds,
            RequestPattern pattern,
            Instant firstSeen
    ) {
        public SybilPattern {
            requesterIds = requesterIds != null ? Set.copyOf(requesterIds) : Set.of();
        }
    }

    /**
     * Sybil analysis result.
     */
    public record SybilAnalysisResult(
            boolean suspicious,
            int matchingRequesters,
            String reason,
            String patternFingerprint
    ) {
        public static SybilAnalysisResult clean() {
            return new SybilAnalysisResult(false, 0, null, null);
        }

        public static SybilAnalysisResult suspicious(int count, String reason, String fingerprint) {
            return new SybilAnalysisResult(true, count, reason, fingerprint);
        }
    }

    /**
     * Rate limit state for a requester.
     */
    private static class RateLimitState {
        private final UUID requesterId;
        private final List<Instant> requestTimes;
        private Instant lastActivity;

        RateLimitState(UUID requesterId, Instant created) {
            this.requesterId = requesterId;
            this.requestTimes = Collections.synchronizedList(new ArrayList<>());
            this.lastActivity = created;
        }

        void recordRequest(Instant time) {
            requestTimes.add(time);
            lastActivity = time;
        }

        int getCountInWindow(Instant now, Duration window) {
            Instant cutoff = now.minus(window);
            return (int) requestTimes.stream()
                    .filter(t -> t.isAfter(cutoff))
                    .count();
        }

        void cleanupOldEntries(Instant now) {
            Instant cutoff = now.minus(DAILY_WINDOW);
            requestTimes.removeIf(t -> t.isBefore(cutoff));
        }

        Instant getNextAllowedTime(Duration window) {
            if (requestTimes.isEmpty()) return Instant.now();
            return requestTimes.get(0).plus(window);
        }

        Instant getLastActivity() {
            return lastActivity;
        }
    }
}
