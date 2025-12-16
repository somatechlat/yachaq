package com.yachaq.node.connector;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Spotify OAuth Connector.
 * Requirement 305.1: OAuth connectors use user-authorized APIs.
 * Requirement 305.2: NO scraping, keylogging, screen reading, or bypassing.
 * Requirement 305.3: Use official OAuth flows.
 * Requirement 305.5: Implement rate-limit backoff and retry logic.
 * 
 * This connector interfaces with Spotify Web API through official OAuth 2.0.
 * Spotify API rate limits: ~180 requests per minute (varies by endpoint).
 */
public class SpotifyConnector extends AbstractConnector {

    public static final String CONNECTOR_ID = "spotify";
    
    // Spotify data types
    public static final String TYPE_RECENTLY_PLAYED = "recently_played";
    public static final String TYPE_TOP_TRACKS = "top_tracks";
    public static final String TYPE_TOP_ARTISTS = "top_artists";
    public static final String TYPE_SAVED_TRACKS = "saved_tracks";
    public static final String TYPE_PLAYLISTS = "playlists";

    // OAuth scopes required
    public static final Set<String> REQUIRED_SCOPES = Set.of(
            "user-read-recently-played",
            "user-top-read",
            "user-library-read",
            "playlist-read-private"
    );

    private static final ConnectorCapabilities CAPABILITIES = ConnectorCapabilities.builder()
            .dataTypes(List.of(
                    TYPE_RECENTLY_PLAYED, TYPE_TOP_TRACKS, TYPE_TOP_ARTISTS,
                    TYPE_SAVED_TRACKS, TYPE_PLAYLISTS
            ))
            .labelFamilies(List.of("media", "entertainment", "music"))
            .supportsIncremental(true)
            .requiresOAuth(true)
            .supportedPlatforms(Set.of("all"))
            .rateLimitPerMinute(180) // Spotify's approximate rate limit
            .maxBatchSize(50) // Spotify's max limit per request
            .build();

    private final SpotifyBridge bridge;
    private OAuthTokens tokens;
    private Instant lastSyncTime;

    /**
     * Creates a Spotify connector with the default bridge.
     */
    public SpotifyConnector() {
        this(new DefaultSpotifyBridge());
    }

    /**
     * Creates a Spotify connector with a custom bridge.
     * Allows injection for testing.
     */
    public SpotifyConnector(SpotifyBridge bridge) {
        super(CONNECTOR_ID, ConnectorType.OAUTH, 5, 1000);
        this.bridge = bridge;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected CompletableFuture<AuthResult> doAuthorize() {
        // Initiate OAuth 2.0 Authorization Code flow
        return bridge.initiateOAuth(REQUIRED_SCOPES)
                .thenCompose(authCode -> bridge.exchangeCodeForTokens(authCode))
                .thenApply(tokenResponse -> {
                    if (tokenResponse.success()) {
                        this.tokens = new OAuthTokens(
                                tokenResponse.accessToken(),
                                tokenResponse.refreshToken(),
                                Instant.now().plusSeconds(tokenResponse.expiresIn())
                        );
                        return AuthResult.success(
                                tokenResponse.scopes(),
                                tokens.expiresAt()
                        );
                    } else {
                        return AuthResult.failure(tokenResponse.errorCode(), tokenResponse.errorMessage());
                    }
                });
    }

    @Override
    protected CompletableFuture<SyncResult> doSync(String sinceCursor) {
        // Ensure tokens are valid
        return ensureValidTokens()
                .thenCompose(valid -> {
                    if (!valid) {
                        return CompletableFuture.completedFuture(
                                SyncResult.failure("TOKEN_EXPIRED", "Unable to refresh access token")
                        );
                    }
                    return fetchRecentlyPlayed(sinceCursor);
                });
    }

    /**
     * Fetches recently played tracks from Spotify.
     */
    private CompletableFuture<SyncResult> fetchRecentlyPlayed(String sinceCursor) {
        // Parse cursor for pagination
        Long afterTimestamp = parseCursor(sinceCursor);
        
        return bridge.getRecentlyPlayed(tokens.accessToken(), afterTimestamp, CAPABILITIES.maxBatchSize())
                .thenApply(response -> {
                    if (response.error() != null) {
                        if (response.error().equals("RATE_LIMITED")) {
                            return SyncResult.rateLimited(
                                    SyncResult.RetryInfo.exponentialBackoff(1, 5, 1000)
                            );
                        }
                        return SyncResult.failure(response.error(), response.errorMessage());
                    }

                    List<SyncItem> items = response.items().stream()
                            .map(this::convertToSyncItem)
                            .toList();

                    if (items.isEmpty()) {
                        return SyncResult.noNewData(sinceCursor);
                    }

                    // Create cursor from latest played_at timestamp
                    String newCursor = items.stream()
                            .map(item -> item.timestamp().toEpochMilli())
                            .max(Long::compareTo)
                            .map(String::valueOf)
                            .orElse(sinceCursor);

                    lastSyncTime = Instant.now();
                    return SyncResult.success(items, newCursor, response.hasNext());
                });
    }

    /**
     * Ensures OAuth tokens are valid, refreshing if needed.
     */
    private CompletableFuture<Boolean> ensureValidTokens() {
        if (tokens == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Refresh if expiring within 5 minutes
        if (tokens.expiresAt().isBefore(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            return bridge.refreshTokens(tokens.refreshToken())
                    .thenApply(response -> {
                        if (response.success()) {
                            this.tokens = new OAuthTokens(
                                    response.accessToken(),
                                    response.refreshToken() != null ? response.refreshToken() : tokens.refreshToken(),
                                    Instant.now().plusSeconds(response.expiresIn())
                            );
                            return true;
                        }
                        return false;
                    });
        }

        return CompletableFuture.completedFuture(true);
    }

    @Override
    protected CompletableFuture<HealthStatus> doHealthcheck() {
        if (tokens == null) {
            return CompletableFuture.completedFuture(HealthStatus.unauthorized());
        }

        return bridge.checkApiStatus(tokens.accessToken())
                .thenApply(status -> {
                    if (status.healthy()) {
                        return HealthStatus.healthy(lastSyncTime, Map.of(
                                "platform", "spotify",
                                "api_version", "v1"
                        ));
                    } else {
                        return HealthStatus.degraded(status.errorCode(), status.errorMessage(), lastSyncTime);
                    }
                });
    }

    @Override
    protected CompletableFuture<Void> doRevokeAuthorization() {
        if (tokens == null) {
            return CompletableFuture.completedFuture(null);
        }

        return bridge.revokeTokens(tokens.accessToken())
                .thenRun(() -> this.tokens = null);
    }

    /**
     * Converts Spotify track to SyncItem.
     */
    private SyncItem convertToSyncItem(SpotifyTrack track) {
        return SyncItem.builder()
                .itemId(track.id())
                .recordType(TYPE_RECENTLY_PLAYED)
                .timestamp(track.playedAt())
                .data(Map.of(
                        "track_name", track.name(),
                        "artist_names", track.artistNames(),
                        "album_name", track.albumName(),
                        "duration_ms", track.durationMs(),
                        "popularity", track.popularity()
                ))
                .metadata(Map.of(
                        "source", "spotify",
                        "track_uri", track.uri()
                ))
                .sourceId(CONNECTOR_ID)
                .checksum(track.checksum())
                .build();
    }

    /**
     * Parses cursor to extract timestamp.
     */
    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Bridge Interface ====================

    /**
     * Bridge interface for Spotify API operations.
     */
    public interface SpotifyBridge {
        CompletableFuture<String> initiateOAuth(Set<String> scopes);
        CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode);
        CompletableFuture<TokenResponse> refreshTokens(String refreshToken);
        CompletableFuture<RecentlyPlayedResponse> getRecentlyPlayed(String accessToken, Long after, int limit);
        CompletableFuture<ApiStatus> checkApiStatus(String accessToken);
        CompletableFuture<Void> revokeTokens(String accessToken);
    }

    /**
     * OAuth tokens.
     */
    public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt) {}

    /**
     * Token exchange response.
     */
    public record TokenResponse(
            boolean success,
            String accessToken,
            String refreshToken,
            long expiresIn,
            Set<String> scopes,
            String errorCode,
            String errorMessage
    ) {}

    /**
     * Recently played tracks response.
     */
    public record RecentlyPlayedResponse(
            List<SpotifyTrack> items,
            boolean hasNext,
            String error,
            String errorMessage
    ) {}

    /**
     * A Spotify track.
     */
    public record SpotifyTrack(
            String id,
            String name,
            List<String> artistNames,
            String albumName,
            int durationMs,
            int popularity,
            String uri,
            Instant playedAt,
            String checksum
    ) {}

    /**
     * API status check result.
     */
    public record ApiStatus(boolean healthy, String errorCode, String errorMessage) {}

    /**
     * Default bridge implementation.
     */
    public static class DefaultSpotifyBridge implements SpotifyBridge {
        @Override
        public CompletableFuture<String> initiateOAuth(Set<String> scopes) {
            // In production: Open browser to Spotify authorization URL
            return CompletableFuture.completedFuture("mock_auth_code");
        }

        @Override
        public CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode) {
            // In production: POST to https://accounts.spotify.com/api/token
            return CompletableFuture.completedFuture(new TokenResponse(
                    true, "mock_access_token", "mock_refresh_token", 3600,
                    REQUIRED_SCOPES, null, null
            ));
        }

        @Override
        public CompletableFuture<TokenResponse> refreshTokens(String refreshToken) {
            // In production: POST to https://accounts.spotify.com/api/token with grant_type=refresh_token
            return CompletableFuture.completedFuture(new TokenResponse(
                    true, "new_access_token", null, 3600,
                    REQUIRED_SCOPES, null, null
            ));
        }

        @Override
        public CompletableFuture<RecentlyPlayedResponse> getRecentlyPlayed(String accessToken, Long after, int limit) {
            // In production: GET https://api.spotify.com/v1/me/player/recently-played
            return CompletableFuture.completedFuture(new RecentlyPlayedResponse(List.of(), false, null, null));
        }

        @Override
        public CompletableFuture<ApiStatus> checkApiStatus(String accessToken) {
            // In production: GET https://api.spotify.com/v1/me
            return CompletableFuture.completedFuture(new ApiStatus(true, null, null));
        }

        @Override
        public CompletableFuture<Void> revokeTokens(String accessToken) {
            // Spotify doesn't have a token revocation endpoint
            return CompletableFuture.completedFuture(null);
        }
    }
}
