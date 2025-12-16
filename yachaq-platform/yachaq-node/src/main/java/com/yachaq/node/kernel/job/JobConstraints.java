package com.yachaq.node.kernel.job;

/**
 * Resource constraints for job execution.
 * Requirement 302.2: Enforce battery, charging, thermal, and network constraints.
 */
public record JobConstraints(
        int minBatteryPercent,
        boolean requireCharging,
        int maxThermalState,
        NetworkRequirement networkRequirement,
        int maxExecutionTimeSeconds,
        Priority priority
) {
    /**
     * Default constraints for general jobs.
     */
    public static JobConstraints defaults() {
        return new JobConstraints(
                20,                          // minBatteryPercent
                false,                       // requireCharging
                3,                           // maxThermalState (0-4 scale)
                NetworkRequirement.ANY,      // networkRequirement
                300,                         // maxExecutionTimeSeconds (5 min)
                Priority.NORMAL              // priority
        );
    }

    /**
     * Constraints for background sync jobs (more restrictive).
     */
    public static JobConstraints backgroundSync() {
        return new JobConstraints(
                30,                          // minBatteryPercent
                false,                       // requireCharging
                2,                           // maxThermalState
                NetworkRequirement.WIFI,     // networkRequirement
                600,                         // maxExecutionTimeSeconds (10 min)
                Priority.LOW                 // priority
        );
    }

    /**
     * Constraints for user-initiated jobs (less restrictive).
     */
    public static JobConstraints userInitiated() {
        return new JobConstraints(
                10,                          // minBatteryPercent
                false,                       // requireCharging
                4,                           // maxThermalState
                NetworkRequirement.ANY,      // networkRequirement
                120,                         // maxExecutionTimeSeconds (2 min)
                Priority.HIGH                // priority
        );
    }

    /**
     * Constraints for heavy processing jobs.
     */
    public static JobConstraints heavyProcessing() {
        return new JobConstraints(
                50,                          // minBatteryPercent
                true,                        // requireCharging
                2,                           // maxThermalState
                NetworkRequirement.NONE,     // networkRequirement
                1800,                        // maxExecutionTimeSeconds (30 min)
                Priority.LOW                 // priority
        );
    }

    public enum NetworkRequirement {
        NONE,       // No network needed
        ANY,        // Any network (WiFi or cellular)
        WIFI,       // WiFi only
        UNMETERED   // Unmetered connection only
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        CRITICAL
    }
}
