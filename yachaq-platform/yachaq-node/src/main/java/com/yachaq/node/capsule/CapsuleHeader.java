package com.yachaq.node.capsule;

import java.time.Instant;
import java.util.*;

/**
 * Header for a Time Capsule containing metadata.
 * Requirement 316.1: Include header with plan_id, TTL, schema, summary.
 */
public record CapsuleHeader(
        String capsuleId,
        String planId,
        String contractId,
        Instant ttl,
        String schemaVersion,
        Map<String, String> schema,
        CapsuleSummary summary,
        Instant createdAt,
        String dsNodeId,
        String requesterId
) {
    
    public CapsuleHeader {
        Objects.requireNonNull(capsuleId, "Capsule ID cannot be null");
        Objects.requireNonNull(planId, "Plan ID cannot be null");
        Objects.requireNonNull(contractId, "Contract ID cannot be null");
        Objects.requireNonNull(ttl, "TTL cannot be null");
        Objects.requireNonNull(schemaVersion, "Schema version cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        Objects.requireNonNull(dsNodeId, "DS Node ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        
        schema = schema != null ? Map.copyOf(schema) : Map.of();
    }

    /**
     * Checks if the capsule has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(ttl);
    }

    /**
     * Gets remaining TTL in seconds.
     */
    public long remainingTtlSeconds() {
        long remaining = ttl.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    /**
     * Gets canonical bytes for hashing/signing.
     */
    public byte[] getCanonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append(capsuleId).append("|");
        sb.append(planId).append("|");
        sb.append(contractId).append("|");
        sb.append(ttl.toEpochMilli()).append("|");
        sb.append(schemaVersion).append("|");
        sb.append(dsNodeId).append("|");
        sb.append(requesterId);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Summary of capsule contents.
     */
    public record CapsuleSummary(
            int recordCount,
            Set<String> fieldNames,
            Set<String> labelCategories,
            long payloadSizeBytes,
            String outputMode
    ) {
        public CapsuleSummary {
            fieldNames = fieldNames != null ? Set.copyOf(fieldNames) : Set.of();
            labelCategories = labelCategories != null ? Set.copyOf(labelCategories) : Set.of();
        }

        public static CapsuleSummary empty() {
            return new CapsuleSummary(0, Set.of(), Set.of(), 0, "UNKNOWN");
        }

        public static CapsuleSummary of(int recordCount, Set<String> fields, long size, String mode) {
            return new CapsuleSummary(recordCount, fields, Set.of(), size, mode);
        }
    }

    /**
     * Builder for CapsuleHeader.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String capsuleId;
        private String planId;
        private String contractId;
        private Instant ttl;
        private String schemaVersion = "1.0";
        private Map<String, String> schema = new HashMap<>();
        private CapsuleSummary summary = CapsuleSummary.empty();
        private Instant createdAt = Instant.now();
        private String dsNodeId;
        private String requesterId;

        public Builder capsuleId(String id) { this.capsuleId = id; return this; }
        public Builder planId(String id) { this.planId = id; return this; }
        public Builder contractId(String id) { this.contractId = id; return this; }
        public Builder ttl(Instant ttl) { this.ttl = ttl; return this; }
        public Builder schemaVersion(String version) { this.schemaVersion = version; return this; }
        public Builder schema(Map<String, String> schema) { this.schema = new HashMap<>(schema); return this; }
        public Builder summary(CapsuleSummary summary) { this.summary = summary; return this; }
        public Builder createdAt(Instant at) { this.createdAt = at; return this; }
        public Builder dsNodeId(String id) { this.dsNodeId = id; return this; }
        public Builder requesterId(String id) { this.requesterId = id; return this; }

        public CapsuleHeader build() {
            if (capsuleId == null) capsuleId = UUID.randomUUID().toString();
            return new CapsuleHeader(capsuleId, planId, contractId, ttl, schemaVersion, 
                    schema, summary, createdAt, dsNodeId, requesterId);
        }
    }
}
