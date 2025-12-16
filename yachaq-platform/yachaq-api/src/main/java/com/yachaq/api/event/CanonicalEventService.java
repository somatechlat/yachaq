package com.yachaq.api.event;

import com.yachaq.core.domain.CanonicalEvent;
import com.yachaq.core.domain.CanonicalEvent.ActorType;
import com.yachaq.core.domain.CanonicalEvent.EventCategory;
import com.yachaq.core.domain.CanonicalEvent.EventStatus;
import com.yachaq.core.repository.CanonicalEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical Event Service - Manages the canonical event bus.
 * 
 * Implements:
 * - Event emission with correlation IDs (Requirement 191.6)
 * - Idempotent processing (Requirement 191.7)
 * - Dead-letter queue routing (Requirement 191.8)
 * - Schema versioning (Requirement 191.9)
 * - Event metrics (Requirement 191.10)
 * 
 * Validates: Requirement 191 (Canonical Event Bus)
 */
@Service
public class CanonicalEventService {

    private static final int MAX_RETRIES = 3;
    private final CanonicalEventRepository eventRepository;

    public CanonicalEventService(CanonicalEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    // ==================== Event Emission ====================

    /**
     * Emits a request event.
     * Requirement 191.1: request.created, request.screened
     */
    @Transactional
    public CanonicalEvent emitRequestEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID requestId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.REQUEST,
                eventName,
                actorId,
                actorType,
                requestId,
                "Request",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a consent event.
     * Requirement 191.2: consent.accepted, consent.revoked
     */
    @Transactional
    public CanonicalEvent emitConsentEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID consentContractId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.CONSENT,
                eventName,
                actorId,
                actorType,
                consentContractId,
                "ConsentContract",
                consentContractId,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a token event.
     * Requirement 191.2: token.issued
     */
    @Transactional
    public CanonicalEvent emitTokenEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID tokenId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.TOKEN,
                eventName,
                actorId,
                actorType,
                tokenId,
                "Token",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a data access event.
     * Requirement 191.3: data.accessed
     */
    @Transactional
    public CanonicalEvent emitDataAccessEvent(
            UUID actorId,
            ActorType actorType,
            UUID resourceId,
            String resourceType,
            UUID consentContractId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.DATA,
                "data.accessed",
                actorId,
                actorType,
                resourceId,
                resourceType,
                consentContractId,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a settlement event.
     * Requirement 191.3: settlement.posted
     */
    @Transactional
    public CanonicalEvent emitSettlementEvent(
            String eventName,
            UUID actorId,
            UUID settlementId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.SETTLEMENT,
                eventName,
                actorId,
                ActorType.SYSTEM,
                settlementId,
                "Settlement",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a payout event.
     * Requirement 191.3: payout.initiated, payout.completed
     */
    @Transactional
    public CanonicalEvent emitPayoutEvent(
            String eventName,
            UUID dsId,
            UUID payoutId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.PAYOUT,
                eventName,
                dsId,
                ActorType.DS,
                payoutId,
                "Payout",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a P2P event.
     * Requirement 191.4: p2p.intent.created, p2p.payment.confirmed, p2p.delivery.completed
     */
    @Transactional
    public CanonicalEvent emitP2PEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID resourceId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.P2P,
                eventName,
                actorId,
                actorType,
                resourceId,
                "P2PTransaction",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a device event.
     */
    @Transactional
    public CanonicalEvent emitDeviceEvent(
            String eventName,
            UUID dsId,
            UUID deviceId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.DEVICE,
                eventName,
                dsId,
                ActorType.DS,
                deviceId,
                "Device",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a match event.
     */
    @Transactional
    public CanonicalEvent emitMatchEvent(
            String eventName,
            UUID requestId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.MATCH,
                eventName,
                requestId,
                ActorType.SYSTEM,
                requestId,
                "Match",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a capsule event.
     */
    @Transactional
    public CanonicalEvent emitCapsuleEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID capsuleId,
            UUID consentContractId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.CAPSULE,
                eventName,
                actorId,
                actorType,
                capsuleId,
                "TimeCapsule",
                consentContractId,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a clean room event.
     */
    @Transactional
    public CanonicalEvent emitCleanRoomEvent(
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID sessionId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.CLEAN_ROOM,
                eventName,
                actorId,
                actorType,
                sessionId,
                "CleanRoomSession",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits an account event.
     */
    @Transactional
    public CanonicalEvent emitAccountEvent(
            String eventName,
            UUID accountId,
            ActorType actorType,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.ACCOUNT,
                eventName,
                accountId,
                actorType,
                accountId,
                "Account",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits an index event.
     */
    @Transactional
    public CanonicalEvent emitIndexEvent(
            String eventName,
            UUID dsId,
            UUID indexId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.INDEX,
                eventName,
                dsId,
                ActorType.DS,
                indexId,
                "ODXIndex",
                null,
                traceId,
                payloadSummary);
    }

    /**
     * Emits a training event.
     */
    @Transactional
    public CanonicalEvent emitTrainingEvent(
            String eventName,
            UUID modelId,
            UUID traceId,
            String payloadSummary) {
        
        return emitEvent(
                EventCategory.TRAINING,
                eventName,
                modelId,
                ActorType.SYSTEM,
                modelId,
                "ModelTraining",
                null,
                traceId,
                payloadSummary);
    }


    // ==================== Core Event Emission ====================

    /**
     * Core event emission method with idempotency check.
     * Requirement 191.7: Handle at-least-once delivery with idempotent processing.
     */
    private CanonicalEvent emitEvent(
            EventCategory eventType,
            String eventName,
            UUID actorId,
            ActorType actorType,
            UUID resourceId,
            String resourceType,
            UUID consentContractId,
            UUID traceId,
            String payloadSummary) {
        
        // Generate idempotency key from event details
        String idempotencyKey = generateIdempotencyKey(
                eventType, eventName, actorId, resourceId, traceId);
        
        // Check for duplicate (idempotent processing)
        Optional<CanonicalEvent> existing = eventRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get(); // Return existing event without re-processing
        }
        
        // Create new event
        String payloadHash = computePayloadHash(payloadSummary);
        
        CanonicalEvent event = CanonicalEvent.builder()
                .eventType(eventType)
                .eventName(eventName)
                .traceId(traceId != null ? traceId : UUID.randomUUID())
                .correlationId(resourceId)
                .idempotencyKey(idempotencyKey)
                .actorId(actorId)
                .actorType(actorType)
                .resourceId(resourceId)
                .resourceType(resourceType)
                .consentContractId(consentContractId)
                .payloadHash(payloadHash)
                .payloadSummary(payloadSummary)
                .build();
        
        return eventRepository.save(event);
    }

    /**
     * Generates a unique idempotency key for the event.
     */
    private String generateIdempotencyKey(
            EventCategory eventType,
            String eventName,
            UUID actorId,
            UUID resourceId,
            UUID traceId) {
        
        String components = String.format("%s:%s:%s:%s:%s",
                eventType.name(),
                eventName,
                actorId != null ? actorId.toString() : "null",
                resourceId != null ? resourceId.toString() : "null",
                traceId != null ? traceId.toString() : UUID.randomUUID().toString());
        
        return computePayloadHash(components);
    }

    /**
     * Computes SHA-256 hash of the payload.
     * Requirement 191.5: Include minimal data plus hashes (no raw PII in events).
     */
    private String computePayloadHash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Event Processing ====================

    /**
     * Processes a pending event.
     */
    @Transactional
    public void processEvent(UUID eventId, EventProcessor processor) {
        CanonicalEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
        
        if (event.getStatus() != EventStatus.PENDING && 
            event.getStatus() != EventStatus.FAILED) {
            return; // Already processed or in dead-letter
        }
        
        event.markProcessing();
        eventRepository.save(event);
        
        try {
            processor.process(event);
            event.markCompleted();
        } catch (Exception e) {
            event.markFailed(e.getMessage());
            
            // Move to dead-letter if max retries exceeded
            if (!event.canRetry(MAX_RETRIES)) {
                event.moveToDeadLetter("Max retries exceeded: " + e.getMessage());
            }
        }
        
        eventRepository.save(event);
    }

    /**
     * Retries failed events.
     */
    @Transactional
    public int retryFailedEvents(EventProcessor processor) {
        List<CanonicalEvent> failedEvents = eventRepository.findRetryableEvents(MAX_RETRIES);
        int retried = 0;
        
        for (CanonicalEvent event : failedEvents) {
            try {
                processEvent(event.getId(), processor);
                retried++;
            } catch (Exception e) {
                // Continue with next event
            }
        }
        
        return retried;
    }

    // ==================== Event Queries ====================

    /**
     * Finds events by trace ID (distributed tracing).
     * Requirement 191.9: Support trace lookup by any correlation ID.
     */
    public List<CanonicalEvent> findByTraceId(UUID traceId) {
        return eventRepository.findByTraceIdOrderByTimestampAsc(traceId);
    }

    /**
     * Finds events by correlation ID.
     */
    public List<CanonicalEvent> findByCorrelationId(UUID correlationId) {
        return eventRepository.findByCorrelationIdOrderByTimestampAsc(correlationId);
    }

    /**
     * Finds events by actor.
     */
    public List<CanonicalEvent> findByActor(UUID actorId) {
        return eventRepository.findByActorIdOrderByTimestampDesc(actorId);
    }

    /**
     * Finds events by resource.
     */
    public List<CanonicalEvent> findByResource(UUID resourceId) {
        return eventRepository.findByResourceIdOrderByTimestampDesc(resourceId);
    }

    /**
     * Gets dead-letter events for alerting.
     * Requirement 191.8: Route to dead-letter queue with alerting.
     */
    public List<CanonicalEvent> getDeadLetterEvents() {
        return eventRepository.findDeadLetterEvents();
    }

    // ==================== Metrics ====================

    /**
     * Gets event metrics.
     * Requirement 191.10: Track event volume, latency, and processing errors.
     */
    public EventMetrics getMetrics() {
        long pending = eventRepository.countByStatus(EventStatus.PENDING);
        long processing = eventRepository.countByStatus(EventStatus.PROCESSING);
        long completed = eventRepository.countByStatus(EventStatus.COMPLETED);
        long failed = eventRepository.countByStatus(EventStatus.FAILED);
        long deadLetter = eventRepository.countByStatus(EventStatus.DEAD_LETTER);
        
        return new EventMetrics(pending, processing, completed, failed, deadLetter);
    }

    /**
     * Gets event count by type in time range.
     */
    public long countEventsByType(EventCategory eventType, Instant start, Instant end) {
        return eventRepository.countByEventTypeInTimeRange(eventType, start, end);
    }

    // ==================== Supporting Types ====================

    /**
     * Functional interface for event processing.
     */
    @FunctionalInterface
    public interface EventProcessor {
        void process(CanonicalEvent event) throws Exception;
    }

    /**
     * Event metrics record.
     */
    public record EventMetrics(
            long pending,
            long processing,
            long completed,
            long failed,
            long deadLetter) {
        
        public long total() {
            return pending + processing + completed + failed + deadLetter;
        }
        
        public double errorRate() {
            long total = total();
            return total > 0 ? (double) (failed + deadLetter) / total : 0.0;
        }
    }
}
