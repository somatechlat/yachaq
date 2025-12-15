package com.yachaq.api.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting configuration using Bucket4j.
 * 
 * Requirements: 27.4, 69.1
 * - API rate limiting per client
 * - Prevent abuse and ensure fair access
 */
@Configuration
public class RateLimitConfig {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Default rate limit: 100 requests per minute per client.
     */
    public Bucket resolveBucket(String clientId) {
        return buckets.computeIfAbsent(clientId, this::createDefaultBucket);
    }

    /**
     * Strict rate limit for sensitive operations: 10 requests per minute.
     */
    public Bucket resolveStrictBucket(String clientId) {
        return buckets.computeIfAbsent(clientId + ":strict", this::createStrictBucket);
    }

    /**
     * High-volume rate limit for read operations: 500 requests per minute.
     */
    public Bucket resolveHighVolumeBucket(String clientId) {
        return buckets.computeIfAbsent(clientId + ":high", this::createHighVolumeBucket);
    }

    private Bucket createDefaultBucket(String key) {
        Bandwidth limit = Bandwidth.classic(100, Refill.greedy(100, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket createStrictBucket(String key) {
        Bandwidth limit = Bandwidth.classic(10, Refill.greedy(10, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket createHighVolumeBucket(String key) {
        Bandwidth limit = Bandwidth.classic(500, Refill.greedy(500, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Clear rate limit bucket for a client (for testing).
     */
    public void clearBucket(String clientId) {
        buckets.remove(clientId);
        buckets.remove(clientId + ":strict");
        buckets.remove(clientId + ":high");
    }
}
