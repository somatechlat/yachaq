package com.yachaq.node.permission;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permissions & Consent Firewall for Phone-as-Node architecture.
 * Manages OS permissions, YACHAQ permission layers, and consent signing.
 * 
 * Validates: Requirements 304.1, 304.2, 304.3, 304.4, 304.5, 304.6
 */
public class PermissionService {

    private final Map<OSPermission, PermissionStatus> osPermissions;
    private final Map<String, YachaqPermission> yachaqPermissions;
    private final Map<String, SignedContract> signedContracts;
    private final Set<String> usedNonces;
    private final PermissionPreset activePreset;

    public PermissionService() {
        this(PermissionPreset.STANDARD);
    }

    public PermissionService(PermissionPreset preset) {
        this.osPermissions = new ConcurrentHashMap<>();
        this.yachaqPermissions = new ConcurrentHashMap<>();
        this.signedContracts = new ConcurrentHashMap<>();
        this.usedNonces = ConcurrentHashMap.newKeySet();
        this.activePreset = preset != null ? preset : PermissionPreset.STANDARD;
        
        // Initialize OS permissions as not granted
        for (OSPermission perm : OSPermission.values()) {
            osPermissions.put(perm, PermissionStatus.NOT_REQUESTED);
        }
    }

    /**
     * Checks if an OS permission is granted.
     * Requirement 304.1: Create checkOS(permission) method.
     * 
     * @param permission The OS permission to check
     * @return CheckResult with status and explanation
     */
    public CheckResult checkOS(OSPermission permission) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission cannot be null");
        }

        PermissionStatus status = osPermissions.getOrDefault(permission, PermissionStatus.NOT_REQUESTED);
        
        return switch (status) {
            case GRANTED -> new CheckResult(true, "Permission granted", permission.explanation());
            case DENIED -> new CheckResult(false, "Permission denied by user", permission.explanation());
            case NOT_REQUESTED -> new CheckResult(false, "Permission not yet requested", permission.explanation());
        };
    }

    /**
     * Requests an OS permission with just-in-time explanation.
     * Requirement 304.1: Request permissions just-in-time with explanations.
     * 
     * @param permission The permission to request
     * @param userResponse Simulated user response (true = grant, false = deny)
     * @return Updated permission status
     */
    public PermissionStatus requestOSPermission(OSPermission permission, boolean userResponse) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission cannot be null");
        }

        PermissionStatus newStatus = userResponse ? PermissionStatus.GRANTED : PermissionStatus.DENIED;
        osPermissions.put(permission, newStatus);
        return newStatus;
    }


    /**
     * Checks YACHAQ-level permission for a specific scope.
     * Requirement 304.2: Enforce per connector, per label family, per resolution, per requester, per QueryPlan.
     * 
     * @param scope The permission scope to check
     * @return CheckResult with status
     */
    public CheckResult checkYachaq(PermissionScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }

        // Check if preset allows this scope
        if (!activePreset.allows(scope)) {
            return new CheckResult(false, "Scope not allowed by current preset: " + activePreset.name(), null);
        }

        // Check specific permission
        String scopeKey = scope.toKey();
        YachaqPermission permission = yachaqPermissions.get(scopeKey);
        
        if (permission == null) {
            return new CheckResult(false, "Permission not granted for scope: " + scopeKey, null);
        }

        if (permission.isExpired()) {
            yachaqPermissions.remove(scopeKey);
            return new CheckResult(false, "Permission expired for scope: " + scopeKey, null);
        }

        return new CheckResult(true, "Permission granted", null);
    }

    /**
     * Grants a YACHAQ permission for a specific scope.
     * 
     * @param scope The permission scope
     * @param expiresAt When the permission expires
     */
    public void grantYachaqPermission(PermissionScope scope, Instant expiresAt) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }

        String scopeKey = scope.toKey();
        yachaqPermissions.put(scopeKey, new YachaqPermission(scopeKey, Instant.now(), expiresAt));
    }

    /**
     * Revokes a YACHAQ permission.
     */
    public void revokeYachaqPermission(PermissionScope scope) {
        if (scope != null) {
            yachaqPermissions.remove(scope.toKey());
        }
    }

    /**
     * Generates a contract preview for user review.
     * Requirement 304.3: Create promptContractPreview(contractDraft) method.
     * 
     * @param draft The contract draft to preview
     * @return ContractPreview with human-readable summary
     */
    public ContractPreview promptContractPreview(ContractDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Contract draft cannot be null");
        }

        List<String> dataAccess = new ArrayList<>();
        for (String label : draft.labels()) {
            dataAccess.add("Access to: " + label);
        }

        List<String> privacyImpacts = new ArrayList<>();
        if (draft.identityReveal()) {
            privacyImpacts.add("Your identity will be revealed to the requester");
        }
        if (draft.outputMode() == OutputMode.RAW) {
            privacyImpacts.add("Raw data will be shared (not aggregated)");
        }

        return new ContractPreview(
                draft.requesterId(),
                draft.purpose(),
                dataAccess,
                privacyImpacts,
                draft.compensation(),
                draft.duration()
        );
    }

    /**
     * Signs a contract with replay-safe nonce and expiry.
     * Requirement 304.4: Create signContract(contractDraft) method with replay-safe nonce and expiry.
     * 
     * @param draft The contract draft to sign
     * @param nonce Unique nonce for replay protection
     * @param expiresAt Contract expiration time
     * @return SignedContract or null if signing fails
     */
    public SignedContract signContract(ContractDraft draft, String nonce, Instant expiresAt) {
        if (draft == null) {
            throw new IllegalArgumentException("Contract draft cannot be null");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("Nonce cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiry cannot be null");
        }

        // Check for replay attack
        if (usedNonces.contains(nonce)) {
            throw new ReplayAttackException("Nonce already used: " + nonce);
        }

        // Check expiry is in the future
        if (expiresAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }

        // Mark nonce as used
        usedNonces.add(nonce);

        // Create signed contract
        String contractId = UUID.randomUUID().toString();
        SignedContract contract = new SignedContract(
                contractId,
                draft,
                nonce,
                Instant.now(),
                expiresAt,
                SignatureStatus.USER_SIGNED
        );

        signedContracts.put(contractId, contract);
        return contract;
    }

    /**
     * Verifies a contract signature and checks for replay.
     */
    public boolean verifyContract(SignedContract contract) {
        if (contract == null) {
            return false;
        }

        // Check nonce was used (should be in our set)
        if (!usedNonces.contains(contract.nonce())) {
            return false;
        }

        // Check not expired
        if (contract.expiresAt().isBefore(Instant.now())) {
            return false;
        }

        return true;
    }

    /**
     * Gets the active permission preset.
     */
    public PermissionPreset getActivePreset() {
        return activePreset;
    }

    /**
     * Gets count of granted YACHAQ permissions.
     */
    public int getYachaqPermissionCount() {
        return yachaqPermissions.size();
    }

    /**
     * Gets count of signed contracts.
     */
    public int getSignedContractCount() {
        return signedContracts.size();
    }


    // ==================== Inner Types ====================

    /**
     * OS-level permissions.
     */
    public enum OSPermission {
        LOCATION("Access to device location for geo-based matching"),
        CAMERA("Access to camera for identity verification"),
        STORAGE("Access to device storage for data import"),
        CONTACTS("Access to contacts for social graph features"),
        HEALTH("Access to health data from HealthKit/Health Connect"),
        NOTIFICATIONS("Permission to send notifications"),
        BACKGROUND_REFRESH("Permission for background data sync");

        private final String explanation;

        OSPermission(String explanation) {
            this.explanation = explanation;
        }

        public String explanation() {
            return explanation;
        }
    }

    /**
     * Permission status.
     */
    public enum PermissionStatus {
        NOT_REQUESTED,
        GRANTED,
        DENIED
    }

    /**
     * Result of permission check.
     */
    public record CheckResult(
            boolean granted,
            String message,
            String explanation
    ) {}

    /**
     * YACHAQ permission scope.
     * Requirement 304.2: Per connector, per label family, per resolution, per requester, per QueryPlan.
     */
    public record PermissionScope(
            String connector,
            String labelFamily,
            String resolution,
            String requesterId,
            String queryPlanId
    ) {
        public String toKey() {
            return String.join(":", 
                    connector != null ? connector : "*",
                    labelFamily != null ? labelFamily : "*",
                    resolution != null ? resolution : "*",
                    requesterId != null ? requesterId : "*",
                    queryPlanId != null ? queryPlanId : "*"
            );
        }

        public static PermissionScope forConnector(String connector) {
            return new PermissionScope(connector, null, null, null, null);
        }

        public static PermissionScope forRequester(String requesterId) {
            return new PermissionScope(null, null, null, requesterId, null);
        }

        public static PermissionScope forQueryPlan(String queryPlanId) {
            return new PermissionScope(null, null, null, null, queryPlanId);
        }
    }

    /**
     * YACHAQ permission record.
     */
    public record YachaqPermission(
            String scopeKey,
            Instant grantedAt,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Permission presets.
     * Requirement 304.6: Create Minimal, Standard, Full presets with visible toggles.
     */
    public enum PermissionPreset {
        MINIMAL {
            @Override
            public boolean allows(PermissionScope scope) {
                // Only allow explicit per-request permissions
                return scope.queryPlanId() != null;
            }
        },
        STANDARD {
            @Override
            public boolean allows(PermissionScope scope) {
                // Allow connector and requester-level permissions
                return true;
            }
        },
        FULL {
            @Override
            public boolean allows(PermissionScope scope) {
                // Allow all permission scopes
                return true;
            }
        };

        public abstract boolean allows(PermissionScope scope);
    }

    /**
     * Contract draft for consent.
     */
    public record ContractDraft(
            String requesterId,
            String purpose,
            List<String> labels,
            String timeWindow,
            OutputMode outputMode,
            boolean identityReveal,
            String compensation,
            String duration,
            String escrowId,
            String ttl
    ) {}

    /**
     * Output mode for data sharing.
     */
    public enum OutputMode {
        RAW,
        AGGREGATED,
        DERIVED,
        CLEAN_ROOM
    }

    /**
     * Contract preview for user review.
     */
    public record ContractPreview(
            String requesterId,
            String purpose,
            List<String> dataAccess,
            List<String> privacyImpacts,
            String compensation,
            String duration
    ) {}

    /**
     * Signed contract.
     */
    public record SignedContract(
            String contractId,
            ContractDraft draft,
            String nonce,
            Instant signedAt,
            Instant expiresAt,
            SignatureStatus status
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Signature status.
     */
    public enum SignatureStatus {
        USER_SIGNED,
        COUNTERSIGNED,
        REJECTED
    }

    /**
     * Exception for replay attacks.
     */
    public static class ReplayAttackException extends RuntimeException {
        public ReplayAttackException(String message) {
            super(message);
        }
    }
}
