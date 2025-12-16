package com.yachaq.node.kernel.resource;

import com.yachaq.node.kernel.job.JobConstraints;

import java.util.ArrayList;
import java.util.List;

/**
 * Monitors device resources for job constraint enforcement.
 * Requirement 302.2: Enforce battery, charging, thermal, and network constraints.
 */
public class ResourceMonitor {

    private int batteryPercent = 100;
    private boolean isCharging = false;
    private int thermalState = 0;  // 0-4 scale (0=nominal, 4=critical)
    private NetworkState networkState = NetworkState.WIFI;

    /**
     * Checks if current resources meet the specified constraints.
     * 
     * @param constraints Job constraints to check
     * @return Resource status with constraint check result
     */
    public ResourceStatus checkResources(JobConstraints constraints) {
        List<String> violations = new ArrayList<>();

        // Check battery level
        if (batteryPercent < constraints.minBatteryPercent()) {
            violations.add("Battery " + batteryPercent + "% < required " + constraints.minBatteryPercent() + "%");
        }

        // Check charging requirement
        if (constraints.requireCharging() && !isCharging) {
            violations.add("Charging required but device not charging");
        }

        // Check thermal state
        if (thermalState > constraints.maxThermalState()) {
            violations.add("Thermal state " + thermalState + " > max " + constraints.maxThermalState());
        }

        // Check network requirement
        if (!meetsNetworkRequirement(constraints.networkRequirement())) {
            violations.add("Network requirement not met: " + constraints.networkRequirement());
        }

        boolean constraintsMet = violations.isEmpty();
        String reason = constraintsMet ? null : String.join("; ", violations);

        return new ResourceStatus(
                constraintsMet,
                reason,
                batteryPercent,
                isCharging,
                thermalState,
                networkState
        );
    }

    private boolean meetsNetworkRequirement(JobConstraints.NetworkRequirement requirement) {
        return switch (requirement) {
            case NONE -> true;
            case ANY -> networkState != NetworkState.NONE;
            case WIFI -> networkState == NetworkState.WIFI;
            case UNMETERED -> networkState == NetworkState.WIFI || networkState == NetworkState.ETHERNET;
        };
    }

    // Setters for updating resource state (called by platform-specific code)
    
    public void setBatteryPercent(int percent) {
        this.batteryPercent = Math.max(0, Math.min(100, percent));
    }

    public void setCharging(boolean charging) {
        this.isCharging = charging;
    }

    public void setThermalState(int state) {
        this.thermalState = Math.max(0, Math.min(4, state));
    }

    public void setNetworkState(NetworkState state) {
        this.networkState = state != null ? state : NetworkState.NONE;
    }

    // Getters
    
    public int getBatteryPercent() {
        return batteryPercent;
    }

    public boolean isCharging() {
        return isCharging;
    }

    public int getThermalState() {
        return thermalState;
    }

    public NetworkState getNetworkState() {
        return networkState;
    }

    /**
     * Network connection states.
     */
    public enum NetworkState {
        NONE,
        CELLULAR,
        WIFI,
        ETHERNET
    }

    /**
     * Result of resource constraint check.
     */
    public record ResourceStatus(
            boolean constraintsMet,
            String reason,
            int batteryPercent,
            boolean isCharging,
            int thermalState,
            NetworkState networkState
    ) {}
}
