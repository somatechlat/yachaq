package com.yachaq.node.transport;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Network Gate for controlling all network traffic from the device.
 * Requirement 318.1: Route all network calls through the Network Gate.
 * Requirement 318.2: Distinguish metadata-only from ciphertext capsule traffic.
 * Requirement 318.3: Block unknown destinations by default.
 * Requirement 318.4: Require explicit domain and purpose registration.
 * Requirement 318.5: Block and log raw payload egress attempts.
 */
public class NetworkGate {

    private static final double ENTROPY_THRESHOLD = 7.0;
    private static final int MIN_CIPHERTEXT_SIZE = 28; // IV (12) + tag (16)

    private final Map<String, AllowedDestination> allowlist;
    private final List<EgressAttempt> blockedAttempts;
    private final AtomicLong totalRequests;
    private final AtomicLong blockedRequests;
    private final Set<Pattern> forbiddenPatterns;
    private volatile boolean gateEnabled;

    public NetworkGate() {
        this.allowlist = new ConcurrentHashMap<>();
        this.blockedAttempts = Collections.synchronizedList(new ArrayList<>());
        this.totalRequests = new AtomicLong(0);
        this.blockedRequests = new AtomicLong(0);
        this.forbiddenPatterns = initForbiddenPatterns();
        this.gateEnabled = true;
    }

    /**
     * Registers an allowed destination with purpose.
     * Requirement 318.4: Require explicit domain and purpose registration.
     */
    public void allow(String domain, String purpose) {
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(purpose, "Purpose cannot be null");

        if (domain.isBlank() || purpose.isBlank()) {
            throw new IllegalArgumentException("Domain and purpose cannot be blank");
        }

        String normalizedDomain = normalizeDomain(domain);
        allowlist.put(normalizedDomain, new AllowedDestination(
                normalizedDomain,
                purpose,
                Instant.now(),
                true
        ));
    }

    /**
     * Removes a destination from the allowlist.
     */
    public void revoke(String domain) {
        if (domain != null) {
            allowlist.remove(normalizeDomain(domain));
        }
    }

    /**
     * Sends a request through the network gate.
     * Requirement 318.1: Route all network calls through the Network Gate.
     * Requirement 318.3: Block unknown destinations by default.
     */
    public GateResult send(NetworkRequest request) throws NetworkGateException {
        Objects.requireNonNull(request, "Request cannot be null");
        totalRequests.incrementAndGet();

        if (!gateEnabled) {
            throw new NetworkGateException("Network gate is disabled");
        }

        // Check destination allowlist
        String normalizedDomain = normalizeDomain(request.destination());
        AllowedDestination allowed = allowlist.get(normalizedDomain);
        
        if (allowed == null || !allowed.active()) {
            blockedRequests.incrementAndGet();
            logBlockedAttempt(request, BlockReason.UNKNOWN_DESTINATION);
            throw new NetworkGateException("Destination not in allowlist: " + request.destination());
        }

        // Classify payload
        PayloadClassification classification = classifyPayload(request.payload());

        // Block raw payload egress
        if (classification == PayloadClassification.RAW_PAYLOAD) {
            blockedRequests.incrementAndGet();
            logBlockedAttempt(request, BlockReason.RAW_PAYLOAD_EGRESS);
            throw new NetworkGateException("Raw payload egress blocked");
        }

        // Check for forbidden patterns in payload
        if (containsForbiddenPatterns(request.payload())) {
            blockedRequests.incrementAndGet();
            logBlockedAttempt(request, BlockReason.FORBIDDEN_PATTERN);
            throw new NetworkGateException("Payload contains forbidden patterns");
        }

        return new GateResult(
                true,
                request.requestId(),
                classification,
                allowed.purpose(),
                Instant.now()
        );
    }

    /**
     * Classifies a payload as metadata-only or ciphertext.
     * Requirement 318.2: Distinguish metadata-only from ciphertext capsule traffic.
     */
    public PayloadClassification classifyPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return PayloadClassification.METADATA_ONLY;
        }

        if (payload.length < MIN_CIPHERTEXT_SIZE) {
            // Too small to be valid ciphertext
            return isLikelyMetadata(payload) ? 
                    PayloadClassification.METADATA_ONLY : PayloadClassification.RAW_PAYLOAD;
        }

        double entropy = calculateEntropy(payload);
        
        if (entropy > ENTROPY_THRESHOLD) {
            return PayloadClassification.CIPHERTEXT_CAPSULE;
        } else if (isLikelyMetadata(payload)) {
            return PayloadClassification.METADATA_ONLY;
        } else {
            return PayloadClassification.RAW_PAYLOAD;
        }
    }

    /**
     * Checks if a destination is allowed.
     */
    public boolean isAllowed(String domain) {
        if (domain == null) return false;
        AllowedDestination allowed = allowlist.get(normalizeDomain(domain));
        return allowed != null && allowed.active();
    }

    /**
     * Gets the purpose for an allowed destination.
     */
    public Optional<String> getPurpose(String domain) {
        if (domain == null) return Optional.empty();
        AllowedDestination allowed = allowlist.get(normalizeDomain(domain));
        return allowed != null ? Optional.of(allowed.purpose()) : Optional.empty();
    }

    /**
     * Gets all blocked egress attempts.
     * Requirement 318.6: Log blocked attempts.
     */
    public List<EgressAttempt> getBlockedAttempts() {
        return new ArrayList<>(blockedAttempts);
    }

    /**
     * Gets blocked attempts for a specific reason.
     */
    public List<EgressAttempt> getBlockedAttempts(BlockReason reason) {
        return blockedAttempts.stream()
                .filter(a -> a.reason() == reason)
                .toList();
    }

    /**
     * Gets gate statistics.
     */
    public GateStatistics getStatistics() {
        return new GateStatistics(
                totalRequests.get(),
                blockedRequests.get(),
                allowlist.size(),
                blockedAttempts.size()
        );
    }

    /**
     * Clears blocked attempt logs.
     */
    public void clearBlockedAttempts() {
        blockedAttempts.clear();
    }

    /**
     * Enables or disables the gate.
     */
    public void setEnabled(boolean enabled) {
        this.gateEnabled = enabled;
    }

    /**
     * Checks if the gate is enabled.
     */
    public boolean isEnabled() {
        return gateEnabled;
    }

    // ==================== Private Methods ====================

    private Set<Pattern> initForbiddenPatterns() {
        Set<Pattern> patterns = new HashSet<>();
        // Patterns that indicate raw personal data
        patterns.add(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b")); // Email
        patterns.add(Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b")); // Phone
        patterns.add(Pattern.compile("\\b\\d{3}[-]?\\d{2}[-]?\\d{4}\\b")); // SSN-like
        patterns.add(Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")); // IP address
        return patterns;
    }

    private String normalizeDomain(String domain) {
        String normalized = domain.toLowerCase().trim();
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring(8);
        } else if (normalized.startsWith("http://")) {
            normalized = normalized.substring(7);
        }
        int pathIndex = normalized.indexOf('/');
        if (pathIndex > 0) {
            normalized = normalized.substring(0, pathIndex);
        }
        return normalized;
    }

    private void logBlockedAttempt(NetworkRequest request, BlockReason reason) {
        EgressAttempt attempt = new EgressAttempt(
                UUID.randomUUID().toString(),
                request.destination(),
                reason,
                request.payload() != null ? request.payload().length : 0,
                classifyPayload(request.payload()),
                Instant.now()
        );
        blockedAttempts.add(attempt);
    }

    private double calculateEntropy(byte[] data) {
        if (data == null || data.length == 0) return 0;
        
        int[] frequency = new int[256];
        for (byte b : data) {
            frequency[b & 0xFF]++;
        }
        
        double entropy = 0;
        for (int freq : frequency) {
            if (freq > 0) {
                double p = (double) freq / data.length;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    private boolean isLikelyMetadata(byte[] payload) {
        if (payload == null || payload.length == 0) return true;
        
        // Check if it looks like JSON or structured metadata
        String sample = new String(payload, 0, Math.min(payload.length, 100));
        return sample.startsWith("{") || sample.startsWith("[") || 
               sample.contains("\":") || sample.contains("=");
    }

    private boolean containsForbiddenPatterns(byte[] payload) {
        if (payload == null || payload.length == 0) return false;
        
        // Only check if it's not ciphertext
        if (calculateEntropy(payload) > ENTROPY_THRESHOLD) {
            return false; // Ciphertext won't match patterns
        }
        
        String content = new String(payload);
        for (Pattern pattern : forbiddenPatterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    // ==================== Inner Types ====================

    /**
     * Network request to be sent through the gate.
     */
    public record NetworkRequest(
            String requestId,
            String destination,
            byte[] payload,
            RequestType type,
            Map<String, String> headers
    ) {
        public NetworkRequest {
            Objects.requireNonNull(requestId, "Request ID cannot be null");
            Objects.requireNonNull(destination, "Destination cannot be null");
            Objects.requireNonNull(type, "Type cannot be null");
            payload = payload != null ? payload.clone() : new byte[0];
            headers = headers != null ? Map.copyOf(headers) : Map.of();
        }

        public enum RequestType {
            METADATA,
            CAPSULE_TRANSFER,
            SIGNALING,
            ACKNOWLEDGMENT
        }

        public static NetworkRequest metadata(String destination, byte[] payload) {
            return new NetworkRequest(
                    UUID.randomUUID().toString(),
                    destination,
                    payload,
                    RequestType.METADATA,
                    Map.of()
            );
        }

        public static NetworkRequest capsule(String destination, byte[] payload) {
            return new NetworkRequest(
                    UUID.randomUUID().toString(),
                    destination,
                    payload,
                    RequestType.CAPSULE_TRANSFER,
                    Map.of()
            );
        }
    }

    /**
     * Allowed destination with purpose.
     */
    public record AllowedDestination(
            String domain,
            String purpose,
            Instant allowedAt,
            boolean active
    ) {}

    /**
     * Payload classification.
     * Requirement 318.2: Distinguish metadata-only from ciphertext capsule traffic.
     */
    public enum PayloadClassification {
        METADATA_ONLY,
        CIPHERTEXT_CAPSULE,
        RAW_PAYLOAD
    }

    /**
     * Reason for blocking egress.
     */
    public enum BlockReason {
        UNKNOWN_DESTINATION,
        RAW_PAYLOAD_EGRESS,
        FORBIDDEN_PATTERN,
        GATE_DISABLED
    }

    /**
     * Result of gate evaluation.
     */
    public record GateResult(
            boolean allowed,
            String requestId,
            PayloadClassification classification,
            String purpose,
            Instant evaluatedAt
    ) {}

    /**
     * Record of a blocked egress attempt.
     * Requirement 318.6: Log blocked attempts.
     */
    public record EgressAttempt(
            String attemptId,
            String destination,
            BlockReason reason,
            int payloadSize,
            PayloadClassification classification,
            Instant timestamp
    ) {}

    /**
     * Gate statistics.
     */
    public record GateStatistics(
            long totalRequests,
            long blockedRequests,
            int allowlistSize,
            int blockedAttemptCount
    ) {
        public double blockRate() {
            return totalRequests > 0 ? (double) blockedRequests / totalRequests : 0;
        }
    }

    /**
     * Exception for network gate operations.
     */
    public static class NetworkGateException extends Exception {
        public NetworkGateException(String message) {
            super(message);
        }
    }
}
