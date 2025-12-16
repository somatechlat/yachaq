package com.yachaq.node.connector;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A single item returned from a connector sync operation.
 * Contains normalized data ready for processing by the normalizer.
 */
public record SyncItem(
        String itemId,
        String recordType,
        Instant timestamp,
        Instant endTimestamp,
        Map<String, Object> data,
        Map<String, String> metadata,
        String sourceId,
        String checksum
) {
    public SyncItem {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Item ID cannot be null or blank");
        }
        if (recordType == null || recordType.isBlank()) {
            throw new IllegalArgumentException("Record type cannot be null or blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }
        data = data != null ? Map.copyOf(data) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Creates a builder for SyncItem.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the end timestamp if present.
     */
    public Optional<Instant> getEndTimestamp() {
        return Optional.ofNullable(endTimestamp);
    }

    /**
     * Returns the duration in seconds if end timestamp is present.
     */
    public Optional<Long> getDurationSeconds() {
        if (endTimestamp == null) {
            return Optional.empty();
        }
        return Optional.of(endTimestamp.getEpochSecond() - timestamp.getEpochSecond());
    }

    public static class Builder {
        private String itemId;
        private String recordType;
        private Instant timestamp;
        private Instant endTimestamp;
        private Map<String, Object> data = Map.of();
        private Map<String, String> metadata = Map.of();
        private String sourceId;
        private String checksum;

        public Builder itemId(String itemId) {
            this.itemId = itemId;
            return this;
        }

        public Builder recordType(String recordType) {
            this.recordType = recordType;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder endTimestamp(Instant endTimestamp) {
            this.endTimestamp = endTimestamp;
            return this;
        }

        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder checksum(String checksum) {
            this.checksum = checksum;
            return this;
        }

        public SyncItem build() {
            return new SyncItem(itemId, recordType, timestamp, endTimestamp, data, metadata, sourceId, checksum);
        }
    }
}
