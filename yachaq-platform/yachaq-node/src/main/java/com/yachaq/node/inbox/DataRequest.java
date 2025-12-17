package com.yachaq.node.inbox;

import java.time.Instant;
import java.util.*;

/**
 * Represents a data request received from the coordinator.
 * Requirement 313.1: Create Inbox.receive(request) method.
 */
public record DataRequest(
        String id,
        String requesterId,
        String requesterName,
        RequestType type,
        Set<String> requiredLabels,
        Set<String> optionalLabels,
        TimeWindow timeWindow,
        GeoConstraint geoConstraint,
        OutputMode outputMode,
        CompensationOffer compensation,
        String policyStamp,
        String signature,
        Instant createdAt,
        Instant expiresAt,
        Map<String, Object> metadata
) {
    
    public DataRequest {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(requiredLabels, "Required labels cannot be null");
        Objects.requireNonNull(outputMode, "Output mode cannot be null");
        Objects.requireNonNull(createdAt, "Created at cannot be null");
        Objects.requireNonNull(expiresAt, "Expires at cannot be null");
        
        requiredLabels = Set.copyOf(requiredLabels);
        optionalLabels = optionalLabels != null ? Set.copyOf(optionalLabels) : Set.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Checks if the request has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if the request has a valid policy stamp.
     */
    public boolean hasPolicyStamp() {
        return policyStamp != null && !policyStamp.isBlank();
    }

    /**
     * Checks if the request is signed.
     */
    public boolean isSigned() {
        return signature != null && !signature.isBlank();
    }

    /**
     * Request types.
     */
    public enum RequestType {
        BROADCAST,      // Mode A: Broadcast to all eligible nodes
        GEO_TOPIC,      // Mode B: Rotating geo topics
        TARGETED        // Direct request (requires higher verification)
    }

    /**
     * Output modes for data delivery.
     */
    public enum OutputMode {
        AGGREGATE_ONLY,     // Only aggregated results
        CLEAN_ROOM,         // View in clean room only
        EXPORT_ALLOWED,     // Export permitted
        RAW_EXPORT          // Raw data export (highest tier only)
    }

    /**
     * Time window constraint.
     */
    public record TimeWindow(
            Instant start,
            Instant end
    ) {
        public TimeWindow {
            Objects.requireNonNull(start, "Start cannot be null");
            Objects.requireNonNull(end, "End cannot be null");
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Start must be before end");
            }
        }

        public boolean contains(Instant timestamp) {
            return !timestamp.isBefore(start) && !timestamp.isAfter(end);
        }
    }

    /**
     * Geographic constraint (coarse only).
     */
    public record GeoConstraint(
            String regionCode,
            GeoResolution resolution
    ) {
        public enum GeoResolution {
            COUNTRY,
            REGION,
            CITY
        }
    }

    /**
     * Compensation offer.
     */
    public record CompensationOffer(
            double amount,
            String currency,
            String escrowId
    ) {
        public CompensationOffer {
            if (amount < 0) {
                throw new IllegalArgumentException("Amount cannot be negative");
            }
            Objects.requireNonNull(currency, "Currency cannot be null");
        }
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String requesterId;
        private String requesterName;
        private RequestType type = RequestType.BROADCAST;
        private Set<String> requiredLabels = new HashSet<>();
        private Set<String> optionalLabels = new HashSet<>();
        private TimeWindow timeWindow;
        private GeoConstraint geoConstraint;
        private OutputMode outputMode = OutputMode.AGGREGATE_ONLY;
        private CompensationOffer compensation;
        private String policyStamp;
        private String signature;
        private Instant createdAt;
        private Instant expiresAt;
        private Map<String, Object> metadata = new HashMap<>();

        public Builder id(String id) { this.id = id; return this; }
        public Builder generateId() { this.id = UUID.randomUUID().toString(); return this; }
        public Builder requesterId(String requesterId) { this.requesterId = requesterId; return this; }
        public Builder requesterName(String requesterName) { this.requesterName = requesterName; return this; }
        public Builder type(RequestType type) { this.type = type; return this; }
        public Builder requiredLabels(Set<String> labels) { this.requiredLabels = new HashSet<>(labels); return this; }
        public Builder addRequiredLabel(String label) { this.requiredLabels.add(label); return this; }
        public Builder optionalLabels(Set<String> labels) { this.optionalLabels = new HashSet<>(labels); return this; }
        public Builder addOptionalLabel(String label) { this.optionalLabels.add(label); return this; }
        public Builder timeWindow(TimeWindow window) { this.timeWindow = window; return this; }
        public Builder geoConstraint(GeoConstraint constraint) { this.geoConstraint = constraint; return this; }
        public Builder outputMode(OutputMode mode) { this.outputMode = mode; return this; }
        public Builder compensation(CompensationOffer offer) { this.compensation = offer; return this; }
        public Builder policyStamp(String stamp) { this.policyStamp = stamp; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = new HashMap<>(metadata); return this; }

        public DataRequest build() {
            if (id == null) id = UUID.randomUUID().toString();
            if (createdAt == null) createdAt = Instant.now();
            if (expiresAt == null) expiresAt = createdAt.plusSeconds(86400); // 24h default
            return new DataRequest(id, requesterId, requesterName, type, requiredLabels,
                    optionalLabels, timeWindow, geoConstraint, outputMode, compensation,
                    policyStamp, signature, createdAt, expiresAt, metadata);
        }
    }
}
