package com.yachaq.node.importer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of a data import operation.
 * Requirement 306.3: Handle supported export schemas and store in vault.
 * Requirement 306.4: Emit canonical events for normalizer.
 */
public record ImportResult(
        boolean success,
        ImportStatus status,
        int totalItemsProcessed,
        int itemsImported,
        int itemsSkipped,
        int itemsFailed,
        List<ImportedItem> importedItems,
        List<ImportError> errors,
        Instant startedAt,
        Instant completedAt,
        Map<String, Integer> itemCountByType,
        String importId
) {
    public ImportResult {
        importedItems = importedItems != null ? List.copyOf(importedItems) : List.of();
        errors = errors != null ? List.copyOf(errors) : List.of();
        itemCountByType = itemCountByType != null ? Map.copyOf(itemCountByType) : Map.of();
    }

    /**
     * Creates a successful import result.
     */
    public static ImportResult success(
            List<ImportedItem> items,
            int skipped,
            Instant started,
            String importId,
            Map<String, Integer> countByType) {
        
        return new ImportResult(
                true, ImportStatus.COMPLETED,
                items.size() + skipped, items.size(), skipped, 0,
                items, List.of(), started, Instant.now(), countByType, importId
        );
    }

    /**
     * Creates a partial success result (some items failed).
     */
    public static ImportResult partial(
            List<ImportedItem> items,
            int skipped,
            List<ImportError> errors,
            Instant started,
            String importId,
            Map<String, Integer> countByType) {
        
        return new ImportResult(
                true, ImportStatus.PARTIAL,
                items.size() + skipped + errors.size(), items.size(), skipped, errors.size(),
                items, errors, started, Instant.now(), countByType, importId
        );
    }

    /**
     * Creates a failed import result.
     */
    public static ImportResult failure(List<ImportError> errors, Instant started, String importId) {
        return new ImportResult(
                false, ImportStatus.FAILED,
                0, 0, 0, errors.size(),
                List.of(), errors, started, Instant.now(), Map.of(), importId
        );
    }

    /**
     * Creates a cancelled import result.
     */
    public static ImportResult cancelled(
            List<ImportedItem> itemsSoFar,
            Instant started,
            String importId) {
        
        return new ImportResult(
                false, ImportStatus.CANCELLED,
                itemsSoFar.size(), itemsSoFar.size(), 0, 0,
                itemsSoFar, List.of(), started, Instant.now(), Map.of(), importId
        );
    }

    /**
     * Returns the duration of the import.
     */
    public Duration getDuration() {
        if (startedAt == null || completedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, completedAt);
    }

    /**
     * Returns the success rate as a percentage.
     */
    public double getSuccessRate() {
        if (totalItemsProcessed == 0) {
            return 100.0;
        }
        return (double) itemsImported / totalItemsProcessed * 100.0;
    }

    /**
     * Import status.
     */
    public enum ImportStatus {
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    /**
     * A single imported item.
     */
    public record ImportedItem(
            String itemId,
            String dataType,
            String vaultRef,
            Instant timestamp,
            Map<String, String> metadata
    ) {
        public ImportedItem {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }

    /**
     * An import error.
     */
    public record ImportError(
            String itemPath,
            String errorCode,
            String errorMessage,
            boolean recoverable
    ) {}
}
