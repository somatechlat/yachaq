package com.yachaq.node.transport;

import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;

/**
 * Represents a P2P session between DS node and requester.
 * Requirement 317.1: Secure handshake with mutual authentication and forward secrecy.
 */
public record P2PSession(
        String sessionId,
        String localNodeId,
        String remoteNodeId,
        PublicKey remotePublicKey,
        SecretKey sessionKey,
        SessionState state,
        Instant createdAt,
        Instant expiresAt,
        TransportProtocol protocol,
        RendezvousInfo rendezvousInfo,
        Map<String, Object> metadata
) {
    
    public P2PSession {
        Objects.requireNonNull(sessionId, "Session ID cannot be null");
        Objects.requireNonNull(localNodeId, "Local node ID cannot be null");
        Objects.requireNonNull(remoteNodeId, "Remote node ID cannot be null");
        Objects.requireNonNull(state, "State cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        Objects.requireNonNull(protocol, "Protocol cannot be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Checks if the session is active.
     */
    public boolean isActive() {
        return state == SessionState.CONNECTED && !isExpired();
    }

    /**
     * Checks if the session has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if the session has a valid session key.
     */
    public boolean hasSessionKey() {
        return sessionKey != null;
    }

    /**
     * Session lifecycle states.
     */
    public enum SessionState {
        INITIATING,         // Handshake started
        AUTHENTICATING,     // Mutual authentication in progress
        KEY_EXCHANGE,       // Key exchange in progress
        CONNECTED,          // Session established
        TRANSFERRING,       // Data transfer in progress
        CLOSING,            // Session closing
        CLOSED,             // Session closed
        FAILED              // Session failed
    }

    /**
     * Supported transport protocols.
     * Requirement 317.6: Support WebRTC DataChannel or libp2p.
     */
    public enum TransportProtocol {
        WEBRTC_DATACHANNEL,
        LIBP2P,
        RELAY_ONLY
    }

    /**
     * Rendezvous information for NAT traversal.
     * Requirement 317.2: Use relays that carry only ciphertext.
     */
    public record RendezvousInfo(
            String relayEndpoint,
            String sessionToken,
            Instant tokenExpiry,
            List<String> iceServers,
            boolean relayOnly
    ) {
        public RendezvousInfo {
            Objects.requireNonNull(sessionToken, "Session token cannot be null");
            iceServers = iceServers != null ? List.copyOf(iceServers) : List.of();
        }

        public boolean isTokenExpired() {
            return tokenExpiry != null && Instant.now().isAfter(tokenExpiry);
        }
    }

    /**
     * Builder for P2PSession.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String localNodeId;
        private String remoteNodeId;
        private PublicKey remotePublicKey;
        private SecretKey sessionKey;
        private SessionState state = SessionState.INITIATING;
        private Instant createdAt = Instant.now();
        private Instant expiresAt;
        private TransportProtocol protocol = TransportProtocol.WEBRTC_DATACHANNEL;
        private RendezvousInfo rendezvousInfo;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder sessionId(String id) { this.sessionId = id; return this; }
        public Builder generateSessionId() { this.sessionId = UUID.randomUUID().toString(); return this; }
        public Builder localNodeId(String id) { this.localNodeId = id; return this; }
        public Builder remoteNodeId(String id) { this.remoteNodeId = id; return this; }
        public Builder remotePublicKey(PublicKey key) { this.remotePublicKey = key; return this; }
        public Builder sessionKey(SecretKey key) { this.sessionKey = key; return this; }
        public Builder state(SessionState state) { this.state = state; return this; }
        public Builder createdAt(Instant at) { this.createdAt = at; return this; }
        public Builder expiresAt(Instant at) { this.expiresAt = at; return this; }
        public Builder protocol(TransportProtocol protocol) { this.protocol = protocol; return this; }
        public Builder rendezvousInfo(RendezvousInfo info) { this.rendezvousInfo = info; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = new HashMap<>(metadata); return this; }

        public P2PSession build() {
            if (sessionId == null) sessionId = UUID.randomUUID().toString();
            if (expiresAt == null) expiresAt = createdAt.plusSeconds(3600); // 1 hour default
            return new P2PSession(sessionId, localNodeId, remoteNodeId, remotePublicKey,
                    sessionKey, state, createdAt, expiresAt, protocol, rendezvousInfo, metadata);
        }
    }
}
