package com.yachaq.core.repository;

import com.yachaq.core.domain.CanonicalEvent;
import com.yachaq.core.domain.CanonicalEvent.EventCategory;
import com.yachaq.core.domain.CanonicalEvent.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Canonical Event entities.
 * 
 * Validates: Requirement 191 (Canonical Event Bus)
 */
@Repository
public interface CanonicalEventRepository extends JpaRepository<CanonicalEvent, UUID> {

    /**
     * Finds an event by idempotency key (for duplicate detection).
     * Requirement 191.7: Handle at-least-once delivery with idempotent processing.
     */
    Optional<CanonicalEvent> findByIdempotencyKey(String idempotencyKey);

    /**
     * Checks if an event with the idempotency key already exists.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Finds events by trace ID (for distributed tracing).
     * Requirement 191.6: Include correlation IDs for distributed tracing.
     */
    List<CanonicalEvent> findByTraceIdOrderByTimestampAsc(UUID traceId);

    /**
     * Finds events by correlation ID.
     */
    List<CanonicalEvent> findByCorrelationIdOrderByTimestampAsc(UUID correlationId);

    /**
     * Finds events by status (for processing queue).
     */
    List<CanonicalEvent> findByStatusOrderByTimestampAsc(EventStatus status);

    /**
     * Finds pending events for processing.
     */
    @Query("SELECT e FROM CanonicalEvent e WHERE e.status = 'PENDING' ORDER BY e.timestamp ASC")
    List<CanonicalEvent> findPendingEvents();

    /**
     * Finds failed events that can be retried.
     */
    @Query("SELECT e FROM CanonicalEvent e WHERE e.status = 'FAILED' AND e.retryCount < :maxRetries ORDER BY e.timestamp ASC")
    List<CanonicalEvent> findRetryableEvents(@Param("maxRetries") int maxRetries);

    /**
     * Finds events by type and time range.
     */
    List<CanonicalEvent> findByEventTypeAndTimestampBetweenOrderByTimestampAsc(
            EventCategory eventType, Instant start, Instant end);

    /**
     * Finds events by actor.
     */
    List<CanonicalEvent> findByActorIdOrderByTimestampDesc(UUID actorId);

    /**
     * Finds events by resource.
     */
    List<CanonicalEvent> findByResourceIdOrderByTimestampDesc(UUID resourceId);

    /**
     * Counts events by status (for monitoring).
     * Requirement 191.10: Track event volume, latency, and processing errors.
     */
    long countByStatus(EventStatus status);

    /**
     * Counts events by type in time range (for metrics).
     */
    @Query("SELECT COUNT(e) FROM CanonicalEvent e WHERE e.eventType = :eventType AND e.timestamp BETWEEN :start AND :end")
    long countByEventTypeInTimeRange(
            @Param("eventType") EventCategory eventType,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Finds dead-letter events for alerting.
     * Requirement 191.8: Route to dead-letter queue with alerting.
     */
    @Query("SELECT e FROM CanonicalEvent e WHERE e.status = 'DEAD_LETTER' ORDER BY e.timestamp DESC")
    List<CanonicalEvent> findDeadLetterEvents();

    /**
     * Cleans up old processed events (retention policy).
     */
    @Query("DELETE FROM CanonicalEvent e WHERE e.status = 'COMPLETED' AND e.processedAt < :cutoff")
    void deleteCompletedEventsBefore(@Param("cutoff") Instant cutoff);
}
