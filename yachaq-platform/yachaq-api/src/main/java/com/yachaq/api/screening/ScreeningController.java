package com.yachaq.api.screening;

import com.yachaq.core.domain.Request.RequestStatus;
import com.yachaq.core.domain.Request.UnitType;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for request and screening management.
 * 
 * Validates: Requirements 5.1, 5.2, 6.1, 6.2, 6.3
 */
@RestController
@RequestMapping("/api/v1/requests")
public class ScreeningController {

    private final RequestService requestService;
    private final ScreeningService screeningService;

    public ScreeningController(RequestService requestService, ScreeningService screeningService) {
        this.requestService = requestService;
        this.screeningService = screeningService;
    }

    /**
     * Create a new request.
     * POST /api/v1/requests
     */
    @PostMapping
    public ResponseEntity<RequestService.RequestDto> createRequest(
            @RequestBody CreateRequestRequest request) {
        
        RequestService.CreateRequestCommand command = new RequestService.CreateRequestCommand(
                request.requesterId(),
                request.purpose(),
                request.scope(),
                request.eligibilityCriteria(),
                request.durationStart(),
                request.durationEnd(),
                request.unitType(),
                request.unitPrice(),
                request.maxParticipants(),
                request.budget()
        );
        
        RequestService.RequestDto result = requestService.createRequest(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Get a request by ID.
     * GET /api/v1/requests/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<RequestService.RequestDto> getRequest(@PathVariable UUID id) {
        RequestService.RequestDto request = requestService.getRequest(id);
        return ResponseEntity.ok(request);
    }

    /**
     * Get requests for a requester.
     * GET /api/v1/requests
     */
    @GetMapping
    public ResponseEntity<Page<RequestService.RequestDto>> getRequests(
            @RequestHeader("X-Requester-ID") UUID requesterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<RequestService.RequestDto> requests = requestService.getRequestsByRequester(requesterId, page, size);
        return ResponseEntity.ok(requests);
    }

    /**
     * Get requests by status (admin).
     * GET /api/v1/requests/status/{status}
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<Page<RequestService.RequestDto>> getRequestsByStatus(
            @PathVariable RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Page<RequestService.RequestDto> requests = requestService.getRequestsByStatus(status, page, size);
        return ResponseEntity.ok(requests);
    }

    /**
     * Submit request for screening.
     * POST /api/v1/requests/{id}/submit
     */
    @PostMapping("/{id}/submit")
    public ResponseEntity<ScreeningService.ScreeningResultDto> submitForScreening(
            @PathVariable UUID id,
            @RequestHeader("X-Requester-ID") UUID requesterId) {
        
        ScreeningService.ScreeningResultDto result = requestService.submitForScreening(id, requesterId);
        return ResponseEntity.ok(result);
    }

    /**
     * Cancel a request.
     * DELETE /api/v1/requests/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<RequestService.RequestDto> cancelRequest(
            @PathVariable UUID id,
            @RequestHeader("X-Requester-ID") UUID requesterId) {
        
        RequestService.RequestDto result = requestService.cancelRequest(id, requesterId);
        return ResponseEntity.ok(result);
    }

    /**
     * Get screening result for a request.
     * GET /api/v1/requests/{id}/screening
     */
    @GetMapping("/{id}/screening")
    public ResponseEntity<ScreeningService.ScreeningResultDto> getScreeningResult(@PathVariable UUID id) {
        ScreeningService.ScreeningResultDto result = screeningService.getScreeningResult(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Get reason codes for a screening.
     * GET /api/v1/requests/screening/{screeningId}/reasons
     */
    @GetMapping("/screening/{screeningId}/reasons")
    public ResponseEntity<List<String>> getReasonCodes(@PathVariable UUID screeningId) {
        List<String> reasons = screeningService.getReasonCodes(screeningId);
        return ResponseEntity.ok(reasons);
    }

    /**
     * Submit appeal for a rejected screening.
     * POST /api/v1/requests/screening/{screeningId}/appeal
     */
    @PostMapping("/screening/{screeningId}/appeal")
    public ResponseEntity<ScreeningService.ScreeningResultDto> submitAppeal(
            @PathVariable UUID screeningId,
            @RequestBody AppealRequest request) {
        
        ScreeningService.AppealRequest appealRequest = new ScreeningService.AppealRequest(
                request.evidence(), request.justification());
        ScreeningService.ScreeningResultDto result = screeningService.submitAppeal(screeningId, appealRequest);
        return ResponseEntity.ok(result);
    }

    /**
     * Get pending appeals (admin).
     * GET /api/v1/requests/screening/appeals/pending
     */
    @GetMapping("/screening/appeals/pending")
    public ResponseEntity<List<ScreeningService.ScreeningResultDto>> getPendingAppeals() {
        List<ScreeningService.ScreeningResultDto> appeals = screeningService.getPendingAppeals();
        return ResponseEntity.ok(appeals);
    }

    /**
     * Resolve an appeal (admin).
     * POST /api/v1/requests/screening/{screeningId}/appeal/resolve
     */
    @PostMapping("/screening/{screeningId}/appeal/resolve")
    public ResponseEntity<ScreeningService.ScreeningResultDto> resolveAppeal(
            @PathVariable UUID screeningId,
            @RequestHeader("X-Reviewer-ID") UUID reviewerId,
            @RequestBody ResolveAppealRequest request) {
        
        ScreeningService.ScreeningResultDto result = screeningService.resolveAppeal(
                screeningId, reviewerId, request.approved());
        return ResponseEntity.ok(result);
    }

    // Request DTOs
    public record CreateRequestRequest(
            UUID requesterId,
            String purpose,
            Map<String, Object> scope,
            Map<String, Object> eligibilityCriteria,
            Instant durationStart,
            Instant durationEnd,
            UnitType unitType,
            BigDecimal unitPrice,
            Integer maxParticipants,
            BigDecimal budget
    ) {}

    public record AppealRequest(String evidence, String justification) {}

    public record ResolveAppealRequest(boolean approved) {}

    // Exception handlers
    @ExceptionHandler(ScreeningService.RequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRequestNotFound(ScreeningService.RequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("REQUEST_001", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.ScreeningNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScreeningNotFound(ScreeningService.ScreeningNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SCREEN_001", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.InvalidScreeningStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(ScreeningService.InvalidScreeningStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("SCREEN_002", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.AlreadyScreenedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyScreened(ScreeningService.AlreadyScreenedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("SCREEN_003", e.getMessage()));
    }

    @ExceptionHandler(RequestService.InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(RequestService.InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("REQUEST_002", e.getMessage()));
    }

    @ExceptionHandler(RequestService.UnauthorizedRequestAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(RequestService.UnauthorizedRequestAccessException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("REQUEST_003", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
