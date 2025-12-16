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
     */
    public static class DefaultStravaBridge implements StravaBridge {
        @Override
        public CompletableFuture<String> initiateOAuth(Set<String> scopes) {
            // In production: Open browser to https://www.strava.com/oauth/authorize
            return CompletableFuture.completedFuture("mock_auth_code");
        }

        @Override
        public CompletableFuture<TokenResponse> exchangeCodeForTokens(String authCode) {
            // In production: POST to https://www.strava.com/oauth/token
            return CompletableFuture.completedFuture(new TokenResponse(
                    true, "mock_access_token", "mock_refresh_token",
                    Instant.now().plusSeconds(21600).getEpochSecond(), // 6 hours
                    REQUIRED_SCOPES, null, null
            ));
        }

        @Override
        public CompletableFuture<TokenResponse> refreshTokens(String refreshToken) {
            // In production: POST to https://www.strava.com/oauth/token with grant_type=refresh_token
            return CompletableFuture.completedFuture(new TokenResponse(
                    true, "new_access_token", "new_refresh_token",
                    Instant.now().plusSeconds(21600).getEpochSecond(),
                    REQUIRED_SCOPES, null, null
            ));
        }

        @Override
        public CompletableFuture<ActivitiesResponse> getActivities(String accessToken, long after, int page, int perPage) {
            // In production: GET https://www.strava.com/api/v3/athlete/activities
            return CompletableFuture.completedFuture(new ActivitiesResponse(List.of(), null, null));
        }

        @Override
        public CompletableFuture<AthleteResponse> getAthlete(String accessToken) {
            // In production: GET https://www.strava.com/api/v3/athlete
            return CompletableFuture.completedFuture(new AthleteResponse(true, "12345", null, null));
        }

        @Override
        public CompletableFuture<Void> deauthorize(String accessToken) {
            // In production: POST to https://www.strava.com/oauth/deauthorize
            return CompletableFuture.completedFuture(null);
        }
    }
}
