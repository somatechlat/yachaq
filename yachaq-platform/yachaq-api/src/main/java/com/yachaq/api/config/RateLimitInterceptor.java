package com.yachaq.api.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate limiting interceptor for API requests.
 * 
 * Requirements: 27.4, 69.1
 * - Enforces rate limits per client
 * - Returns 429 Too Many Requests when limit exceeded
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitConfig rateLimitConfig;

    public RateLimitInterceptor(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, 
                            Object handler) throws Exception {
        
        String clientId = resolveClientId(request);
        Bucket bucket = selectBucket(request, clientId);
        
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        
        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining", 
                String.valueOf(probe.getRemainingTokens()));
            return true;
        }
        
        long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000;
        response.addHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitForRefill));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.getWriter().write("{\"code\":\"RATE_001\",\"message\":\"Rate limit exceeded. Retry after " + waitForRefill + " seconds.\"}");
        response.setContentType("application/json");
        return false;
    }

    private String resolveClientId(HttpServletRequest request) {
        // Try to get client ID from JWT subject or API key
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Extract subject from JWT (simplified - in production use proper JWT parsing)
            return authHeader.substring(7, Math.min(50, authHeader.length()));
        }
        
        // Fall back to DS ID header
        String dsId = request.getHeader("X-DS-ID");
        if (dsId != null) {
            return dsId;
        }
        
        // Fall back to IP address
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            return forwarded.split(",")[0].trim();
        }
        
        return request.getRemoteAddr();
    }

    private Bucket selectBucket(HttpServletRequest request, String clientId) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        // Strict rate limit for sensitive operations
        if (path.contains("/auth/") || 
            path.contains("/consent/grant") ||
            path.contains("/wallet/payout") ||
            "DELETE".equals(method)) {
            return rateLimitConfig.resolveStrictBucket(clientId);
        }
        
        // High volume for read operations
        if ("GET".equals(method) && 
            (path.contains("/audit/") || path.contains("/receipts"))) {
            return rateLimitConfig.resolveHighVolumeBucket(clientId);
        }
        
        // Default rate limit
        return rateLimitConfig.resolveBucket(clientId);
    }
}
