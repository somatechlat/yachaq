package com.yachaq.node.connector;

import java.util.concurrent.CompletableFuture;

/**
 * Standard interface for data acquisition from various sources.
 * Implements the Connector Framework for Phone-as-Node architecture.
 * 
 * Validates: Requirements 305.1, 305.2, 305.3, 305.4, 305.5
 * 
 * Connector Types:
 * - Framework: OS data frameworks (HealthKit, Health Connect)
 * - OAuth: User-authorized APIs (Spotify, Strava)
 * - Import: User-provided exports (Takeout, Telegram, WhatsApp)
 * 
 * Security Constraints (305.2):
 * - NO scraping
 * - NO keylogging
 * - NO screen reading
 * - NO bypassing other apps
 */
public interface Connector {

    /**
     * Returns the unique identifier for this connector.
     * 
     * @return Connector ID (e.g., "healthkit", "spotify", "strava")
     */
    String getId();

    /**
     * Returns the connector type.
     * 
     * @return ConnectorType (FRAMEWORK, OAUTH, or IMPORT)
     */
    ConnectorType getType();

    /**
     * Returns the capabilities of this connector.
     * Requirement 305.1: Expose capabilities including data types and label families.
     * 
     * @return ConnectorCapabilities describing what this connector can provide
     */
    ConnectorCapabilities capabilities();

    /**
     * Initiates authorization flow for this connector.
     * Requirement 305.3: Execute appropriate OAuth or OS permission flow.
     * 
     * @return CompletableFuture with AuthResult indicating success/failure
     */
    CompletableFuture<AuthResult> authorize();

    /**
     * Synchronizes data from the source using incremental cursor.
     * Requirement 305.4: Support incremental sync with cursor-based pagination.
     * 
     * @param sinceCursor Cursor from previous sync (null for initial sync)
     * @return CompletableFuture with SyncResult containing data and next cursor
     */
    CompletableFuture<SyncResult> sync(String sinceCursor);

    /**
     * Checks the health status of this connector.
     * 
     * @return CompletableFuture with HealthStatus
     */
    CompletableFuture<HealthStatus> healthcheck();

    /**
     * Returns whether this connector is currently authorized.
     * 
     * @return true if authorized and ready to sync
     */
    boolean isAuthorized();

    /**
     * Revokes authorization for this connector.
     * 
     * @return CompletableFuture completing when revocation is done
     */
    CompletableFuture<Void> revokeAuthorization();
}
