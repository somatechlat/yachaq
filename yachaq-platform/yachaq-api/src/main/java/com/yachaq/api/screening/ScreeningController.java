package com.yachaq.api.screening;

import com.yachaq.core.domain.Request.RequestStatus;
import com.yachaq.core.domain.Request.UnitType;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ScreeningController {

    private final RequestService requestService;
    private final ScreeningService screeningService;

    public ScreeningController(RequestService requestService, ScreeningService screeningService) {
        this.requestService = requestService;
        this.screeningService = screeningService;
    }

    @PostMapping
    public ResponseEntity<RequestService.RequestDto> createRequest(@RequestBody CreateRequestRequest request) {
        RequestService.CreateRequestCommand cmd = new RequestService.CreateRequestCommand(
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
        RequestService.RequestDto result = requestService.createRequest(cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<ScreeningService.ScreeningResultDto> submitForScreening(
            @PathVariable UUID id,
            @RequestHeader("X-Requester-ID") UUID requesterId) {
        ScreeningService.ScreeningResultDto result = requestService.submitForScreening(id, requesterId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequestService.RequestDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(requestService.getRequest(id));
    }

    @GetMapping
    public ResponseEntity<Page<RequestService.RequestDto>> listByRequester(
            @RequestHeader("X-Requester-ID") UUID requesterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(requestService.getRequestsByRequester(requesterId, page, size));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<Page<RequestService.RequestDto>> listByStatus(
            @PathVariable RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(requestService.getRequestsByStatus(status, page, size));
    }

    @GetMapping("/{id}/screening")
    public ResponseEntity<ScreeningService.ScreeningResultDto> getScreening(@PathVariable UUID id) {
        return ResponseEntity.ok(screeningService.getScreeningResult(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<RequestService.RequestDto> cancel(
            @PathVariable UUID id,
            @RequestHeader("X-Requester-ID") UUID requesterId) {
        return ResponseEntity.ok(requestService.cancelRequest(id, requesterId));
    }

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

    @ExceptionHandler(ScreeningService.RequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRequestNotFound(ScreeningService.RequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("REQUEST_001", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.ScreeningNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScreeningNotFound(ScreeningService.ScreeningNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("SCREEN_001", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.InvalidScreeningStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(ScreeningService.InvalidScreeningStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("SCREEN_002", e.getMessage()));
    }

    @ExceptionHandler(ScreeningService.AlreadyScreenedException.class)
    public ResponseEntity<ErrorResponse> handleAlready(ScreeningService.AlreadyScreenedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("SCREEN_003", e.getMessage()));
    }

    @ExceptionHandler(RequestService.InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(RequestService.InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("REQUEST_002", e.getMessage()));
    }

    @ExceptionHandler(RequestService.UnauthorizedRequestAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(RequestService.UnauthorizedRequestAccessException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("REQUEST_003", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
