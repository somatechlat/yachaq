package com.yachaq.node.transport;

import com.yachaq.node.capsule.TimeCapsule;
import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.transport.P2PSession.*;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2P Transport for secure capsule delivery.
 * Requirement 317.1: Secure handshake with mutual authentication and forward secrecy.
 * Requirement 317.2: NAT traversal with ciphertext-only relays.
 * Requirement 317.3: Resumable transfers with chunk hashes.
 * Requirement 317.4: Acknowledgment receipts.
 */
public class P2PTransport {

    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64KB chunks
    private static final int SESSION_TTL_SECONDS = 3600;

    private final KeyManagementService keyManagement;
    private final String localNodeId;
    private final Map<String, P2PSession> activeSessions;
    private final Map<String, TransferState> activeTransfers;
    private final SecureRandom secureRandom;

    public P2PTransport(KeyManagementService keyManagement, String localNodeId) {
        this.keyManagement = Objects.requireNonNull(keyManagement, "Key management cannot be null");
        this.localNodeId = Objects.requireNonNull(localNodeId, "Local node ID cannot be null");
        this.activeSessions = new ConcurrentHashMap<>();
        this.activeTransfers = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
    }

    /**
     * Establishes a P2P connection with mutual authentication.
     * Requirement 317.1: Secure handshake with mutual authentication and forward secrecy.
     */
    public P2PSession connect(String requesterEndpoint, RendezvousInfo rendezvousInfo) 
            throws P2PException {
        Objects.requireNonNull(requesterEndpoint, "Requester endpoint cannot be null");
        Objects.requireNonNull(rendezvousInfo, "Rendezvous info cannot be null");

        if (rendezvousInfo.isTokenExpired()) {
            throw new P2PException("Rendezvous token has expired");
        }

        try {
            // Generate ephemeral key pair for forward secrecy
            KeyPair ephemeralKeyPair = generateEphemeralKeyPair();

            // Create session in initiating state
            P2PSession session = P2PSession.builder()
                    .generateSessionId()
                    .localNodeId(localNodeId)
                    .remoteNodeId(requesterEndpoint)
                    .state(SessionState.INITIATING)
                    .protocol(rendezvousInfo.relayOnly() ? 
                            TransportProtocol.RELAY_ONLY : TransportProtocol.WEBRTC_DATACHANNEL)
                    .rendezvousInfo(rendezvousInfo)
                    .expiresAt(Instant.now().plusSeconds(SESSION_TTL_SECONDS))
                    .build();

            activeSessions.put(session.sessionId(), session);

            // Perform handshake (simulated - in production would use actual network)
            HandshakeResult handshake = performHandshake(session, ephemeralKeyPair);

            // Update session with handshake results
            P2PSession connectedSession = P2PSession.builder()
                    .sessionId(session.sessionId())
                    .localNodeId(localNodeId)
                    .remoteNodeId(requesterEndpoint)
                    .remotePublicKey(handshake.remotePublicKey())
                    .sessionKey(handshake.sessionKey())
                    .state(SessionState.CONNECTED)
                    .createdAt(session.createdAt())
                    .expiresAt(session.expiresAt())
                    .protocol(session.protocol())
                    .rendezvousInfo(rendezvousInfo)
                    .build();

            activeSessions.put(connectedSession.sessionId(), connectedSession);
            return connectedSession;

        } catch (Exception e) {
            throw new P2PException("Failed to establish P2P connection: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a capsule over the P2P connection.
     * Requirement 317.3: Resumable transfers with chunk hashes.
     */
    public TransferResult sendCapsule(P2PSession session, TimeCapsule capsule) throws P2PException {
        Objects.requireNonNull(session, "Session cannot be null");
        Objects.requireNonNull(capsule, "Capsule cannot be null");

        if (!session.isActive()) {
            throw new P2PException("Session is not active");
        }

        if (!session.hasSessionKey()) {
            throw new P2PException("Session key not established");
        }

        try {
            // Serialize capsule to bytes
            byte[] capsuleData = serializeCapsule(capsule);

            // Create transfer state
            String transferId = UUID.randomUUID().toString();
            List<Chunk> chunks = createChunks(capsuleData, transferId);
            
            TransferState state = new TransferState(
                    transferId,
                    session.sessionId(),
                    capsule.getId(),
                    chunks.size(),
                    capsuleData.length,
                    new HashSet<>(),
                    Instant.now()
            );
            activeTransfers.put(transferId, state);

            // Send chunks (encrypted with session key)
            List<ChunkReceipt> receipts = new ArrayList<>();
            for (Chunk chunk : chunks) {
                byte[] encryptedChunk = encryptChunk(chunk, session.sessionKey());
                ChunkReceipt receipt = sendChunk(session, encryptedChunk, chunk);
                receipts.add(receipt);
                state.acknowledgedChunks().add(chunk.index());
            }

            // Verify all chunks acknowledged
            if (state.acknowledgedChunks().size() != chunks.size()) {
                return TransferResult.partial(transferId, state.acknowledgedChunks().size(), chunks.size());
            }

            return TransferResult.success(transferId, capsuleData.length, receipts);

        } catch (Exception e) {
            throw new P2PException("Failed to send capsule: " + e.getMessage(), e);
        }
    }

    /**
     * Resumes a partial transfer.
     * Requirement 317.3: Support resumable transfers.
     */
    public TransferResult resumeTransfer(String transferId, P2PSession session, TimeCapsule capsule) 
            throws P2PException {
        TransferState state = activeTransfers.get(transferId);
        if (state == null) {
            throw new P2PException("Transfer not found: " + transferId);
        }

        try {
            byte[] capsuleData = serializeCapsule(capsule);
            List<Chunk> chunks = createChunks(capsuleData, transferId);

            // Send only unacknowledged chunks
            List<ChunkReceipt> receipts = new ArrayList<>();
            for (Chunk chunk : chunks) {
                if (!state.acknowledgedChunks().contains(chunk.index())) {
                    byte[] encryptedChunk = encryptChunk(chunk, session.sessionKey());
                    ChunkReceipt receipt = sendChunk(session, encryptedChunk, chunk);
                    receipts.add(receipt);
                    state.acknowledgedChunks().add(chunk.index());
                }
            }

            if (state.acknowledgedChunks().size() == chunks.size()) {
                activeTransfers.remove(transferId);
                return TransferResult.success(transferId, capsuleData.length, receipts);
            }

            return TransferResult.partial(transferId, state.acknowledgedChunks().size(), chunks.size());

        } catch (Exception e) {
            throw new P2PException("Failed to resume transfer: " + e.getMessage(), e);
        }
    }

    /**
     * Receives an acknowledgment receipt.
     * Requirement 317.4: Require acknowledgment receipts from requesters.
     */
    public AcknowledgmentReceipt receiveAck(P2PSession session) throws P2PException {
        Objects.requireNonNull(session, "Session cannot be null");

        if (!session.isActive()) {
            throw new P2PException("Session is not active");
        }

        // In production, this would receive from network
        // For now, generate a valid acknowledgment
        String ackId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        
        // Sign the acknowledgment
        String signature;
        try {
            byte[] dataToSign = (ackId + "|" + session.sessionId() + "|" + timestamp).getBytes(StandardCharsets.UTF_8);
            byte[] sigBytes = keyManagement.signWithRootKey(dataToSign);
            signature = Base64.getEncoder().encodeToString(sigBytes);
        } catch (Exception e) {
            throw new P2PException("Failed to sign acknowledgment: " + e.getMessage(), e);
        }

        return new AcknowledgmentReceipt(
                ackId,
                session.sessionId(),
                session.remoteNodeId(),
                timestamp,
                signature,
                AcknowledgmentReceipt.AckStatus.CONFIRMED
        );
    }

    /**
     * Verifies an acknowledgment receipt.
     * Requirement 317.4: Require acknowledgment receipts.
     */
    public boolean verifyAck(AcknowledgmentReceipt ack, PublicKey requesterPublicKey) {
        if (ack == null || requesterPublicKey == null) {
            return false;
        }

        try {
            byte[] dataToVerify = (ack.ackId() + "|" + ack.sessionId() + "|" + ack.timestamp())
                    .getBytes(StandardCharsets.UTF_8);
            byte[] sigBytes = Base64.getDecoder().decode(ack.signature());
            return keyManagement.verifySignature(dataToVerify, sigBytes, requesterPublicKey);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Closes a P2P session.
     */
    public void closeSession(String sessionId) {
        P2PSession session = activeSessions.remove(sessionId);
        if (session != null) {
            // Clean up any active transfers for this session
            activeTransfers.entrySet().removeIf(e -> e.getValue().sessionId().equals(sessionId));
        }
    }

    /**
     * Gets an active session by ID.
     */
    public Optional<P2PSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * Checks if data is ciphertext only (for relay validation).
     * Requirement 317.2: Relays carry only ciphertext.
     */
    public boolean isCiphertextOnly(byte[] data) {
        if (data == null || data.length < GCM_IV_LENGTH + 16) {
            return false;
        }
        // Check for GCM structure: IV (12 bytes) + ciphertext + tag (16 bytes)
        // Ciphertext should have high entropy (> 7.0 bits per byte for random data)
        return calculateEntropy(data) > 7.0; // High entropy indicates encryption
    }

    // ==================== Private Methods ====================

    private KeyPair generateEphemeralKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256, secureRandom);
        return keyGen.generateKeyPair();
    }

    private HandshakeResult performHandshake(P2PSession session, KeyPair ephemeralKeyPair) 
            throws Exception {
        // In production, this would:
        // 1. Send our ephemeral public key + signature
        // 2. Receive remote ephemeral public key + signature
        // 3. Verify signatures using long-term keys
        // 4. Perform ECDH key agreement
        // 5. Derive session key using HKDF

        // For now, simulate successful handshake
        KeyPair remoteEphemeral = generateEphemeralKeyPair();
        
        // Derive shared secret using ECDH
        KeyAgreement keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
        keyAgreement.init(ephemeralKeyPair.getPrivate());
        keyAgreement.doPhase(remoteEphemeral.getPublic(), true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        // Derive session key from shared secret
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sessionKeyBytes = digest.digest(sharedSecret);
        SecretKey sessionKey = new SecretKeySpec(sessionKeyBytes, "AES");

        return new HandshakeResult(remoteEphemeral.getPublic(), sessionKey, true);
    }

    private byte[] serializeCapsule(TimeCapsule capsule) {
        // Simple serialization - in production would use proper format
        StringBuilder sb = new StringBuilder();
        sb.append("CAPSULE|");
        sb.append(capsule.getId()).append("|");
        sb.append(capsule.header().contractId()).append("|");
        sb.append(Base64.getEncoder().encodeToString(capsule.payload().encryptedData()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<Chunk> createChunks(byte[] data, String transferId) throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        int totalChunks = (int) Math.ceil((double) data.length / DEFAULT_CHUNK_SIZE);
        
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        for (int i = 0; i < totalChunks; i++) {
            int start = i * DEFAULT_CHUNK_SIZE;
            int end = Math.min(start + DEFAULT_CHUNK_SIZE, data.length);
            byte[] chunkData = Arrays.copyOfRange(data, start, end);
            
            byte[] hash = digest.digest(chunkData);
            String chunkHash = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            
            chunks.add(new Chunk(transferId, i, chunkData, chunkHash, totalChunks));
        }
        
        return chunks;
    }

    private byte[] encryptChunk(Chunk chunk, SecretKey sessionKey) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec);

        // Include chunk metadata in AAD
        byte[] aad = (chunk.transferId() + "|" + chunk.index()).getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(aad);

        byte[] encrypted = cipher.doFinal(chunk.data());

        // Prepend IV to encrypted data
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return buffer.array();
    }

    private ChunkReceipt sendChunk(P2PSession session, byte[] encryptedChunk, Chunk chunk) {
        // In production, this would send over network
        // Verify it's ciphertext only before sending
        if (!isCiphertextOnly(encryptedChunk)) {
            throw new SecurityException("Attempted to send non-ciphertext data");
        }

        return new ChunkReceipt(
                chunk.transferId(),
                chunk.index(),
                chunk.hash(),
                Instant.now(),
                true
        );
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

    // ==================== Inner Types ====================

    private record HandshakeResult(PublicKey remotePublicKey, SecretKey sessionKey, boolean authenticated) {}

    /**
     * Represents a data chunk for transfer.
     */
    public record Chunk(
            String transferId,
            int index,
            byte[] data,
            String hash,
            int totalChunks
    ) {
        public Chunk {
            Objects.requireNonNull(transferId, "Transfer ID cannot be null");
            Objects.requireNonNull(data, "Data cannot be null");
            Objects.requireNonNull(hash, "Hash cannot be null");
            data = data.clone();
        }
    }

    /**
     * Receipt for a sent chunk.
     */
    public record ChunkReceipt(
            String transferId,
            int chunkIndex,
            String chunkHash,
            Instant sentAt,
            boolean acknowledged
    ) {}

    /**
     * State of an ongoing transfer.
     */
    public record TransferState(
            String transferId,
            String sessionId,
            String capsuleId,
            int totalChunks,
            long totalBytes,
            Set<Integer> acknowledgedChunks,
            Instant startedAt
    ) {
        public TransferState {
            acknowledgedChunks = acknowledgedChunks != null ? 
                    new HashSet<>(acknowledgedChunks) : new HashSet<>();
        }

        public double progress() {
            return totalChunks > 0 ? (double) acknowledgedChunks.size() / totalChunks : 0;
        }
    }

    /**
     * Result of a transfer operation.
     */
    public record TransferResult(
            String transferId,
            boolean complete,
            int chunksTransferred,
            int totalChunks,
            long bytesTransferred,
            List<ChunkReceipt> receipts,
            String error
    ) {
        public static TransferResult success(String transferId, long bytes, List<ChunkReceipt> receipts) {
            return new TransferResult(transferId, true, receipts.size(), receipts.size(), 
                    bytes, receipts, null);
        }

        public static TransferResult partial(String transferId, int transferred, int total) {
            return new TransferResult(transferId, false, transferred, total, 0, List.of(), null);
        }

        public static TransferResult failed(String transferId, String error) {
            return new TransferResult(transferId, false, 0, 0, 0, List.of(), error);
        }
    }

    /**
     * Acknowledgment receipt from requester.
     * Requirement 317.4: Require acknowledgment receipts.
     */
    public record AcknowledgmentReceipt(
            String ackId,
            String sessionId,
            String remoteNodeId,
            Instant timestamp,
            String signature,
            AckStatus status
    ) {
        public enum AckStatus {
            CONFIRMED,
            PARTIAL,
            REJECTED,
            TIMEOUT
        }
    }

    /**
     * Exception for P2P operations.
     */
    public static class P2PException extends Exception {
        public P2PException(String message) {
            super(message);
        }

        public P2PException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
