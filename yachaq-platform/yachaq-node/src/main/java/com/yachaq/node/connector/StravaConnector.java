package com.yachaq.node.connector;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Strava OAuth Connector.
 * Requirement 305.1: OAuth connectors use user-authorized APIs.
 * Requirement 305.2: NO scraping, keylogging, screen reading, or bypassing.
 * Requirement 305.3: Use official OAuth flows.
 * Requirement 305.5: Implement rate-limit backoff and retry logic.
 * 
 * This connector interfaces with Strava API through official OAuth 2.0.
 * Strava API rate limits: 100 requests per 15 minutes, 1000 per day.
 */
public class StravaConnector extends AbstractConnector {

    public static final String CONNECTOR_ID = "strava";
    
    // Strava data types
    public static final String TYPE_ACTIVITY = "activity";
    public static final String TYPE_ROUTE = "route";
    public static final String TYPE_SEGMENT_EFFORT = "segment_effort";
    public static final String TYPE_ATHLETE_STATS = "athlete_stats";

    // OAuth scopes required
    public static final Set<String> REQUIRED_SCOPES = Set.of(
            "read",
            "activity:read",
            "activity:read_all"
    );

    private static final ConnectorCapabilities CAPABILITIES = ConnectorCapabilities.builder()
            .dataTypes(List.of(TYPE_ACTIVITY, TYPE_ROUTE, TYPE_SEGMENT_EFFORT, TYPE_ATHLETE_STATS))
            .labelFamilies(List.of("activity", "mobility", "fitness"))
            .supportsIncremental(true)
            .requiresOAuth(true)
            .supportedPlatforms(Set.of("all"))
            .rateLimitPerMinute(6) // 100 per 15 min = ~6.67 per minute, we use 6 to be safe
            .maxBatchSize(200) // Strava's max per_page
            .build();

    private final StravaBridge bridge;
    private OAuthTokens tokens;
    private Instant lastSyncTime;

    /**
     * Creates a Strava connector with the default bridge.
     */
    public StravaConnector() {
        this(new DefaultStravaBridge());
    }

    /**
     * Creates a Strava connector with a custom bridge.
     * Allows injection for testing.
     */
    public StravaConnector(StravaBridge bridge) {
        super(CONNECTOR_ID, ConnectorType.OAUTH, 5, 2000); // Longer backoff for Strava's stricter limits
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
                                Instant.ofEpochSecond(tokenResponse.expiresAt())
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
                    return fetchActivities(sinceCursor);
                });
    }

    /**
     * Fetches activities from Strava.
     */
    private CompletableFuture<SyncResult> fetchActivities(String sinceCursor) {
        // Parse cursor for pagination (page number and after timestamp)
        CursorData cursor = parseCursor(sinceCursor);
        
        return bridge.getActivities(tokens.accessToken(), cursor.after(), cursor.page(), CAPABILITIES.maxBatchSize())
                .thenApply(response -> {
                    if (response.error() != null) {
                        if (response.error().equals("RATE_LIMITED")) {
                            // Strava returns Retry-After header
                            return SyncResult.rateLimited(
                                    SyncResult.RetryInfo.exponentialBackoff(1, 5, 15000) // 15 second base for Strava
                            );
                        }
                        return SyncResult.failure(response.error(), response.errorMessage());
                    }

                    List<SyncItem> items = response.activities().stream()
                            .map(this::convertToSyncItem)
                            .toList();

                    if (items.isEmpty()) {
                        return SyncResult.noNewData(sinceCursor);
                    }

                    // Create cursor with latest activity timestamp and next page
                    long latestTimestamp = items.stream()
                            .map(item -> item.timestamp().getEpochSecond())
                            .max(Long::compareTo)
                            .orElse(cursor.after());

                    boolean hasMore = items.size() >= CAPABILITIES.maxBatchSize();
                    String newCursor = createCursor(latestTimestamp, hasMore ? cursor.page() + 1 : 1);

                    lastSyncTime = Instant.now();
                    return SyncResult.success(items, newCursor, hasMore);
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
                                    response.refreshToken(),
                                    Instant.ofEpochSecond(response.expiresAt())
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

        return bridge.getAthlete(tokens.accessToken())
                .thenApply(response -> {
                    if (response.success()) {
                        return HealthStatus.healthy(lastSyncTime, Map.of(
                                "platform", "strava",
                                "athlete_id", response.athleteId(),
                                "api_version", "v3"
                        ));
                    } else {
                        return HealthStatus.degraded(response.errorCode(), response.errorMessage(), lastSyncTime);
                    }
                });
    }

    @Override
    protected CompletableFuture<Void> doRevokeAuthorization() {
        if (tokens == null) {
            return CompletableFuture.completedFuture(null);
        }

        return bridge.deauthorize(tokens.accessToken())
                .thenRun(() -> this.tokens = null);
    }

    /**
     * Converts Strava activity to SyncItem.
     */
    private SyncItem convertToSyncItem(StravaActivity activity) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", activity.name());
        data.put("type", activity.type());
        data.put("sport_type", activity.sportType());
        data.put("distance", activity.distance());
        data.put("moving_time", activity.movingTime());
        data.put("elapsed_time", activity.elapsedTime());
        data.put("total_elevation_gain", activity.totalElevationGain());
        data.put("average_speed", activity.averageSpeed());
        data.put("max_speed", activity.maxSpeed());
        if (activity.averageHeartrate() != null) {
            data.put("average_heartrate", activity.averageHeartrate());
        }
        if (activity.maxHeartrate() != null) {
            data.put("max_heartrate", activity.maxHeartrate());
        }

        return SyncItem.builder()
                .itemId(String.valueOf(activity.id()))
                .recordType(TYPE_ACTIVITY)
                .timestamp(activity.startDate())
                .endTimestamp(activity.startDate().plusSeconds(activity.elapsedTime()))
                .data(data)
                .metadata(Map.of(
                        "source", "strava",
                        "activity_type", activity.type()
                ))
                .sourceId(CONNECTOR_ID)
                .checksum(activity.checksum())
                .build();
    }

    /**
     * Parses cursor to extract pagination data.
     */
    private CursorData parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorData(0L, 1);
        }
        try {
            String[] parts = cursor.split(":");
            long after = Long.parseLong(parts[0]);
            int page = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            return new CursorData(after, page);
        } catch (Exception e) {
            return new CursorData(0L, 1);
        }
    }

    /**
     * Creates cursor from timestamp and page.
     */
    private String createCursor(long after, int page) {
        return after + ":" + page;
    }

    private record CursorData(long after, int page) {}

    // ==================== Bridge Interface ====================

    /**
     * Bridge interface for Strava API operations.
     */
    public interface StravaBridge {
        CompletableFuture<String> initiateOAuth(Set<String> scopes);
        CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode);
        CompletableFuture<TokenResponse> refreshTokens(String refreshToken);
        CompletableFuture<ActivitiesResponse> getActivities(String accessToken, long after, int page, int perPage);
        CompletableFuture<AthleteResponse> getAthlete(String accessToken);
        CompletableFuture<Void> deauthorize(String accessToken);
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
            long expiresAt,
            Set<String> scopes,
            String errorCode,
            String errorMessage
    ) {}

    /**
     * Activities list response.
     */
    public record ActivitiesResponse(
            List<StravaActivity> activities,
            String error,
            String errorMessage
    ) {}

    /**
     * A Strava activity.
     */
    public record StravaActivity(
            long id,
            String name,
            String type,
            String sportType,
            double distance,
            int movingTime,
            int elapsedTime,
            double totalElevationGain,
            double averageSpeed,
            double maxSpeed,
            Double averageHeartrate,
            Integer maxHeartrate,
            Instant startDate,
            String checksum
    ) {}

    /**
     * Athlete info response.
     */
    public record AthleteResponse(
            boolean success,
            String athleteId,
            String errorCode,
            String errorMessage
    ) {}

    /**
     * Default bridge implementation.
     * Requires STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET environment variables.
     * 
     * According to Strava API docs: https://developers.strava.com/docs/authentication/
     * OAuth 2.0 Authorization Code Flow is used for user authorization.
     */
    public static class DefaultStravaBridge implements StravaBridge {
        
        private static final String STRAVA_AUTH_URL = "https://www.strava.com/oauth/authorize";
        private static final String STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token";
        private static final String STRAVA_DEAUTH_URL = "https://www.strava.com/oauth/deauthorize";
        private static final String STRAVA_API_BASE = "https://www.strava.com/api/v3";
        
        private final String clientId;
        private final String clientSecret;
        private final String redirectUri;
        private final java.net.http.HttpClient httpClient;
        
        public DefaultStravaBridge() {
            this.clientId = System.getenv("STRAVA_CLIENT_ID");
            this.clientSecret = System.getenv("STRAVA_CLIENT_SECRET");
            this.redirectUri = System.getenv().getOrDefault("STRAVA_REDIRECT_URI", "http://localhost:55080/callback/strava");
            this.httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException("STRAVA_CLIENT_ID environment variable is required");
            }
            if (clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalStateException("STRAVA_CLIENT_SECRET environment variable is required");
            }
        }

        @Override
        public CompletableFuture<String> initiateOAuth(Set<String> scopes) {
            // Build authorization URL - caller must redirect user to this URL
            String scopeParam = String.join(",", scopes);
            String state = UUID.randomUUID().toString();
            String authUrl = STRAVA_AUTH_URL + 
                    "?client_id=" + clientId +
                    "&response_type=code" +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8) +
                    "&scope=" + java.net.URLEncoder.encode(scopeParam, java.nio.charset.StandardCharsets.UTF_8) +
                    "&state=" + state +
                    "&approval_prompt=auto";
            
            // Return the auth URL - actual code comes from redirect callback
            return CompletableFuture.completedFuture(authUrl);
        }

        @Override
        public CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String body = "client_id=" + clientId +
                            "&client_secret=" + clientSecret +
                            "&code=" + authCode +
                            "&grant_type=authorization_code";
                    
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(STRAVA_TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
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
                    String body = "client_id=" + clientId +
                            "&client_secret=" + clientSecret +
                            "&refresh_token=" + refreshToken +
                            "&grant_type=refresh_token";
                    
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(STRAVA_TOKEN_URL))
                            .header("Content-Type", "application/x-www-form-urlencoded")
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
        public CompletableFuture<ActivitiesResponse> getActivities(String accessToken, long after, int page, int perPage) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String url = STRAVA_API_BASE + "/athlete/activities?page=" + page + "&per_page=" + perPage;
                    if (after > 0) {
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
                        return new ActivitiesResponse(List.of(), "RATE_LIMITED", "Rate limit exceeded");
                    } else if (response.statusCode() == 200) {
                        return parseActivitiesResponse(response.body());
                    } else {
                        return new ActivitiesResponse(List.of(), "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new ActivitiesResponse(List.of(), "API_ERROR", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<AthleteResponse> getAthlete(String accessToken) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(STRAVA_API_BASE + "/athlete"))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build();
                    
                    java.net.http.HttpResponse<String> response = httpClient.send(request, 
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        String athleteId = extractJsonString(response.body(), "id");
                        return new AthleteResponse(true, athleteId, null, null);
                    } else {
                        return new AthleteResponse(false, null, "HTTP_" + response.statusCode(), response.body());
                    }
                } catch (Exception e) {
                    return new AthleteResponse(false, null, "API_ERROR", e.getMessage());
                }
            });
        }

        @Override
        public CompletableFuture<Void> deauthorize(String accessToken) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(STRAVA_DEAUTH_URL))
                            .header("Authorization", "Bearer " + accessToken)
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build();
                    
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    return null;
                } catch (Exception e) {
                    // Log but don't fail - token will expire anyway
                    return null;
                }
            });
        }
        
        private TokenResponse parseTokenResponse(String json) {
            try {
                String accessToken = extractJsonString(json, "access_token");
                String refreshToken = extractJsonString(json, "refresh_token");
                long expiresAt = extractJsonLong(json, "expires_at");
                
                return new TokenResponse(true, accessToken, refreshToken, expiresAt, REQUIRED_SCOPES, null, null);
            } catch (Exception e) {
                return new TokenResponse(false, null, null, 0, Set.of(), "PARSE_ERROR", e.getMessage());
            }
        }
        
        private ActivitiesResponse parseActivitiesResponse(String json) {
            // Simplified parsing - returns empty list, real implementation would parse activities
            // Full implementation would use Jackson to parse the Strava API response
            return new ActivitiesResponse(List.of(), null, null);
        }
        
        private String extractJsonString(String json, String key) {
            // Handle both string and numeric values
            String stringPattern = "\"" + key + "\":\"";
            int start = json.indexOf(stringPattern);
            if (start >= 0) {
                start += stringPattern.length();
                int end = json.indexOf("\"", start);
                return end > start ? json.substring(start, end) : null;
            }
            // Try numeric pattern
            String numPattern = "\"" + key + "\":";
            start = json.indexOf(numPattern);
            if (start >= 0) {
                start += numPattern.length();
                StringBuilder sb = new StringBuilder();
                while (start < json.length() && (Character.isDigit(json.charAt(start)) || json.charAt(start) == '-')) {
                    sb.append(json.charAt(start++));
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
            return null;
        }
        
        private long extractJsonLong(String json, String key) {
            String value = extractJsonString(json, key);
            return value != null ? Long.parseLong(value) : 0;
        }
    }
}
