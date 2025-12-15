package com.yachaq.api.screening;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Request;
import com.yachaq.core.domain.Request.RequestStatus;
import com.yachaq.core.domain.Request.UnitType;
import com.yachaq.core.repository.RequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RequestService {

    private final RequestRepository requestRepository;
    private final ScreeningService screeningService;
    private final AuditService auditService;

    public RequestService(
            RequestRepository requestRepository,
            ScreeningService screeningService,
            AuditService auditService) {
        this.requestRepository = requestRepository;
        this.screeningService = screeningService;
        this.auditService = auditService;
    }

    @Transactional
    public RequestDto createRequest(CreateRequestCommand command) {
        validateCreateCommand(command);

        Request request = Request.create(
                command.requesterId(),
                command.purpose(),
                command.scope(),
                command.eligibilityCriteria(),
                command.durationStart(),
                command.durationEnd(),
                command.unitType(),
                command.unitPrice(),
                command.maxParticipants(),
                command.budget()
        );

        Request saved = requestRepository.save(request);

        auditService.appendReceipt(
                AuditReceipt.EventType.REQUEST_CREATED,
                command.requesterId(),
                AuditReceipt.ActorType.REQUESTER,
                saved.getId(),
                "Request",
                computeDetailsHash(saved)
        );

        return toDto(saved);
    }

    @Transactional
    public ScreeningService.ScreeningResultDto submitForScreening(UUID requestId, UUID requesterId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ScreeningService.RequestNotFoundException("Request not found: " + requestId));

        if (!request.getRequesterId().equals(requesterId)) {
            throw new UnauthorizedRequestAccessException("Not authorized to submit this request");
        }

        request.submitForScreening();
        requestRepository.save(request);

        return screeningService.screenRequest(requestId);
    }

    public RequestDto getRequest(UUID requestId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ScreeningService.RequestNotFoundException("Request not found: " + requestId));
        return toDto(request);
    }

    public Page<RequestDto> getRequestsByRequester(UUID requesterId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return requestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable)
                .map(this::toDto);
    }

    public Page<RequestDto> getRequestsByStatus(RequestStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return requestRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                .map(this::toDto);
    }

    @Transactional
    public RequestDto cancelRequest(UUID requestId, UUID requesterId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ScreeningService.RequestNotFoundException("Request not found: " + requestId));

        if (!request.getRequesterId().equals(requesterId)) {
            throw new UnauthorizedRequestAccessException("Not authorized to cancel this request");
        }

        request.cancel();
        Request saved = requestRepository.save(request);
        return toDto(saved);
    }

    @Transactional
    public RequestDto linkEscrow(UUID requestId, UUID escrowId) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ScreeningService.RequestNotFoundException("Request not found: " + requestId));
        request.linkEscrow(escrowId);
        return toDto(requestRepository.save(request));
    }

    private void validateCreateCommand(CreateRequestCommand command) {
        if (command.requesterId() == null) throw new InvalidRequestException("Requester ID is required");
        if (command.purpose() == null || command.purpose().isBlank()) throw new InvalidRequestException("Purpose is required");
        if (command.scope() == null || command.scope().isEmpty()) throw new InvalidRequestException("Scope is required");
        if (command.eligibilityCriteria() == null) throw new InvalidRequestException("Eligibility criteria is required");
        if (command.durationStart() == null || command.durationEnd() == null) throw new InvalidRequestException("Duration start and end are required");
        if (command.durationEnd().isBefore(command.durationStart())) throw new InvalidRequestException("Duration end must be after start");
        if (command.unitType() == null) throw new InvalidRequestException("Unit type is required");
        if (command.unitPrice() == null || command.unitPrice().compareTo(BigDecimal.ZERO) <= 0) throw new InvalidRequestException("Unit price must be positive");
        if (command.maxParticipants() == null || command.maxParticipants() <= 0) throw new InvalidRequestException("Max participants must be positive");
        if (command.budget() == null || command.budget().compareTo(BigDecimal.ZERO) <= 0) throw new InvalidRequestException("Budget must be positive");
    }

    private String computeDetailsHash(Request request) {
        String data = String.join("|",
                request.getId().toString(),
                request.getStatus().name(),
                request.getPurpose(),
                request.getUnitPrice().toPlainString()
        );
        return com.yachaq.api.audit.MerkleTree.sha256(data);
    }

    private RequestDto toDto(Request request) {
        return new RequestDto(
                request.getId(),
                request.getRequesterId(),
                request.getPurpose(),
                request.getScope(),
                request.getEligibilityCriteria(),
                request.getDurationStart(),
                request.getDurationEnd(),
                request.getUnitType(),
                request.getUnitPrice(),
                request.getMaxParticipants(),
                request.getBudget(),
                request.getEscrowId(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getSubmittedAt()
        );
    }

    public record CreateRequestCommand(
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

    public record RequestDto(
            UUID id,
            UUID requesterId,
            String purpose,
            Map<String, Object> scope,
            Map<String, Object> eligibilityCriteria,
            Instant durationStart,
            Instant durationEnd,
            UnitType unitType,
            BigDecimal unitPrice,
            Integer maxParticipants,
            BigDecimal budget,
            UUID escrowId,
            RequestStatus status,
            Instant createdAt,
            Instant submittedAt
    ) {}

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }

    public static class UnauthorizedRequestAccessException extends RuntimeException {
        public UnauthorizedRequestAccessException(String message) { super(message); }
    }
}
