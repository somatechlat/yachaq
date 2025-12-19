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
     * Requires SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET environment variables.
     * 
     * According to Spotify Web API docs: https://developer.spotify.com/documentation/web-api
     * OAuth 2.0 Authorization Code Flow is used for user authorization.
     */
    public static class DefaultSpotifyBridge implements SpotifyBridge {
        
        private static final String SPOTIFY_AUTH_URL = "https://accounts.spotify.com/authorize";
        private static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token";
        private static final String SPOTIFY_API_BASE = "https://api.spotify.com/v1";
        
        private final String clientId;
        private final String clientSecret;
        private final String redirectUri;
        private final java.net.http.HttpClient httpClient;
        
        public DefaultSpotifyBridge() {
            this.clientId = System.getenv("SPOTIFY_CLIENT_ID");
            this.clientSecret = System.getenv("SPOTIFY_CLIENT_SECRET");
            this.redirectUri = System.getenv().getOrDefault("SPOTIFY_REDIRECT_URI", "http://localhost:55080/callback/spotify");
            this.httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException("SPOTIFY_CLIENT_ID environment variable is required");
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalStateException("SPOTIFY_CLIENT_SECRET environment variable is required");
            }
        }

        @Override
        public CompletableFuture<String> initiateOAuth(Set<String> scopes) {
            // Build authorization URL - caller must redirect user to this URL
            String scopeParam = String.join(" ", scopes);
            String state = UUID.randomUUID().toString();
            String authUrl = SPOTIFY_AUTH_URL + 
                    "?client_id=" + clientId +
                    "&response_type=code" +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8) +
                    "&scope=" + java.net.URLEncoder.encode(scopeParam, java.nio.charset.StandardCharsets.UTF_8) +
                    "&state=" + state;
            
            // Return the auth URL - actual code comes from redirect callback
            return CompletableFuture.completedFuture(authUrl);
        }

        @Override
        public CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String body = "grant_type=authorization_code" +
                            "&code=" + authCode +
                            "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8);
                    
                    String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
                    
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(SPOTIFY_TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Authorization", "Basic " + auth)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    
                    java.net.http.HttpResponse<String> response = httpClient.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        return parseTokenResponse(response.body());
                    } else {
                        return new TokenResponse(false, null, null, 0, Set.of(), 
                                "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new TokenResponse(false, null, null, 0, Set.of(), 
                            "EXCHANGE_FAILED", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<TokenResponse> refreshTokens(String refreshToken) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String body = "grant_type=refresh_token&refresh_token=" + refreshToken;
                    String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
                    
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(SPOTIFY_TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Authorization", "Basic " + auth)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    
                    java.net.http.HttpResponse<String> response = httpClient.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        return parseTokenResponse(response.body());
                    } else {
                        return new TokenResponse(false, null, null, 0, Set.of(), 
                                "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new TokenResponse(false, null, null, 0, Set.of(), 
                            "REFRESH_FAILED", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<RecentlyPlayedResponse> getRecentlyPlayed(String accessToken, Long after, int limit) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String url = SPOTIFY_API_BASE + "/me/player/recently-played?limit=" + limit;
                    if (after != null) {
                        url += "&after=" + after;
                    }
                    
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(url))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    
                    java.net.http.HttpResponse<String> response = httpClient.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 429) {
                        return new RecentlyPlayedResponse(List.of(), false, "RATE_LIMITED", "Rate limit exceeded");
                    } else if (response.statusCode() == 200) {
                        return parseRecentlyPlayedResponse(response.body());
                    } else {
                        return new RecentlyPlayedResponse(List.of(), false, 
                                "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new RecentlyPlayedResponse(List.of(), false, "API_ERROR", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<ApiStatus> checkApiStatus(String accessToken) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(SPOTIFY_API_BASE + "/me"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    
                    java.net.http.HttpResponse<String> response = httpClient.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        return new ApiStatus(true, null, null);
                    } else {
                        return new ApiStatus(false, "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new ApiStatus(false, "API_ERROR", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<Void> revokeTokens(String accessToken) {
            // Spotify doesn't have a token revocation endpoint - tokens expire naturally
            return CompletableFuture.completedFuture(null);
        }
        
        private TokenResponse parseTokenResponse(String json) {
            // Simple JSON parsing - in production use Jackson
            try {
                String accessToken = extractJsonString(json, "access_token");
                String refreshToken = extractJsonString(json, "refresh_token");
                long expiresIn = extractJsonLong(json, "expires_in");
                String scope = extractJsonString(json, "scope");
                Set<String> scopes = scope != null ? Set.of(scope.split(" ")) : Set.of();
                
                return new TokenResponse(true, accessToken, refreshToken, expiresIn, scopes, null, null);
            } catch (Exception e) {
                return new TokenResponse(false, null, null, 0, Set.of(), "PARSE_ERROR", e.getMessage());
            }
        }
        
        private RecentlyPlayedResponse parseRecentlyPlayedResponse(String json) {
            // Simplified parsing - returns empty list, real implementation would parse tracks
            // Full implementation would use Jackson to parse the Spotify API response
            boolean hasNext = json.contains("\"next\"") && !json.contains("\"next\":null");
            return new RecentlyPlayedResponse(List.of(), hasNext, null, null);
        }
        
        private String extractJsonString(String json, String key) {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern);
            if (start < 0) return null;
            start += pattern.length();
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : null;
        }
        
        private long extractJsonLong(String json, String key) {
            String pattern = "\"" + key + "\":";
            int start = json.indexOf(pattern);
            if (start < 0) return 0;
            start += pattern.length();
            StringBuilder sb = new StringBuilder();
            while (start < json.length() && Character.isDigit(json.charAt(start))) {
                sb.append(json.charAt(start++));
            }
            return sb.length() > 0 ? Long.parseLong(sb.toString()) : 0;
        }
    }
}
