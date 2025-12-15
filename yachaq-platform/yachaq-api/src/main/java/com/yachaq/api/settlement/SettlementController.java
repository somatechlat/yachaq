package com.yachaq.api.settlement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Settlement REST API.
 * 
 * Requirements: 11.1, 11.2, 11.4
 * - Trigger settlement on consent completion
 * - Update DS balance and escrow
 */
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * Trigger settlement for a completed consent.
     * POST /api/v1/settlement/process
     */
    @PostMapping("/process")
    public ResponseEntity<SettlementResponse> processSettlement(
            @Valid @RequestBody SettlementRequest request) {
        
        SettlementService.SettlementResult result = settlementService.processSettlement(
            request.consentContractId(),
            request.dsId(),
            request.escrowAccountId(),
            request.amount()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(new SettlementResponse(
            result.settlementId(),
            result.dsId(),
            result.amount(),
            result.status().name(),
            result.receiptId()
        ));
    }

    /**
     * Get settlement history for a DS.
     * GET /api/v1/settlement/history
     */
    @GetMapping("/history")
    public ResponseEntity<List<SettlementRecord>> getSettlementHistory(
            @RequestHeader("X-DS-ID") UUID dsId) {
        List<SettlementRecord> history = settlementService.getSettlementHistory(dsId);
        return ResponseEntity.ok(history);
    }

    /**
     * Get settlement by consent contract.
     * GET /api/v1/settlement/consent/{contractId}
     */
    @GetMapping("/consent/{contractId}")
    public ResponseEntity<SettlementRecord> getSettlementByConsent(
            @PathVariable UUID contractId) {
        SettlementRecord record = settlementService.getSettlementByConsent(contractId);
        return ResponseEntity.ok(record);
    }

    /**
     * Get DS balance.
     * GET /api/v1/settlement/balance
     */
    @GetMapping("/balance")
    public ResponseEntity<DSBalance> getBalance(@RequestHeader("X-DS-ID") UUID dsId) {
        DSBalance balance = settlementService.getOrCreateBalance(dsId);
        return ResponseEntity.ok(balance);
    }

    // DTOs
    public record SettlementRequest(
        @NotNull UUID consentContractId,
        @NotNull UUID dsId,
        @NotNull UUID escrowAccountId,
        @NotNull BigDecimal amount
    ) {}

    public record SettlementResponse(
        UUID settlementId,
        UUID dsId,
        BigDecimal amount,
        String status,
        UUID auditReceiptId
    ) {}

    public record SettlementRecord(
        UUID settlementId,
        UUID consentContractId,
        UUID dsId,
        BigDecimal amount,
        String status,
        String settledAt
    ) {}

    // Exception handlers
    @ExceptionHandler(SettlementService.SettlementNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            SettlementService.SettlementNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("SETTLE_001", e.getMessage()));
    }

    @ExceptionHandler(SettlementService.InsufficientEscrowException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientEscrow(
            SettlementService.InsufficientEscrowException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("SETTLE_002", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
