package com.yachaq.api.coordinator;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinator Rendezvous and Signaling Service.
 * Requirement 323.1: Provide ephemeral session tokens for P2P connection.
 * Requirement 323.2: Operate relay service that carries only ciphertext.
 * Requirement 323.3: Enforce short TTL on all signaling metadata.
 * Requirement 323.4: Use no stable identifiers.
 * Requirement 323.5: Store only ephemeral session data with automatic expiry.
 */
@Service
public class RendezvousService {

    // Default TTL for session tokens (5 minutes)
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(5);
    // Maximum TTL allowed (15 minutes)
    private static final Duration MAX_TOKEN_TTL = Duration.ofMinutes(15);
    // Relay message TTL (30 seconds)
    private static final Duration RELAY_MESSAGE_TTL = Duration.ofSeconds(30);
    // Cleanup interval (1 minute)
    private static final long CLEANUP_INTERVAL_MS = 60_000;

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    // Ephemeral session storage (no persistent storage)
    private final Map<String, EphemeralSession> activeSessions;
    // Relay message queue (ciphertext only)
    private final Map<String, RelayMessage> relayQueue;
    // Token signing key (rotated regularly)
    private final byte[] tokenSigningKey;
    private final SecureRandom secureRandom;
    private final AuditService auditService;

    @Value("${yachaq.rendezvous.token-ttl-seconds:300}")
    private int tokenTtlSeconds;

    @Value("${yachaq.rendezvous.max-sessions-per-peer:10}")
    private int maxSessionsPerPeer;

    public RendezvousService(AuditService auditService) {
        this.auditService = auditService;
        this.activeSessions = new ConcurrentHashMap<>();
        this.relayQueue = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
        this.tokenSigningKey = new byte[32];
        this.secureRandom.nextBytes(this.tokenSigningKey);
    }

    /**
     * Creates an ephemeral session token for P2P connection.
     * Requirement 323.1: Provide ephemeral session tokens for P2P connection.
     * Requirement 323.4: Use no stable identifiers.
     */
    public SessionTokenResult createSessionToken(SessionRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");

        // Validate request
        if (request.requesterId() == null || request.dsEphemeralId() == null) {
            return SessionTokenResult.failure("Missing required identifiers");
        }

        // Generate ephemeral session ID (no stable identifiers)
        String sessionId = generateEphemeralId();
        
        // Calculate TTL (enforce maximum)
        Duration ttl = request.requestedTtl() != null ? 
                request.requestedTtl() : DEFAULT_TOKEN_TTL;
        if (ttl.compareTo(MAX_TOKEN_TTL) > 0) {
            ttl = MAX_TOKEN_TTL;
        }
        Instant expiresAt = Instant.now().plus(ttl);

        // Generate session token (signed, ephemeral)
        String token = generateSignedToken(sessionId, expiresAt);

        // Create ephemeral session (no persistent storage)
        EphemeralSession session = new EphemeralSession(
                sessionId,
                request.dsEphemeralId(),
                request.requesterEphemeralId(),
                token,
                Instant.now(),
                expiresAt,
                SessionStatus.PENDING,
                request.relayEndpoint(),
                request.iceServers()
        );

        activeSessions.put(sessionId, session);

        // Audit (no stable identifiers in audit)
        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_CREATED, // Session creation event
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                AuditReceipt.ActorType.SYSTEM,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                "RendezvousSession",
                sha256(sessionId)
        );

        return SessionTokenResult.success(
                sessionId,
                token,
                expiresAt,
                session.relayEndpoint(),
                session.iceServers()
        );
    }


    /**
     * Validates a session token.
     * Requirement 323.3: Enforce short TTL on all signaling metadata.
     */
    public TokenValidationResult validateToken(String token) {
        if (token == null || token.isBlank()) {
            return TokenValidationResult.invalid("Token is required");
        }

        try {
            // Parse and verify token
            TokenPayload payload = parseAndVerifyToken(token);
            if (payload == null) {
                return TokenValidationResult.invalid("Invalid token signature");
            }

            // Check expiry
            if (Instant.now().isAfter(payload.expiresAt())) {
                return TokenValidationResult.expired("Token has expired");
            }

            // Check session exists
            EphemeralSession session = activeSessions.get(payload.sessionId());
            if (session == null) {
                return TokenValidationResult.invalid("Session not found");
            }

            return TokenValidationResult.valid(payload.sessionId(), session.status());

        } catch (Exception e) {
            return TokenValidationResult.invalid("Token validation failed: " + e.getMessage());
        }
    }

    /**
     * Relays ciphertext between peers.
     * Requirement 323.2: Operate relay service that carries only ciphertext.
     */
    public RelayResult relayMessage(String sessionId, byte[] ciphertext, String fromPeer) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null");
        Objects.requireNonNull(ciphertext, "Ciphertext cannot be null");

        // Validate session
        EphemeralSession session = activeSessions.get(sessionId);
        if (session == null) {
            return RelayResult.failure("Session not found");
        }

        if (session.isExpired()) {
            activeSessions.remove(sessionId);
            return RelayResult.failure("Session has expired");
        }

        // Verify ciphertext only (no plaintext allowed)
        if (!isCiphertextOnly(ciphertext)) {
            // Log potential security violation
            auditService.appendReceipt(
                    AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
                    UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    AuditReceipt.ActorType.SYSTEM,
                    UUID.fromString("00000000-0000-0000-0000-000000000000"),
                    "RelayViolation",
                    sha256(sessionId + "|plaintext_attempt")
            );
            return RelayResult.failure("Only ciphertext is allowed through relay");
        }

        // Generate ephemeral message ID
        String messageId = generateEphemeralId();
        Instant expiresAt = Instant.now().plus(RELAY_MESSAGE_TTL);

        // Queue message for recipient (ephemeral storage only)
        RelayMessage message = new RelayMessage(
                messageId,
                sessionId,
                fromPeer,
                ciphertext,
                Instant.now(),
                expiresAt
        );
        relayQueue.put(messageId, message);

        return RelayResult.success(messageId, expiresAt);
    }


    /**
     * Retrieves pending relay messages for a peer.
     * Requirement 323.5: Store only ephemeral session data with automatic expiry.
     */
    public List<RelayMessage> retrieveMessages(String sessionId, String forPeer) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null");
        Objects.requireNonNull(forPeer, "Peer ID cannot be null");

        // Validate session
        EphemeralSession session = activeSessions.get(sessionId);
        if (session == null || session.isExpired()) {
            return List.of();
        }

        // Retrieve and remove messages (ephemeral - single delivery)
        List<RelayMessage> messages = new ArrayList<>();
        Iterator<Map.Entry<String, RelayMessage>> it = relayQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RelayMessage> entry = it.next();
            RelayMessage msg = entry.getValue();
            if (msg.sessionId().equals(sessionId) && !msg.fromPeer().equals(forPeer)) {
                if (!msg.isExpired()) {
                    messages.add(msg);
                }
                it.remove(); // Remove after retrieval (ephemeral)
            }
        }

        return messages;
    }

    /**
     * Marks a session as connected.
     */
    public void markSessionConnected(String sessionId) {
        EphemeralSession session = activeSessions.get(sessionId);
        if (session != null && !session.isExpired()) {
            activeSessions.put(sessionId, session.withStatus(SessionStatus.CONNECTED));
        }
    }

    /**
     * Closes a session and removes all associated data.
     * Requirement 323.5: Store only ephemeral session data with automatic expiry.
     */
    public void closeSession(String sessionId) {
        activeSessions.remove(sessionId);
        // Remove all relay messages for this session
        relayQueue.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
    }

    /**
     * Scheduled cleanup of expired sessions and messages.
     * Requirement 323.3: Enforce short TTL on all signaling metadata.
     * Requirement 323.5: Store only ephemeral session data with automatic expiry.
     */
    @Scheduled(fixedRate = CLEANUP_INTERVAL_MS)
    public void cleanupExpiredData() {
        Instant now = Instant.now();
        
        // Remove expired sessions
        activeSessions.entrySet().removeIf(e -> e.getValue().isExpired());
        
        // Remove expired relay messages
        relayQueue.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    /**
     * Gets session statistics (for monitoring, no PII).
     */
    public SessionStats getStats() {
        int activeSess = (int) activeSessions.values().stream()
                .filter(s -> !s.isExpired())
                .count();
        int pendingMsgs = (int) relayQueue.values().stream()
                .filter(m -> !m.isExpired())
                .count();
        return new SessionStats(activeSess, pendingMsgs);
    }

    // ==================== Private Methods ====================

    /**
     * Generates an ephemeral ID with no stable identifiers.
     * Requirement 323.4: Use no stable identifiers.
     */
    private String generateEphemeralId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


    /**
     * Generates a signed session token.
     */
    private String generateSignedToken(String sessionId, Instant expiresAt) {
        String payload = sessionId + "|" + expiresAt.toEpochMilli();
        String signature = hmacSha256(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses and verifies a session token.
     */
    private TokenPayload parseAndVerifyToken(String token) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            String tokenStr = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = tokenStr.split("\\|");
            if (parts.length != 3) {
                return null;
            }

            String sessionId = parts[0];
            long expiryMillis = Long.parseLong(parts[1]);
            String signature = parts[2];

            // Verify signature
            String expectedSig = hmacSha256(sessionId + "|" + expiryMillis);
            if (!MessageDigest.isEqual(signature.getBytes(), expectedSig.getBytes())) {
                return null;
            }

            return new TokenPayload(sessionId, Instant.ofEpochMilli(expiryMillis));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies data is ciphertext only (high entropy).
     * Requirement 323.2: Operate relay service that carries only ciphertext.
     */
    private boolean isCiphertextOnly(byte[] data) {
        if (data == null || data.length < GCM_IV_LENGTH + 16) {
            return false;
        }
        // Check for high entropy (> 7.0 bits per byte indicates encryption)
        return calculateEntropy(data) > 7.0;
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

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSigningKey, "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

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
     * Request to create a session token.
     */
    public record SessionRequest(
            UUID requesterId,
            String dsEphemeralId,
            String requesterEphemeralId,
            Duration requestedTtl,
            String relayEndpoint,
            List<String> iceServers
    ) {
        public SessionRequest {
            iceServers = iceServers != null ? List.copyOf(iceServers) : List.of();
        }
    }

    /**
     * Result of session token creation.
     */
    public record SessionTokenResult(
            boolean success,
            String sessionId,
            String token,
            Instant expiresAt,
            String relayEndpoint,
            List<String> iceServers,
            String error
    ) {
        public static SessionTokenResult success(String sessionId, String token, Instant expiresAt,
                                                  String relayEndpoint, List<String> iceServers) {
            return new SessionTokenResult(true, sessionId, token, expiresAt, 
                    relayEndpoint, iceServers, null);
        }

        public static SessionTokenResult failure(String error) {
            return new SessionTokenResult(false, null, null, null, null, List.of(), error);
        }
    }

    /**
     * Result of token validation.
     */
    public record TokenValidationResult(
            boolean valid,
            String sessionId,
            SessionStatus status,
            String error
    ) {
        public static TokenValidationResult valid(String sessionId, SessionStatus status) {
            return new TokenValidationResult(true, sessionId, status, null);
        }

        public static TokenValidationResult invalid(String error) {
            return new TokenValidationResult(false, null, null, error);
        }

        public static TokenValidationResult expired(String error) {
            return new TokenValidationResult(false, null, null, error);
        }
    }

    /**
     * Result of relay operation.
     */
    public record RelayResult(
            boolean success,
            String messageId,
            Instant expiresAt,
            String error
    ) {
        public static RelayResult success(String messageId, Instant expiresAt) {
            return new RelayResult(true, messageId, expiresAt, null);
        }

        public static RelayResult failure(String error) {
            return new RelayResult(false, null, null, error);
        }
    }

    /**
     * Ephemeral session data (no persistent storage).
     * Requirement 323.5: Store only ephemeral session data with automatic expiry.
     */
    public record EphemeralSession(
            String sessionId,
            String dsEphemeralId,
            String requesterEphemeralId,
            String token,
            Instant createdAt,
            Instant expiresAt,
            SessionStatus status,
            String relayEndpoint,
            List<String> iceServers
    ) {
        public EphemeralSession {
            iceServers = iceServers != null ? List.copyOf(iceServers) : List.of();
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        public EphemeralSession withStatus(SessionStatus newStatus) {
            return new EphemeralSession(sessionId, dsEphemeralId, requesterEphemeralId,
                    token, createdAt, expiresAt, newStatus, relayEndpoint, iceServers);
        }
    }

    /**
     * Session status.
     */
    public enum SessionStatus {
        PENDING,
        CONNECTED,
        CLOSED
    }

    /**
     * Relay message (ciphertext only).
     * Requirement 323.2: Operate relay service that carries only ciphertext.
     */
    public record RelayMessage(
            String messageId,
            String sessionId,
            String fromPeer,
            byte[] ciphertext,
            Instant createdAt,
            Instant expiresAt
    ) {
        public RelayMessage {
            ciphertext = ciphertext != null ? ciphertext.clone() : new byte[0];
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Token payload (internal).
     */
    private record TokenPayload(String sessionId, Instant expiresAt) {}

    /**
     * Session statistics (no PII).
     */
    public record SessionStats(int activeSessions, int pendingMessages) {}
}
