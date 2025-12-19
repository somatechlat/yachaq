package com.yachaq.node.onboarding;

import com.yachaq.node.audit.OnDeviceAuditLog;
import com.yachaq.node.vault.LocalVault;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emergency Controls for Provider App UI.
 * Provides instant stop, relationship revocation, and vault purge capabilities.
 * 
 * Security: All emergency actions are logged with full audit trail.
 * Performance: Emergency stop is immediate with no delays.
 * UX: Clear warnings before destructive actions.
 * 
 * Validates: Requirements 347.1, 347.2, 347.3, 347.4, 347.5
 */
public class EmergencyControls {

    private final SharingController sharingController;
    private final RelationshipManager relationshipManager;
    private final VaultManager vaultManager;
    private final AuditLogger auditLogger;
    private final AtomicBoolean emergencyStopActive;
    private final Map<String, EmergencyAction> actionHistory;

    public EmergencyControls(SharingController sharingController,
                            RelationshipManager relationshipManager,
                            VaultManager vaultManager,
                            AuditLogger auditLogger) {
        this.sharingController = Objects.requireNonNull(sharingController, "SharingController cannot be null");
        this.relationshipManager = Objects.requireNonNull(relationshipManager, "RelationshipManager cannot be null");
        this.vaultManager = Objects.requireNonNull(vaultManager, "VaultManager cannot be null");
        this.auditLogger = Objects.requireNonNull(auditLogger, "AuditLogger cannot be null");
        this.emergencyStopActive = new AtomicBoolean(false);
        this.actionHistory = new ConcurrentHashMap<>();
    }

    // ==================== Task 91.1: Emergency Stop ====================

    /**
     * Stops all sharing instantly.
     * Requirement 347.1: Stop all sharing instantly.
     * 
     * @param reason Reason for emergency stop
     * @return EmergencyStopResult with details
     */
    public EmergencyStopResult emergencyStop(String reason) {
        Objects.requireNonNull(reason, "Reason cannot be null");

        String actionId = generateActionId("STOP");
        Instant timestamp = Instant.now();

        // Set emergency stop flag immediately
        emergencyStopActive.set(true);

        // Stop all active transfers
        int transfersStopped = sharingController.stopAllTransfers();

        // Pause all active contracts
        int contractsPaused = sharingController.pauseAllContracts();

        // Block new requests
        sharingController.blockNewRequests(true);

        // Log the action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.EMERGENCY_STOP,
                reason,
                timestamp,
                EmergencyActionStatus.COMPLETED,
                Map.of(
                        "transfersStopped", String.valueOf(transfersStopped),
                        "contractsPaused", String.valueOf(contractsPaused)
                )
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return new EmergencyStopResult(
                actionId,
                true,
                transfersStopped,
                contractsPaused,
                timestamp,
                "All sharing stopped immediately. " + transfersStopped + " transfers stopped, " + 
                        contractsPaused + " contracts paused."
        );
    }

    /**
     * Resumes sharing after emergency stop.
     * 
     * @param confirmationCode Confirmation code to prevent accidental resume
     * @return ResumeResult
     */
    public ResumeResult resumeSharing(String confirmationCode) {
        Objects.requireNonNull(confirmationCode, "Confirmation code cannot be null");

        if (!emergencyStopActive.get()) {
            return ResumeResult.failed("Emergency stop is not active");
        }

        // Verify confirmation code (simple check - in production would be more secure)
        if (!confirmationCode.equals("CONFIRM_RESUME")) {
            return ResumeResult.failed("Invalid confirmation code");
        }

        String actionId = generateActionId("RESUME");
        Instant timestamp = Instant.now();

        // Resume operations
        emergencyStopActive.set(false);
        sharingController.blockNewRequests(false);
        int contractsResumed = sharingController.resumePausedContracts();

        // Log the action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.RESUME_SHARING,
                "User resumed sharing",
                timestamp,
                EmergencyActionStatus.COMPLETED,
                Map.of("contractsResumed", String.valueOf(contractsResumed))
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return ResumeResult.success(actionId, contractsResumed, timestamp);
    }

    /**
     * Checks if emergency stop is active.
     * 
     * @return true if emergency stop is active
     */
    public boolean isEmergencyStopActive() {
        return emergencyStopActive.get();
    }

    // ==================== Task 91.2: Relationship Revocation ====================

    /**
     * Revokes all relationships with a specific requester.
     * Requirement 347.2: Allow revoking all requester relationships.
     * 
     * @param requesterId Requester ID to revoke
     * @param reason Reason for revocation
     * @return RevocationResult
     */
    public RevocationResult revokeRequesterRelationship(String requesterId, String reason) {
        Objects.requireNonNull(requesterId, "Requester ID cannot be null");
        Objects.requireNonNull(reason, "Reason cannot be null");

        String actionId = generateActionId("REVOKE");
        Instant timestamp = Instant.now();

        // Get all contracts with this requester
        List<String> contractIds = relationshipManager.getContractIds(requesterId);

        // Revoke all contracts
        int contractsRevoked = 0;
        List<String> errors = new ArrayList<>();
        for (String contractId : contractIds) {
            try {
                relationshipManager.revokeContract(contractId, reason);
                contractsRevoked++;
            } catch (Exception e) {
                errors.add("Failed to revoke " + contractId + ": " + e.getMessage());
            }
        }

        // Block future requests from this requester
        relationshipManager.blockRequester(requesterId, reason);

        // Crypto-shred any data shared with this requester
        int capsulesShredded = vaultManager.shredRequesterData(requesterId);

        EmergencyActionStatus status = errors.isEmpty() ? 
                EmergencyActionStatus.COMPLETED : EmergencyActionStatus.PARTIAL;

        // Log the action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.REVOKE_RELATIONSHIP,
                reason,
                timestamp,
                status,
                Map.of(
                        "requesterId", requesterId,
                        "contractsRevoked", String.valueOf(contractsRevoked),
                        "capsulesShredded", String.valueOf(capsulesShredded)
                )
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return new RevocationResult(
                actionId,
                errors.isEmpty(),
                requesterId,
                contractsRevoked,
                capsulesShredded,
                timestamp,
                errors
        );
    }

    /**
     * Revokes all requester relationships.
     * Requirement 347.2: Allow revoking all requester relationships.
     * 
     * @param reason Reason for revocation
     * @param confirmationCode Confirmation code
     * @return BulkRevocationResult
     */
    public BulkRevocationResult revokeAllRelationships(String reason, String confirmationCode) {
        Objects.requireNonNull(reason, "Reason cannot be null");
        Objects.requireNonNull(confirmationCode, "Confirmation code cannot be null");

        if (!confirmationCode.equals("CONFIRM_REVOKE_ALL")) {
            return BulkRevocationResult.failed("Invalid confirmation code");
        }

        String actionId = generateActionId("REVOKE_ALL");
        Instant timestamp = Instant.now();

        List<String> requesterIds = relationshipManager.getAllRequesterIds();
        int totalRevoked = 0;
        int totalShredded = 0;
        List<String> errors = new ArrayList<>();

        for (String requesterId : requesterIds) {
            RevocationResult result = revokeRequesterRelationship(requesterId, reason);
            if (result.success()) {
                totalRevoked += result.contractsRevoked();
                totalShredded += result.capsulesShredded();
            } else {
                errors.addAll(result.errors());
            }
        }

        // Log the bulk action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.REVOKE_ALL_RELATIONSHIPS,
                reason,
                timestamp,
                errors.isEmpty() ? EmergencyActionStatus.COMPLETED : EmergencyActionStatus.PARTIAL,
                Map.of(
                        "requestersProcessed", String.valueOf(requesterIds.size()),
                        "contractsRevoked", String.valueOf(totalRevoked),
                        "capsulesShredded", String.valueOf(totalShredded)
                )
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return new BulkRevocationResult(
                actionId,
                errors.isEmpty(),
                requesterIds.size(),
                totalRevoked,
                totalShredded,
                timestamp,
                errors
        );
    }

    // ==================== Task 91.3: Vault Purge ====================

    /**
     * Gets purge warning for a category.
     * Requirement 347.3: Allow category purging with strong warnings.
     * 
     * @param category Category to purge
     * @return PurgeWarning with details
     */
    public PurgeWarning getPurgeWarning(String category) {
        Objects.requireNonNull(category, "Category cannot be null");

        VaultCategoryInfo info = vaultManager.getCategoryInfo(category);

        List<String> warnings = new ArrayList<>();
        warnings.add("⚠️ THIS ACTION IS IRREVERSIBLE");
        warnings.add("All data in category '" + category + "' will be permanently deleted");
        warnings.add("This includes " + info.itemCount() + " items totaling " + formatBytes(info.totalBytes()));
        
        if (info.hasActiveContracts()) {
            warnings.add("⚠️ " + info.activeContractCount() + " active contracts use this data - they will be terminated");
        }
        
        if (info.hasPendingPayouts()) {
            warnings.add("⚠️ Pending payouts of " + info.pendingPayoutAmount() + " may be affected");
        }

        warnings.add("You will need to re-import this data if you want to share it again");

        return new PurgeWarning(
                category,
                info.itemCount(),
                info.totalBytes(),
                info.activeContractCount(),
                warnings,
                generateConfirmationCode(category)
        );
    }

    /**
     * Purges a vault category.
     * Requirement 347.3: Allow category purging with strong warnings.
     * 
     * @param category Category to purge
     * @param confirmationCode Confirmation code from getPurgeWarning
     * @return PurgeResult
     */
    public PurgeResult purgeCategory(String category, String confirmationCode) {
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(confirmationCode, "Confirmation code cannot be null");

        // Verify confirmation code
        String expectedCode = generateConfirmationCode(category);
        if (!confirmationCode.equals(expectedCode)) {
            return PurgeResult.failed(category, "Invalid confirmation code");
        }

        String actionId = generateActionId("PURGE");
        Instant timestamp = Instant.now();

        // Get category info before purge
        VaultCategoryInfo info = vaultManager.getCategoryInfo(category);

        // Terminate affected contracts first
        int contractsTerminated = 0;
        if (info.hasActiveContracts()) {
            contractsTerminated = relationshipManager.terminateContractsForCategory(category, 
                    "Data category purged by user");
        }

        // Crypto-shred the category
        int itemsShredded = vaultManager.purgeCategory(category);

        // Clear ODX entries for this category
        int odxEntriesCleared = vaultManager.clearOdxForCategory(category);

        // Log the action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.PURGE_CATEGORY,
                "User purged category: " + category,
                timestamp,
                EmergencyActionStatus.COMPLETED,
                Map.of(
                        "category", category,
                        "itemsShredded", String.valueOf(itemsShredded),
                        "bytesFreed", String.valueOf(info.totalBytes()),
                        "contractsTerminated", String.valueOf(contractsTerminated),
                        "odxEntriesCleared", String.valueOf(odxEntriesCleared)
                )
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return new PurgeResult(
                actionId,
                true,
                category,
                itemsShredded,
                info.totalBytes(),
                contractsTerminated,
                odxEntriesCleared,
                timestamp,
                null
        );
    }

    /**
     * Purges all vault data.
     * Requirement 347.3: Allow category purging with strong warnings.
     * 
     * @param confirmationCode Confirmation code
     * @return BulkPurgeResult
     */
    public BulkPurgeResult purgeAllData(String confirmationCode) {
        Objects.requireNonNull(confirmationCode, "Confirmation code cannot be null");

        if (!confirmationCode.equals("CONFIRM_PURGE_ALL_DATA")) {
            return BulkPurgeResult.failed("Invalid confirmation code");
        }

        String actionId = generateActionId("PURGE_ALL");
        Instant timestamp = Instant.now();

        // First, emergency stop
        emergencyStop("Purging all data");

        // Revoke all relationships
        revokeAllRelationships("Purging all data", "CONFIRM_REVOKE_ALL");

        // Get all categories
        List<String> categories = vaultManager.getAllCategories();
        int totalItemsShredded = 0;
        long totalBytesFreed = 0;

        for (String category : categories) {
            VaultCategoryInfo info = vaultManager.getCategoryInfo(category);
            totalItemsShredded += vaultManager.purgeCategory(category);
            totalBytesFreed += info.totalBytes();
        }

        // Clear all ODX
        int odxEntriesCleared = vaultManager.clearAllOdx();

        // Log the action
        EmergencyAction action = new EmergencyAction(
                actionId,
                EmergencyActionType.PURGE_ALL_DATA,
                "User purged all data",
                timestamp,
                EmergencyActionStatus.COMPLETED,
                Map.of(
                        "categoriesPurged", String.valueOf(categories.size()),
                        "itemsShredded", String.valueOf(totalItemsShredded),
                        "bytesFreed", String.valueOf(totalBytesFreed),
                        "odxEntriesCleared", String.valueOf(odxEntriesCleared)
                )
        );
        actionHistory.put(actionId, action);
        auditLogger.logEmergencyAction(action);

        return new BulkPurgeResult(
                actionId,
                true,
                categories.size(),
                totalItemsShredded,
                totalBytesFreed,
                odxEntriesCleared,
                timestamp,
                null
        );
    }

    // ==================== Audit Trail ====================

    /**
     * Gets the emergency action history.
     * Requirement 347.4: Audit logging.
     * 
     * @return List of emergency actions
     */
    public List<EmergencyAction> getActionHistory() {
        return actionHistory.values().stream()
                .sorted(Comparator.comparing(EmergencyAction::timestamp).reversed())
                .toList();
    }

    /**
     * Gets a specific action by ID.
     * 
     * @param actionId Action ID
     * @return EmergencyAction or null
     */
    public EmergencyAction getAction(String actionId) {
        return actionHistory.get(actionId);
    }

    // ==================== Recovery ====================

    /**
     * Gets recovery options after emergency actions.
     * Requirement 347.5: Recovery options.
     * 
     * @return RecoveryOptions
     */
    public RecoveryOptions getRecoveryOptions() {
        List<RecoveryOption> options = new ArrayList<>();

        if (emergencyStopActive.get()) {
            options.add(new RecoveryOption(
                    "RESUME_SHARING",
                    "Resume Sharing",
                    "Resume all paused contracts and allow new requests",
                    "CONFIRM_RESUME"
            ));
        }

        // Check for recoverable data
        List<String> recoverableCategories = vaultManager.getRecoverableCategories();
        if (!recoverableCategories.isEmpty()) {
            options.add(new RecoveryOption(
                    "RESTORE_DATA",
                    "Restore Data",
                    "Restore recently purged data from backup (if available)",
                    "CONFIRM_RESTORE"
            ));
        }

        return new RecoveryOptions(
                emergencyStopActive.get(),
                options,
                getActionHistory().stream().limit(5).toList()
        );
    }

    // ==================== Private Helper Methods ====================

    private String generateActionId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateConfirmationCode(String category) {
        // Simple hash-based confirmation code
        return "PURGE-" + Math.abs(category.hashCode() % 10000);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }


    // ==================== Inner Types ====================

    /**
     * Emergency action type enumeration.
     */
    public enum EmergencyActionType {
        EMERGENCY_STOP("Emergency Stop"),
        RESUME_SHARING("Resume Sharing"),
        REVOKE_RELATIONSHIP("Revoke Relationship"),
        REVOKE_ALL_RELATIONSHIPS("Revoke All Relationships"),
        PURGE_CATEGORY("Purge Category"),
        PURGE_ALL_DATA("Purge All Data");

        private final String displayName;

        EmergencyActionType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Emergency action status enumeration.
     */
    public enum EmergencyActionStatus {
        PENDING("Pending"),
        IN_PROGRESS("In Progress"),
        COMPLETED("Completed"),
        PARTIAL("Partially Completed"),
        FAILED("Failed");

        private final String displayName;

        EmergencyActionStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
    }

    /**
     * Emergency action record.
     */
    public record EmergencyAction(
            String actionId,
            EmergencyActionType type,
            String reason,
            Instant timestamp,
            EmergencyActionStatus status,
            Map<String, String> details
    ) {
        public EmergencyAction {
            Objects.requireNonNull(actionId, "Action ID cannot be null");
            Objects.requireNonNull(type, "Type cannot be null");
            Objects.requireNonNull(timestamp, "Timestamp cannot be null");
            Objects.requireNonNull(status, "Status cannot be null");
            details = details != null ? Map.copyOf(details) : Map.of();
        }
    }

    /**
     * Emergency stop result.
     * Requirement 347.1: Stop all sharing instantly.
     */
    public record EmergencyStopResult(
            String actionId,
            boolean success,
            int transfersStopped,
            int contractsPaused,
            Instant timestamp,
            String message
    ) {}

    /**
     * Resume result.
     */
    public record ResumeResult(
            boolean success,
            String actionId,
            int contractsResumed,
            Instant timestamp,
            String error
    ) {
        public static ResumeResult success(String actionId, int contractsResumed, Instant timestamp) {
            return new ResumeResult(true, actionId, contractsResumed, timestamp, null);
        }

        public static ResumeResult failed(String error) {
            return new ResumeResult(false, null, 0, null, error);
        }
    }

    /**
     * Revocation result.
     * Requirement 347.2: Allow revoking all requester relationships.
     */
    public record RevocationResult(
            String actionId,
            boolean success,
            String requesterId,
            int contractsRevoked,
            int capsulesShredded,
            Instant timestamp,
            List<String> errors
    ) {
        public RevocationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }
    }

    /**
     * Bulk revocation result.
     */
    public record BulkRevocationResult(
            String actionId,
            boolean success,
            int requestersProcessed,
            int totalContractsRevoked,
            int totalCapsulesShredded,
            Instant timestamp,
            List<String> errors
    ) {
        public BulkRevocationResult {
            errors = errors != null ? List.copyOf(errors) : List.of();
        }

        public static BulkRevocationResult failed(String error) {
            return new BulkRevocationResult(null, false, 0, 0, 0, null, List.of(error));
        }
    }

    /**
     * Purge warning.
     * Requirement 347.3: Allow category purging with strong warnings.
     */
    public record PurgeWarning(
            String category,
            int itemCount,
            long totalBytes,
            int activeContractCount,
            List<String> warnings,
            String confirmationCode
    ) {
        public PurgeWarning {
            warnings = warnings != null ? List.copyOf(warnings) : List.of();
        }
    }

    /**
     * Purge result.
     */
    public record PurgeResult(
            String actionId,
            boolean success,
            String category,
            int itemsShredded,
            long bytesFreed,
            int contractsTerminated,
            int odxEntriesCleared,
            Instant timestamp,
            String error
    ) {
        public static PurgeResult failed(String category, String error) {
            return new PurgeResult(null, false, category, 0, 0, 0, 0, null, error);
        }
    }

    /**
     * Bulk purge result.
     */
    public record BulkPurgeResult(
            String actionId,
            boolean success,
            int categoriesPurged,
            int totalItemsShredded,
            long totalBytesFreed,
            int odxEntriesCleared,
            Instant timestamp,
            String error
    ) {
        public static BulkPurgeResult failed(String error) {
            return new BulkPurgeResult(null, false, 0, 0, 0, 0, null, error);
        }
    }

    /**
     * Vault category info.
     */
    public record VaultCategoryInfo(
            String category,
            int itemCount,
            long totalBytes,
            boolean hasActiveContracts,
            int activeContractCount,
            boolean hasPendingPayouts,
            String pendingPayoutAmount
    ) {}

    /**
     * Recovery options.
     * Requirement 347.5: Recovery options.
     */
    public record RecoveryOptions(
            boolean emergencyStopActive,
            List<RecoveryOption> options,
            List<EmergencyAction> recentActions
    ) {}

    /**
     * Recovery option.
     */
    public record RecoveryOption(
            String id,
            String displayName,
            String description,
            String confirmationCode
    ) {}

    // ==================== Interfaces ====================

    /**
     * Interface for sharing control.
     */
    public interface SharingController {
        int stopAllTransfers();
        int pauseAllContracts();
        int resumePausedContracts();
        void blockNewRequests(boolean block);
    }

    /**
     * Interface for relationship management.
     */
    public interface RelationshipManager {
        List<String> getContractIds(String requesterId);
        List<String> getAllRequesterIds();
        void revokeContract(String contractId, String reason);
        void blockRequester(String requesterId, String reason);
        int terminateContractsForCategory(String category, String reason);
    }

    /**
     * Interface for vault management.
     */
    public interface VaultManager {
        VaultCategoryInfo getCategoryInfo(String category);
        List<String> getAllCategories();
        List<String> getRecoverableCategories();
        int shredRequesterData(String requesterId);
        int purgeCategory(String category);
        int clearOdxForCategory(String category);
        int clearAllOdx();
    }

    /**
     * Interface for audit logging.
     */
    public interface AuditLogger {
        void logEmergencyAction(EmergencyAction action);
    }

    // ==================== Default Implementations ====================

    /**
     * In-memory sharing controller for testing.
     */
    public static class InMemorySharingController implements SharingController {
        private final AtomicBoolean blocked = new AtomicBoolean(false);
        private int activeTransfers = 0;
        private int activeContracts = 0;
        private int pausedContracts = 0;

        public void setActiveTransfers(int count) { this.activeTransfers = count; }
        public void setActiveContracts(int count) { this.activeContracts = count; }

        @Override
        public int stopAllTransfers() {
            int stopped = activeTransfers;
            activeTransfers = 0;
            return stopped;
        }

        @Override
        public int pauseAllContracts() {
            pausedContracts = activeContracts;
            activeContracts = 0;
            return pausedContracts;
        }

        @Override
        public int resumePausedContracts() {
            int resumed = pausedContracts;
            activeContracts = pausedContracts;
            pausedContracts = 0;
            return resumed;
        }

        @Override
        public void blockNewRequests(boolean block) {
            blocked.set(block);
        }

        public boolean isBlocked() { return blocked.get(); }
    }

    /**
     * In-memory relationship manager for testing.
     */
    public static class InMemoryRelationshipManager implements RelationshipManager {
        private final Map<String, List<String>> contractsByRequester = new HashMap<>();
        private final Set<String> blockedRequesters = new HashSet<>();
        private final Set<String> revokedContracts = new HashSet<>();

        public void addContract(String requesterId, String contractId) {
            contractsByRequester.computeIfAbsent(requesterId, k -> new ArrayList<>()).add(contractId);
        }

        @Override
        public List<String> getContractIds(String requesterId) {
            return contractsByRequester.getOrDefault(requesterId, List.of());
        }

        @Override
        public List<String> getAllRequesterIds() {
            return new ArrayList<>(contractsByRequester.keySet());
        }

        @Override
        public void revokeContract(String contractId, String reason) {
            revokedContracts.add(contractId);
        }

        @Override
        public void blockRequester(String requesterId, String reason) {
            blockedRequesters.add(requesterId);
        }

        @Override
        public int terminateContractsForCategory(String category, String reason) {
            return 0; // Simplified
        }

        public boolean isBlocked(String requesterId) { return blockedRequesters.contains(requesterId); }
        public boolean isRevoked(String contractId) { return revokedContracts.contains(contractId); }
    }

    /**
     * In-memory vault manager for testing.
     */
    public static class InMemoryVaultManager implements VaultManager {
        private final Map<String, VaultCategoryInfo> categories = new HashMap<>();
        private final Set<String> purgedCategories = new HashSet<>();
        private int odxEntries = 0;

        public void addCategory(String category, int itemCount, long totalBytes, int activeContracts) {
            categories.put(category, new VaultCategoryInfo(
                    category, itemCount, totalBytes, 
                    activeContracts > 0, activeContracts, 
                    false, "0.00"
            ));
        }

        public void setOdxEntries(int count) { this.odxEntries = count; }

        @Override
        public VaultCategoryInfo getCategoryInfo(String category) {
            return categories.getOrDefault(category, 
                    new VaultCategoryInfo(category, 0, 0, false, 0, false, "0.00"));
        }

        @Override
        public List<String> getAllCategories() {
            return new ArrayList<>(categories.keySet());
        }

        @Override
        public List<String> getRecoverableCategories() {
            return List.of(); // No recovery in test implementation
        }

        @Override
        public int shredRequesterData(String requesterId) {
            return 5; // Simulated
        }

        @Override
        public int purgeCategory(String category) {
            VaultCategoryInfo info = categories.remove(category);
            if (info != null) {
                purgedCategories.add(category);
                return info.itemCount();
            }
            return 0;
        }

        @Override
        public int clearOdxForCategory(String category) {
            return 10; // Simulated
        }

        @Override
        public int clearAllOdx() {
            int cleared = odxEntries;
            odxEntries = 0;
            return cleared;
        }

        public boolean isPurged(String category) { return purgedCategories.contains(category); }
    }

    /**
     * In-memory audit logger for testing.
     */
    public static class InMemoryAuditLogger implements AuditLogger {
        private final List<EmergencyAction> actions = new ArrayList<>();

        @Override
        public void logEmergencyAction(EmergencyAction action) {
            actions.add(action);
        }

        public List<EmergencyAction> getActions() { return List.copyOf(actions); }
    }
}
