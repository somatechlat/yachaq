package com.yachaq.api.sdk;

import com.yachaq.api.requester.RequesterPortalService;
import com.yachaq.api.requester.RequesterPortalService.*;
import com.yachaq.api.verification.CapsuleVerificationService;
import com.yachaq.api.verification.CapsuleVerificationService.*;
import com.yachaq.api.vetting.RequesterVettingService;
import com.yachaq.api.vetting.RequesterVettingService.*;
import com.yachaq.api.dispute.DisputeResolutionService;
import com.yachaq.api.dispute.DisputeResolutionService.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Requester SDK - Programmatic API for data requesters.
 * Provides unified access to request creation, verification, and dispute resolution.
 * 
 * Security: All operations enforce policy screening and tier restrictions.
 * Performance: Batch operations supported for efficiency.
 * 
 * Validates: Requirements 352.1, 352.2, 352.3, 352.4, 352.5
 */
@Component
public class RequesterSDK {

    private final RequesterPortalService portalService;
    private final CapsuleVerificationService verificationService;
    private final RequesterVettingService vettingService;
    private final DisputeResolutionService disputeService;

    public RequesterSDK(RequesterPortalService portalService,
                        CapsuleVerificationService verificationService,
                        RequesterVettingService vettingService,
                        DisputeResolutionService disputeService) {
        this.portalService = portalService;
        this.verificationService = verificationService;
        this.vettingService = vettingService;
        this.disputeService = disputeService;
    }

    // ==================== Task 98.1: Request Creation API ====================

    /**
     * Creates a new data request.
     * Requirement 352.1: Provide programmatic request creation.
     */
    public SDKResponse<RequestCreationResult> createRequest(UUID requesterId, RequestConfig config) {
        try {
            Objects.requireNonNull(requesterId, "Requester ID cannot be null");
            Objects.requireNonNull(config, "Request config cannot be null");

            // Validate requester tier
            var tierOpt = vettingService.getTier(requesterId);
            if (tierOpt.isEmpty()) {
                return SDKResponse.error("REQUESTER_NOT_FOUND", "Requester not found or not vetted");
            }

            // Check tier restrictions
            RequesterVettingService.RequestTypeCheck check = new RequesterVettingService.RequestTypeCheck(
                config.outputMode(),
                config.compensation(),
                config.requiredLabels(),
                false
            );
            RequesterVettingService.RestrictionCheckResult restrictions = 
                    vettingService.checkRestrictions(requesterId, check);
            if (!restrictions.allowed()) {
                return SDKResponse.error("TIER_RESTRICTION", 
                    String.join("; ", restrictions.violations()));
            }

            // Validate criteria
            OdxCriteria criteria = new OdxCriteria(
                config.requiredLabels(),
                config.optionalLabels(),
                config.timeWindow(),
                config.geoCriteria()
            );
            CriteriaValidationResult validation = portalService.validateCriteria(criteria);
            if (!validation.valid()) {
                return SDKResponse.validationError(validation.errors());
            }

            // Create from template or custom
            RequestCreationResult result;
            if (config.templateId() != null) {
                RequestCustomization customization = new RequestCustomization(
                    config.requiredLabels(),
                    Set.of(),
                    config.timeWindow(),
                    config.compensation()
                );
                result = portalService.createFromTemplate(requesterId, config.templateId(), customization);
            } else {
                // Create custom request
                RequestCustomization customization = new RequestCustomization(
                    config.requiredLabels(),
                    Set.of(),
                    config.timeWindow(),
                    config.compensation()
                );
                result = portalService.createFromTemplate(requesterId, "custom", customization);
            }

            if (!result.success()) {
                return SDKResponse.error("REQUEST_REJECTED", result.errors().toString());
            }

            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("INTERNAL_ERROR", e.getMessage());
        }
    }

    /**
     * Creates multiple requests in batch.
     * Requirement 352.1: Batch request creation.
     */
    public SDKResponse<List<RequestCreationResult>> createRequestsBatch(UUID requesterId, 
                                                                         List<RequestConfig> configs) {
        List<RequestCreationResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < configs.size(); i++) {
            SDKResponse<RequestCreationResult> response = createRequest(requesterId, configs.get(i));
            if (response.success()) {
                results.add(response.data());
            } else {
                errors.add("Request " + i + ": " + response.errorMessage());
            }
        }

        if (!errors.isEmpty()) {
            return SDKResponse.partialSuccess(results, errors);
        }
        return SDKResponse.success(results);
    }

    /**
     * Gets available templates.
     */
    public SDKResponse<List<RequestTemplate>> getTemplates(String category) {
        try {
            List<RequestTemplate> templates = portalService.getTemplates(category);
            return SDKResponse.success(templates);
        } catch (Exception e) {
            return SDKResponse.error("INTERNAL_ERROR", e.getMessage());
        }
    }

    /**
     * Validates ODX criteria without creating a request.
     */
    public SDKResponse<CriteriaValidationResult> validateCriteria(OdxCriteria criteria) {
        try {
            CriteriaValidationResult result = portalService.validateCriteria(criteria);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("INTERNAL_ERROR", e.getMessage());
        }
    }


    // ==================== Task 98.2: Capsule Verification API ====================

    /**
     * Verifies a capsule's signature.
     * Requirement 352.2: Provide verification functions.
     */
    public SDKResponse<SignatureVerificationResult> verifySignature(CapsuleData capsule) {
        try {
            Objects.requireNonNull(capsule, "Capsule cannot be null");
            SignatureVerificationResult result = verificationService.verifySignature(capsule);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("VERIFICATION_ERROR", e.getMessage());
        }
    }

    /**
     * Validates a capsule against its schema.
     * Requirement 352.2: Provide verification functions.
     */
    public SDKResponse<SchemaValidationResult> validateSchema(CapsuleData capsule, CapsuleSchema schema) {
        try {
            Objects.requireNonNull(capsule, "Capsule cannot be null");
            Objects.requireNonNull(schema, "Schema cannot be null");
            SchemaValidationResult result = verificationService.validateSchema(capsule, schema);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("VALIDATION_ERROR", e.getMessage());
        }
    }

    /**
     * Verifies hash receipts for a capsule.
     * Requirement 352.2: Provide verification functions.
     */
    public SDKResponse<HashReceiptVerificationResult> verifyHashReceipt(CapsuleData capsule, HashReceipt receipt) {
        try {
            Objects.requireNonNull(capsule, "Capsule cannot be null");
            Objects.requireNonNull(receipt, "Receipt cannot be null");
            HashReceiptVerificationResult result = verificationService.verifyHashReceipt(capsule, receipt);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("VERIFICATION_ERROR", e.getMessage());
        }
    }

    /**
     * Performs complete verification of a capsule.
     * Requirement 352.2: Complete verification.
     */
    public SDKResponse<CompleteVerificationResult> verifyComplete(CapsuleData capsule,
                                                                   CapsuleSchema schema,
                                                                   HashReceipt receipt) {
        try {
            CompleteVerificationResult result = verificationService.verifyComplete(capsule, schema, receipt);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("VERIFICATION_ERROR", e.getMessage());
        }
    }


    // ==================== Dispute Resolution API ====================

    /**
     * Files a dispute.
     * Requirement 352.4: Policy enforcement.
     */
    public SDKResponse<DisputeResolutionService.DisputeFilingResult> fileDispute(
            DisputeResolutionService.DisputeRequest request) {
        try {
            Objects.requireNonNull(request, "Request cannot be null");
            DisputeResolutionService.DisputeFilingResult result = disputeService.fileDispute(request);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("DISPUTE_ERROR", e.getMessage());
        }
    }

    /**
     * Gets dispute details.
     */
    public SDKResponse<DisputeResolutionService.Dispute> getDispute(String disputeId) {
        try {
            return disputeService.getDispute(disputeId)
                    .map(SDKResponse::success)
                    .orElse(SDKResponse.error("NOT_FOUND", "Dispute not found"));
        } catch (Exception e) {
            return SDKResponse.error("DISPUTE_ERROR", e.getMessage());
        }
    }

    /**
     * Adds evidence to a dispute.
     */
    public SDKResponse<DisputeResolutionService.EvidenceAddResult> addEvidence(
            String disputeId, DisputeResolutionService.EvidenceSubmission evidence) {
        try {
            DisputeResolutionService.EvidenceAddResult result = 
                    disputeService.addEvidence(disputeId, evidence);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("EVIDENCE_ERROR", e.getMessage());
        }
    }

    // ==================== Tier & Status API ====================

    /**
     * Gets requester tier.
     */
    public SDKResponse<RequesterVettingService.TierCapabilities> getTierCapabilities(UUID requesterId) {
        try {
            return vettingService.getTier(requesterId)
                    .map(tier -> SDKResponse.success(vettingService.getCapabilities(tier.getTier())))
                    .orElse(SDKResponse.error("NOT_FOUND", "No tier assigned"));
        } catch (Exception e) {
            return SDKResponse.error("VETTING_ERROR", e.getMessage());
        }
    }

    /**
     * Checks tier restrictions for a request.
     */
    public SDKResponse<RequesterVettingService.RestrictionCheckResult> checkRestrictions(
            UUID requesterId, RequesterVettingService.RequestTypeCheck check) {
        try {
            RequesterVettingService.RestrictionCheckResult result = 
                    vettingService.checkRestrictions(requesterId, check);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("RESTRICTION_ERROR", e.getMessage());
        }
    }

    /**
     * Checks bond requirement for a request.
     */
    public SDKResponse<RequesterVettingService.BondRequirement> checkBondRequirement(
            UUID requesterId, RequesterVettingService.RequestRiskAssessment assessment) {
        try {
            RequesterVettingService.BondRequirement result = 
                    vettingService.checkBondRequirement(requesterId, assessment);
            return SDKResponse.success(result);
        } catch (Exception e) {
            return SDKResponse.error("BOND_ERROR", e.getMessage());
        }
    }

    /**
     * Gets request status.
     */
    public SDKResponse<RequestStatus> getRequestStatus(UUID requesterId, UUID requestId) {
        try {
            RequestStatus status = portalService.getRequestStatus(requesterId, requestId);
            return SDKResponse.success(status);
        } catch (Exception e) {
            return SDKResponse.error("STATUS_ERROR", e.getMessage());
        }
    }

    /**
     * Gets requester analytics.
     */
    public SDKResponse<RequesterAnalytics> getAnalytics(UUID requesterId) {
        try {
            RequesterAnalytics analytics = portalService.getAnalytics(requesterId);
            return SDKResponse.success(analytics);
        } catch (Exception e) {
            return SDKResponse.error("ANALYTICS_ERROR", e.getMessage());
        }
    }


    // ==================== Inner Types ====================

    /**
     * Configuration for creating a new request.
     */
    public record RequestConfig(
        String templateId,
        Set<String> requiredLabels,
        Set<String> optionalLabels,
        TimeWindow timeWindow,
        GeoCriteria geoCriteria,
        BigDecimal compensation,
        String outputMode,
        int ttlHours
    ) {}

    /**
     * Generic SDK response wrapper with error handling.
     */
    public record SDKResponse<T>(
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

        public static <T> SDKResponse<T> validationError(List<String> errors) {
            return new SDKResponse<>(false, null, "VALIDATION_ERROR", 
                "Validation failed", errors);
        }

        public static <T> SDKResponse<T> partialSuccess(T data, List<String> errors) {
            return new SDKResponse<>(true, data, "PARTIAL_SUCCESS", 
                "Some operations failed", errors);
        }
    }
}
