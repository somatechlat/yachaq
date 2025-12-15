package com.yachaq.api.escrow;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.EscrowAccount;
import com.yachaq.core.domain.EscrowAccount.EscrowStatus;
import com.yachaq.core.domain.JournalEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Escrow account management service.
 * 
 * Property 3: Escrow Funding Prerequisite
 * Validates: Requirements 7.1, 7.2, 7.3
 */
@Service
public class EscrowService {

    private final EscrowRepository escrowRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AuditService auditService;

    public EscrowService(
            EscrowRepository escrowRepository,
            JournalEntryRepository journalEntryRepository,
            AuditService auditService) {
        this.escrowRepository = escrowRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.auditService = auditService;
    }

    @Transactional
    public EscrowAccountDto createEscrow(UUID requesterId, UUID requestId) {
        if (escrowRepository.existsByRequestId(requestId)) {
            throw new EscrowAlreadyExistsException("Escrow already exists for request: " + requestId);
        }

        EscrowAccount escrow = EscrowAccount.create(requesterId, requestId);
        EscrowAccount saved = escrowRepository.save(escrow);

        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_CREATED,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                saved.getId(),
                "EscrowAccount",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    @Transactional
    public EscrowAccountDto fundEscrow(UUID escrowId, BigDecimal amount, UUID requesterId) {
        EscrowAccount escrow = escrowRepository.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException("Escrow not found: " + escrowId));

        if (!escrow.getRequesterId().equals(requesterId)) {
            throw new UnauthorizedEscrowAccessException("Not authorized to fund this escrow");
        }

        escrow.fund(amount);
        EscrowAccount saved = escrowRepository.save(escrow);

        // Record journal entry: Debit REQUESTER_FUNDS, Credit ESCROW
        String idempotencyKey = "FUND:" + escrowId + ":" + System.currentTimeMillis();
        JournalEntry entry = JournalEntry.create(
                "REQUESTER_FUNDS:" + requesterId,
                "ESCROW:" + escrowId,
                amount,
                "USD",
                "Escrow funding for request",
                idempotencyKey
        );
        journalEntryRepository.save(entry);

        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_FUNDED,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                escrowId,
                "EscrowAccount",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    @Transactional
    public EscrowAccountDto lockEscrow(UUID escrowId, BigDecimal amount) {
        EscrowAccount escrow = escrowRepository.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException("Escrow not found: " + escrowId));

        escrow.lock(amount);
        EscrowAccount saved = escrowRepository.save(escrow);

        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_LOCKED,
                escrow.getRequesterId(),
                AuditReceipt.ActorType.SYSTEM,
                escrowId,
                "EscrowAccount",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }


    @Transactional
    public EscrowAccountDto releaseEscrow(UUID escrowId, BigDecimal amount, UUID dsId) {
        EscrowAccount escrow = escrowRepository.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException("Escrow not found: " + escrowId));

        escrow.release(amount);
        EscrowAccount saved = escrowRepository.save(escrow);

        // Record journal entry: Debit ESCROW, Credit DS_BALANCE
        String idempotencyKey = "RELEASE:" + escrowId + ":" + dsId + ":" + System.currentTimeMillis();
        JournalEntry entry = JournalEntry.create(
                "ESCROW:" + escrowId,
                "DS_BALANCE:" + dsId,
                amount,
                "USD",
                "Settlement release to DS",
                idempotencyKey
        );
        journalEntryRepository.save(entry);

        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_RELEASED,
                escrow.getRequesterId(),
                AuditReceipt.ActorType.SYSTEM,
                escrowId,
                "EscrowAccount",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    @Transactional
    public EscrowAccountDto refundEscrow(UUID escrowId, BigDecimal amount, UUID requesterId) {
        EscrowAccount escrow = escrowRepository.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException("Escrow not found: " + escrowId));

        if (!escrow.getRequesterId().equals(requesterId)) {
            throw new UnauthorizedEscrowAccessException("Not authorized to refund this escrow");
        }

        escrow.refund(amount);
        EscrowAccount saved = escrowRepository.save(escrow);

        // Record journal entry: Debit ESCROW, Credit REQUESTER_FUNDS
        String idempotencyKey = "REFUND:" + escrowId + ":" + System.currentTimeMillis();
        JournalEntry entry = JournalEntry.create(
                "ESCROW:" + escrowId,
                "REQUESTER_FUNDS:" + requesterId,
                amount,
                "USD",
                "Escrow refund to requester",
                idempotencyKey
        );
        journalEntryRepository.save(entry);

        auditService.appendReceipt(
                AuditReceipt.EventType.ESCROW_REFUNDED,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                escrowId,
                "EscrowAccount",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    public EscrowAccountDto getEscrow(UUID escrowId) {
        EscrowAccount escrow = escrowRepository.findById(escrowId)
                .orElseThrow(() -> new EscrowNotFoundException("Escrow not found: " + escrowId));
        return toDto(escrow);
    }

    public Optional<EscrowAccountDto> getEscrowByRequestId(UUID requestId) {
        return escrowRepository.findByRequestId(requestId).map(this::toDto);
    }

    /**
     * Property 3: Escrow Funding Prerequisite
     * Checks if escrow is sufficiently funded for request delivery.
     */
    public boolean isEscrowSufficientlyFunded(UUID requestId, BigDecimal requiredAmount) {
        return escrowRepository.findByRequestId(requestId)
                .map(escrow -> escrow.isSufficientlyFunded(requiredAmount) 
                        && (escrow.getStatus() == EscrowStatus.FUNDED || escrow.getStatus() == EscrowStatus.LOCKED))
                .orElse(false);
    }

    private String computeDetailsHash(EscrowAccount escrow) {
        String data = String.join("|",
                escrow.getId().toString(),
                escrow.getStatus().name(),
                escrow.getFundedAmount().toPlainString(),
                escrow.getLockedAmount().toPlainString()
        );
        return com.yachaq.api.audit.MerkleTree.sha256(data);
    }

    private EscrowAccountDto toDto(EscrowAccount escrow) {
        return new EscrowAccountDto(
                escrow.getId(),
                escrow.getRequesterId(),
                escrow.getRequestId(),
                escrow.getFundedAmount(),
                escrow.getLockedAmount(),
                escrow.getReleasedAmount(),
                escrow.getRefundedAmount(),
                escrow.getStatus(),
                escrow.getBlockchainTxHash(),
                escrow.getCreatedAt()
        );
    }

    public record EscrowAccountDto(
            UUID id,
            UUID requesterId,
            UUID requestId,
            BigDecimal fundedAmount,
            BigDecimal lockedAmount,
            BigDecimal releasedAmount,
            BigDecimal refundedAmount,
            EscrowStatus status,
            String blockchainTxHash,
            java.time.Instant createdAt
    ) {}

    public static class EscrowNotFoundException extends RuntimeException {
        public EscrowNotFoundException(String message) { super(message); }
    }

    public static class EscrowAlreadyExistsException extends RuntimeException {
        public EscrowAlreadyExistsException(String message) { super(message); }
    }

    public static class UnauthorizedEscrowAccessException extends RuntimeException {
        public UnauthorizedEscrowAccessException(String message) { super(message); }
    }
}
