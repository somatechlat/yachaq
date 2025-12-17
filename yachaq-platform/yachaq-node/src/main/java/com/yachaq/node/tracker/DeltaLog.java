package com.yachaq.node.tracker;

import com.yachaq.node.tracker.Delta.DeltaType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Hash-chained delta log for tracking changes.
 * Requirement 312.1: Create DeltaLog.append(delta) method with prev_hash → new_hash chain.
 * Requirement 312.3: Create DeltaLog.summarize(window) method.
 * Requirement 312.4: Test tamper detection, delta correctness.
 */
public class DeltaLog {

    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String HASH_ALGORITHM = "SHA-256";

    private final Deque<Delta> deltas;
    private final int maxSize;
    private String lastHash;

    public DeltaLog() {
        this(10000); // Default max 10k entries
    }

    public DeltaLog(int maxSize) {
        this.deltas = new ConcurrentLinkedDeque<>();
        this.maxSize = maxSize;
        this.lastHash = GENESIS_HASH;
    }

    /**
     * Appends a delta to the log with hash chaining.
     * Requirement 312.1: Create DeltaLog.append(delta) method.
     */
    public synchronized Delta append(DeltaType type, String entityId, String entityType,
                                      Map<String, Object> changes, String source) {
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(entityId, "Entity ID cannot be null");
        Objects.requireNonNull(entityType, "Entity type cannot be null");

        String prevHash = lastHash;
        Instant timestamp = Instant.now();
        String deltaId = UUID.randomUUID().toString();

        // Compute hash of this delta
        String hash = computeHash(deltaId, type, entityId, entityType, changes, prevHash, timestamp);

        Delta delta = Delta.builder()
                .id(deltaId)
                .type(type)
                .entityId(entityId)
                .entityType(entityType)
                .changes(changes)
                .prevHash(prevHash)
                .hash(hash)
                .timestamp(timestamp)
                .source(source)
                .build();

        deltas.addLast(delta);
        lastHash = hash;

        // Trim if exceeds max size
        while (deltas.size() > maxSize) {
            deltas.removeFirst();
        }

        return delta;
    }

    /**
     * Appends a pre-built delta (for recovery/replay).
     */
    public synchronized void appendDelta(Delta delta) {
        Objects.requireNonNull(delta, "Delta cannot be null");

        // Verify chain integrity
        if (!delta.prevHash().equals(lastHash)) {
            throw new ChainIntegrityException(
                    "Delta prev_hash does not match last hash: expected " + lastHash + 
                    ", got " + delta.prevHash());
        }

        // Verify hash
        String expectedHash = computeHash(delta.id(), delta.type(), delta.entityId(),
                delta.entityType(), delta.changes(), delta.prevHash(), delta.timestamp());
        if (!expectedHash.equals(delta.hash())) {
            throw new ChainIntegrityException(
                    "Delta hash mismatch: expected " + expectedHash + ", got " + delta.hash());
        }

        deltas.addLast(delta);
        lastHash = delta.hash();

        while (deltas.size() > maxSize) {
            deltas.removeFirst();
        }
    }

    /**
     * Summarizes changes within a time window.
     * Requirement 312.3: Create DeltaLog.summarize(window) method.
     */
    public DeltaSummary summarize(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        
        List<Delta> windowDeltas = deltas.stream()
                .filter(d -> d.timestamp().isAfter(cutoff))
                .toList();

        Map<DeltaType, Long> countsByType = windowDeltas.stream()
                .collect(Collectors.groupingBy(Delta::type, Collectors.counting()));

        Map<String, Long> countsByEntityType = windowDeltas.stream()
                .collect(Collectors.groupingBy(Delta::entityType, Collectors.counting()));

        Set<String> affectedEntities = windowDeltas.stream()
                .map(Delta::entityId)
                .collect(Collectors.toSet());

        return new DeltaSummary(
                windowDeltas.size(),
                countsByType,
                countsByEntityType,
                affectedEntities.size(),
                window,
                windowDeltas.isEmpty() ? null : windowDeltas.get(0).timestamp(),
                windowDeltas.isEmpty() ? null : windowDeltas.get(windowDeltas.size() - 1).timestamp()
        );
    }

    /**
     * Summarizes habit patterns from the delta log.
     */
    public HabitSummary summarizeHabits(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        
        List<Delta> windowDeltas = deltas.stream()
                .filter(d -> d.timestamp().isAfter(cutoff))
                .toList();

        // Count by hour of day
        Map<Integer, Long> byHour = windowDeltas.stream()
                .collect(Collectors.groupingBy(
                        d -> d.timestamp().atZone(java.time.ZoneId.of("UTC")).getHour(),
                        Collectors.counting()
                ));

        // Count by day of week
        Map<Integer, Long> byDayOfWeek = windowDeltas.stream()
                .collect(Collectors.groupingBy(
                        d -> d.timestamp().atZone(java.time.ZoneId.of("UTC")).getDayOfWeek().getValue(),
                        Collectors.counting()
                ));

        // Most active entity types
        Map<String, Long> byEntityType = windowDeltas.stream()
                .collect(Collectors.groupingBy(Delta::entityType, Collectors.counting()));

        return new HabitSummary(byHour, byDayOfWeek, byEntityType, windowDeltas.size());
    }

    /**
     * Verifies the integrity of the hash chain.
     * Requirement 312.4: Test tamper detection.
     */
    public ChainVerificationResult verifyChain() {
        if (deltas.isEmpty()) {
            return new ChainVerificationResult(true, 0, List.of());
        }

        List<String> violations = new ArrayList<>();
        String expectedPrevHash = GENESIS_HASH;
        int verified = 0;

        for (Delta delta : deltas) {
            // Check prev_hash chain
            if (!delta.prevHash().equals(expectedPrevHash)) {
                violations.add("Chain break at delta " + delta.id() + 
                        ": expected prev_hash " + expectedPrevHash + 
                        ", got " + delta.prevHash());
            }

            // Verify hash computation
            String computedHash = computeHash(delta.id(), delta.type(), delta.entityId(),
                    delta.entityType(), delta.changes(), delta.prevHash(), delta.timestamp());
            if (!computedHash.equals(delta.hash())) {
                violations.add("Hash mismatch at delta " + delta.id() + 
                        ": expected " + computedHash + ", got " + delta.hash());
            }

            expectedPrevHash = delta.hash();
            verified++;
        }

        return new ChainVerificationResult(violations.isEmpty(), verified, violations);
    }

    /**
     * Gets deltas for a specific entity.
     */
    public List<Delta> getDeltasForEntity(String entityId) {
        return deltas.stream()
                .filter(d -> d.entityId().equals(entityId))
                .toList();
    }

    /**
     * Gets deltas within a time range.
     */
    public List<Delta> getDeltasInRange(Instant start, Instant end) {
        return deltas.stream()
                .filter(d -> !d.timestamp().isBefore(start) && !d.timestamp().isAfter(end))
                .toList();
    }

    /**
     * Gets the last N deltas.
     */
    public List<Delta> getLastDeltas(int count) {
        List<Delta> all = new ArrayList<>(deltas);
        int start = Math.max(0, all.size() - count);
        return all.subList(start, all.size());
    }

    /**
     * Gets the current chain head hash.
     */
    public String getLastHash() {
        return lastHash;
    }

    /**
     * Gets the total number of deltas.
     */
    public int size() {
        return deltas.size();
    }

    /**
     * Checks if the log is empty.
     */
    public boolean isEmpty() {
        return deltas.isEmpty();
    }

    // ==================== Hash Computation ====================

    private String computeHash(String id, DeltaType type, String entityId, String entityType,
                               Map<String, Object> changes, String prevHash, Instant timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            
            // Build canonical string representation
            StringBuilder sb = new StringBuilder();
            sb.append(id).append("|");
            sb.append(type.name()).append("|");
            sb.append(entityId).append("|");
            sb.append(entityType).append("|");
            sb.append(prevHash).append("|");
            sb.append(timestamp.toEpochMilli()).append("|");
            
            // Sort changes for deterministic hashing
            if (changes != null && !changes.isEmpty()) {
                changes.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append(","));
            }
            
            byte[] hashBytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== Supporting Records ====================

    /**
     * Summary of deltas within a time window.
     */
    public record DeltaSummary(
            int totalDeltas,
            Map<DeltaType, Long> countsByType,
            Map<String, Long> countsByEntityType,
            int uniqueEntities,
            Duration window,
            Instant firstTimestamp,
            Instant lastTimestamp
    ) {}

    /**
     * Habit pattern summary.
     */
    public record HabitSummary(
            Map<Integer, Long> activityByHour,
            Map<Integer, Long> activityByDayOfWeek,
            Map<String, Long> activityByEntityType,
            int totalEvents
    ) {
        public int peakHour() {
            return activityByHour.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(-1);
        }

        public int peakDayOfWeek() {
            return activityByDayOfWeek.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(-1);
        }
    }

    /**
     * Result of chain verification.
     */
    public record ChainVerificationResult(
            boolean isValid,
            int verifiedCount,
            List<String> violations
    ) {}

    /**
     * Exception for chain integrity violations.
     */
    public static class ChainIntegrityException extends RuntimeException {
        public ChainIntegrityException(String message) {
            super(message);
        }
    }
}
