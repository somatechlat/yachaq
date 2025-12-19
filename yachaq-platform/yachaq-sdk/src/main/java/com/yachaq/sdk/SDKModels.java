package com.yachaq.sdk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SDK Data Models - Language-agnostic DTOs for the YACHAQ Requester API.
 * These models are designed to be easily serializable to JSON for cross-language support.
 * 
 * Validates: Requirements 352.3
 */
public class SDKModels {
    private SDKModels() {} // Utility class
}

// ==================== Response Wrapper ====================

record SDKResponse<T>(
    boolean success,
    T data,
    String errorCode,
    String errorMessage,
    List<String> validationErrors
) {
    public static <T> SDKResponse<T> success(T data) {
        return new SDKResponse<>(true, data, null, null, List.of());
    }

    public static <T> SDKResponse<T> error(String code, String message) {
        return new SDKResponse<>(false, null, code, message, List.of());
    }
}

// ==================== Authentication ====================

record AuthResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,
    String tokenType
) {}

// ==================== Request Management ====================

record RequestConfig(
    String templateId,
    Set<String> requiredLabels,
    Set<String> optionalLabels,
    TimeWindow timeWindow,
    GeoCriteria geoCriteria,
    BigDecimal compensation,
    String outputMode,
    int ttlHours
) {}

record TimeWindow(Instant start, Instant end) {}

record GeoCriteria(String precision, List<String> regions) {}

record OdxCriteria(
    Set<String> requiredLabels,
    Set<String> optionalLabels,
    TimeWindow timeWindow,
    GeoCriteria geoCriteria
) {}

record RequestCreationResult(
    boolean success,
    String requestId,
    String status,
    List<String> errors,
    List<RemediationSuggestion> suggestions
) {}

record RemediationSuggestion(
    String id,
    String title,
    String description,
    String action
) {}

record RequestTemplate(
    String id,
    String name,
    String description,
    String category,
    Set<String> defaultLabels,
    Set<String> optionalLabels,
    String outputMode,
    TimeWindow defaultTimeWindow,
    BigDecimal suggestedCompensation,
    int defaultTtlHours
) {}

record CriteriaValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings,
    int estimatedCohortSize
) {}

record RequestStatus(
    String requestId,
    String status,
    String screeningStatus,
    Instant createdAt,
    Instant expiresAt,
    ResponseStats responseStats
) {}

record ResponseStats(
    int totalResponses,
    int completedResponses,
    int pendingResponses,
    BigDecimal totalCost
) {}

// ==================== Capsule Verification ====================

record CapsuleData(
    String capsuleId,
    String contractId,
    String requestId,
    byte[] encryptedPayload,
    String signature,
    Map<String, String> headers,
    Instant createdAt,
    Instant expiresAt
) {}

record CapsuleSchema(
    String schemaId,
    String version,
    Map<String, FieldSchema> fields,
    List<String> requiredFields
) {}

record FieldSchema(
    String type,
    String format,
    boolean nullable,
    Map<String, Object> constraints
) {}

record HashReceipt(
    String receiptId,
    String capsuleHash,
    String merkleRoot,
    List<String> merkleProof,
    String blockchainAnchor,
    Instant anchoredAt
) {}

record SignatureVerificationResult(
    boolean valid,
    String signerId,
    String algorithm,
    Instant signedAt,
    String errorMessage
) {}

record SchemaValidationResult(
    boolean valid,
    List<SchemaViolation> violations
) {}

record SchemaViolation(
    String field,
    String violation,
    String expected,
    String actual
) {}

record HashReceiptVerificationResult(
    boolean valid,
    boolean merkleProofValid,
    boolean blockchainAnchorValid,
    String errorMessage
) {}

record CompleteVerificationResult(
    boolean valid,
    SignatureVerificationResult signatureResult,
    SchemaValidationResult schemaResult,
    HashReceiptVerificationResult receiptResult
) {}

// ==================== Dispute Resolution ====================

record DisputeRequest(
    String requestId,
    String capsuleId,
    String reason,
    String description,
    List<String> evidenceIds
) {}

record DisputeFilingResult(
    boolean success,
    String disputeId,
    String status,
    String errorMessage
) {}

record Dispute(
    String disputeId,
    String requestId,
    String capsuleId,
    String requesterId,
    String reason,
    String description,
    String status,
    List<Evidence> evidence,
    String resolution,
    Instant filedAt,
    Instant resolvedAt
) {}

record Evidence(
    String evidenceId,
    String type,
    String description,
    String contentHash,
    Instant submittedAt
) {}

record EvidenceSubmission(
    String type,
    String description,
    byte[] content
) {}

record EvidenceAddResult(
    boolean success,
    String evidenceId,
    String errorMessage
) {}

// ==================== Tier & Analytics ====================

record TierCapabilities(
    String tier,
    BigDecimal maxBudget,
    int maxParticipants,
    List<String> allowedOutputModes,
    boolean exportAllowed,
    List<String> allowedCategories
) {}

record RequestTypeCheck(
    String outputMode,
    BigDecimal compensation,
    Set<String> requiredLabels,
    boolean identityReveal
) {}

record RestrictionCheckResult(
    boolean allowed,
    List<String> violations,
    List<String> warnings
) {}

record RequesterAnalytics(
    String requesterId,
    int totalRequests,
    int approvedRequests,
    int rejectedRequests,
    int pendingRequests,
    int totalResponses,
    BigDecimal totalSpent,
    Instant generatedAt
) {}
