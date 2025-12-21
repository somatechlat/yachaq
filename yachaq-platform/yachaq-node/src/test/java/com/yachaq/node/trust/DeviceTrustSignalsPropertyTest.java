package com.yachaq.node.trust;

import com.yachaq.node.trust.DeviceTrustSignalsService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for DeviceTrustSignalsService.
 * 
 * **Feature: yachaq-platform, Task 106: Device Trust Signals**
 * **Validates: Requirements 358.1, 358.2, 358.3, 358.4, 358.5**
 */
class DeviceTrustSignalsPropertyTest {

    private final DeviceTrustSignalsService service = new DeviceTrustSignalsService();

    // ========================================================================
    // Property 1: Root Detection Triggers UNTRUSTED Level
    // For any device with root indicators, trust level must be UNTRUSTED
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Root detection triggers UNTRUSTED level")
    void rootDetectionTriggersUntrustedLevel(
            @ForAll("rootedAndroidProperties") Map<String, String> systemProperties,
            @ForAll("rootPackages") Set<String> installedPackages) {
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            installedPackages,
            null
        );
        
        if (assessment.signals().contains(TrustSignal.ROOT_DETECTED)) {
            assertThat(assessment.trustLevel())
                .as("Rooted device must have UNTRUSTED level")
                .isEqualTo(TrustLevel.UNTRUSTED);
            
            assertThat(assessment.isRooted()).isTrue();
            assertThat(assessment.requiresOutputDowngrade()).isTrue();
        }
    }

    // ========================================================================
    // Property 2: Jailbreak Detection Triggers UNTRUSTED Level
    // For any iOS device with jailbreak indicators, trust level must be UNTRUSTED
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Jailbreak detection triggers UNTRUSTED level")
    void jailbreakDetectionTriggersUntrustedLevel(
            @ForAll("jailbrokenIOSProperties") Map<String, String> systemProperties) {
        
        TrustAssessment assessment = service.assessTrust(
            Platform.IOS,
            systemProperties,
            Set.of(),
            null
        );
        
        if (assessment.signals().contains(TrustSignal.JAILBREAK_DETECTED)) {
            assertThat(assessment.trustLevel())
                .as("Jailbroken device must have UNTRUSTED level")
                .isEqualTo(TrustLevel.UNTRUSTED);
            
            assertThat(assessment.isRooted()).isTrue();
        }
    }

    // ========================================================================
    // Property 3: Trust Score is Always in Valid Range
    // For any assessment, trust score must be between 0.0 and 1.0
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Trust score is always in valid range [0.0, 1.0]")
    void trustScoreIsAlwaysInValidRange(
            @ForAll("platforms") Platform platform,
            @ForAll("randomSystemProperties") Map<String, String> systemProperties,
            @ForAll("randomPackages") Set<String> installedPackages) {
        
        TrustAssessment assessment = service.assessTrust(
            platform,
            systemProperties,
            installedPackages,
            null
        );
        
        assertThat(assessment.trustScore())
            .as("Trust score must be >= 0.0")
            .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        
        assertThat(assessment.trustScore())
            .as("Trust score must be <= 1.0")
            .isLessThanOrEqualTo(BigDecimal.ONE);
    }

    // ========================================================================
    // Property 4: UNTRUSTED Level Always Has Maximum Restrictions
    // For any UNTRUSTED assessment, all critical restrictions must be applied
    // ========================================================================
    
    @Property(tries = 50)
    @Label("UNTRUSTED level always has maximum restrictions")
    void untrustedLevelHasMaximumRestrictions(
            @ForAll("compromisedDeviceProperties") Map<String, String> systemProperties,
            @ForAll("rootPackages") Set<String> installedPackages) {
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            installedPackages,
            null
        );
        
        if (assessment.trustLevel() == TrustLevel.UNTRUSTED) {
            assertThat(assessment.restrictions())
                .as("UNTRUSTED must have NO_RAW_DATA_EXPORT")
                .contains(OutputRestriction.NO_RAW_DATA_EXPORT);
            
            assertThat(assessment.restrictions())
                .as("UNTRUSTED must have AGGREGATE_ONLY")
                .contains(OutputRestriction.AGGREGATE_ONLY);
            
            assertThat(assessment.restrictions())
                .as("UNTRUSTED must have NO_IDENTITY_REVEAL")
                .contains(OutputRestriction.NO_IDENTITY_REVEAL);
        }
    }

    // ========================================================================
    // Property 5: HIGH Trust Level Has No Restrictions
    // For any HIGH trust assessment, no restrictions should be applied
    // ========================================================================
    
    @Property(tries = 50)
    @Label("HIGH trust level has no restrictions")
    void highTrustLevelHasNoRestrictions(
            @ForAll("secureDeviceProperties") Map<String, String> systemProperties) {
        
        // Provide valid hardware attestation
        byte[] attestationProof = new byte[512];
        new Random().nextBytes(attestationProof);
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            Set.of(), // No suspicious packages
            attestationProof
        );
        
        if (assessment.trustLevel() == TrustLevel.HIGH) {
            assertThat(assessment.restrictions())
                .as("HIGH trust should have no restrictions")
                .isEmpty();
        }
    }

    // ========================================================================
    // Property 6: Hook Framework Detection Triggers UNTRUSTED
    // For any device with hook frameworks, trust level must be UNTRUSTED
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Hook framework detection triggers UNTRUSTED")
    void hookFrameworkDetectionTriggersUntrusted(
            @ForAll("hookFrameworkPackages") Set<String> installedPackages) {
        
        Assume.that(!installedPackages.isEmpty());
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            Map.of(),
            installedPackages,
            null
        );
        
        if (assessment.signals().contains(TrustSignal.HOOK_FRAMEWORK_DETECTED) ||
            assessment.signals().contains(TrustSignal.XPOSED_DETECTED) ||
            assessment.signals().contains(TrustSignal.FRIDA_DETECTED)) {
            
            assertThat(assessment.trustLevel())
                .as("Hook framework must trigger UNTRUSTED")
                .isEqualTo(TrustLevel.UNTRUSTED);
        }
    }

    // ========================================================================
    // Property 7: Emulator Detection Triggers LOW Trust
    // For any emulator, trust level must be at most LOW
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Emulator detection triggers at most LOW trust")
    void emulatorDetectionTriggersLowTrust(
            @ForAll("emulatorProperties") Map<String, String> systemProperties) {
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            Set.of(),
            null
        );
        
        if (assessment.signals().contains(TrustSignal.EMULATOR_DETECTED)) {
            assertThat(assessment.trustLevel())
                .as("Emulator must have at most LOW trust")
                .isIn(TrustLevel.LOW, TrustLevel.UNTRUSTED);
        }
    }

    // ========================================================================
    // Property 8: Trust Status Display is Always Valid
    // For any assessment, status display must have all required fields
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Trust status display is always valid")
    void trustStatusDisplayIsAlwaysValid(
            @ForAll("platforms") Platform platform,
            @ForAll("randomSystemProperties") Map<String, String> systemProperties) {
        
        TrustAssessment assessment = service.assessTrust(
            platform,
            systemProperties,
            Set.of(),
            null
        );
        
        TrustStatusDisplay display = service.getTrustStatusDisplay(assessment);
        
        assertThat(display.title()).isNotNull().isNotEmpty();
        assertThat(display.description()).isNotNull().isNotEmpty();
        assertThat(display.color()).isNotNull().matches("^#[0-9A-Fa-f]{6}$");
        assertThat(display.level()).isNotNull().isIn("HIGH", "MEDIUM", "LOW", "UNTRUSTED");
        assertThat(display.score()).isBetween(0.0, 1.0);
    }

    // ========================================================================
    // Property 9: Assessment Timestamp is Always Present
    // For any assessment, timestamp must be set and recent
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Assessment timestamp is always present and recent")
    void assessmentTimestampIsAlwaysPresent(
            @ForAll("platforms") Platform platform) {
        
        java.time.Instant before = java.time.Instant.now();
        
        TrustAssessment assessment = service.assessTrust(
            platform,
            Map.of(),
            Set.of(),
            null
        );
        
        java.time.Instant after = java.time.Instant.now();
        
        assertThat(assessment.assessedAt())
            .as("Assessment timestamp must be present")
            .isNotNull();
        
        assertThat(assessment.assessedAt())
            .as("Assessment timestamp must be recent")
            .isAfterOrEqualTo(before)
            .isBeforeOrEqualTo(after);
    }

    // ========================================================================
    // Property 10: Developer Options Triggers at Most MEDIUM Trust
    // For any device with developer options, trust level must be at most MEDIUM
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Developer options triggers at most MEDIUM trust")
    void developerOptionsTriggersAtMostMediumTrust(
            @ForAll("developerOptionsProperties") Map<String, String> systemProperties) {
        
        TrustAssessment assessment = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            Set.of(),
            null
        );
        
        if (assessment.signals().contains(TrustSignal.DEVELOPER_OPTIONS_ENABLED) ||
            assessment.signals().contains(TrustSignal.USB_DEBUGGING_ENABLED) ||
            assessment.signals().contains(TrustSignal.ADB_ENABLED)) {
            
            assertThat(assessment.trustLevel())
                .as("Developer options must trigger at most MEDIUM trust")
                .isIn(TrustLevel.MEDIUM, TrustLevel.LOW, TrustLevel.UNTRUSTED);
        }
    }

    // ========================================================================
    // Property 11: Hardware Attestation Improves Trust Score
    // For any device with hardware attestation, score should be higher
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Hardware attestation improves trust score")
    void hardwareAttestationImprovesTrustScore(
            @ForAll("secureDeviceProperties") Map<String, String> systemProperties) {
        
        // Without attestation
        TrustAssessment withoutAttestation = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            Set.of(),
            null
        );
        
        // With hardware attestation
        byte[] attestationProof = new byte[512];
        new Random().nextBytes(attestationProof);
        
        TrustAssessment withAttestation = service.assessTrust(
            Platform.ANDROID,
            systemProperties,
            Set.of(),
            attestationProof
        );
        
        assertThat(withAttestation.trustScore())
            .as("Hardware attestation should improve trust score")
            .isGreaterThanOrEqualTo(withoutAttestation.trustScore());
    }

    // ========================================================================
    // Property 12: Quick Root Check Consistency
    // Quick root check must be consistent with full assessment
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Quick root check is consistent with full assessment")
    void quickRootCheckIsConsistent(
            @ForAll("platforms") Platform platform,
            @ForAll("randomSystemProperties") Map<String, String> systemProperties,
            @ForAll("randomPackages") Set<String> installedPackages) {
        
        boolean quickCheck = service.isRootedOrJailbroken(platform, systemProperties, installedPackages);
        
        TrustAssessment fullAssessment = service.assessTrust(
            platform,
            systemProperties,
            installedPackages,
            null
        );
        
        assertThat(quickCheck)
            .as("Quick check must match full assessment")
            .isEqualTo(fullAssessment.isRooted());
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<Platform> platforms() {
        return Arbitraries.of(Platform.ANDROID, Platform.IOS, Platform.DESKTOP);
    }

    @Provide
    Arbitrary<Map<String, String>> rootedAndroidProperties() {
        return Arbitraries.of(
            Map.of("ro.build.tags", "test-keys"),
            Map.of("ro.boot.verifiedbootstate", "orange"),
            Map.of("ro.debuggable", "1")
        );
    }

    @Provide
    Arbitrary<Set<String>> rootPackages() {
        return Arbitraries.of(
            Set.of("com.topjohnwu.magisk"),
            Set.of("eu.chainfire.supersu"),
            Set.of("com.noshufou.android.su"),
            Set.of("com.koushikdutta.superuser"),
            Set.of() // Empty set for non-rooted
        );
    }

    @Provide
    Arbitrary<Map<String, String>> jailbrokenIOSProperties() {
        return Arbitraries.of(
            Map.of("sandbox.status", "disabled"),
            Map.of("cydia.scheme.available", "true"),
            Map.of() // Empty for non-jailbroken
        );
    }

    @Provide
    Arbitrary<Map<String, String>> randomSystemProperties() {
        return Arbitraries.maps(
            Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)
        ).ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<Set<String>> randomPackages() {
        return Arbitraries.strings()
            .alpha()
            .ofMinLength(5)
            .ofMaxLength(30)
            .set()
            .ofMinSize(0)
            .ofMaxSize(10);
    }

    @Provide
    Arbitrary<Map<String, String>> compromisedDeviceProperties() {
        return Arbitraries.of(
            Map.of("ro.build.tags", "test-keys", "ro.debuggable", "1"),
            Map.of("ro.boot.verifiedbootstate", "orange")
        );
    }

    @Provide
    Arbitrary<Map<String, String>> secureDeviceProperties() {
        return Arbitraries.of(
            Map.of("ro.boot.verifiedbootstate", "green", "ro.build.tags", "release-keys"),
            Map.of("ro.boot.verifiedbootstate", "green")
        );
    }

    @Provide
    Arbitrary<Set<String>> hookFrameworkPackages() {
        return Arbitraries.of(
            Set.of("de.robv.android.xposed"),
            Set.of("de.robv.android.xposed.installer"),
            Set.of("com.saurik.substrate"),
            Set.of("me.weishu.exp"),
            Set.of() // Empty for no hooks
        );
    }

    @Provide
    Arbitrary<Map<String, String>> emulatorProperties() {
        return Arbitraries.of(
            Map.of("ro.hardware", "goldfish"),
            Map.of("ro.hardware", "ranchu"),
            Map.of("ro.product.model", "sdk"),
            Map.of("ro.product.model", "google_sdk"),
            Map.of("ro.product.model", "Genymotion")
        );
    }

    @Provide
    Arbitrary<Map<String, String>> developerOptionsProperties() {
        return Arbitraries.of(
            Map.of("settings.global.development_settings_enabled", "1"),
            Map.of("settings.global.adb_enabled", "1"),
            Map.of("settings.global.usb_debugging", "1"),
            Map.of(
                "settings.global.development_settings_enabled", "1",
                "settings.global.adb_enabled", "1"
            )
        );
    }
}
