package com.yachaq.node.importer;

import java.util.Set;

/**
 * Options for data import operations.
 */
public record ImportOptions(
        Set<String> dataTypesToImport,
        boolean skipDuplicates,
        boolean continueOnError,
        long maxFileSizeBytes,
        long maxMemoryBytes,
        int maxConcurrentFiles,
        boolean emitEvents,
        ProgressCallback progressCallback
) {
    public ImportOptions {
        dataTypesToImport = dataTypesToImport != null ? Set.copyOf(dataTypesToImport) : Set.of();
    }

    /**
     * Creates default import options.
     */
    public static ImportOptions defaults() {
        return new ImportOptions(
                Set.of(), // Empty = import all types
                true,     // Skip duplicates
                true,     // Continue on error
                5L * 1024 * 1024 * 1024, // 5GB max file size
                512L * 1024 * 1024,      // 512MB max memory
                4,        // 4 concurrent files
                true,     // Emit events
                null      // No progress callback
        );
    }

    /**
     * Creates a builder for ImportOptions.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Callback for import progress updates.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(ProgressUpdate update);
    }

    /**
     * Progress update during import.
     */
    public record ProgressUpdate(
            int totalItems,
            int processedItems,
            int currentFileIndex,
            int totalFiles,
            String currentFileName,
            double percentComplete
    ) {}

    public static class Builder {
        private Set<String> dataTypesToImport = Set.of();
        private boolean skipDuplicates = true;
        private boolean continueOnError = true;
        private long maxFileSizeBytes = 5L * 1024 * 1024 * 1024;
        private long maxMemoryBytes = 512L * 1024 * 1024;
        private int maxConcurrentFiles = 4;
        private boolean emitEvents = true;
        private ProgressCallback progressCallback = null;

        public Builder dataTypesToImport(Set<String> types) {
            this.dataTypesToImport = types;
            return this;
        }

        public Builder skipDuplicates(boolean skip) {
            this.skipDuplicates = skip;
            return this;
        }

        public Builder continueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public Builder maxFileSizeBytes(long maxSize) {
            this.maxFileSizeBytes = maxSize;
            return this;
        }

        public Builder maxMemoryBytes(long maxMemory) {
            this.maxMemoryBytes = maxMemory;
            return this;
        }

        public Builder maxConcurrentFiles(int maxConcurrent) {
            this.maxConcurrentFiles = maxConcurrent;
            return this;
        }

        public Builder emitEvents(boolean emit) {
            this.emitEvents = emit;
            return this;
        }

        public Builder progressCallback(ProgressCallback callback) {
            this.progressCallback = callback;
            return this;
        }

        public ImportOptions build() {
            return new ImportOptions(
                    dataTypesToImport, skipDuplicates, continueOnError,
                    maxFileSizeBytes, maxMemoryBytes, maxConcurrentFiles,
                    emitEvents, progressCallback
            );
        }
    }
}
