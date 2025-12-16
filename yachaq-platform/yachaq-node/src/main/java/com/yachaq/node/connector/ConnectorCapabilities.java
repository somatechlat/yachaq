package com.yachaq.node.connector;

import java.util.List;
import java.util.Set;

/**
 * Describes the capabilities of a connector.
 * Requirement 305.1: Expose capabilities including data types and label families.
 * 
 * @param dataTypes List of data types this connector can provide (e.g., "sleep", "workout", "heart_rate")
 * @param labelFamilies List of label families this connector maps to (e.g., "health", "mobility", "media")
 * @param supportsIncremental Whether this connector supports incremental sync with cursors
 * @param requiresOAuth Whether this connector requires OAuth authorization
 * @param supportedPlatforms Platforms this connector supports (e.g., "ios", "android", "all")
 * @param rateLimitPerMinute Maximum requests per minute (0 = unlimited)
 * @param maxBatchSize Maximum items per sync batch
 */
public record ConnectorCapabilities(
        List<String> dataTypes,
        List<String> labelFamilies,
        boolean supportsIncremental,
        boolean requiresOAuth,
        Set<String> supportedPlatforms,
        int rateLimitPerMinute,
        int maxBatchSize
) {
    public ConnectorCapabilities {
        // Defensive copies for immutability
        dataTypes = dataTypes != null ? List.copyOf(dataTypes) : List.of();
        labelFamilies = labelFamilies != null ? List.copyOf(labelFamilies) : List.of();
        supportedPlatforms = supportedPlatforms != null ? Set.copyOf(supportedPlatforms) : Set.of();
        
        if (rateLimitPerMinute < 0) {
            throw new IllegalArgumentException("Rate limit cannot be negative");
        }
        if (maxBatchSize < 0) {
            throw new IllegalArgumentException("Max batch size cannot be negative");
        }
    }

    /**
     * Creates a builder for ConnectorCapabilities.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<String> dataTypes = List.of();
        private List<String> labelFamilies = List.of();
        private boolean supportsIncremental = true;
        private boolean requiresOAuth = false;
        private Set<String> supportedPlatforms = Set.of("all");
        private int rateLimitPerMinute = 0;
        private int maxBatchSize = 100;

        public Builder dataTypes(List<String> dataTypes) {
            this.dataTypes = dataTypes;
            return this;
        }

        public Builder labelFamilies(List<String> labelFamilies) {
            this.labelFamilies = labelFamilies;
            return this;
        }

        public Builder supportsIncremental(boolean supportsIncremental) {
            this.supportsIncremental = supportsIncremental;
            return this;
        }

        public Builder requiresOAuth(boolean requiresOAuth) {
            this.requiresOAuth = requiresOAuth;
            return this;
        }

        public Builder supportedPlatforms(Set<String> supportedPlatforms) {
            this.supportedPlatforms = supportedPlatforms;
            return this;
        }

        public Builder rateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public ConnectorCapabilities build() {
            return new ConnectorCapabilities(
                    dataTypes, labelFamilies, supportsIncremental, requiresOAuth,
                    supportedPlatforms, rateLimitPerMinute, maxBatchSize
            );
        }
    }
}
