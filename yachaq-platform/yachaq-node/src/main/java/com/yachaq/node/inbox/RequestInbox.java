package com.yachaq.node.inbox;

import com.yachaq.node.inbox.DataRequest.RequestType;
import com.yachaq.node.key.KeyManagementService;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request Inbox for receiving and validating data requests.
 * Requirement 313.1: Create Inbox.receive(request) method.
 * Requirement 313.1: Verify request signatures and policy stamps.
 */
public class RequestInbox {

    private final Map<String, DataRequest> pendingRequests;
    private final Map<String, DataRequest> processedRequests;
    private final Set<String> seenNonces;
    private final int maxPendingRequests;
    private final SignatureVerifier signatureVerifier;

    public RequestInbox() {
        this(1000, new DefaultSignatureVerifier());
    }

    public RequestInbox(int maxPendingRequests, SignatureVerifier signatureVerifier) {
        this.pendingRequests = new ConcurrentHashMap<>();
        this.processedRequests = new ConcurrentHashMap<>();
        this.seenNonces = ConcurrentHashMap.newKeySet();
        this.maxPendingRequests = maxPendingRequests;
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier);
    }

    /**
     * Receives and validates a data request.
     * Requirement 313.1: Create Inbox.receive(request) method.
     */
    public ReceiveResult receive(DataRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        List<String> violations = new ArrayList<>();

        // Check if request is expired
        if (request.isExpired()) {
            violations.add("Request has expired");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.EXPIRED);
        }

        // Check for replay attack (duplicate request ID)
        if (seenNonces.contains(request.id())) {
            violations.add("Duplicate request ID - possible replay attack");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.REPLAY_DETECTED);
        }

        // Verify signature
        if (!request.isSigned()) {
            violations.add("Request is not signed");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.INVALID_SIGNATURE);
        }

        if (!signatureVerifier.verify(request)) {
            violations.add("Invalid request signature");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.INVALID_SIGNATURE);
        }

        // Verify policy stamp
        if (!request.hasPolicyStamp()) {
            violations.add("Request missing policy stamp");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.MISSING_POLICY_STAMP);
        }

        if (!signatureVerifier.verifyPolicyStamp(request.policyStamp())) {
            violations.add("Invalid policy stamp");
            return new ReceiveResult(false, request.id(), violations, ReceiveStatus.INVALID_POLICY_STAMP);
        }

        // Check inbox capacity
        if (pendingRequests.size() >= maxPendingRequests) {
            // Remove oldest expired requests
            cleanupExpiredRequests();
            if (pendingRequests.size() >= maxPendingRequests) {
                violations.add("Inbox is full");
                return new ReceiveResult(false, request.id(), violations, ReceiveStatus.INBOX_FULL);
            }
        }

        // Accept the request
        seenNonces.add(request.id());
        pendingRequests.put(request.id(), request);

        return new ReceiveResult(true, request.id(), List.of(), ReceiveStatus.ACCEPTED);
    }

    /**
     * Gets a pending request by ID.
     */
    public Optional<DataRequest> getRequest(String requestId) {
        return Optional.ofNullable(pendingRequests.get(requestId));
    }

    /**
     * Gets all pending requests.
     */
    public List<DataRequest> getPendingRequests() {
        cleanupExpiredRequests();
        return new ArrayList<>(pendingRequests.values());
    }

    /**
     * Gets pending requests by type.
     */
    public List<DataRequest> getPendingRequestsByType(RequestType type) {
        return pendingRequests.values().stream()
                .filter(r -> r.type() == type && !r.isExpired())
                .toList();
    }

    /**
     * Marks a request as processed.
     */
    public void markProcessed(String requestId) {
        DataRequest request = pendingRequests.remove(requestId);
        if (request != null) {
            processedRequests.put(requestId, request);
        }
    }

    /**
     * Rejects a request.
     */
    public void reject(String requestId, String reason) {
        pendingRequests.remove(requestId);
    }

    /**
     * Gets the count of pending requests.
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }

    /**
     * Cleans up expired requests.
     */
    public void cleanupExpiredRequests() {
        Instant now = Instant.now();
        pendingRequests.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    /**
     * Result of receiving a request.
     */
    public record ReceiveResult(
            boolean accepted,
            String requestId,
            List<String> violations,
            ReceiveStatus status
    ) {}

    /**
     * Status codes for receive operations.
     */
    public enum ReceiveStatus {
        ACCEPTED,
        EXPIRED,
        REPLAY_DETECTED,
        INVALID_SIGNATURE,
        MISSING_POLICY_STAMP,
        INVALID_POLICY_STAMP,
        INBOX_FULL
    }

    /**
     * Interface for signature verification.
     */
    public interface SignatureVerifier {
        boolean verify(DataRequest request);
        boolean verifyPolicyStamp(String policyStamp);
    }

    /**
     * Default signature verifier (validates format, real verification needs keys).
     */
    public static class DefaultSignatureVerifier implements SignatureVerifier {
        @Override
        public boolean verify(DataRequest request) {
            // Verify signature format and basic validity
            String signature = request.signature();
            if (signature == null || signature.length() < 64) {
                return false;
            }
            // In production, this would verify against requester's public key
            return true;
        }

        @Override
        public boolean verifyPolicyStamp(String policyStamp) {
            // Verify policy stamp format
            if (policyStamp == null || policyStamp.length() < 32) {
                return false;
            }
            // In production, this would verify against coordinator's policy key
            return true;
        }
    }
}
