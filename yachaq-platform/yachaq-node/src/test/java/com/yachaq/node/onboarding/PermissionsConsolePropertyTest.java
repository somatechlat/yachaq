package com.yachaq.node.onboarding;

import com.yachaq.node.onboarding.PermissionsConsole.*;
import com.yachaq.node.permission.PermissionService;
import com.yachaq.node.permission.PermissionService.PermissionPreset;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for PermissionsConsole.
 * 
 * Validates: Requirements 341.1, 341.2, 341.3, 341.4, 341.5
 */
class PermissionsConsolePropertyTest {

    private PermissionsConsole createConsole() {
        return new PermissionsConsole(new PermissionService());
    }

    // ==================== Task 83.1: Unified Permissions View Tests ====================

    @Test
    void unifiedView_containsOSPermissions() {
        // Requirement 341.1: Display OS permissions
        PermissionsConsole console = createConsole();
        UnifiedPermissionsView view = console.getUnifiedView();
        
        assertThat(view.osPermissions()).isNotEmpty();
        assertThat(view.osPermissions())
                .extracting(OSPermissionView::permission)
                .containsExactlyInAnyOrder(PermissionService.OSPermission.values());
    }

    @Test
    void unifiedView_containsYachaqScopes() {
        // Requirement 341.1: Display YACHAQ scopes
        PermissionsConsole console = createConsole();
        UnifiedPermissionsView view = console.getUnifiedView();
        
        assertThat(view.yachaqScopes()).isNotEmpty();
        assertThat(view.yachaqScopes())
                .extracting(YachaqScopeView::scopeKey)
                .contains("connector.*", "label.health.*", "label.location.*");
    }

    @Test
    void unifiedView_containsRequestExceptions() {
        // Requirement 341.1: Display per-request exceptions
        PermissionsConsole console = createConsole();
        
        // Add an exception
        RequestException exception = new RequestException(
                "Test Requester",
                ExceptionType.ALLOW,
                List.of("health.*"),
                Instant.now(),
                Instant.now().plusSeconds(3600)
        );
        console.addRequestException("req-123", exception);
        
        UnifiedPermissionsView view = console.getUnifiedView();
        
        assertThat(view.requestExceptions()).hasSize(1);
        assertThat(view.requestExceptions().get(0).requestId()).isEqualTo("req-123");
    }

    @Test
    void unifiedView_osPermissionsHaveExplanations() {
        PermissionsConsole console = createConsole();
        UnifiedPermissionsView view = console.getUnifiedView();
        
        assertThat(view.osPermissions()).allSatisfy(perm -> {
            assertThat(perm.explanation()).isNotBlank();
            assertThat(perm.impact()).isNotBlank();
        });
    }

    // ==================== Task 83.2: Permission Presets Tests ====================

    @Property
    void applyPreset_updatesActivePreset(@ForAll("presets") PermissionPreset preset) {
        // Requirement 341.2: Offer Minimal/Standard/Full presets
        PermissionsConsole console = createConsole();
        
        PermissionPresetConfig config = console.applyPreset(preset);
        
        assertThat(config.preset()).isEqualTo(preset);
        assertThat(console.getActivePresetConfig().preset()).isEqualTo(preset);
    }

    @Test
    void applyPreset_minimalHasRestrictiveToggles() {
        // Requirement 341.2: Minimal preset is most restrictive
        PermissionsConsole console = createConsole();
        
        PermissionPresetConfig config = console.applyPreset(PermissionPreset.MINIMAL);
        
        assertThat(config.advancedToggles().get("auto_approve_trusted")).isFalse();
        assertThat(config.advancedToggles().get("allow_fine_resolution")).isFalse();
        assertThat(config.advancedToggles().get("allow_identity_reveal")).isFalse();
        assertThat(config.advancedToggles().get("allow_raw_output")).isFalse();
    }

    @Test
    void applyPreset_fullHasPermissiveToggles() {
        // Requirement 341.2: Full preset is most permissive
        PermissionsConsole console = createConsole();
        
        PermissionPresetConfig config = console.applyPreset(PermissionPreset.FULL);
        
        assertThat(config.advancedToggles().get("auto_approve_trusted")).isTrue();
        assertThat(config.advancedToggles().get("allow_fine_resolution")).isTrue();
        assertThat(config.advancedToggles().get("allow_identity_reveal")).isTrue();
        assertThat(config.advancedToggles().get("allow_raw_output")).isTrue();
    }

    @Test
    void applyPreset_standardIsBalanced() {
        // Requirement 341.2: Standard preset is balanced
        PermissionsConsole console = createConsole();
        
        PermissionPresetConfig config = console.applyPreset(PermissionPreset.STANDARD);
        
        // Standard allows fine resolution but not identity reveal
        assertThat(config.advancedToggles().get("allow_fine_resolution")).isTrue();
        assertThat(config.advancedToggles().get("allow_identity_reveal")).isFalse();
    }

    @Test
    void applyPreset_rejectsNull() {
        PermissionsConsole console = createConsole();
        
        assertThatThrownBy(() -> console.applyPreset(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    // ==================== Advanced Toggles Tests ====================

    @Test
    void updateAdvancedToggle_modifiesToggle() {
        // Requirement 341.2: Advanced toggles within presets
        PermissionsConsole console = createConsole();
        console.applyPreset(PermissionPreset.STANDARD);
        
        // Standard has allow_identity_reveal = false, let's enable it
        PermissionPresetConfig updated = console.updateAdvancedToggle("allow_identity_reveal", true);
        
        assertThat(updated.advancedToggles().get("allow_identity_reveal")).isTrue();
    }

    @Test
    void updateAdvancedToggle_rejectsUnknownToggle() {
        PermissionsConsole console = createConsole();
        
        assertThatThrownBy(() -> console.updateAdvancedToggle("unknown_toggle", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown toggle");
    }

    @Test
    void updateAdvancedToggle_rejectsNullId() {
        PermissionsConsole console = createConsole();
        
        assertThatThrownBy(() -> console.updateAdvancedToggle(null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    // ==================== Task 83.3: Permission Impact Tests ====================

    @Test
    void calculateImpact_showsAffectedRequests() {
        // Requirement 341.5: Show impact on active requests
        PermissionsConsole console = createConsole();
        
        PermissionChange change = new PermissionChange("health.*", ChangeType.REVOKE, "Privacy concern");
        PermissionImpact impact = console.calculateImpact(change);
        
        assertThat(impact.affectedRequests()).isNotEmpty();
        assertThat(impact.change()).isEqualTo(change);
    }

    @Test
    void calculateImpact_showsEarningsImpact() {
        // Requirement 341.5: Show impact on earnings
        PermissionsConsole console = createConsole();
        
        PermissionChange change = new PermissionChange("location.*", ChangeType.REVOKE, "Privacy concern");
        PermissionImpact impact = console.calculateImpact(change);
        
        assertThat(impact.earningsImpact()).isNotBlank();
    }

    @Test
    void calculateImpact_revokeHasHighSeverity() {
        PermissionsConsole console = createConsole();
        
        PermissionChange revoke = new PermissionChange("health.*", ChangeType.REVOKE, "Test");
        PermissionImpact impact = console.calculateImpact(revoke);
        
        assertThat(impact.severity()).isEqualTo(ImpactSeverity.HIGH);
    }

    @Test
    void calculateImpact_grantHasLowSeverity() {
        PermissionsConsole console = createConsole();
        
        PermissionChange grant = new PermissionChange("media.*", ChangeType.GRANT, "Test");
        PermissionImpact impact = console.calculateImpact(grant);
        
        assertThat(impact.severity()).isEqualTo(ImpactSeverity.LOW);
    }

    @Test
    void calculateImpact_rejectsNull() {
        PermissionsConsole console = createConsole();
        
        assertThatThrownBy(() -> console.calculateImpact(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    // ==================== Request Exceptions Tests ====================

    @Test
    void addRequestException_storesException() {
        PermissionsConsole console = createConsole();
        
        RequestException exception = new RequestException(
                "Acme Research",
                ExceptionType.ALLOW,
                List.of("health.steps", "health.heart_rate"),
                Instant.now(),
                Instant.now().plusSeconds(86400)
        );
        
        console.addRequestException("req-456", exception);
        
        assertThat(console.getRequestExceptionCount()).isEqualTo(1);
    }

    @Test
    void removeRequestException_removesException() {
        PermissionsConsole console = createConsole();
        
        RequestException exception = new RequestException(
                "Test",
                ExceptionType.DENY,
                List.of("location.*"),
                Instant.now(),
                Instant.now().plusSeconds(3600)
        );
        
        console.addRequestException("req-789", exception);
        assertThat(console.getRequestExceptionCount()).isEqualTo(1);
        
        console.removeRequestException("req-789");
        assertThat(console.getRequestExceptionCount()).isEqualTo(0);
    }

    @Test
    void addRequestException_rejectsNullId() {
        PermissionsConsole console = createConsole();
        RequestException exception = new RequestException("Test", ExceptionType.ALLOW, List.of(), Instant.now(), null);
        
        assertThatThrownBy(() -> console.addRequestException(null, exception))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void addRequestException_rejectsNullException() {
        PermissionsConsole console = createConsole();
        
        assertThatThrownBy(() -> console.addRequestException("req-123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    // ==================== Constructor Tests ====================

    @Test
    void constructor_rejectsNullPermissionService() {
        assertThatThrownBy(() -> new PermissionsConsole(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_defaultsToStandardPreset() {
        PermissionsConsole console = createConsole();
        
        assertThat(console.getActivePresetConfig().preset()).isEqualTo(PermissionPreset.STANDARD);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<PermissionPreset> presets() {
        return Arbitraries.of(PermissionPreset.values());
    }
}
