package com.yachaq.node.connector;

import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Connector Framework.
 * 
 * **Feature: yachaq-platform, Property 61: Connector Interface Contract**
 * **Validates: Requirements 305.1, 305.2, 305.3, 305.4, 305.5**
 */
class ConnectorPropertyTest {

    // ==================== Property 61: Connector Interface Contract ====================

    @Property(tries = 100)
    void property61_allConnectorsExposeCapabilities(@ForAll("connectors") Connector connector) {
        // Property: Every connector must expose its capabilities
        // Validates: Requirement 305.1 - Expose capabilities including data types and label families
        
        ConnectorCapabilities capabilities = connector.capabilities();
        
        assertThat(capabilities).isNotNull();
        assertThat(capabilities.dataTypes()).isNotNull();
        assertThat(capabilities.labelFamilies()).isNotNull();
        assertThat(capabilities.supportedPlatforms()).isNotNull();
    }

    @Property(tries = 100)
    void property61_connectorCapabilitiesAreImmutable(@ForAll("connectors") Connector connector) {
        // Property: Connector capabilities must be immutable
        
        ConnectorCapabilities cap1 = connector.capabilities();
        ConnectorCapabilities cap2 = connector.capabilities();
        
        // Same instance or equal values
        assertThat(cap1.dataTypes()).isEqualTo(cap2.dataTypes());
        assertThat(cap1.labelFamilies()).isEqualTo(cap2.labelFamilies());
        
        // Attempting to modify should throw
        assertThatThrownBy(() -> cap1.dataTypes().add("hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Property(tries = 100)
    void property61_unauthorizedConnectorCannotSync(@ForAll("connectors") Connector connector) {
        // Property: Unauthorized connectors must fail sync
        // Validates: Requirement 305.3 - Execute appropriate OAuth or OS permission flow
        
        // Fresh connector is not authorized
        assertThat(connector.isAuthorized()).isFalse();
        
        // Sync should fail
        SyncResult result = connector.sync(null).join();
        
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Property(tries = 50)
    void property61_authorizedConnectorCanSync(@ForAll("authorizableConnectors") Connector connector) {
        // Property: Authorized connectors can sync
        
        // Authorize first
        AuthResult authResult = connector.authorize().join();
        assertThat(authResult.success()).isTrue();
        assertThat(connector.isAuthorized()).isTrue();
        
        // Sync should succeed (or return no data)
        SyncResult result = connector.sync(null).join();
        
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isIn(
                SyncResult.SyncStatus.COMPLETED,
                SyncResult.SyncStatus.NO_NEW_DATA
        );
    }

    @Property(tries = 50)
    void property61_syncReturnsValidCursor(@ForAll("authorizableConnectors") Connector connector) {
        // Property: Sync must return a valid cursor for incremental sync
        // Validates: Requirement 305.4 - Support incremental sync with cursor-based pagination
        
        connector.authorize().join();
        
        SyncResult result = connector.sync(null).join();
        
        if (result.success() && result.status() != SyncResult.SyncStatus.NO_NEW_DATA) {
            // If there are items, there should be a cursor
            if (!result.items().isEmpty()) {
                assertThat(result.getNextCursor()).isPresent();
            }
        }
    }

    @Property(tries = 50)
    void property61_healthcheckReflectsAuthState(@ForAll("connectors") Connector connector) {
        // Property: Healthcheck must reflect authorization state
        
        // Unauthorized connector
        HealthStatus status1 = connector.healthcheck().join();
        assertThat(status1.status()).isEqualTo(HealthStatus.Status.UNAUTHORIZED);
        
        // After authorization
        connector.authorize().join();
        HealthStatus status2 = connector.healthcheck().join();
        assertThat(status2.status()).isIn(
                HealthStatus.Status.HEALTHY,
                HealthStatus.Status.DEGRADED
        );
    }

    // ==================== Property 62: Rate Limit Backoff ====================

    @Property(tries = 50)
    void property62_rateLimitedResponseIncludesRetryInfo() {
        // Property: Rate-limited responses must include retry information
        // Validates: Requirement 305.5 - Implement backoff and retry logic
        
        SyncResult.RetryInfo retryInfo = SyncResult.RetryInfo.exponentialBackoff(1, 5, 1000);
        SyncResult rateLimited = SyncResult.rateLimited(retryInfo);
        
        assertThat(rateLimited.success()).isFalse();
        assertThat(rateLimited.status()).isEqualTo(SyncResult.SyncStatus.RATE_LIMITED);
        assertThat(rateLimited.getRetryInfo()).isPresent();
        assertThat(rateLimited.getRetryInfo().get().retryAfter()).isAfter(Instant.now());
    }

    @Property(tries = 100)
    void property62_exponentialBackoffIncreasesDelay(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10) int attempt,
            @ForAll @net.jqwik.api.constraints.LongRange(min = 100, max = 5000) long baseDelay) {
        // Property: Exponential backoff delay increases with each attempt
        
        SyncResult.RetryInfo info1 = SyncResult.RetryInfo.exponentialBackoff(attempt, 10, baseDelay);
        SyncResult.RetryInfo info2 = SyncResult.RetryInfo.exponentialBackoff(attempt + 1, 10, baseDelay);
        
        // Later attempts should have longer backoff (up to cap)
        if (info1.backoffMillis() < 300_000) { // Below cap
            assertThat(info2.backoffMillis()).isGreaterThanOrEqualTo(info1.backoffMillis());
        }
    }

    @Property(tries = 100)
    void property62_backoffIsCappedAt5Minutes(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 20) int attempt) {
        // Property: Backoff delay is capped at 5 minutes
        
        SyncResult.RetryInfo info = SyncResult.RetryInfo.exponentialBackoff(attempt, 20, 1000);
        
        assertThat(info.backoffMillis()).isLessThanOrEqualTo(300_000);
    }

    // ==================== Property 63: Connector Type Constraints ====================

    @Property(tries = 50)
    void property63_frameworkConnectorsDoNotRequireOAuth() {
        // Property: Framework connectors use OS permissions, not OAuth
        // Validates: Requirement 305.1 - Framework connectors use OS-level APIs
        
        HealthKitConnector healthKit = new HealthKitConnector();
        HealthConnectConnector healthConnect = new HealthConnectConnector();
        
        assertThat(healthKit.getType()).isEqualTo(ConnectorType.FRAMEWORK);
        assertThat(healthKit.capabilities().requiresOAuth()).isFalse();
        
        assertThat(healthConnect.getType()).isEqualTo(ConnectorType.FRAMEWORK);
        assertThat(healthConnect.capabilities().requiresOAuth()).isFalse();
    }

    @Test
    void property63_oauthConnectorsRequireOAuth() {
        // Property: OAuth connectors require OAuth authorization
        // Validates: Requirement 305.1 - OAuth connectors use user-authorized APIs
        
        // Skip if OAuth credentials not available (per Vibe Coding Rules - no mocks)
        org.junit.jupiter.api.Assumptions.assumeTrue(
                hasSpotifyCredentials() || hasStravaCredentials(),
                "Skipping OAuth connector test - no OAuth credentials available"
        );
        
        if (hasSpotifyCredentials()) {
            SpotifyConnector spotify = new SpotifyConnector();
            assertThat(spotify.getType()).isEqualTo(ConnectorType.OAUTH);
            assertThat(spotify.capabilities().requiresOAuth()).isTrue();
        }
        
        if (hasStravaCredentials()) {
            StravaConnector strava = new StravaConnector();
            assertThat(strava.getType()).isEqualTo(ConnectorType.OAUTH);
            assertThat(strava.capabilities().requiresOAuth()).isTrue();
        }
    }

    // ==================== Property 64: SyncItem Integrity ====================

    @Property(tries = 100)
    void property64_syncItemRequiresValidFields(
            @ForAll("itemIds") String itemId,
            @ForAll("recordTypes") String recordType) {
        // Property: SyncItem must have valid required fields
        
        Instant now = Instant.now();
        
        SyncItem item = SyncItem.builder()
                .itemId(itemId)
                .recordType(recordType)
                .timestamp(now)
                .sourceId("test")
                .build();
        
        assertThat(item.itemId()).isEqualTo(itemId);
        assertThat(item.recordType()).isEqualTo(recordType);
        assertThat(item.timestamp()).isEqualTo(now);
    }

    @Property(tries = 50)
    void property64_syncItemRejectsNullId() {
        // Property: SyncItem must reject null item ID
        
        assertThatThrownBy(() -> SyncItem.builder()
                .itemId(null)
                .recordType("test")
                .timestamp(Instant.now())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 50)
    void property64_syncItemRejectsBlankId() {
        // Property: SyncItem must reject blank item ID
        
        assertThatThrownBy(() -> SyncItem.builder()
                .itemId("   ")
                .recordType("test")
                .timestamp(Instant.now())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 100)
    void property64_syncItemDurationCalculation(
            @ForAll @net.jqwik.api.constraints.LongRange(min = 60, max = 86400) long durationSeconds) {
        // Property: SyncItem duration calculation is correct
        
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSeconds);
        
        SyncItem item = SyncItem.builder()
                .itemId("test-id")
                .recordType("test")
                .timestamp(start)
                .endTimestamp(end)
                .sourceId("test")
                .build();
        
        assertThat(item.getDurationSeconds()).isPresent();
        assertThat(item.getDurationSeconds().get()).isEqualTo(durationSeconds);
    }

    // ==================== Property 65: AuthResult States ====================

    @Property(tries = 100)
    void property65_successfulAuthHasGrantedScopes(
            @ForAll("scopeSets") Set<String> scopes) {
        // Property: Successful auth must have granted scopes
        
        Assume.that(!scopes.isEmpty());
        
        AuthResult result = AuthResult.success(scopes, Instant.now().plus(1, ChronoUnit.HOURS));
        
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(AuthResult.AuthStatus.AUTHORIZED);
        assertThat(result.grantedScopes()).containsAll(scopes);
        assertThat(result.deniedScopes()).isEmpty();
    }

    @Property(tries = 100)
    void property65_partialAuthHasBothGrantedAndDenied(
            @ForAll("scopeSets") Set<String> granted,
            @ForAll("scopeSets") Set<String> denied) {
        // Property: Partial auth has both granted and denied scopes
        
        Assume.that(!granted.isEmpty());
        Assume.that(!denied.isEmpty());
        Assume.that(Collections.disjoint(granted, denied)); // No overlap
        
        AuthResult result = AuthResult.partial(granted, denied, Instant.now().plus(1, ChronoUnit.HOURS));
        
        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(AuthResult.AuthStatus.PARTIAL);
        assertThat(result.grantedScopes()).containsAll(granted);
        assertThat(result.deniedScopes()).containsAll(denied);
    }

    @Property(tries = 50)
    void property65_expiredAuthIsDetected() {
        // Property: Expired auth is correctly detected
        
        AuthResult result = AuthResult.success(
                Set.of("read"),
                Instant.now().minus(1, ChronoUnit.HOURS) // Already expired
        );
        
        assertThat(result.isExpired()).isTrue();
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<Connector> connectors() {
        // Only include OAuth connectors if credentials are available
        List<Connector> available = new ArrayList<>();
        available.add(new HealthKitConnector());
        available.add(new HealthConnectConnector());
        
        // OAuth connectors require real credentials per Vibe Coding Rules
        if (hasSpotifyCredentials()) {
            available.add(new SpotifyConnector());
        }
        if (hasStravaCredentials()) {
            available.add(new StravaConnector());
        }
        
        return Arbitraries.of(available.toArray(new Connector[0]));
    }

    @Provide
    Arbitrary<Connector> authorizableConnectors() {
        // Only include OAuth connectors if credentials are available
        List<Connector> available = new ArrayList<>();
        available.add(new HealthKitConnector());
        available.add(new HealthConnectConnector());
        
        // OAuth connectors require real credentials per Vibe Coding Rules
        if (hasSpotifyCredentials()) {
            available.add(new SpotifyConnector());
        }
        if (hasStravaCredentials()) {
            available.add(new StravaConnector());
        }
        
        return Arbitraries.of(available.toArray(new Connector[0]));
    }
    
    private static boolean hasSpotifyCredentials() {
        String clientId = System.getenv("SPOTIFY_CLIENT_ID");
        String clientSecret = System.getenv("SPOTIFY_CLIENT_SECRET");
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
    
    private static boolean hasStravaCredentials() {
        String clientId = System.getenv("STRAVA_CLIENT_ID");
        String clientSecret = System.getenv("STRAVA_CLIENT_SECRET");
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    @Provide
    Arbitrary<String> itemIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(50);
    }

    @Provide
    Arbitrary<String> recordTypes() {
        return Arbitraries.of(
                "sleep", "workout", "heart_rate", "steps", "activity",
                "track", "playlist", "route"
        );
    }

    @Provide
    Arbitrary<Set<String>> scopeSets() {
        return Arbitraries.of(
                "read", "write", "activity:read", "user-read-recently-played",
                "health", "location", "contacts"
        ).set().ofMinSize(1).ofMaxSize(5);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void connectorCapabilities_rejectsNegativeRateLimit() {
        assertThatThrownBy(() -> ConnectorCapabilities.builder()
                .rateLimitPerMinute(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectorCapabilities_rejectsNegativeBatchSize() {
        assertThatThrownBy(() -> ConnectorCapabilities.builder()
                .maxBatchSize(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abstractConnector_rejectsNullId() {
        assertThatThrownBy(() -> new TestConnector(null, ConnectorType.FRAMEWORK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abstractConnector_rejectsBlankId() {
        assertThatThrownBy(() -> new TestConnector("  ", ConnectorType.FRAMEWORK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abstractConnector_rejectsNullType() {
        assertThatThrownBy(() -> new TestConnector("test", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void healthStatus_healthyIsOperational() {
        HealthStatus status = HealthStatus.healthy(Instant.now());
        assertThat(status.isOperational()).isTrue();
    }

    @Test
    void healthStatus_degradedIsOperational() {
        HealthStatus status = HealthStatus.degraded("WARN", "Warning", Instant.now());
        assertThat(status.isOperational()).isTrue();
    }

    @Test
    void healthStatus_unhealthyIsNotOperational() {
        HealthStatus status = HealthStatus.unhealthy("ERROR", "Error");
        assertThat(status.isOperational()).isFalse();
    }

    @Test
    void syncResult_successHasItems() {
        List<SyncItem> items = List.of(
                SyncItem.builder()
                        .itemId("1")
                        .recordType("test")
                        .timestamp(Instant.now())
                        .sourceId("test")
                        .build()
        );
        
        SyncResult result = SyncResult.success(items, "cursor", false);
        
        assertThat(result.success()).isTrue();
        assertThat(result.items()).hasSize(1);
        assertThat(result.getNextCursor()).contains("cursor");
    }

    @Test
    void syncResult_failureHasNoItems() {
        SyncResult result = SyncResult.failure("ERROR", "Something went wrong");
        
        assertThat(result.success()).isFalse();
        assertThat(result.items()).isEmpty();
        assertThat(result.errorCode()).isEqualTo("ERROR");
    }

    // ==================== Test Connector ====================

    /**
     * Test connector for edge case testing.
     */
    private static class TestConnector extends AbstractConnector {
        public TestConnector(String id, ConnectorType type) {
            super(id, type);
        }

        @Override
        public ConnectorCapabilities capabilities() {
            return ConnectorCapabilities.builder().build();
        }

        @Override
        protected CompletableFuture<AuthResult> doAuthorize() {
            return CompletableFuture.completedFuture(AuthResult.success(Set.of("test"), Instant.now().plusSeconds(3600)));
        }

        @Override
        protected CompletableFuture<SyncResult> doSync(String sinceCursor) {
            return CompletableFuture.completedFuture(SyncResult.noNewData(sinceCursor));
        }

        @Override
        protected CompletableFuture<HealthStatus> doHealthcheck() {
            return CompletableFuture.completedFuture(HealthStatus.healthy(Instant.now()));
        }

        @Override
        protected CompletableFuture<Void> doRevokeAuthorization() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
