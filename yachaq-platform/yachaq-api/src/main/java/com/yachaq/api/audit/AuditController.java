package com.yachaq.api.audit;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.EventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for audit receipt management.
 * 
 * Property 5: Audit Receipt Generation
 * Property 8: Merkle Tree Validity
 * Validates: Requirements 12.1, 126.1, 128.1, 128.2
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Get receipts for the authenticated DS.
     * GET /api/v1/audit/receipts
     */
    @GetMapping("/receipts")
    public ResponseEntity<Page<AuditReceipt>> getReceipts(
            @RequestHeader("X-DS-ID") UUID dsId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditReceipt> receipts = auditService.getReceiptsByActor(dsId, pageable);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Get a specific receipt.
     * GET /api/v1/audit/receipts/{id}
     */
    @GetMapping("/receipts/{id}")
    public ResponseEntity<AuditReceipt> getReceipt(@PathVariable UUID id) {
        AuditReceipt receipt = auditService.getReceipt(id);
        return ResponseEntity.ok(receipt);
    }

    /**
     * Get receipts for a resource.
     * GET /api/v1/audit/receipts/resource/{resourceId}
     */
    @GetMapping("/receipts/resource/{resourceId}")
    public ResponseEntity<List<AuditReceipt>> getReceiptsByResource(
            @PathVariable UUID resourceId) {
        List<AuditReceipt> receipts = auditService.getReceiptsByResource(resourceId);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Get receipts by event type.
     * GET /api/v1/audit/receipts/type/{eventType}
     */
    @GetMapping("/receipts/type/{eventType}")
    public ResponseEntity<Page<AuditReceipt>> getReceiptsByType(
            @PathVariable EventType eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditReceipt> receipts = auditService.getReceiptsByEventType(eventType, pageable);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Get receipts in a time range.
     * GET /api/v1/audit/receipts/range
     */
    @GetMapping("/receipts/range")
    public ResponseEntity<Page<AuditReceipt>> getReceiptsByRange(
            @RequestParam Instant start,
            @RequestParam Instant end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditReceipt> receipts = auditService.getReceiptsByTimeRange(start, end, pageable);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Get receipts for a consent contract.
     * GET /api/v1/audit/receipts/consent/{contractId}
     */
    @GetMapping("/receipts/consent/{contractId}")
    public ResponseEntity<List<AuditReceipt>> getReceiptsByConsent(
            @PathVariable UUID contractId) {
        List<AuditReceipt> receipts = auditService.getReceiptsByConsentContract(contractId);
        return ResponseEntity.ok(receipts);
    }

    /**
     * Verify receipt integrity.
     * POST /api/v1/audit/receipts/{id}/verify
     */
    @PostMapping("/receipts/{id}/verify")
    public ResponseEntity<AuditService.ReceiptVerificationResult> verifyReceipt(
            @PathVariable UUID id) {
        AuditService.ReceiptVerificationResult result = auditService.verifyReceiptIntegrity(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Verify Merkle proof against a root.
     * POST /api/v1/audit/receipts/{id}/verify-merkle
     */
    @PostMapping("/receipts/{id}/verify-merkle")
    public ResponseEntity<AuditService.MerkleVerificationResult> verifyMerkleProof(
            @PathVariable UUID id,
            @RequestBody MerkleVerifyRequest request) {
        AuditService.MerkleVerificationResult result = 
                auditService.verifyMerkleProof(id, request.expectedRoot());
        return ResponseEntity.ok(result);
    }

    /**
     * Trigger Merkle tree anchoring batch (admin endpoint).
     * POST /api/v1/audit/anchor
     */
    @PostMapping("/anchor")
    public ResponseEntity<AuditService.MerkleAnchorResult> anchorBatch() {
        AuditService.MerkleAnchorResult result = auditService.anchorBatch();
        return ResponseEntity.ok(result);
    }

    // Request DTOs
    public record MerkleVerifyRequest(String expectedRoot) {}

    // Exception handlers
    @ExceptionHandler(AuditService.ReceiptNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AuditService.ReceiptNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("AUDIT_001", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
