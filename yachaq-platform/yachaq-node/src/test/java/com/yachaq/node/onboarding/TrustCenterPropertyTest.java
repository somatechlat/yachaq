package com.yachaq.node.onboarding;

import com.yachaq.node.key.KeyManagementService;
import com.yachaq.node.onboarding.TrustCenter.*;
import com.yachaq.node.permission.PermissionService;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for TrustCenter onboarding.
 * 
 * Validates: Requirements 339.1, 339.2, 339.3, 339.4, 339.5
 */
class TrustCenterPropertyTest {

    private TrustCenter createTrustCenter() {
        KeyManagementService keyManagementService = new KeyManagementService();
        PermissionService permissionService = new PermissionService();
        return new TrustCenter(keyManagementService, permissionService);
    }

    // ==================== Task 81.1: Trust Center Screen Tests ====================

    @Test
    void invariants_containsDataLocalityGuarantee() {
        // Requirement 339.1: Display invariants: data stays on phone
        TrustCenter trustCenter = createTrustCenter();
        List<TrustInvariant> invariants = trustCenter.getInvariants();
        
        assertThat(invariants)
                .extracting(TrustInvariant::id)
                .contains("DATA_LOCALITY");
        
        TrustInvariant dataLocality = invariants.stream()
                .filter(i -> i.id().equals("DATA_LOCALITY"))
                .findFirst()
                .orElseThrow();
        
        assertThat(dataLocality.title()).containsIgnoringCase("data");
        assertThat(dataLocality.title()).containsIgnoringCase("phone");
        assertThat(dataLocality.trustLevel()).isEqualTo(TrustLevel.CRYPTOGRAPHIC);
    }

    @Test
    void invariants_containsODXOnlyDiscovery() {
        // Requirement 339.1: Display invariants: ODX-only discovery
        TrustCenter trustCenter = createTrustCenter();
        List<TrustInvariant> invariants = trustCenter.getInvariants();
        
        assertThat(invariants)
                .extracting(TrustInvariant::id)
                .contains("ODX_ONLY_DISCOVERY");
        
        TrustInvariant odxDiscovery = invariants.stream()
                .filter(i -> i.id().equals("ODX_ONLY_DISCOVERY"))
                .findFirst()
                .orElseThrow();
        
        assertThat(odxDiscovery.description()).containsIgnoringCase("ODX");
        assertThat(odxDiscovery.trustLevel()).isEqualTo(TrustLevel.CRYPTOGRAPHIC);
    }

    @Test
    void invariants_containsP2PFulfillment() {
        // Requirement 339.1: Display invariants: P2P fulfillment
        TrustCenter trustCenter = createTrustCenter();
        List<TrustInvariant> invariants = trustCenter.getInvariants();
        
        assertThat(invariants)
                .extracting(TrustInvariant::id)
                .contains("P2P_FULFILLMENT");
        
        TrustInvariant p2p = invariants.stream()
                .filter(i -> i.id().equals("P2P_FULFILLMENT"))
                .findFirst()
                .orElseThrow();
        
        assertThat(p2p.description()).containsIgnoringCase("P2P");
        assertThat(p2p.trustLevel()).isEqualTo(TrustLevel.CRYPTOGRAPHIC);
    }

    @Test
    void invariants_allHaveEnforcement() {
        // All invariants must explain how they are enforced
        TrustCenter trustCenter = createTrustCenter();
        List<TrustInvariant> invariants = trustCenter.getInvariants();
        
        assertThat(invariants).allSatisfy(invariant -> {
            assertThat(invariant.enforcement()).isNotBlank();
            assertThat(invariant.trustLevel()).isNotNull();
        });
    }

    // ==================== Task 81.2: Proof Dashboard Tests ====================

    @Test
    void proofDashboard_showsCapabilities() {
        // Requirement 339.2: Demonstrate what app can do
        TrustCenter trustCenter = createTrustCenter();
        ProofDashboard dashboard = trustCenter.getProofDashboard();
        
        assertThat(dashboard.getCapabilities()).isNotEmpty();
        assertThat(dashboard.getCapabilities())
                .extracting(Capability::id)
                .contains("STORE_DATA", "MATCH_REQUESTS", "CONSENT_CONTROL", "EARN_COMPENSATION");
    }

    @Test
    void proofDashboard_showsRestrictions() {
        // Requirement 339.2: Demonstrate what app cannot do
        TrustCenter trustCenter = createTrustCenter();
        ProofDashboard dashboard = trustCenter.getProofDashboard();
        
        assertThat(dashboard.getRestrictions()).isNotEmpty();
        assertThat(dashboard.getRestrictions())
                .extracting(Restriction::id)
                .contains("NO_RAW_UPLOAD", "NO_TRACKING", "NO_SILENT_ACCESS");
    }

    @Test
    void proofDashboard_restrictionsHaveEnforcement() {
        // All restrictions must explain how they are enforced
        TrustCenter trustCenter = createTrustCenter();
        ProofDashboard dashboard = trustCenter.getProofDashboard();
        
        assertThat(dashboard.getRestrictions()).allSatisfy(restriction -> {
            assertThat(restriction.enforcement()).isNotBlank();
        });
    }


    // ==================== Task 81.3: Identity Setup Tests ====================

    @Property
    void identitySetup_generatesNodeDID(@ForAll("backupPolicies") BackupPolicy policy) {
        // Requirement 339.3: Generate node identity with backup policy selection
        TrustCenter trustCenter = createTrustCenter();
        IdentitySetupResult result = trustCenter.setupIdentity(policy);
        
        assertThat(result.success()).isTrue();
        assertThat(result.nodeDID()).isNotBlank();
        assertThat(result.nodeDID()).startsWith("did:yachaq:node:");
        assertThat(result.backupPolicy()).isEqualTo(policy);
    }

    @Property
    void identitySetup_appliesBackupPolicy(@ForAll("backupPolicies") BackupPolicy policy) {
        // Requirement 339.3: Backup policy selection
        TrustCenter trustCenter = createTrustCenter();
        IdentitySetupResult result = trustCenter.setupIdentity(policy);
        
        assertThat(result.backupResult()).isNotNull();
        
        if (policy == BackupPolicy.NONE) {
            assertThat(result.backupResult().enabled()).isFalse();
        } else {
            assertThat(result.backupResult().enabled()).isTrue();
            assertThat(result.backupResult().location()).isNotNull();
        }
    }

    @Test
    void identitySetup_updatesStatus() {
        // Identity status should update through the flow
        TrustCenter trustCenter = createTrustCenter();
        assertThat(trustCenter.getIdentityStatus()).isEqualTo(IdentitySetupStatus.NOT_STARTED);
        
        trustCenter.setupIdentity(BackupPolicy.CLOUD_ENCRYPTED);
        
        assertThat(trustCenter.getIdentityStatus()).isEqualTo(IdentitySetupStatus.COMPLETED);
    }

    @Test
    void identitySetup_rejectsNullPolicy() {
        TrustCenter trustCenter = createTrustCenter();
        assertThatThrownBy(() -> trustCenter.setupIdentity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Backup policy cannot be null");
    }

    // ==================== Task 81.4: Consent Defaults Tests ====================

    @Property
    void consentDefaults_configurable(@ForAll("outputModes") OutputMode outputMode,
                                       @ForAll boolean identityReveal,
                                       @ForAll boolean autoAccept,
                                       @ForAll boolean manualReview) {
        // Requirement 339.4: Allow consent defaults configuration
        TrustCenter trustCenter = createTrustCenter();
        ConsentDefaultsConfig config = new ConsentDefaultsConfig(
                outputMode,
                identityReveal,
                "0.05 USD",
                "7 days",
                autoAccept,
                manualReview
        );
        
        trustCenter.configureConsentDefaults(config);
        
        ConsentDefaults defaults = trustCenter.getConsentDefaults();
        assertThat(defaults.getDefaultOutputMode()).isEqualTo(outputMode);
        assertThat(defaults.isDefaultIdentityReveal()).isEqualTo(identityReveal);
        assertThat(defaults.isAutoAcceptTrustedRequesters()).isEqualTo(autoAccept);
        assertThat(defaults.isRequireManualReview()).isEqualTo(manualReview);
    }

    @Test
    void consentDefaults_defaultsArePrivacyPreserving() {
        // Default settings should be privacy-preserving
        TrustCenter trustCenter = createTrustCenter();
        ConsentDefaults defaults = trustCenter.getConsentDefaults();
        
        // Identity reveal should default to OFF
        assertThat(defaults.isDefaultIdentityReveal()).isFalse();
        
        // Manual review should default to ON
        assertThat(defaults.isRequireManualReview()).isTrue();
        
        // Auto-accept should default to OFF
        assertThat(defaults.isAutoAcceptTrustedRequesters()).isFalse();
        
        // Output mode should default to AGGREGATED (not RAW)
        assertThat(defaults.getDefaultOutputMode()).isEqualTo(OutputMode.AGGREGATED);
    }

    @Test
    void consentDefaults_rejectsNullConfig() {
        TrustCenter trustCenter = createTrustCenter();
        assertThatThrownBy(() -> trustCenter.configureConsentDefaults(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Config cannot be null");
    }

    // ==================== Task 81.5: Network Verification Tests ====================

    @Test
    void networkVerification_onlyAllowsCoordinatorMetadata() {
        // Requirement 339.5: Verify no network calls except coordinator metadata
        TrustCenter trustCenter = createTrustCenter();
        NetworkVerificationResult result = trustCenter.verifyNetworkRestrictions();
        
        assertThat(result.verified()).isTrue();
        assertThat(result.allowedEndpoints())
                .allMatch(endpoint -> endpoint.contains("coordinator"));
        assertThat(result.blockedEndpoints())
                .anyMatch(endpoint -> endpoint.contains("raw-data"));
    }

    @Test
    void networkVerification_blocksRawDataEndpoints() {
        // Raw data endpoints must be blocked
        TrustCenter trustCenter = createTrustCenter();
        NetworkVerificationResult result = trustCenter.verifyNetworkRestrictions();
        
        assertThat(result.blockedEndpoints())
                .anyMatch(endpoint -> endpoint.contains("raw-data") || endpoint.contains("upload"));
    }

    @Test
    void networkVerification_blocksTrackingEndpoints() {
        // Tracking endpoints must be blocked
        TrustCenter trustCenter = createTrustCenter();
        NetworkVerificationResult result = trustCenter.verifyNetworkRestrictions();
        
        assertThat(result.blockedEndpoints())
                .anyMatch(endpoint -> endpoint.contains("tracking") || endpoint.contains("analytics"));
    }

    // ==================== Onboarding Progress Tests ====================

    @Test
    void onboardingProgress_tracksCompletion() {
        TrustCenter trustCenter = createTrustCenter();
        OnboardingProgress progress = trustCenter.getProgress();
        
        assertThat(progress.totalSteps()).isGreaterThan(0);
        assertThat(progress.completedSteps()).isGreaterThanOrEqualTo(0);
        assertThat(progress.completedSteps()).isLessThanOrEqualTo(progress.totalSteps());
    }

    @Test
    void onboardingProgress_showsNextStep() {
        TrustCenter trustCenter = createTrustCenter();
        OnboardingProgress progress = trustCenter.getProgress();
        
        if (!progress.complete()) {
            assertThat(progress.nextStep()).isNotBlank();
        }
    }

    @Test
    void onboardingProgress_percentCalculation() {
        TrustCenter trustCenter = createTrustCenter();
        OnboardingProgress progress = trustCenter.getProgress();
        
        double expectedPercent = (double) progress.completedSteps() / progress.totalSteps() * 100;
        assertThat(progress.percentComplete()).isEqualTo(expectedPercent);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullKeyManagementService() {
        PermissionService permissionService = new PermissionService();
        assertThatThrownBy(() -> new TrustCenter(null, permissionService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KeyManagementService cannot be null");
    }

    @Test
    void constructor_rejectsNullPermissionService() {
        KeyManagementService keyManagementService = new KeyManagementService();
        assertThatThrownBy(() -> new TrustCenter(keyManagementService, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PermissionService cannot be null");
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<BackupPolicy> backupPolicies() {
        return Arbitraries.of(BackupPolicy.values());
    }

    @Provide
    Arbitrary<OutputMode> outputModes() {
        return Arbitraries.of(OutputMode.values());
    }
}
