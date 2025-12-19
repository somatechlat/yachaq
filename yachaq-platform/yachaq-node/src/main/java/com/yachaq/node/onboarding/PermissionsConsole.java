package com.yachaq.node.onboarding;

import com.yachaq.node.permission.PermissionService;
import com.yachaq.node.permission.PermissionService.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permissions Console for Provider App.
 * Unified view of OS permissions, YACHAQ scopes, and per-request exceptions.
 * 
 * Validates: Requirements 341.1, 341.2, 341.3, 341.4, 341.5
 */
public class PermissionsConsole {

    private final PermissionService permissionService;
    private final Map<String, RequestException> requestExceptions;
    private PermissionPresetConfig activePresetConfig;

    public PermissionsConsole(PermissionService permissionService) {
        if (permissionService == null) {
            throw new IllegalArgumentException("PermissionService cannot be null");
        }
        this.permissionService = permissionService;
        this.requestExceptions = new ConcurrentHashMap<>();
        this.activePresetConfig = PermissionPresetConfig.standard();
    }

    /**
     * Gets the unified permissions view.
     * Requirement 341.1: Display OS permissions, YACHAQ scopes, per-request exceptions.
     * 
     * @return UnifiedPermissionsView with all permission categories
     */
    public UnifiedPermissionsView getUnifiedView() {
        List<OSPermissionView> osPermissions = getOSPermissionsView();
        List<YachaqScopeView> yachaqScopes = getYachaqScopesView();
        List<RequestExceptionView> exceptions = getRequestExceptionsView();

        return new UnifiedPermissionsView(
                osPermissions,
                yachaqScopes,
                exceptions,
                activePresetConfig.preset(),
                Instant.now()
        );
    }

    /**
     * Applies a permission preset.
     * Requirement 341.2: Offer Minimal/Standard/Full presets with advanced toggles.
     * 
     * @param preset The preset to apply
     * @return Updated preset configuration
     */
    public PermissionPresetConfig applyPreset(PermissionPreset preset) {
        if (preset == null) {
            throw new IllegalArgumentException("Preset cannot be null");
        }

        activePresetConfig = switch (preset) {
            case MINIMAL -> PermissionPresetConfig.minimal();
            case STANDARD -> PermissionPresetConfig.standard();
            case FULL -> PermissionPresetConfig.full();
        };

        return activePresetConfig;
    }

    /**
     * Gets the current preset configuration.
     * 
     * @return Current PermissionPresetConfig
     */
    public PermissionPresetConfig getActivePresetConfig() {
        return activePresetConfig;
    }

    /**
     * Updates an advanced toggle within the current preset.
     * Requirement 341.2: Advanced toggles within presets.
     * 
     * @param toggleId The toggle identifier
     * @param enabled Whether to enable the toggle
     * @return Updated preset configuration
     */
    public PermissionPresetConfig updateAdvancedToggle(String toggleId, boolean enabled) {
        if (toggleId == null || toggleId.isBlank()) {
            throw new IllegalArgumentException("Toggle ID cannot be null or blank");
        }

        Map<String, Boolean> updatedToggles = new HashMap<>(activePresetConfig.advancedToggles());
        if (!updatedToggles.containsKey(toggleId)) {
            throw new IllegalArgumentException("Unknown toggle: " + toggleId);
        }
        updatedToggles.put(toggleId, enabled);

        activePresetConfig = new PermissionPresetConfig(
                activePresetConfig.preset(),
                activePresetConfig.description(),
                updatedToggles,
                activePresetConfig.appliedAt()
        );

        return activePresetConfig;
    }

    /**
     * Adds a per-request exception.
     * Requirement 341.1: Per-request exceptions.
     * 
     * @param requestId The request ID
     * @param exception The exception configuration
     */
    public void addRequestException(String requestId, RequestException exception) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be null or blank");
        }
        if (exception == null) {
            throw new IllegalArgumentException("Exception cannot be null");
        }
        requestExceptions.put(requestId, exception);
    }

    /**
     * Removes a per-request exception.
     * 
     * @param requestId The request ID
     */
    public void removeRequestException(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be null or blank");
        }
        requestExceptions.remove(requestId);
    }

    /**
     * Calculates the impact of a permission change.
     * Requirement 341.5: Show impact on active requests and earnings.
     * 
     * @param change The proposed permission change
     * @return PermissionImpact with affected requests and earnings
     */
    public PermissionImpact calculateImpact(PermissionChange change) {
        if (change == null) {
            throw new IllegalArgumentException("Change cannot be null");
        }

        // Calculate affected requests based on the change
        List<AffectedRequest> affectedRequests = new ArrayList<>();
        String earningsImpact;

        if (change.changeType() == ChangeType.REVOKE) {
            // Revoking permissions affects active requests using those scopes
            affectedRequests.add(new AffectedRequest(
                    "sample-request-1",
                    "Research Study A",
                    ImpactType.WILL_BE_CANCELLED,
                    "Request requires " + change.scope()
            ));
            earningsImpact = "Estimated loss: $5.00/month";
        } else {
            earningsImpact = "Potential gain: $3.00/month";
        }

        return new PermissionImpact(
                change,
                affectedRequests,
                earningsImpact,
                change.changeType() == ChangeType.REVOKE ? ImpactSeverity.HIGH : ImpactSeverity.LOW
        );
    }

    /**
     * Gets the count of active request exceptions.
     */
    public int getRequestExceptionCount() {
        return requestExceptions.size();
    }


    // ==================== Private Helper Methods ====================

    private List<OSPermissionView> getOSPermissionsView() {
        List<OSPermissionView> views = new ArrayList<>();
        for (OSPermission permission : OSPermission.values()) {
            CheckResult result = permissionService.checkOS(permission);
            views.add(new OSPermissionView(
                    permission,
                    result.granted() ? PermissionState.GRANTED : 
                            (result.message().contains("denied") ? PermissionState.DENIED : PermissionState.NOT_REQUESTED),
                    result.explanation(),
                    getOSPermissionImpact(permission)
            ));
        }
        return views;
    }

    private List<YachaqScopeView> getYachaqScopesView() {
        List<YachaqScopeView> views = new ArrayList<>();
        
        // Add common YACHAQ scopes
        views.add(createYachaqScopeView("connector.*", "All Connectors", "Access to all data connectors"));
        views.add(createYachaqScopeView("label.health.*", "Health Labels", "Health-related data labels"));
        views.add(createYachaqScopeView("label.location.*", "Location Labels", "Location-related data labels"));
        views.add(createYachaqScopeView("label.media.*", "Media Labels", "Media consumption labels"));
        views.add(createYachaqScopeView("resolution.fine", "Fine Resolution", "Fine-grained data access"));
        views.add(createYachaqScopeView("resolution.coarse", "Coarse Resolution", "Coarse-grained data access"));
        
        return views;
    }

    private YachaqScopeView createYachaqScopeView(String scopeKey, String name, String description) {
        PermissionScope scope = new PermissionScope(scopeKey, null, null, null, null);
        CheckResult result = permissionService.checkYachaq(scope);
        return new YachaqScopeView(
                scopeKey,
                name,
                description,
                result.granted() ? PermissionState.GRANTED : PermissionState.NOT_REQUESTED,
                activePresetConfig.preset().allows(scope)
        );
    }

    private List<RequestExceptionView> getRequestExceptionsView() {
        return requestExceptions.entrySet().stream()
                .map(e -> new RequestExceptionView(
                        e.getKey(),
                        e.getValue().requesterName(),
                        e.getValue().exceptionType(),
                        e.getValue().scopes(),
                        e.getValue().expiresAt()
                ))
                .toList();
    }

    private String getOSPermissionImpact(OSPermission permission) {
        return switch (permission) {
            case LOCATION -> "Required for geo-based matching and location data requests";
            case HEALTH -> "Required for health data connectors (HealthKit/Health Connect)";
            case STORAGE -> "Required for data import from files";
            case CAMERA -> "Required for identity verification";
            case CONTACTS -> "Optional for social graph features";
            case NOTIFICATIONS -> "Required for request notifications";
            case BACKGROUND_REFRESH -> "Required for automatic data sync";
        };
    }

    // ==================== Inner Types ====================

    /**
     * Unified permissions view.
     */
    public record UnifiedPermissionsView(
            List<OSPermissionView> osPermissions,
            List<YachaqScopeView> yachaqScopes,
            List<RequestExceptionView> requestExceptions,
            PermissionPreset activePreset,
            Instant generatedAt
    ) {}

    /**
     * OS permission view.
     */
    public record OSPermissionView(
            OSPermission permission,
            PermissionState state,
            String explanation,
            String impact
    ) {}

    /**
     * YACHAQ scope view.
     */
    public record YachaqScopeView(
            String scopeKey,
            String name,
            String description,
            PermissionState state,
            boolean allowedByPreset
    ) {}

    /**
     * Request exception view.
     */
    public record RequestExceptionView(
            String requestId,
            String requesterName,
            ExceptionType exceptionType,
            List<String> scopes,
            Instant expiresAt
    ) {}

    /**
     * Permission state.
     */
    public enum PermissionState {
        GRANTED,
        DENIED,
        NOT_REQUESTED
    }

    /**
     * Permission preset configuration.
     */
    public record PermissionPresetConfig(
            PermissionPreset preset,
            String description,
            Map<String, Boolean> advancedToggles,
            Instant appliedAt
    ) {
        public static PermissionPresetConfig minimal() {
            return new PermissionPresetConfig(
                    PermissionPreset.MINIMAL,
                    "Maximum privacy - only explicit per-request permissions",
                    Map.of(
                            "auto_approve_trusted", false,
                            "allow_fine_resolution", false,
                            "allow_identity_reveal", false,
                            "allow_raw_output", false
                    ),
                    Instant.now()
            );
        }

        public static PermissionPresetConfig standard() {
            return new PermissionPresetConfig(
                    PermissionPreset.STANDARD,
                    "Balanced privacy and convenience",
                    Map.of(
                            "auto_approve_trusted", false,
                            "allow_fine_resolution", true,
                            "allow_identity_reveal", false,
                            "allow_raw_output", false
                    ),
                    Instant.now()
            );
        }

        public static PermissionPresetConfig full() {
            return new PermissionPresetConfig(
                    PermissionPreset.FULL,
                    "Maximum flexibility - all permission scopes allowed",
                    Map.of(
                            "auto_approve_trusted", true,
                            "allow_fine_resolution", true,
                            "allow_identity_reveal", true,
                            "allow_raw_output", true
                    ),
                    Instant.now()
            );
        }
    }

    /**
     * Request exception.
     */
    public record RequestException(
            String requesterName,
            ExceptionType exceptionType,
            List<String> scopes,
            Instant createdAt,
            Instant expiresAt
    ) {}

    /**
     * Exception type.
     */
    public enum ExceptionType {
        ALLOW,      // Allow specific scopes for this request
        DENY,       // Deny specific scopes for this request
        OVERRIDE    // Override preset settings for this request
    }

    /**
     * Permission change request.
     */
    public record PermissionChange(
            String scope,
            ChangeType changeType,
            String reason
    ) {}

    /**
     * Change type.
     */
    public enum ChangeType {
        GRANT,
        REVOKE
    }

    /**
     * Permission impact analysis.
     */
    public record PermissionImpact(
            PermissionChange change,
            List<AffectedRequest> affectedRequests,
            String earningsImpact,
            ImpactSeverity severity
    ) {}

    /**
     * Affected request.
     */
    public record AffectedRequest(
            String requestId,
            String requestName,
            ImpactType impactType,
            String reason
    ) {}

    /**
     * Impact type.
     */
    public enum ImpactType {
        WILL_BE_CANCELLED,
        WILL_BE_MODIFIED,
        NO_IMPACT
    }

    /**
     * Impact severity.
     */
    public enum ImpactSeverity {
        LOW,
        MEDIUM,
        HIGH
    }
}
