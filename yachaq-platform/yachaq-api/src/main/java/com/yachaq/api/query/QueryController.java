package com.yachaq.api.query;

import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.TimeCapsule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Query Orchestrator REST API.
 * 
 * Requirements: 205.1, 205.3, 206.1, 206.2
 * - Dispatch live queries to devices
 * - Create time capsules with TTL
 * - Manage query plans
 */
@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private final QueryOrchestratorService queryService;

    public QueryController(QueryOrchestratorService queryService) {
        this.queryService = queryService;
    }

    /**
     * Create and sign a query plan.
     * POST /api/v1/query/plans
     */
    @PostMapping("/plans")
    public ResponseEntity<QueryPlan> createQueryPlan(
            @RequestHeader("X-Requester-ID") UUID requesterId,
            @Valid @RequestBody CreatePlanRequest request) {
        
        QueryPlan plan = queryService.createQueryPlan(
            requesterId,
            request.consentContractId(),
            request.scope(),
            request.transforms(),
            request.ttlMinutes()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    /**
     * Get a query plan.
     * GET /api/v1/query/plans/{id}
     */
    @GetMapping("/plans/{id}")
    public ResponseEntity<QueryPlan> getQueryPlan(@PathVariable UUID id) {
        QueryPlan plan = queryService.getQueryPlan(id);
        return ResponseEntity.ok(plan);
    }

    /**
     * Verify a query plan signature.
     * POST /api/v1/query/plans/{id}/verify
     */
    @PostMapping("/plans/{id}/verify")
    public ResponseEntity<VerificationResponse> verifyPlan(@PathVariable UUID id) {
        QueryPlan plan = queryService.getQueryPlan(id);
        boolean valid = queryService.verifyPlanSignature(plan);
        return ResponseEntity.ok(new VerificationResponse(id, valid));
    }

    /**
     * Dispatch a query to eligible devices.
     * POST /api/v1/query/dispatch
     */
    @PostMapping("/dispatch")
    public ResponseEntity<DispatchResponse> dispatchQuery(
            @Valid @RequestBody DispatchRequest request) {
        
        QueryOrchestratorService.DispatchResult result = queryService.dispatchQuery(
            request.planId(),
            request.eligibleDeviceIds(),
            Duration.ofSeconds(request.timeoutSeconds())
        );
        
        return ResponseEntity.ok(new DispatchResponse(
            result.queryId(),
            result.dispatchedCount(),
            result.status()
        ));
    }

    /**
     * Create a time capsule from query responses.
     * POST /api/v1/query/capsules
     */
    @PostMapping("/capsules")
    public ResponseEntity<TimeCapsule> createCapsule(
            @Valid @RequestBody CreateCapsuleRequest request) {
        
        TimeCapsule capsule = queryService.createTimeCapsule(
            request.queryId(),
            request.ttlMinutes()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(capsule);
    }

    /**
     * Get a time capsule.
     * GET /api/v1/query/capsules/{id}
     */
    @GetMapping("/capsules/{id}")
    public ResponseEntity<TimeCapsule> getCapsule(@PathVariable UUID id) {
        TimeCapsule capsule = queryService.getTimeCapsule(id);
        return ResponseEntity.ok(capsule);
    }

    /**
     * Check if a capsule is expired.
     * GET /api/v1/query/capsules/{id}/expired
     */
    @GetMapping("/capsules/{id}/expired")
    public ResponseEntity<ExpirationResponse> checkExpiration(@PathVariable UUID id) {
        TimeCapsule capsule = queryService.getTimeCapsule(id);
        boolean expired = queryService.isCapsuleExpired(capsule);
        return ResponseEntity.ok(new ExpirationResponse(id, expired, capsule.getExpiresAt().toString()));
    }

    // DTOs
    public record CreatePlanRequest(
        @NotNull UUID consentContractId,
        @NotBlank String scope,
        List<String> transforms,
        int ttlMinutes
    ) {}

    public record DispatchRequest(
        @NotNull UUID planId,
        @NotNull Set<UUID> eligibleDeviceIds,
        int timeoutSeconds
    ) {}

    public record CreateCapsuleRequest(
        @NotNull UUID queryId,
        int ttlMinutes
    ) {}

    public record VerificationResponse(UUID planId, boolean valid) {}

    public record DispatchResponse(UUID queryId, int dispatchedCount, String status) {}

    public record ExpirationResponse(UUID capsuleId, boolean expired, String expiresAt) {}

    // Exception handlers
    @ExceptionHandler(QueryOrchestratorService.QueryPlanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePlanNotFound(
            QueryOrchestratorService.QueryPlanNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("QUERY_001", e.getMessage()));
    }

    @ExceptionHandler(QueryOrchestratorService.CapsuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCapsuleNotFound(
            QueryOrchestratorService.CapsuleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("QUERY_002", e.getMessage()));
    }

    @ExceptionHandler(QueryOrchestratorService.CapsuleExpiredException.class)
    public ResponseEntity<ErrorResponse> handleCapsuleExpired(
            QueryOrchestratorService.CapsuleExpiredException e) {
        return ResponseEntity.status(HttpStatus.GONE)
            .body(new ErrorResponse("QUERY_003", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
