package com.yachaq.api.matching;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Matching Engine REST API.
 * 
 * Requirements: 8.1, 8.2, 10.1, 10.2
 * - Privacy-preserving matching using ODX labels
 * - K-anonymity enforcement
 * - Uniform compensation
 */
@RestController
@RequestMapping("/api/v1/matching")
public class MatchingController {

    private final MatchingService matchingService;

    public MatchingController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    /**
     * Find eligible DS for a request based on ODX labels.
     * POST /api/v1/matching/eligible
     */
    @PostMapping("/eligible")
    public ResponseEntity<EligibilityResponse> findEligible(
            @Valid @RequestBody EligibilityRequest request) {
        
        MatchingService.MatchResult result = matchingService.findEligibleDS(
            request.requestId(),
            request.requiredLabels(),
            request.excludedLabels(),
            request.minCohortSize()
        );
        
        return ResponseEntity.ok(new EligibilityResponse(
            request.requestId(),
            result.eligibleDsIds(),
            result.cohortSize(),
            result.meetsKAnonymity()
        ));
    }

    /**
     * Calculate uniform compensation for a request.
     * POST /api/v1/matching/compensation
     */
    @PostMapping("/compensation")
    public ResponseEntity<CompensationResponse> calculateCompensation(
            @Valid @RequestBody CompensationRequest request) {
        
        MatchingService.CompensationResult result = matchingService.calculateCompensation(
            request.requestId(),
            request.dataCategories(),
            request.outputMode(),
            request.ttlMinutes()
        );
        
        return ResponseEntity.ok(new CompensationResponse(
            request.requestId(),
            result.unitPrice(),
            result.currency(),
            result.displayCurrency(),
            result.displayAmount()
        ));
    }

    /**
     * Verify uniform compensation across all DS in a request.
     * POST /api/v1/matching/verify-uniform
     */
    @PostMapping("/verify-uniform")
    public ResponseEntity<UniformVerificationResponse> verifyUniformCompensation(
            @Valid @RequestBody UniformVerificationRequest request) {
        
        boolean uniform = matchingService.verifyUniformCompensationForController(
            request.requestId(),
            request.dsCompensations()
        );
        
        return ResponseEntity.ok(new UniformVerificationResponse(
            request.requestId(),
            uniform
        ));
    }

    /**
     * Get matching statistics for a request.
     * GET /api/v1/matching/stats/{requestId}
     */
    @GetMapping("/stats/{requestId}")
    public ResponseEntity<MatchingStats> getMatchingStats(@PathVariable UUID requestId) {
        MatchingService.MatchingStats stats = matchingService.getMatchingStats(requestId);
        return ResponseEntity.ok(new MatchingStats(
            requestId,
            stats.totalEligible(),
            stats.totalMatched(),
            stats.cohortSize(),
            stats.kAnonymityMet()
        ));
    }

    // DTOs
    public record EligibilityRequest(
        @NotNull UUID requestId,
        @NotNull Set<String> requiredLabels,
        Set<String> excludedLabels,
        int minCohortSize
    ) {}

    public record EligibilityResponse(
        UUID requestId,
        Set<UUID> eligibleDsIds,
        int cohortSize,
        boolean meetsKAnonymity
    ) {}

    public record CompensationRequest(
        @NotNull UUID requestId,
        @NotNull List<String> dataCategories,
        @NotNull String outputMode,
        int ttlMinutes
    ) {}

    public record CompensationResponse(
        UUID requestId,
        BigDecimal unitPrice,
        String currency,
        String displayCurrency,
        BigDecimal displayAmount
    ) {}

    public record UniformVerificationRequest(
        @NotNull UUID requestId,
        @NotNull List<DsCompensation> dsCompensations
    ) {}

    public record DsCompensation(UUID dsId, BigDecimal amount) {}

    public record UniformVerificationResponse(UUID requestId, boolean uniform) {}

    public record MatchingStats(
        UUID requestId,
        int totalEligible,
        int totalMatched,
        int cohortSize,
        boolean kAnonymityMet
    ) {}

    // Exception handlers
    @ExceptionHandler(MatchingService.CohortTooSmallException.class)
    public ResponseEntity<ErrorResponse> handleCohortTooSmall(
            MatchingService.CohortTooSmallException e) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("MATCH_001", e.getMessage()));
    }

    @ExceptionHandler(MatchingService.NonUniformCompensationException.class)
    public ResponseEntity<ErrorResponse> handleNonUniform(
            MatchingService.NonUniformCompensationException e) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("MATCH_002", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
