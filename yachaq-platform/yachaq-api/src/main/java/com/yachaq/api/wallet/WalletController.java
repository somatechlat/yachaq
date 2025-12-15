package com.yachaq.api.wallet;

import com.yachaq.api.settlement.DSBalance;
import com.yachaq.api.settlement.PayoutInstruction;
import com.yachaq.api.settlement.PayoutService;
import com.yachaq.api.settlement.SettlementService;
import com.yachaq.api.token.YCToken;
import com.yachaq.api.token.YCTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Wallet REST API for DS earnings and payouts.
 * 
 * Requirements: 11.1, 11.4, 192.1
 * - View earnings balance
 * - Request payouts
 * - View YC token balance
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final SettlementService settlementService;
    private final PayoutService payoutService;
    private final YCTokenService ycTokenService;

    public WalletController(SettlementService settlementService,
                           PayoutService payoutService,
                           YCTokenService ycTokenService) {
        this.settlementService = settlementService;
        this.payoutService = payoutService;
        this.ycTokenService = ycTokenService;
    }

    /**
     * Get DS balance.
     * GET /api/v1/wallet/balance
     */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> getBalance(@RequestHeader("X-DS-ID") UUID dsId) {
        DSBalance balance = settlementService.getOrCreateBalance(dsId);
        BigDecimal ycBalance = ycTokenService.getBalance(dsId);
        
        return ResponseEntity.ok(new BalanceResponse(
            dsId,
            balance.getAvailableBalance(),
            balance.getPendingBalance(),
            balance.getTotalEarned(),
            balance.getTotalPaidOut(),
            balance.getCurrency(),
            ycBalance
        ));
    }

    /**
     * Request payout.
     * POST /api/v1/wallet/payout
     */
    @PostMapping("/payout")
    public ResponseEntity<PayoutResponse> requestPayout(
            @RequestHeader("X-DS-ID") UUID dsId,
            @Valid @RequestBody PayoutRequest request) {
        
        PayoutInstruction instruction = payoutService.createPayoutInstruction(
            dsId,
            request.amount(),
            request.method(),
            request.destination()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED).body(new PayoutResponse(
            instruction.getId(),
            instruction.getAmount(),
            instruction.getStatus().name(),
            instruction.getMethod().name(),
            instruction.getCreatedAt().toString()
        ));
    }

    /**
     * Get payout history.
     * GET /api/v1/wallet/payouts
     */
    @GetMapping("/payouts")
    public ResponseEntity<List<PayoutInstruction>> getPayouts(
            @RequestHeader("X-DS-ID") UUID dsId) {
        List<PayoutInstruction> payouts = payoutService.getPayoutHistory(dsId);
        return ResponseEntity.ok(payouts);
    }

    /**
     * Get YC token transactions.
     * GET /api/v1/wallet/yc/transactions
     */
    @GetMapping("/yc/transactions")
    public ResponseEntity<List<YCToken>> getYCTransactions(
            @RequestHeader("X-DS-ID") UUID dsId) {
        List<YCToken> tokens = ycTokenService.getTokensByHolder(dsId);
        return ResponseEntity.ok(tokens);
    }

    // DTOs
    public record BalanceResponse(
        UUID dsId,
        BigDecimal availableBalance,
        BigDecimal pendingBalance,
        BigDecimal totalEarned,
        BigDecimal totalPaidOut,
        String currency,
        BigDecimal ycBalance
    ) {}

    public record PayoutRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull PayoutService.PayoutMethod method,
        @NotNull String destination
    ) {}

    public record PayoutResponse(
        UUID payoutId,
        BigDecimal amount,
        String status,
        String method,
        String createdAt
    ) {}

    // Exception handlers
    @ExceptionHandler(PayoutService.InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(
            PayoutService.InsufficientBalanceException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("WALLET_001", e.getMessage()));
    }

    @ExceptionHandler(PayoutService.PayoutBlockedException.class)
    public ResponseEntity<ErrorResponse> handlePayoutBlocked(
            PayoutService.PayoutBlockedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("WALLET_002", e.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
