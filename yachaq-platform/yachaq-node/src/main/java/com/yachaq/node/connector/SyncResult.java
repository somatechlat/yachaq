package com.yachaq.node.connector;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Result of a connector sync operation.
 * Requirement 305.4: Support incremental sync with cursor-based pagination.
 */
public record SyncResult(
        boolean success,
        SyncStatus status,
        List<SyncItem> items,
        String nextCursor,
        boolean hasMore,
        int totalItemsAvailable,
        Instant syncedAt,
        String errorCode,
        String errorMessage,
        RetryInfo retryInfo
) {
    public SyncResult {
        items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * Creates a successful sync result.
     */
    public static SyncResult success(List<SyncItem> items, String nextCursor, boolean hasMore) {
        return new SyncResult(
                true, SyncStatus.COMPLETED, items, nextCursor, hasMore,
                items.size(), Instant.now(), null, null, null
        );
    }

    /**
     * Creates a successful sync result with total count.
     */
    public static SyncResult success(List<SyncItem> items, String nextCursor, boolean hasMore, int totalAvailable) {
        return new SyncResult(
                true, SyncStatus.COMPLETED, items, nextCursor, hasMore,
                totalAvailable, Instant.now(), null, null, null
        );
    }

    /**
     * Creates a partial sync result (some items failed).
     */
    public static SyncResult partial(List<SyncItem> items, String nextCursor, String errorMessage) {
        return new SyncResult(
                true, SyncStatus.PARTIAL, items, nextCursor, true,
                items.size(), Instant.now(), "PARTIAL_SYNC", errorMessage, null
        );
    }

    /**
     * Creates a failed sync result.
     */
    public static SyncResult failure(String errorCode, String errorMessage) {
        return new SyncResult(
                false, SyncStatus.FAILED, List.of(), null, false,
                0, Instant.now(), errorCode, errorMessage, null
        );
    }

    /**
     * Creates a rate-limited result with retry information.
     * Requirement 305.5: Implement backoff and retry logic.
     */
    public static SyncResult rateLimited(RetryInfo retryInfo) {
        return new SyncResult(
                false, SyncStatus.RATE_LIMITED, List.of(), null, true,
                0, Instant.now(), "RATE_LIMITED", "Rate limit exceeded", retryInfo
        );
    }

    /**
     * Creates a result indicating no new data.
     */
    public static SyncResult noNewData(String cursor) {
        return new SyncResult(
                true, SyncStatus.NO_NEW_DATA, List.of(), cursor, false,
                0, Instant.now(), null, null, null
        );
    }

    /**
     * Returns the next cursor if present.
     */
    public Optional<String> getNextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    /**
     * Returns retry info if present.
     */
    public Optional<RetryInfo> getRetryInfo() {
        return Optional.ofNullable(retryInfo);
    }

    /**
     * Sync status.
     */
    public enum SyncStatus {
        COMPLETED,
        PARTIAL,
        FAILED,
        RATE_LIMITED,
        NO_NEW_DATA,
        UNAUTHORIZED
    }

    /**
     * Information for retry after rate limiting.
     */
    public record RetryInfo(
            Instant retryAfter,
            int retryAttempt,
            int maxRetries,
            long backoffMillis
    ) {
        /**
         * Calculates exponential backoff delay.
         */
        public static RetryInfo exponentialBackoff(int attempt, int maxRetries, long baseDelayMillis) {
            long backoff = (long) (baseDelayMillis * Math.pow(2, attempt - 1));
            // Cap at 5 minutes
            backoff = Math.min(backoff, 300_000);
            return new RetryInfo(
                    Instant.now().plusMillis(backoff),
                    attempt,
                    maxRetries,
                    backoff
            );
        }
    }
}
