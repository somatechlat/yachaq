package com.yachaq.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * YACHAQ Platform SDK Client - Language-agnostic HTTP client for the Requester API.
 * 
 * This client provides a clean interface for interacting with the YACHAQ platform,
 * supporting request creation, capsule verification, dispute resolution, and analytics.
 * 
 * Security: All requests are authenticated via OAuth2 Bearer tokens.
 * Performance: Connection pooling and configurable timeouts.
 * 
 * Validates: Requirements 352.1, 352.2, 352.3
 */
public class YachaqClient implements AutoCloseable {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String accessToken;

    private YachaqClient(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.accessToken = builder.accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(builder.connectTimeoutSeconds))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== Authentication ====================

    /**
     * Sets the access token for authenticated requests.
     */
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    /**
     * Authenticates using API key and returns an access token.
     */
    public SDKResponse<AuthResponse> authenticate() throws IOException, InterruptedException {
        Map<String, String> body = Map.of("apiKey", apiKey);
        return post("/v1/auth/token", body, new TypeReference<SDKResponse<AuthResponse>>() {});
    }

    // ==================== Request Management ====================

    /**
     * Creates a new data request.
     * Requirement 352.1: Provide programmatic request creation.
     */
    public SDKResponse<RequestCreationResult> createRequest(RequestConfig config) 
            throws IOException, InterruptedException {
        return post("/v1/requests", config, new TypeReference<SDKResponse<RequestCreationResult>>() {});
    }

    /**
     * Creates multiple requests in batch.
     */
    public SDKResponse<List<RequestCreationResult>> createRequestsBatch(List<RequestConfig> configs) 
            throws IOException, InterruptedException {
        return post("/v1/requests/batch", configs, 
                new TypeReference<SDKResponse<List<RequestCreationResult>>>() {});
    }

    /**
     * Gets available request templates.
     */
    public SDKResponse<List<RequestTemplate>> getTemplates(String category) 
            throws IOException, InterruptedException {
        String path = category != null ? "/v1/templates?category=" + category : "/v1/templates";
        return get(path, new TypeReference<SDKResponse<List<RequestTemplate>>>() {});
    }

    /**
     * Validates ODX criteria without creating a request.
     */
    public SDKResponse<CriteriaValidationResult> validateCriteria(OdxCriteria criteria) 
            throws IOException, InterruptedException {
        return post("/v1/criteria/validate", criteria, 
                new TypeReference<SDKResponse<CriteriaValidationResult>>() {});
    }

    /**
     * Gets request status.
     */
    public SDKResponse<RequestStatus> getRequestStatus(String requestId) 
            throws IOException, InterruptedException {
        return get("/v1/requests/" + requestId + "/status", 
                new TypeReference<SDKResponse<RequestStatus>>() {});
    }


    // ==================== Capsule Verification ====================

    /**
     * Verifies a capsule's signature.
     * Requirement 352.2: Provide verification functions.
     */
    public SDKResponse<SignatureVerificationResult> verifySignature(CapsuleData capsule) 
            throws IOException, InterruptedException {
        return post("/v1/capsules/verify/signature", capsule, 
                new TypeReference<SDKResponse<SignatureVerificationResult>>() {});
    }

    /**
     * Validates a capsule against its schema.
     */
    public SDKResponse<SchemaValidationResult> validateSchema(CapsuleData capsule, CapsuleSchema schema) 
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("capsule", capsule, "schema", schema);
        return post("/v1/capsules/verify/schema", body, 
                new TypeReference<SDKResponse<SchemaValidationResult>>() {});
    }

    /**
     * Verifies hash receipts for a capsule.
     */
    public SDKResponse<HashReceiptVerificationResult> verifyHashReceipt(CapsuleData capsule, HashReceipt receipt) 
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("capsule", capsule, "receipt", receipt);
        return post("/v1/capsules/verify/receipt", body, 
                new TypeReference<SDKResponse<HashReceiptVerificationResult>>() {});
    }

    /**
     * Performs complete verification of a capsule.
     */
    public SDKResponse<CompleteVerificationResult> verifyComplete(
            CapsuleData capsule, CapsuleSchema schema, HashReceipt receipt) 
            throws IOException, InterruptedException {
        Map<String, Object> body = Map.of("capsule", capsule, "schema", schema, "receipt", receipt);
        return post("/v1/capsules/verify/complete", body, 
                new TypeReference<SDKResponse<CompleteVerificationResult>>() {});
    }

    // ==================== Dispute Resolution ====================

    /**
     * Files a dispute.
     */
    public SDKResponse<DisputeFilingResult> fileDispute(DisputeRequest request) 
            throws IOException, InterruptedException {
        return post("/v1/disputes", request, new TypeReference<SDKResponse<DisputeFilingResult>>() {});
    }

    /**
     * Gets dispute details.
     */
    public SDKResponse<Dispute> getDispute(String disputeId) 
            throws IOException, InterruptedException {
        return get("/v1/disputes/" + disputeId, new TypeReference<SDKResponse<Dispute>>() {});
    }

    /**
     * Adds evidence to a dispute.
     */
    public SDKResponse<EvidenceAddResult> addEvidence(String disputeId, EvidenceSubmission evidence) 
            throws IOException, InterruptedException {
        return post("/v1/disputes/" + disputeId + "/evidence", evidence, 
                new TypeReference<SDKResponse<EvidenceAddResult>>() {});
    }

    // ==================== Tier & Analytics ====================

    /**
     * Gets requester tier capabilities.
     */
    public SDKResponse<TierCapabilities> getTierCapabilities() 
            throws IOException, InterruptedException {
        return get("/v1/requester/tier", new TypeReference<SDKResponse<TierCapabilities>>() {});
    }

    /**
     * Checks tier restrictions for a request type.
     */
    public SDKResponse<RestrictionCheckResult> checkRestrictions(RequestTypeCheck check) 
            throws IOException, InterruptedException {
        return post("/v1/requester/restrictions/check", check, 
                new TypeReference<SDKResponse<RestrictionCheckResult>>() {});
    }

    /**
     * Gets requester analytics.
     */
    public SDKResponse<RequesterAnalytics> getAnalytics() 
            throws IOException, InterruptedException {
        return get("/v1/requester/analytics", new TypeReference<SDKResponse<RequesterAnalytics>>() {});
    }

    // ==================== HTTP Methods ====================

    private <T> T get(String path, TypeReference<T> typeRef) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), typeRef);
    }

    private <T> T post(String path, Object body, TypeReference<T> typeRef) 
            throws IOException, InterruptedException {
        String jsonBody = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), typeRef);
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }

    // ==================== Builder ====================

    public static class Builder {
        private String baseUrl = "https://api.yachaq.io";
        private String apiKey;
        private String accessToken;
        private int connectTimeoutSeconds = 30;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder connectTimeout(int seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        public YachaqClient build() {
            Objects.requireNonNull(baseUrl, "Base URL is required");
            return new YachaqClient(this);
        }
    }
}
