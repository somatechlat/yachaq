package com.yachaq.node.tracker;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a change delta in the ODX or data store.
 * Requirement 312.1: Create DeltaLog.append(delta) method with hash chaining.
 */
public record Delta(
        String id,
        DeltaType type,
        String entityId,
        String entityType,
        Map<String, Object> changes,
        String prevHash,
        String hash,
        Instant timestamp,
        String source
) {
    
    public Delta {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(entityId, "Entity ID cannot be null");
        Objects.requireNonNull(entityType, "Entity type cannot be null");
        Objects.requireNonNull(hash, "Hash cannot be null");
        Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        changes = changes != null ? Map.copyOf(changes) : Map.of();
    }

    /**
     * Types of changes tracked.
     */
    public enum DeltaType {
        CREATE,     // New entity created
        UPDATE,     // Entity updated
        DELETE,     // Entity deleted
        MERGE,      // Entities merged (e.g., ODX upsert)
        SYNC,       // Data synchronized from connector
        IMPORT,     // Data imported from file
        EXPIRE,     // Data expired (TTL)
        SHRED       // Data crypto-shredded
    }

    /**
     * Checks if this delta follows the given previous delta in the chain.
     */
    public boolean followsFrom(Delta previous) {
        if (previous == null) {
            return prevHash == null || prevHash.isEmpty();
        }
        return previous.hash().equals(this.prevHash);
    }

    /**
     * Returns a summary string for logging.
     */
    public String toSummary() {
        return String.format("[%s] %s %s:%s", 
                timestamp, type, entityType, entityId);
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private DeltaType type;
        private String entityId;
        private String entityType;
        private Map<String, Object> changes;
        private String prevHash;
        private String hash;
        private Instant timestamp;
        private String source;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder generateId() {
            this.id = UUID.randomUUID().toString();
            return this;
        }

        public Builder type(DeltaType type) {
            this.type = type;
            return this;
        }

        public Builder entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder changes(Map<String, Object> changes) {
            this.changes = changes;
            return this;
        }

        public Builder prevHash(String prevHash) {
            this.prevHash = prevHash;
            return this;
        }

        public Builder hash(String hash) {
            this.hash = hash;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Delta build() {
            if (id == null) {
                id = UUID.randomUUID().toString();
            }
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new Delta(id, type, entityId, entityType, changes, 
                    prevHash, hash, timestamp, source);
        }
    }
}
