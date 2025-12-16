package com.yachaq.api.event;

import com.yachaq.core.domain.CanonicalEvent;
import com.yachaq.core.domain.CanonicalEvent.ActorType;
import com.yachaq.core.domain.CanonicalEvent.EventCategory;
import com.yachaq.core.domain.CanonicalEvent.EventStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

/**
 * Property-based tests for Canonical Event System.
 * 
 * **Feature: yachaq-platform, Task 36: Canonical Event System**
 * Tests event emission, idempotency, and processing.
 * 
 * **Validates: Requirement 191 (Canonical Event Bus)**
 */
class CanonicalEventSystemPropertyTest {

    // ==================== Event Creation Properties ====================

    /**
     * Property: Events are created with all required fields.
     * **Validates: Requirement 191.5, 191.6**
     */
    @Property(tries = 100)
    void eventCreation_hasAllRequiredFields(
            @ForAll("eventCategories") EventCategory eventType,
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String eventName,
            @ForAll @AlphaChars @StringLength(min = 10, max = 100) String payloadSummary) {
        
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        
        CanonicalEvent event = CanonicalEvent.builder()
                .eventType(eventType)
                .eventName(eventName)
                .idempotencyKey(idempotencyKey)
                .actorId(actorId)
                .actorType(ActorType.DS)
                .resourceId(resourceId)
                .resourceType("TestResource")
                .payloadHash("abc123hash")
                .payloadSummary(payloadSummary)
                .build();

        assert event.getId() != null : "Event ID should be generated";
        assert event.getEventType() == eventType : "Event type should match";
        assert event.getEventName().equals(eventName) : "Event name should match";
        assert event.getTraceId() != null : "Trace ID should be generated";
        assert event.getIdempotencyKey().equals(idempotencyKey) : "Idempotency key should match";
        assert event.getActorId().equals(actorId) : "Actor ID should match";
        assert event.getActorType() == ActorType.DS : "Actor type should match";
        assert event.getTimestamp() != null : "Timestamp should be set";
        assert event.getStatus() == EventStatus.PENDING : "Initial status should be PENDING";
        assert event.getSchemaVersion() != null : "Schema version should be set";
    }

    /**
     * Property: Events require idempotency key.
     * **Validates: Requirement 191.7**
     */
    @Property(tries = 50)
    void eventCreation_requiresIdempotencyKey(
            @ForAll("eventCategories") EventCategory eventType,
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String eventName) {
        
        try {
            CanonicalEvent.builder()
                    .eventType(eventType)
                    .eventName(eventName)
                    .actorId(UUID.randomUUID())
                    .actorType(ActorType.DS)
                    .payloadHash("hash")
                    // Missing idempotencyKey
                    .build();
            assert false : "Should throw exception for missing idempotency key";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("Idempotency key") : 
                    "Exception should mention idempotency key";
        }
    }

    /**
     * Property: Events require actor ID.
     */
    @Property(tries = 50)
    void eventCreation_requiresActorId(
            @ForAll("eventCategories") EventCategory eventType,
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String eventName) {
        
        try {
            CanonicalEvent.builder()
                    .eventType(eventType)
                    .eventName(eventName)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .actorType(ActorType.DS)
                    .payloadHash("hash")
                    // Missing actorId
                    .build();
            assert false : "Should throw exception for missing actor ID";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("Actor ID") : 
                    "Exception should mention actor ID";
        }
    }

    /**
     * Property: Events require payload hash (no raw PII).
     * **Validates: Requirement 191.5**
     */
    @Property(tries = 50)
    void eventCreation_requiresPayloadHash(
            @ForAll("eventCategories") EventCategory eventType,
            @ForAll @AlphaChars @StringLength(min = 5, max = 50) String eventName) {
        
        try {
            CanonicalEvent.builder()
                    .eventType(eventType)
                    .eventName(eventName)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .actorId(UUID.randomUUID())
                    .actorType(ActorType.DS)
                    // Missing payloadHash
                    .build();
            assert false : "Should throw exception for missing payload hash";
        } catch (IllegalStateException e) {
            assert e.getMessage().contains("Payload hash") : 
                    "Exception should mention payload hash";
        }
    }

    // ==================== Event Lifecycle Properties ====================

    /**
     * Property: Events transition through correct lifecycle states.
     */
    @Property(tries = 100)
    void eventLifecycle_correctStateTransitions(
            @ForAll("eventCategories") EventCategory eventType) {
        
        CanonicalEvent event = createTestEvent(eventType);
        
        // Initial state
        assert event.getStatus() == EventStatus.PENDING : 
                "Initial status should be PENDING";
        
        // Mark processing
        event.markProcessing();
        assert event.getStatus() == EventStatus.PROCESSING : 
                "Status should be PROCESSING";
        
        // Mark completed
        event.markCompleted();
        assert event.getStatus() == EventStatus.COMPLETED : 
                "Status should be COMPLETED";
        assert event.getProcessedAt() != null : 
                "Processed timestamp should be set";
    }

    /**
     * Property: Failed events track retry count.
     * **Validates: Requirement 191.8**
     */
    @Property(tries = 100)
    void eventLifecycle_failedEventsTrackRetries(
            @ForAll("eventCategories") EventCategory eventType,
            @ForAll @IntRange(min = 1, max = 5) int failureCount) {
        
        CanonicalEvent event = createTestEvent(eventType);
        
        for (int i = 0; i < failureCount; i++) {
            event.markFailed("Error " + i);
        }
        
        assert event.getStatus() == EventStatus.FAILED : 
                "Status should be FAILED";
        assert event.getRetryCount() == failureCount : 
                "Retry count should be " + failureCount;
        assert event.getErrorMessage() != null : 
                "Error message should be set";
    }

    /**
     * Property: Events can be retried up to max retries.
     */
    @Property(tries = 100)
    void eventLifecycle_canRetryUpToMaxRetries(
            @ForAll @IntRange(min = 1, max = 10) int maxRetries,
            @ForAll @IntRange(min = 0, max = 15) int currentRetries) {
        
        CanonicalEvent event = createTestEvent(EventCategory.REQUEST);
        
        // Simulate retries
        for (int i = 0; i < currentRetries; i++) {
            event.markFailed("Error");
        }
        
        boolean canRetry = event.canRetry(maxRetries);
        boolean expected = event.getStatus() == EventStatus.FAILED && currentRetries < maxRetries;
        
        assert canRetry == expected : 
                "canRetry should be " + expected + " for retries=" + currentRetries + ", max=" + maxRetries;
    }

    /**
     * Property: Events move to dead-letter queue after max retries.
     * **Validates: Requirement 191.8**
     */
    @Property(tries = 50)
    void eventLifecycle_movesToDeadLetterAfterMaxRetries(
            @ForAll("eventCategories") EventCategory eventType) {
        
        CanonicalEvent event = createTestEvent(eventType);
        
        event.moveToDeadLetter("Max retries exceeded");
        
        assert event.getStatus() == EventStatus.DEAD_LETTER : 
                "Status should be DEAD_LETTER";
        assert event.getErrorMessage().contains("Max retries") : 
                "Error message should indicate reason";
    }

    // ==================== Trace ID Properties ====================

    /**
     * Property: Events with same trace ID can be correlated.
     * **Validates: Requirement 191.6**
     */
    @Property(tries = 50)
    void traceId_enablesCorrelation() {
        UUID sharedTraceId = UUID.randomUUID();
        
        CanonicalEvent event1 = CanonicalEvent.builder()
                .eventType(EventCategory.REQUEST)
                .eventName("request.created")
                .traceId(sharedTraceId)
                .idempotencyKey(UUID.randomUUID().toString())
                .actorId(UUID.randomUUID())
                .actorType(ActorType.REQUESTER)
                .payloadHash("hash1")
                .build();
        
        CanonicalEvent event2 = CanonicalEvent.builder()
                .eventType(EventCategory.CONSENT)
                .eventName("consent.accepted")
                .traceId(sharedTraceId)
                .idempotencyKey(UUID.randomUUID().toString())
                .actorId(UUID.randomUUID())
                .actorType(ActorType.DS)
                .payloadHash("hash2")
                .build();
        
        assert event1.getTraceId().equals(event2.getTraceId()) : 
                "Events should share the same trace ID";
        assert !event1.getId().equals(event2.getId()) : 
                "Events should have different IDs";
    }

    /**
     * Property: Events without explicit trace ID get one generated.
     */
    @Property(tries = 50)
    void traceId_generatedIfNotProvided() {
        CanonicalEvent event = CanonicalEvent.builder()
                .eventType(EventCategory.REQUEST)
                .eventName("request.created")
                // No traceId provided
                .idempotencyKey(UUID.randomUUID().toString())
                .actorId(UUID.randomUUID())
                .actorType(ActorType.REQUESTER)
                .payloadHash("hash")
                .build();
        
        assert event.getTraceId() != null : 
                "Trace ID should be auto-generated";
    }

    // ==================== Event Category Properties ====================

    /**
     * Property: All event categories can be used.
     */
    @Property(tries = 50)
    void eventCategories_allCategoriesSupported() {
        for (EventCategory category : EventCategory.values()) {
            CanonicalEvent event = createTestEvent(category);
            assert event.getEventType() == category : 
                    "Event category " + category + " should be supported";
        }
    }

    /**
     * Property: All actor types can be used.
     */
    @Property(tries = 50)
    void actorTypes_allTypesSupported() {
        for (ActorType actorType : ActorType.values()) {
            CanonicalEvent event = CanonicalEvent.builder()
                    .eventType(EventCategory.REQUEST)
                    .eventName("test.event")
                    .idempotencyKey(UUID.randomUUID().toString())
                    .actorId(UUID.randomUUID())
                    .actorType(actorType)
                    .payloadHash("hash")
                    .build();
            
            assert event.getActorType() == actorType : 
                    "Actor type " + actorType + " should be supported";
        }
    }

    // ==================== Helper Methods ====================

    private CanonicalEvent createTestEvent(EventCategory eventType) {
        return CanonicalEvent.builder()
                .eventType(eventType)
                .eventName(eventType.name().toLowerCase() + ".test")
                .idempotencyKey(UUID.randomUUID().toString())
                .actorId(UUID.randomUUID())
                .actorType(ActorType.SYSTEM)
                .payloadHash("testhash")
                .build();
    }

    @Provide
    Arbitrary<EventCategory> eventCategories() {
        return Arbitraries.of(EventCategory.values());
    }
}
