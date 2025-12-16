package com.yachaq.node.connector;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for connectors with rate limiting and backoff logic.
 * Requirement 305.5: Implement rate-limit backoff and retry logic.
 * 
 * Provides:
 * - Rate limiting with configurable limits
 * - Exponential backoff on failures
 * - Request tracking and metrics
 * - Authorization state management
 */
public abstract class AbstractConnector implements Connector {

    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final long DEFAULT_BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 300_000; // 5 minutes

    private final String id;
    private final ConnectorType type;
    private final AtomicReference<AuthState> authState;
    private final AtomicInteger consecutiveFailures;
    private final AtomicLong lastRequestTime;
    private final AtomicLong requestCount;
    private final ConcurrentHashMap<String, Instant> rateLimitWindows;
    private final Semaphore rateLimitSemaphore;

    // Configurable settings
    private final int maxRetries;
    private final long baseBackoffMs;

    protected AbstractConnector(String id, ConnectorType type) {
        this(id, type, DEFAULT_MAX_RETRIES, DEFAULT_BASE_BACKOFF_MS);
    }

    protected AbstractConnector(String id, ConnectorType type, int maxRetries, long baseBackoffMs) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Connector ID cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Connector type cannot be null");
        }

        this.id = id;
        this.type = type;
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
        this.authState = new AtomicReference<>(new AuthState(false, null, null));
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastRequestTime = new AtomicLong(0);
        this.requestCount = new AtomicLong(0);
        this.rateLimitWindows = new ConcurrentHashMap<>();
        
        // Initialize rate limit semaphore based on capabilities
        int rateLimit = capabilities().rateLimitPerMinute();
        this.rateLimitSemaphore = new Semaphore(rateLimit > 0 ? rateLimit : Integer.MAX_VALUE);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ConnectorType getType() {
        return type;
    }

    @Override
    public boolean isAuthorized() {
        AuthState state = authState.get();
        if (!state.authorized()) {
            return false;
        }
        if (state.expiresAt() != null && Instant.now().isAfter(state.expiresAt())) {
            return false;
        }
        return true;
    }

    @Override
    public CompletableFuture<AuthResult> authorize() {
        return doAuthorize().thenApply(result -> {
            if (result.success()) {
                authState.set(new AuthState(true, result.expiresAt(), result.grantedScopes()));
                consecutiveFailures.set(0);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<SyncResult> sync(String sinceCursor) {
        if (!isAuthorized()) {
            return CompletableFuture.completedFuture(
                    SyncResult.failure("UNAUTHORIZED", "Connector not authorized")
            );
        }

        return executeWithRateLimitAndBackoff(() -> doSync(sinceCursor));
    }

    @Override
    public CompletableFuture<HealthStatus> healthcheck() {
        if (!isAuthorized()) {
            return CompletableFuture.completedFuture(HealthStatus.unauthorized());
        }
        return doHealthcheck();
    }

    @Override
    public CompletableFuture<Void> revokeAuthorization() {
        return doRevokeAuthorization().thenRun(() -> {
            authState.set(new AuthState(false, null, null));
        });
    }

    /**
     * Executes a sync operation with rate limiting and exponential backoff.
     * Requirement 305.5: Implement backoff and retry logic for rate limits.
     */
    protected CompletableFuture<SyncResult> executeWithRateLimitAndBackoff(
            java.util.function.Supplier<CompletableFuture<SyncResult>> operation) {
        
        return acquireRateLimit()
                .thenCompose(acquired -> {
                    if (!acquired) {
                        return CompletableFuture.completedFuture(
                                SyncResult.rateLimited(calculateRetryInfo())
                        );
                    }
                    return executeWithBackoff(operation, 1);
                });
    }

    /**
     * Executes operation with exponential backoff on failure.
     */
    private CompletableFuture<SyncResult> executeWithBackoff(
            java.util.function.Supplier<CompletableFuture<SyncResult>> operation,
            int attempt) {
        
        return operation.get()
                .thenCompose(result -> {
                    if (result.success()) {
                        consecutiveFailures.set(0);
                        requestCount.incrementAndGet();
                        lastRequestTime.set(System.currentTimeMillis());
                        return CompletableFuture.completedFuture(result);
                    }

                    if (result.status() == SyncResult.SyncStatus.RATE_LIMITED) {
                        return handleRateLimitedResponse(operation, attempt, result);
                    }

                    if (shouldRetry(result, attempt)) {
                        return retryWithBackoff(operation, attempt);
                    }

                    consecutiveFailures.incrementAndGet();
                    return CompletableFuture.completedFuture(result);
                })
                .exceptionally(ex -> {
                    consecutiveFailures.incrementAndGet();
                    return SyncResult.failure("EXCEPTION", ex.getMessage());
                });
    }

    /**
     * Handles rate-limited response with backoff.
     */
    private CompletableFuture<SyncResult> handleRateLimitedResponse(
            java.util.function.Supplier<CompletableFuture<SyncResult>> operation,
            int attempt,
            SyncResult result) {
        
        if (attempt >= maxRetries) {
            return CompletableFuture.completedFuture(result);
        }

        SyncResult.RetryInfo retryInfo = result.getRetryInfo()
                .orElse(calculateRetryInfo());
        
        return delay(retryInfo.backoffMillis())
                .thenCompose(v -> executeWithBackoff(operation, attempt + 1));
    }

    /**
     * Retries operation after exponential backoff delay.
     */
    private CompletableFuture<SyncResult> retryWithBackoff(
            java.util.function.Supplier<CompletableFuture<SyncResult>> operation,
            int attempt) {
        
        long backoffMs = calculateBackoffMs(attempt);
        return delay(backoffMs)
                .thenCompose(v -> executeWithBackoff(operation, attempt + 1));
    }

    /**
     * Determines if operation should be retried.
     */
    protected boolean shouldRetry(SyncResult result, int attempt) {
        if (attempt >= maxRetries) {
            return false;
        }
        // Retry on transient failures
        return result.status() == SyncResult.SyncStatus.FAILED &&
               isTransientError(result.errorCode());
    }

    /**
     * Determines if an error is transient and retryable.
     */
    protected boolean isTransientError(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return switch (errorCode) {
            case "TIMEOUT", "CONNECTION_ERROR", "SERVICE_UNAVAILABLE", 
                 "INTERNAL_ERROR", "GATEWAY_TIMEOUT" -> true;
            default -> false;
        };
    }

    /**
     * Calculates exponential backoff delay.
     */
    protected long calculateBackoffMs(int attempt) {
        long backoff = (long) (baseBackoffMs * Math.pow(2, attempt - 1));
        // Add jitter (±10%)
        double jitter = 0.9 + (Math.random() * 0.2);
        backoff = (long) (backoff * jitter);
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    /**
     * Calculates retry info for rate limiting.
     */
    protected SyncResult.RetryInfo calculateRetryInfo() {
        int failures = consecutiveFailures.get();
        return SyncResult.RetryInfo.exponentialBackoff(
                failures + 1, maxRetries, baseBackoffMs
        );
    }

    /**
     * Acquires rate limit permit.
     */
    protected CompletableFuture<Boolean> acquireRateLimit() {
        int rateLimit = capabilities().rateLimitPerMinute();
        if (rateLimit <= 0) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return rateLimitSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    /**
     * Releases rate limit permit.
     */
    protected void releaseRateLimit() {
        int rateLimit = capabilities().rateLimitPerMinute();
        if (rateLimit > 0) {
            rateLimitSemaphore.release();
        }
    }

    /**
     * Creates a delayed future.
     */
    protected CompletableFuture<Void> delay(long millis) {
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Returns the number of consecutive failures.
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Returns the total request count.
     */
    public long getRequestCount() {
        return requestCount.get();
    }

    /**
     * Returns the last request timestamp.
     */
    public long getLastRequestTime() {
        return lastRequestTime.get();
    }

    // ==================== Abstract Methods ====================

    /**
     * Performs the actual authorization.
     * Subclasses implement platform-specific authorization.
     */
    protected abstract CompletableFuture<AuthResult> doAuthorize();

    /**
     * Performs the actual sync operation.
     * Subclasses implement platform-specific data retrieval.
     */
    protected abstract CompletableFuture<SyncResult> doSync(String sinceCursor);

    /**
     * Performs the actual health check.
     * Subclasses implement platform-specific health verification.
     */
    protected abstract CompletableFuture<HealthStatus> doHealthcheck();

    /**
     * Performs the actual authorization revocation.
     * Subclasses implement platform-specific revocation.
     */
    protected abstract CompletableFuture<Void> doRevokeAuthorization();

    // ==================== Inner Types ====================

    /**
     * Internal authorization state.
     */
    private record AuthState(
            boolean authorized,
            Instant expiresAt,
            java.util.Set<String> grantedScopes
    ) {}
}
