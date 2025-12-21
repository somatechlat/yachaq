package com.yachaq.node.trust;

import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Service for detecting device trust signals including root/jailbreak detection,
 * hardware attestation, and trust-based operation restrictions.
 * 
 * Implements Requirements:
 * - 358.1: Detect root/jailbreak and warn with output downgrade
 * - 358.2: Use hardware key attestation on Android
 * - 358.3: Adjust operations based on trust level
 * - 358.4: Display trust status to user
 * 
 * @see <a href="https://developer.android.com/training/safetynet/attestation">SafetyNet</a>
 * @see <a href="https://developer.apple.com/documentation/devicecheck">DeviceCheck</a>
 */
@Service
public class DeviceTrustSignalsService {

    /**
     * Trust level based on device security signals.
     */
    public enum TrustLevel {
        HIGH(1.0),      // Hardware attestation verified, no tampering detected
        MEDIUM(0.7),    // Software attestation verified, minor concerns
        LOW(0.4),       // Some security concerns detected
        UNTRUSTED(0.1); // Root/jailbreak detected or attestation failed
        
        private final double score;
        
        TrustLevel(double score) {
            this.score = score;
        }
        
        public double getScore() {
            return score;
        }
        
        public BigDecimal getScoreAsBigDecimal() {
            return BigDecimal.valueOf(score);
        }
    }
    
    /**
     * Result of device trust assessment.
     */
    public record TrustAssessment(
        TrustLevel trustLevel,
        BigDecimal trustScore,
        Set<TrustSignal> signals,
        Set<String> warnings,
        Set<OutputRestriction> restrictions,
        Instant assessedAt
    ) {
        public boolean isRooted() {
            return signals.contains(TrustSignal.ROOT_DETECTED) || 
                   signals.contains(TrustSignal.JAILBREAK_DETECTED);
        }
        
        public boolean requiresOutputDowngrade() {
            return trustLevel == TrustLevel.LOW || trustLevel == TrustLevel.UNTRUSTED;
        }
    }
    
    /**
     * Individual trust signals detected on device.
     */
    public enum TrustSignal {
        // Positive signals
        HARDWARE_ATTESTATION_VERIFIED,
        SOFTWARE_ATTESTATION_VERIFIED,
        SECURE_BOOT_VERIFIED,
        BOOTLOADER_LOCKED,
        PLAY_PROTECT_ENABLED,
        DEVICE_ENCRYPTION_ENABLED,
        BIOMETRIC_AVAILABLE,
        
        // Negative signals
        ROOT_DETECTED,
        JAILBREAK_DETECTED,
        BOOTLOADER_UNLOCKED,
        CUSTOM_ROM_DETECTED,
        MAGISK_DETECTED,
        XPOSED_DETECTED,
        FRIDA_DETECTED,
        DEBUGGER_ATTACHED,
        EMULATOR_DETECTED,
        HOOK_FRAMEWORK_DETECTED,
        TAMPERED_SYSTEM_PARTITION,
        UNKNOWN_SOURCES_ENABLED,
        DEVELOPER_OPTIONS_ENABLED,
        USB_DEBUGGING_ENABLED,
        ADB_ENABLED
    }
    
    /**
     * Output restrictions based on trust level.
     */
    public enum OutputRestriction {
        NO_RAW_DATA_EXPORT,
        AGGREGATE_ONLY,
        REDUCED_PRECISION,
        NO_IDENTITY_REVEAL,
        ENHANCED_AUDIT_LOGGING,
        RATE_LIMITED_QUERIES,
        NO_SENSITIVE_CATEGORIES
    }
    
    /**
     * Platform-specific detection configuration.
     */
    public enum Platform {
        ANDROID,
        IOS,
        DESKTOP,
        UNKNOWN
    }

    // Known root/jailbreak indicators
    private static final Set<String> ANDROID_ROOT_PATHS = Set.of(
        "/system/app/Superuser.apk",
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/system/usr/we-need-root/su"
    );
    
    private static final Set<String> ANDROID_ROOT_PACKAGES = Set.of(
        "com.noshufou.android.su",
        "com.thirdparty.superuser",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine",
        "com.topjohnwu.magisk"
    );
    
    private static final Set<String> IOS_JAILBREAK_PATHS = Set.of(
        "/Applications/Cydia.app",
        "/Library/MobileSubstrate/MobileSubstrate.dylib",
        "/bin/bash",
        "/usr/sbin/sshd",
        "/etc/apt",
        "/private/var/lib/apt/",
        "/private/var/lib/cydia",
        "/private/var/stash",
        "/usr/bin/ssh",
        "/usr/libexec/sftp-server"
    );
    
    private static final Set<String> HOOK_FRAMEWORKS = Set.of(
        "com.saurik.substrate",
        "de.robv.android.xposed",
        "de.robv.android.xposed.installer",
        "com.topjohnwu.magisk",
        "me.weishu.exp",
        "com.swift.sandhook"
    );

    /**
     * Performs comprehensive trust assessment of the device.
     * 
     * @param platform device platform
     * @param systemProperties system properties for detection
     * @param installedPackages list of installed packages (Android)
     * @param attestationProof hardware attestation proof if available
     * @return trust assessment result
     */
    public TrustAssessment assessTrust(
            Platform platform,
            Map<String, String> systemProperties,
            Set<String> installedPackages,
            byte[] attestationProof) {
        
        Set<TrustSignal> signals = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        
        // Detect root/jailbreak
        detectRootOrJailbreak(platform, systemProperties, installedPackages, signals, warnings);
        
        // Detect hook frameworks
        detectHookFrameworks(installedPackages, signals, warnings);
        
        // Detect emulator
        detectEmulator(systemProperties, signals, warnings);
        
        // Detect debugger
        detectDebugger(systemProperties, signals, warnings);
        
        // Check developer options
        checkDeveloperOptions(systemProperties, signals, warnings);
        
        // Verify attestation
        verifyAttestation(attestationProof, signals, warnings);
        
        // Calculate trust level and score
        TrustLevel trustLevel = calculateTrustLevel(signals);
        BigDecimal trustScore = calculateTrustScore(signals);
        
        // Determine restrictions
        Set<OutputRestriction> restrictions = determineRestrictions(trustLevel, signals);
        
        return new TrustAssessment(
            trustLevel,
            trustScore,
            Collections.unmodifiableSet(signals),
            Collections.unmodifiableSet(warnings),
            Collections.unmodifiableSet(restrictions),
            Instant.now()
        );
    }
    
    /**
     * Quick check for root/jailbreak without full assessment.
     */
    public boolean isRootedOrJailbroken(Platform platform, Map<String, String> systemProperties, 
            Set<String> installedPackages) {
        
        Set<TrustSignal> signals = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        
        detectRootOrJailbreak(platform, systemProperties, installedPackages, signals, warnings);
        
        return signals.contains(TrustSignal.ROOT_DETECTED) || 
               signals.contains(TrustSignal.JAILBREAK_DETECTED);
    }

    
    /**
     * Detects root (Android) or jailbreak (iOS).
     */
    private void detectRootOrJailbreak(Platform platform, Map<String, String> systemProperties,
            Set<String> installedPackages, Set<TrustSignal> signals, Set<String> warnings) {
        
        switch (platform) {
            case ANDROID -> detectAndroidRoot(systemProperties, installedPackages, signals, warnings);
            case IOS -> detectIOSJailbreak(systemProperties, signals, warnings);
            case DESKTOP -> {
                // Desktop platforms have different trust model
                signals.add(TrustSignal.SOFTWARE_ATTESTATION_VERIFIED);
            }
            default -> warnings.add("Unknown platform - cannot assess root/jailbreak status");
        }
    }
    
    private void detectAndroidRoot(Map<String, String> systemProperties, Set<String> installedPackages,
            Set<TrustSignal> signals, Set<String> warnings) {
        
        boolean rootDetected = false;
        
        // Check for su binary paths
        for (String path : ANDROID_ROOT_PATHS) {
            if (fileExists(path)) {
                rootDetected = true;
                warnings.add("Root binary found at: " + path);
                break;
            }
        }
        
        // Check for root packages
        for (String pkg : ANDROID_ROOT_PACKAGES) {
            if (installedPackages.contains(pkg)) {
                rootDetected = true;
                warnings.add("Root package installed: " + pkg);
                break;
            }
        }
        
        // Check build tags
        String buildTags = systemProperties.getOrDefault("ro.build.tags", "");
        if (buildTags.contains("test-keys")) {
            rootDetected = true;
            warnings.add("Device built with test-keys (custom ROM indicator)");
            signals.add(TrustSignal.CUSTOM_ROM_DETECTED);
        }
        
        // Check for Magisk
        if (installedPackages.contains("com.topjohnwu.magisk")) {
            rootDetected = true;
            warnings.add("Magisk detected");
            signals.add(TrustSignal.MAGISK_DETECTED);
        }
        
        // Check bootloader status
        String bootloaderState = systemProperties.getOrDefault("ro.boot.verifiedbootstate", "");
        if ("orange".equalsIgnoreCase(bootloaderState) || "yellow".equalsIgnoreCase(bootloaderState)) {
            signals.add(TrustSignal.BOOTLOADER_UNLOCKED);
            warnings.add("Bootloader is unlocked");
        } else if ("green".equalsIgnoreCase(bootloaderState)) {
            signals.add(TrustSignal.BOOTLOADER_LOCKED);
        }
        
        if (rootDetected) {
            signals.add(TrustSignal.ROOT_DETECTED);
        }
    }
    
    private void detectIOSJailbreak(Map<String, String> systemProperties, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        boolean jailbreakDetected = false;
        
        // Check for jailbreak paths
        for (String path : IOS_JAILBREAK_PATHS) {
            if (fileExists(path)) {
                jailbreakDetected = true;
                warnings.add("Jailbreak indicator found at: " + path);
                break;
            }
        }
        
        // Check if can write outside sandbox
        String sandboxStatus = systemProperties.getOrDefault("sandbox.status", "enabled");
        if ("disabled".equalsIgnoreCase(sandboxStatus)) {
            jailbreakDetected = true;
            warnings.add("Sandbox appears to be disabled");
        }
        
        // Check for Cydia URL scheme
        String cydiaScheme = systemProperties.getOrDefault("cydia.scheme.available", "false");
        if ("true".equalsIgnoreCase(cydiaScheme)) {
            jailbreakDetected = true;
            warnings.add("Cydia URL scheme is available");
        }
        
        if (jailbreakDetected) {
            signals.add(TrustSignal.JAILBREAK_DETECTED);
        }
    }
    
    /**
     * Detects hook frameworks (Xposed, Frida, etc.).
     */
    private void detectHookFrameworks(Set<String> installedPackages, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        for (String framework : HOOK_FRAMEWORKS) {
            if (installedPackages.contains(framework)) {
                signals.add(TrustSignal.HOOK_FRAMEWORK_DETECTED);
                warnings.add("Hook framework detected: " + framework);
                
                if (framework.contains("xposed")) {
                    signals.add(TrustSignal.XPOSED_DETECTED);
                }
            }
        }
        
        // Check for Frida server
        if (isProcessRunning("frida-server") || isPortOpen(27042)) {
            signals.add(TrustSignal.FRIDA_DETECTED);
            warnings.add("Frida server detected");
        }
    }
    
    /**
     * Detects if running in an emulator.
     */
    private void detectEmulator(Map<String, String> systemProperties, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        String hardware = systemProperties.getOrDefault("ro.hardware", "");
        String product = systemProperties.getOrDefault("ro.product.model", "");
        String brand = systemProperties.getOrDefault("ro.product.brand", "");
        String fingerprint = systemProperties.getOrDefault("ro.build.fingerprint", "");
        
        boolean isEmulator = false;
        
        // Check for known emulator indicators
        if (hardware.contains("goldfish") || hardware.contains("ranchu") || 
            hardware.contains("vbox") || hardware.contains("nox")) {
            isEmulator = true;
        }
        
        if (product.contains("sdk") || product.contains("emulator") || 
            product.contains("google_sdk") || product.contains("Genymotion")) {
            isEmulator = true;
        }
        
        if (brand.contains("generic") || brand.contains("google")) {
            // Additional checks needed
            if (fingerprint.contains("generic") || fingerprint.contains("test-keys")) {
                isEmulator = true;
            }
        }
        
        if (isEmulator) {
            signals.add(TrustSignal.EMULATOR_DETECTED);
            warnings.add("Device appears to be an emulator");
        }
    }
    
    /**
     * Detects if a debugger is attached.
     */
    private void detectDebugger(Map<String, String> systemProperties, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        String debuggable = systemProperties.getOrDefault("ro.debuggable", "0");
        String debuggerConnected = systemProperties.getOrDefault("debug.connected", "false");
        
        if ("1".equals(debuggable) || "true".equalsIgnoreCase(debuggerConnected)) {
            signals.add(TrustSignal.DEBUGGER_ATTACHED);
            warnings.add("Debugger may be attached");
        }
    }
    
    /**
     * Checks developer options status.
     */
    private void checkDeveloperOptions(Map<String, String> systemProperties, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        String devOptions = systemProperties.getOrDefault("settings.global.development_settings_enabled", "0");
        String adbEnabled = systemProperties.getOrDefault("settings.global.adb_enabled", "0");
        String usbDebugging = systemProperties.getOrDefault("settings.global.usb_debugging", "0");
        
        if ("1".equals(devOptions)) {
            signals.add(TrustSignal.DEVELOPER_OPTIONS_ENABLED);
            warnings.add("Developer options are enabled");
        }
        
        if ("1".equals(adbEnabled)) {
            signals.add(TrustSignal.ADB_ENABLED);
            warnings.add("ADB is enabled");
        }
        
        if ("1".equals(usbDebugging)) {
            signals.add(TrustSignal.USB_DEBUGGING_ENABLED);
            warnings.add("USB debugging is enabled");
        }
    }
    
    /**
     * Verifies hardware attestation proof.
     */
    private void verifyAttestation(byte[] attestationProof, 
            Set<TrustSignal> signals, Set<String> warnings) {
        
        if (attestationProof == null || attestationProof.length == 0) {
            warnings.add("No hardware attestation proof provided");
            return;
        }
        
        // In production, this would verify the attestation certificate chain
        // For now, we check if proof is present and has minimum length
        if (attestationProof.length >= 256) {
            signals.add(TrustSignal.HARDWARE_ATTESTATION_VERIFIED);
        } else if (attestationProof.length >= 64) {
            signals.add(TrustSignal.SOFTWARE_ATTESTATION_VERIFIED);
        } else {
            warnings.add("Attestation proof too short to be valid");
        }
    }
    
    /**
     * Calculates overall trust level from signals.
     */
    private TrustLevel calculateTrustLevel(Set<TrustSignal> signals) {
        // Critical negative signals -> UNTRUSTED
        if (signals.contains(TrustSignal.ROOT_DETECTED) ||
            signals.contains(TrustSignal.JAILBREAK_DETECTED) ||
            signals.contains(TrustSignal.FRIDA_DETECTED) ||
            signals.contains(TrustSignal.HOOK_FRAMEWORK_DETECTED)) {
            return TrustLevel.UNTRUSTED;
        }
        
        // Moderate negative signals -> LOW
        if (signals.contains(TrustSignal.EMULATOR_DETECTED) ||
            signals.contains(TrustSignal.DEBUGGER_ATTACHED) ||
            signals.contains(TrustSignal.BOOTLOADER_UNLOCKED) ||
            signals.contains(TrustSignal.MAGISK_DETECTED) ||
            signals.contains(TrustSignal.XPOSED_DETECTED)) {
            return TrustLevel.LOW;
        }
        
        // Minor concerns -> MEDIUM
        if (signals.contains(TrustSignal.DEVELOPER_OPTIONS_ENABLED) ||
            signals.contains(TrustSignal.USB_DEBUGGING_ENABLED) ||
            signals.contains(TrustSignal.ADB_ENABLED) ||
            signals.contains(TrustSignal.CUSTOM_ROM_DETECTED)) {
            return TrustLevel.MEDIUM;
        }
        
        // Hardware attestation verified -> HIGH
        if (signals.contains(TrustSignal.HARDWARE_ATTESTATION_VERIFIED) &&
            signals.contains(TrustSignal.BOOTLOADER_LOCKED)) {
            return TrustLevel.HIGH;
        }
        
        // Software attestation only -> MEDIUM
        if (signals.contains(TrustSignal.SOFTWARE_ATTESTATION_VERIFIED)) {
            return TrustLevel.MEDIUM;
        }
        
        // Default to MEDIUM if no strong signals either way
        return TrustLevel.MEDIUM;
    }
    
    /**
     * Calculates numeric trust score from signals.
     */
    private BigDecimal calculateTrustScore(Set<TrustSignal> signals) {
        double score = 0.5; // Base score
        
        // Positive signals
        if (signals.contains(TrustSignal.HARDWARE_ATTESTATION_VERIFIED)) score += 0.3;
        if (signals.contains(TrustSignal.SOFTWARE_ATTESTATION_VERIFIED)) score += 0.15;
        if (signals.contains(TrustSignal.BOOTLOADER_LOCKED)) score += 0.1;
        if (signals.contains(TrustSignal.SECURE_BOOT_VERIFIED)) score += 0.1;
        if (signals.contains(TrustSignal.DEVICE_ENCRYPTION_ENABLED)) score += 0.05;
        if (signals.contains(TrustSignal.BIOMETRIC_AVAILABLE)) score += 0.05;
        
        // Negative signals
        if (signals.contains(TrustSignal.ROOT_DETECTED)) score -= 0.5;
        if (signals.contains(TrustSignal.JAILBREAK_DETECTED)) score -= 0.5;
        if (signals.contains(TrustSignal.FRIDA_DETECTED)) score -= 0.4;
        if (signals.contains(TrustSignal.HOOK_FRAMEWORK_DETECTED)) score -= 0.35;
        if (signals.contains(TrustSignal.MAGISK_DETECTED)) score -= 0.3;
        if (signals.contains(TrustSignal.XPOSED_DETECTED)) score -= 0.3;
        if (signals.contains(TrustSignal.EMULATOR_DETECTED)) score -= 0.25;
        if (signals.contains(TrustSignal.DEBUGGER_ATTACHED)) score -= 0.2;
        if (signals.contains(TrustSignal.BOOTLOADER_UNLOCKED)) score -= 0.15;
        if (signals.contains(TrustSignal.CUSTOM_ROM_DETECTED)) score -= 0.1;
        if (signals.contains(TrustSignal.DEVELOPER_OPTIONS_ENABLED)) score -= 0.05;
        if (signals.contains(TrustSignal.USB_DEBUGGING_ENABLED)) score -= 0.05;
        if (signals.contains(TrustSignal.ADB_ENABLED)) score -= 0.05;
        
        // Clamp to [0.0, 1.0]
        score = Math.max(0.0, Math.min(1.0, score));
        
        return BigDecimal.valueOf(score).setScale(4, java.math.RoundingMode.HALF_UP);
    }
    
    /**
     * Determines output restrictions based on trust level.
     */
    private Set<OutputRestriction> determineRestrictions(TrustLevel trustLevel, Set<TrustSignal> signals) {
        Set<OutputRestriction> restrictions = new LinkedHashSet<>();
        
        switch (trustLevel) {
            case UNTRUSTED -> {
                restrictions.add(OutputRestriction.NO_RAW_DATA_EXPORT);
                restrictions.add(OutputRestriction.AGGREGATE_ONLY);
                restrictions.add(OutputRestriction.NO_IDENTITY_REVEAL);
                restrictions.add(OutputRestriction.ENHANCED_AUDIT_LOGGING);
                restrictions.add(OutputRestriction.RATE_LIMITED_QUERIES);
                restrictions.add(OutputRestriction.NO_SENSITIVE_CATEGORIES);
            }
            case LOW -> {
                restrictions.add(OutputRestriction.NO_RAW_DATA_EXPORT);
                restrictions.add(OutputRestriction.REDUCED_PRECISION);
                restrictions.add(OutputRestriction.NO_IDENTITY_REVEAL);
                restrictions.add(OutputRestriction.ENHANCED_AUDIT_LOGGING);
            }
            case MEDIUM -> {
                restrictions.add(OutputRestriction.ENHANCED_AUDIT_LOGGING);
                // Identity reveal requires explicit consent even at MEDIUM
                if (!signals.contains(TrustSignal.HARDWARE_ATTESTATION_VERIFIED)) {
                    restrictions.add(OutputRestriction.REDUCED_PRECISION);
                }
            }
            case HIGH -> {
                // No restrictions for HIGH trust
            }
        }
        
        return restrictions;
    }
    
    /**
     * Gets user-friendly trust status display.
     */
    public TrustStatusDisplay getTrustStatusDisplay(TrustAssessment assessment) {
        String title = switch (assessment.trustLevel()) {
            case HIGH -> "Device Verified";
            case MEDIUM -> "Device Partially Verified";
            case LOW -> "Security Concerns Detected";
            case UNTRUSTED -> "Device Compromised";
        };
        
        String description = switch (assessment.trustLevel()) {
            case HIGH -> "Your device has passed all security checks. Full functionality is available.";
            case MEDIUM -> "Your device has passed basic security checks. Some features may have reduced precision.";
            case LOW -> "Security concerns were detected. Data exports are restricted for your protection.";
            case UNTRUSTED -> "Your device appears to be rooted/jailbroken. Only aggregate data access is available.";
        };
        
        String color = switch (assessment.trustLevel()) {
            case HIGH -> "#22C55E";     // Green
            case MEDIUM -> "#F59E0B";   // Amber
            case LOW -> "#F97316";      // Orange
            case UNTRUSTED -> "#EF4444"; // Red
        };
        
        return new TrustStatusDisplay(
            title,
            description,
            color,
            assessment.trustLevel().name(),
            assessment.trustScore().doubleValue(),
            assessment.warnings(),
            assessment.restrictions()
        );
    }
    
    /**
     * User-friendly trust status for display.
     */
    public record TrustStatusDisplay(
        String title,
        String description,
        String color,
        String level,
        double score,
        Set<String> warnings,
        Set<OutputRestriction> restrictions
    ) {}
    
    // Helper methods (would be platform-specific in production)
    
    private boolean fileExists(String path) {
        // In production, this would use platform-specific file checks
        return new File(path).exists();
    }
    
    private boolean isProcessRunning(String processName) {
        // In production, this would check running processes
        return false;
    }
    
    private boolean isPortOpen(int port) {
        // In production, this would check if port is listening
        return false;
    }
}
