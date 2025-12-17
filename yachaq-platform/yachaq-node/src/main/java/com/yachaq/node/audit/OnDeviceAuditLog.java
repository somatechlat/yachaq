package com.yachaq.node.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * On-Device Audit Log with hash-chaining for tamper detection.
 * Requirement 319.1: Maintain a hash-chained append-only log.
 * Requirement 319.2: Log permissions, requests, contracts, plans, capsules, transfers, crypto-shred events.
 * Requirement 319.3: Display events in plain language through transparency UI.
 * Requirement 319.4: Support audit-friendly export formats.
 */
public class OnDeviceAuditLog {

    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    
    private final List<AuditEntry> entries;
    private final String nodeId;
    private volatile String lastHash;

    public OnDeviceAuditLog(String nodeId) {
        this.nodeId = Objects.requireNonNull(nodeId, "Node ID cannot be null");
        this.entries = new CopyOnWriteArrayList<>();
        this.lastHash = GENESIS_HASH;
    }

    /**
     * Appends an event to the audit log.
     * Requirement 319.1: Maintain a hash-chained append-only log.
     */
    public synchronized AuditEntry append(AuditEvent event) {
        Objects.requireNonNull(event, "Event cannot be null");

        String previousHash = lastHash;
        long sequenceNumber = entries.size();
        Instant timestamp = Instant.now();

        // Compute hash of this entry
        String entryHash = computeEntryHash(sequenceNumber, previousHash, event, timestamp);

        AuditEntry entry = new AuditEntry(
                UUID.randomUUID().toString(),
                sequenceNumber,
                event,
                timestamp,
                previousHash,
                entryHash,
                nodeId
        );

        entries.add(entry);
        lastHash = entryHash;

        return entry;
    }

    /**
     * Logs a permission event.
     * Requirement 319.2: Log permissions granted/revoked.
     */
    public AuditEntry logPermission(String permissionType, String scope, boolean granted) {
        return append(new AuditEvent(
                EventType.PERMISSION,
                granted ? "Permission granted" : "Permission revoked",
                Map.of(
                        "permissionType", permissionType,
                        "scope", scope,
                        "granted", String.valueOf(granted)
                )
        ));
    }

    /**
     * Logs a request received event.
     * Requirement 319.2: Log requests received.
     */
    public AuditEntry logRequestReceived(String requestId, String requesterId, String purpose) {
        return append(new AuditEvent(
                EventType.REQUEST_RECEIVED,
                "Data request received",
                Map.of(
                        "requestId", requestId,
                        "requesterId", requesterId,
                        "purpose", purpose
                )
        ));
    }

    /**
     * Logs a contract signed event.
     * Requirement 319.2: Log contracts signed.
     */
    public AuditEntry logContractSigned(String contractId, String requesterId, Set<String> labels) {
        return append(new AuditEvent(
                EventType.CONTRACT_SIGNED,
                "Consent contract signed",
                Map.of(
                        "contractId", contractId,
                        "requesterId", requesterId,
                        "labels", String.join(",", labels)
                )
        ));
    }

    /**
     * Logs a plan executed event.
     * Requirement 319.2: Log plans executed.
     */
    public AuditEntry logPlanExecuted(String planId, String contractId, int recordCount) {
        return append(new AuditEvent(
                EventType.PLAN_EXECUTED,
                "Query plan executed",
                Map.of(
                        "planId", planId,
                        "contractId", contractId,
                        "recordCount", String.valueOf(recordCount)
                )
        ));
    }

    /**
     * Logs a capsule created event.
     * Requirement 319.2: Log capsule hashes created.
     */
    public AuditEntry logCapsuleCreated(String capsuleId, String capsuleHash, String contractId) {
        return append(new AuditEvent(
                EventType.CAPSULE_CREATED,
                "Time capsule created",
                Map.of(
                        "capsuleId", capsuleId,
                        "capsuleHash", capsuleHash,
                        "contractId", contractId
                )
        ));
    }

    /**
     * Logs a P2P transfer completed event.
     * Requirement 319.2: Log P2P transfers completed.
     */
    public AuditEntry logTransferCompleted(String transferId, String capsuleId, String destination) {
        return append(new AuditEvent(
                EventType.TRANSFER_COMPLETED,
                "P2P transfer completed",
                Map.of(
                        "transferId", transferId,
                        "capsuleId", capsuleId,
                        "destination", destination
                )
        ));
    }

    /**
     * Logs a crypto-shred event.
     * Requirement 319.2: Log TTL crypto-shred events.
     */
    public AuditEntry logCryptoShred(String keyId, String capsuleId, String reason) {
        return append(new AuditEvent(
                EventType.CRYPTO_SHRED,
                "Encryption key destroyed (crypto-shred)",
                Map.of(
                        "keyId", keyId,
                        "capsuleId", capsuleId != null ? capsuleId : "",
                        "reason", reason
                )
        ));
    }

    /**
     * Gets all audit entries.
     */
    public List<AuditEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Gets entries by event type.
     */
    public List<AuditEntry> getEntries(EventType type) {
        return entries.stream()
                .filter(e -> e.event().type() == type)
                .toList();
    }

    /**
     * Gets entries in a time range.
     */
    public List<AuditEntry> getEntries(Instant from, Instant to) {
        return entries.stream()
                .filter(e -> !e.timestamp().isBefore(from) && !e.timestamp().isAfter(to))
                .toList();
    }

    /**
     * Gets the latest N entries.
     */
    public List<AuditEntry> getLatestEntries(int count) {
        int size = entries.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(entries.subList(start, size));
    }

    /**
     * Verifies the integrity of the audit log.
     * Requirement 319.5: Detect tampering through hash chain verification.
     */
    public VerificationResult verifyIntegrity() {
        if (entries.isEmpty()) {
            return new VerificationResult(true, List.of(), entries.size());
        }

        List<String> errors = new ArrayList<>();
        String expectedPrevHash = GENESIS_HASH;

        for (int i = 0; i < entries.size(); i++) {
            AuditEntry entry = entries.get(i);

            // Check sequence number
            if (entry.sequenceNumber() != i) {
                errors.add("Sequence number mismatch at index " + i);
            }

            // Check previous hash
            if (!entry.previousHash().equals(expectedPrevHash)) {
                errors.add("Previous hash mismatch at index " + i);
            }

            // Verify entry hash
            String computedHash = computeEntryHash(
                    entry.sequenceNumber(),
                    entry.previousHash(),
                    entry.event(),
                    entry.timestamp()
            );
            if (!entry.entryHash().equals(computedHash)) {
                errors.add("Entry hash mismatch at index " + i + " - possible tampering");
            }

            expectedPrevHash = entry.entryHash();
        }

        return new VerificationResult(errors.isEmpty(), errors, entries.size());
    }

    /**
     * Exports the audit log in JSON format.
     * Requirement 319.4: Support audit-friendly export formats.
     */
    public String export() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"nodeId\": \"").append(nodeId).append("\",\n");
        sb.append("  \"exportedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"entryCount\": ").append(entries.size()).append(",\n");
        sb.append("  \"entries\": [\n");

        for (int i = 0; i < entries.size(); i++) {
            AuditEntry entry = entries.get(i);
            sb.append("    ").append(entryToJson(entry));
            if (i < entries.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Gets a plain language description of an entry.
     * Requirement 319.3: Display events in plain language.
     */
    public String getPlainLanguageDescription(AuditEntry entry) {
        AuditEvent event = entry.event();
        Map<String, String> details = event.details();

        return switch (event.type()) {
            case PERMISSION -> {
                boolean granted = "true".equals(details.get("granted"));
                yield String.format("You %s %s permission for %s",
                        granted ? "granted" : "revoked",
                        details.get("permissionType"),
                        details.get("scope"));
            }
            case REQUEST_RECEIVED -> String.format(
                    "Received a data request from %s for: %s",
                    details.get("requesterId"),
                    details.get("purpose"));
            case CONTRACT_SIGNED -> String.format(
                    "You signed a consent contract with %s sharing: %s",
                    details.get("requesterId"),
                    details.get("labels"));
            case PLAN_EXECUTED -> String.format(
                    "Query plan executed, processed %s records",
                    details.get("recordCount"));
            case CAPSULE_CREATED -> String.format(
                    "Created encrypted data capsule (ID: %s)",
                    details.get("capsuleId").substring(0, 8) + "...");
            case TRANSFER_COMPLETED -> String.format(
                    "Securely transferred data to %s",
                    details.get("destination"));
            case CRYPTO_SHRED -> String.format(
                    "Permanently destroyed encryption key: %s",
                    details.get("reason"));
        };
    }

    /**
     * Gets the total number of entries.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Gets the last hash in the chain.
     */
    public String getLastHash() {
        return lastHash;
    }

    // ==================== Private Methods ====================

    private String computeEntryHash(long sequenceNumber, String previousHash, 
                                    AuditEvent event, Instant timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = sequenceNumber + "|" + previousHash + "|" + 
                         event.type() + "|" + event.description() + "|" + 
                         event.details().toString() + "|" + timestamp.toEpochMilli();
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String entryToJson(AuditEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(entry.id()).append("\",");
        sb.append("\"seq\":").append(entry.sequenceNumber()).append(",");
        sb.append("\"type\":\"").append(entry.event().type()).append("\",");
        sb.append("\"description\":\"").append(entry.event().description()).append("\",");
        sb.append("\"details\":{");
        boolean first = true;
        for (Map.Entry<String, String> detail : entry.event().details().entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(detail.getKey()).append("\":\"").append(detail.getValue()).append("\"");
            first = false;
        }
        sb.append("},");
        sb.append("\"timestamp\":\"").append(entry.timestamp()).append("\",");
        sb.append("\"prevHash\":\"").append(entry.previousHash().substring(0, 16)).append("...\",");
        sb.append("\"hash\":\"").append(entry.entryHash().substring(0, 16)).append("...\"");
        sb.append("}");
        return sb.toString();
    }

    // ==================== Inner Types ====================

    /**
     * Types of audit events.
     * Requirement 319.2: Log various event types.
     */
    public enum EventType {
        PERMISSION,
        REQUEST_RECEIVED,
        CONTRACT_SIGNED,
        PLAN_EXECUTED,
        CAPSULE_CREATED,
        TRANSFER_COMPLETED,
        CRYPTO_SHRED
    }

    /**
     * An audit event.
     */
    public record AuditEvent(
            EventType type,
            String description,
            Map<String, String> details
    ) {
        public AuditEvent {
            Objects.requireNonNull(type, "Type cannot be null");
            Objects.requireNonNull(description, "Description cannot be null");
            details = details != null ? Map.copyOf(details) : Map.of();
        }
    }

    /**
     * An entry in the audit log.
     */
    public record AuditEntry(
            String id,
            long sequenceNumber,
            AuditEvent event,
            Instant timestamp,
            String previousHash,
            String entryHash,
            String nodeId
    ) {
        public AuditEntry {
            Objects.requireNonNull(id, "ID cannot be null");
            Objects.requireNonNull(event, "Event cannot be null");
            Objects.requireNonNull(timestamp, "Timestamp cannot be null");
            Objects.requireNonNull(previousHash, "Previous hash cannot be null");
            Objects.requireNonNull(entryHash, "Entry hash cannot be null");
            Objects.requireNonNull(nodeId, "Node ID cannot be null");
        }
    }

    /**
     * Result of integrity verification.
     */
    public record VerificationResult(
            boolean valid,
            List<String> errors,
            int entriesVerified
    ) {
        public VerificationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
    }
}
