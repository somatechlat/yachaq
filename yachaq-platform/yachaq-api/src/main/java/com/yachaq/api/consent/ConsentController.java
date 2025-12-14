package com.yachaq.api.consent;

import com.yachaq.core.domain.ConsentContract;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for consent management.
 * 
 * Validates: Requirements 3.1, 3.2, 3.4
 */
@RestController
@RequestMapping("/api/v1/consent")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * Grant new consent.
     * POST /api/v1/consent/grant
     */
    @PostMapping("/grant")
    public ResponseEntity<ConsentService.ConsentResult> grantConsent(
            @RequestBody GrantConsentRequest request) {
        
        ConsentService.ConsentRequest consentRequest = new ConsentService.ConsentRequest(
                request.dsId(),
                request.requesterId(),
                request.requestId(),
                request.scopeHash(),
                request.purposeHash(),
                request.durationStart(),
                request.durationEnd(),
                request.compensationAmount()
        );
        
        ConsentService.ConsentResult result = consentService.createConsent(consentRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Revoke consent.
     * DELETE /api/v1/consent/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ConsentService.RevocationResult> revokeConsent(
            @PathVariable UUID id,
            @RequestHeader("X-DS-ID") UUID dsId) {
        
        ConsentService.RevocationResult result = consentService.revokeConsent(id, dsId);
        return ResponseEntity.ok(result);
    }


    /**
     * Get all contracts for a DS.
     * GET /api/v1/consent/contracts
     */
    @GetMapping("/contracts")
    public ResponseEntity<List<ConsentContract>> getContracts(
            @RequestHeader("X-DS-ID") UUID dsId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        
        List<ConsentContract> contracts = activeOnly 
                ? consentService.getActiveContracts(dsId)
                : consentService.getAllContracts(dsId);
        return ResponseEntity.ok(contracts);
    }

    /**
     * Get a specific contract.
     * GET /api/v1/consent/contracts/{id}
     */
    @GetMapping("/contracts/{id}")
    public ResponseEntity<ConsentContract> getContract(@PathVariable UUID id) {
        ConsentContract contract = consentService.getContract(id);
        return ResponseEntity.ok(contract);
    }

    /**
     * Evaluate access under a consent.
     * POST /api/v1/consent/{id}/evaluate
     */
    @PostMapping("/{id}/evaluate")
    public ResponseEntity<AccessEvaluationResponse> evaluateAccess(
            @PathVariable UUID id,
            @RequestBody AccessEvaluationRequest request) {
        
        boolean permitted = consentService.evaluateAccess(id, request.requestedFieldsHash());
        return ResponseEntity.ok(new AccessEvaluationResponse(id, permitted));
    }

    // Request/Response DTOs
    public record GrantConsentRequest(
            UUID dsId,
            UUID requesterId,
            UUID requestId,
            String scopeHash,
            String purposeHash,
            Instant durationStart,
            Instant durationEnd,
            BigDecimal compensationAmount
    ) {}

    public record AccessEvaluationRequest(String requestedFieldsHash) {}

    public record AccessEvaluationResponse(UUID contractId, boolean permitted) {}

    // Exception handlers
    @ExceptionHandler(ConsentService.ConsentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ConsentService.ConsentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CONSENT_001", e.getMessage()));
    }

    @ExceptionHandler(ConsentService.DuplicateConsentException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(ConsentService.DuplicateConsentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONSENT_005", e.getMessage()));
    }

    @ExceptionHandler(ConsentService.InvalidConsentRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(ConsentService.InvalidConsentRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("CONSENT_006", e.getMessage()));
    }

    @ExceptionHandler(ConsentService.UnauthorizedConsentAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(ConsentService.UnauthorizedConsentAccessException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("CONSENT_007", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
