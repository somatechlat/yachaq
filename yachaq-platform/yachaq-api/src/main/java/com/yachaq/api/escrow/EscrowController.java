package com.yachaq.api.escrow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/escrow")
public class EscrowController {

    private final EscrowService escrowService;

    public EscrowController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @PostMapping
    public ResponseEntity<EscrowService.EscrowAccountDto> createEscrow(
            @RequestBody CreateEscrowRequest request) {
        var escrow = escrowService.createEscrow(request.requesterId(), request.requestId());
        return ResponseEntity.status(HttpStatus.CREATED).body(escrow);
    }

    @PostMapping("/{escrowId}/fund")
    public ResponseEntity<EscrowService.EscrowAccountDto> fundEscrow(
            @PathVariable UUID escrowId,
            @RequestBody FundEscrowRequest request) {
        var escrow = escrowService.fundEscrow(escrowId, request.amount(), request.requesterId());
        return ResponseEntity.ok(escrow);
    }

    @PostMapping("/{escrowId}/lock")
    public ResponseEntity<EscrowService.EscrowAccountDto> lockEscrow(
            @PathVariable UUID escrowId,
            @RequestBody LockEscrowRequest request) {
        var escrow = escrowService.lockEscrow(escrowId, request.amount());
        return ResponseEntity.ok(escrow);
    }

    @PostMapping("/{escrowId}/release")
    public ResponseEntity<EscrowService.EscrowAccountDto> releaseEscrow(
            @PathVariable UUID escrowId,
            @RequestBody ReleaseEscrowRequest request) {
        var escrow = escrowService.releaseEscrow(escrowId, request.amount(), request.dsId());
        return ResponseEntity.ok(escrow);
    }

    @PostMapping("/{escrowId}/refund")
    public ResponseEntity<EscrowService.EscrowAccountDto> refundEscrow(
            @PathVariable UUID escrowId,
            @RequestBody RefundEscrowRequest request) {
        var escrow = escrowService.refundEscrow(escrowId, request.amount(), request.requesterId());
        return ResponseEntity.ok(escrow);
    }

    @GetMapping("/{escrowId}")
    public ResponseEntity<EscrowService.EscrowAccountDto> getEscrow(@PathVariable UUID escrowId) {
        var escrow = escrowService.getEscrow(escrowId);
        return ResponseEntity.ok(escrow);
    }

    @GetMapping("/request/{requestId}")
    public ResponseEntity<EscrowService.EscrowAccountDto> getEscrowByRequest(@PathVariable UUID requestId) {
        return escrowService.getEscrowByRequestId(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/request/{requestId}/check")
    public ResponseEntity<FundingCheckResponse> checkFunding(
            @PathVariable UUID requestId,
            @RequestParam BigDecimal requiredAmount) {
        boolean sufficient = escrowService.isEscrowSufficientlyFunded(requestId, requiredAmount);
        return ResponseEntity.ok(new FundingCheckResponse(sufficient, requiredAmount));
    }

    public record CreateEscrowRequest(UUID requesterId, UUID requestId) {}
    public record FundEscrowRequest(UUID requesterId, BigDecimal amount) {}
    public record LockEscrowRequest(BigDecimal amount) {}
    public record ReleaseEscrowRequest(UUID dsId, BigDecimal amount) {}
    public record RefundEscrowRequest(UUID requesterId, BigDecimal amount) {}
    public record FundingCheckResponse(boolean sufficient, BigDecimal requiredAmount) {}
}
