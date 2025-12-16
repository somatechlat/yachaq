package com.yachaq.node.connector;

/**
 * Types of connectors supported by the Connector Framework.
 * Requirement 305.1: Support three types of connectors.
 */
public enum ConnectorType {
    /**
     * Framework connectors use OS-level APIs.
     * Examples: HealthKit (iOS), Health Connect (Android)
     */
    FRAMEWORK,

    /**
     * OAuth connectors use user-authorized third-party APIs.
     * Examples: Spotify, Strava
     */
    OAUTH,

    /**
     * Import connectors process user-provided data exports.
     * Examples: Google Takeout, WhatsApp export, Telegram export
     */
    IMPORT
}
