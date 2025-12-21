package com.yachaq.node.queue;

import com.yachaq.node.queue.OfflineQueueService.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for OfflineQueueService.
 * 
 * **Feature: yachaq-platform, Property 66: Offline Queue Persistence**
 * **Validates: Requirements 360.1, 360.3**
 */
class OfflineQueuePropertyTest {

    // ========================================================================
    // Property 1: Enqueue Always Succeeds for Valid Operations
    // For any valid operation, enqueue must succeed and return entry ID
    // ========================================================================
    
    @Property(tries = 100)
    @Label("Enqueue always succeeds for valid operations")
    void enqueueAlwaysSucceedsForValidOperations(
            @ForAll("operationTypes") OperationType type,
            @ForAll("priorities") Priority priority) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            QueueOperation operation = new QueueOperation(type, Map.of("test", "data"), priority);
            EnqueueResult result = service.enqueue(operation);
            
            assertThat(result.success())
                .as("Enqueue must succeed for valid operation")
                .isTrue();
            
            assertThat(result.entryId())
                .as("Entry ID must be returned")
                .isNotNull()
                .isNotEmpty();
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 2: Enqueued Operations Are Retrievable
    // For any enqueued operation, it must be retrievable by ID
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Enqueued operations are retrievable by ID")
    void enqueuedOperationsAreRetrievable(
            @ForAll("operationTypes") OperationType type) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            QueueOperation operation = new QueueOperation(type, Map.of("key", "value"), Priority.NORMAL);
            EnqueueResult result = service.enqueue(operation);
            
            Optional<QueueEntry> entry = service.getEntry(result.entryId());
            
            assertThat(entry)
                .as("Enqueued entry must be retrievable")
                .isPresent();
            
            assertThat(entry.get().type())
                .as("Entry type must match")
                .isEqualTo(type);
            
            assertThat(entry.get().status())
                .as("Initial status must be PENDING")
                .isEqualTo(QueueEntryStatus.PENDING);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 3: Queue Status Reflects Enqueued Count
    // For any number of enqueued operations, status must reflect correct count
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Queue status reflects enqueued count")
    void queueStatusReflectsEnqueuedCount(
            @ForAll @IntRange(min = 1, max = 10) int count) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            for (int i = 0; i < count; i++) {
                QueueOperation operation = new QueueOperation(
                    OperationType.CONTRACT_SIGN, 
                    Map.of("index", i), 
                    Priority.NORMAL
                );
                service.enqueue(operation);
            }
            
            QueueStatus status = service.getQueueStatus();
            
            assertThat(status.queueSize())
                .as("Queue size must match enqueued count")
                .isEqualTo(count);
            
            assertThat(status.pending())
                .as("Pending count must match enqueued count")
                .isEqualTo(count);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 4: Processing When Online Processes All Entries
    // For any queued operations, going online must process all
    // **Property 66: Offline Queue Persistence**
    // **Validates: Requirements 360.1, 360.3**
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Processing when online processes all entries")
    void processingWhenOnlineProcessesAll(
            @ForAll @IntRange(min = 1, max = 5) int count) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            // Enqueue while offline
            for (int i = 0; i < count; i++) {
                QueueOperation operation = new QueueOperation(
                    OperationType.PLAN_EXECUTE, 
                    Map.of("index", i), 
                    Priority.NORMAL
                );
                service.enqueue(operation);
            }
            
            // Go online - this triggers automatic processing via setOnline()
            service.setOnline(true);
            
            // Wait briefly for processing to complete (setOnline triggers processQueue internally)
            Thread.sleep(100);
            
            // Verify all entries are completed after going online
            QueueStatus status = service.getQueueStatus();
            assertThat(status.completed())
                .as("All entries must be completed after going online")
                .isEqualTo(count);
            
            assertThat(status.pending())
                .as("No entries should remain pending")
                .isEqualTo(0);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 5: Offline Processing Is Deferred
    // For any operations when offline, processing must be deferred
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Offline processing is deferred")
    void offlineProcessingIsDeferred(
            @ForAll @IntRange(min = 1, max = 5) int count) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            // Enqueue while offline (default state)
            for (int i = 0; i < count; i++) {
                QueueOperation operation = new QueueOperation(
                    OperationType.CAPSULE_UPLOAD, 
                    Map.of("index", i), 
                    Priority.NORMAL
                );
                service.enqueue(operation);
            }
            
            // Try to process while offline
            ProcessingResult result = service.processQueue();
            
            assertThat(result.processed())
                .as("No entries should be processed while offline")
                .isEqualTo(0);
            
            QueueStatus status = service.getQueueStatus();
            assertThat(status.pending())
                .as("All entries should remain pending")
                .isEqualTo(count);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 6: Transfer Enqueue Includes Resumability Data
    // For any transfer operation, resumability data must be preserved
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Transfer enqueue includes resumability data")
    void transferEnqueueIncludesResumabilityData(
            @ForAll @IntRange(min = 1, max = 100) int totalChunks,
            @ForAll @IntRange(min = 0, max = 50) int completedChunks) throws Exception {
        
        Assume.that(completedChunks <= totalChunks);
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            String capsuleId = UUID.randomUUID().toString();
            String resumeToken = UUID.randomUUID().toString();
            
            TransferOperation transfer = new TransferOperation(
                capsuleId,
                "https://example.com/upload",
                totalChunks,
                completedChunks,
                List.of("hash1", "hash2"),
                resumeToken
            );
            
            EnqueueResult result = service.enqueueTransfer(transfer);
            
            assertThat(result.success()).isTrue();
            
            Optional<QueueEntry> entry = service.getEntry(result.entryId());
            assertThat(entry).isPresent();
            assertThat(entry.get().type()).isEqualTo(OperationType.P2P_TRANSFER);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) entry.get().payload();
            assertThat(payload.get("capsuleId")).isEqualTo(capsuleId);
            assertThat(payload.get("totalChunks")).isEqualTo(totalChunks);
            assertThat(payload.get("completedChunks")).isEqualTo(completedChunks);
            assertThat(payload.get("resumeToken")).isEqualTo(resumeToken);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 7: Transfer Progress Updates Are Persisted
    // For any transfer progress update, changes must be persisted
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Transfer progress updates are persisted")
    void transferProgressUpdatesArePersisted(
            @ForAll @IntRange(min = 10, max = 100) int totalChunks) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            TransferOperation transfer = new TransferOperation(
                UUID.randomUUID().toString(),
                "https://example.com/upload",
                totalChunks,
                0,
                List.of(),
                "initial-token"
            );
            
            EnqueueResult result = service.enqueueTransfer(transfer);
            
            // Update progress
            int newCompleted = totalChunks / 2;
            String newToken = "updated-token";
            UpdateResult updateResult = service.updateTransferProgress(
                result.entryId(), newCompleted, newToken
            );
            
            assertThat(updateResult.success()).isTrue();
            
            // Verify update
            Optional<QueueEntry> entry = service.getEntry(result.entryId());
            assertThat(entry).isPresent();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) entry.get().payload();
            assertThat(payload.get("completedChunks")).isEqualTo(newCompleted);
            assertThat(payload.get("resumeToken")).isEqualTo(newToken);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 8: Entry Timestamps Are Always Set
    // For any queue entry, timestamps must be set correctly
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Entry timestamps are always set")
    void entryTimestampsAreAlwaysSet(
            @ForAll("operationTypes") OperationType type) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            java.time.Instant before = java.time.Instant.now();
            
            QueueOperation operation = new QueueOperation(type, Map.of(), Priority.NORMAL);
            EnqueueResult result = service.enqueue(operation);
            
            java.time.Instant after = java.time.Instant.now();
            
            Optional<QueueEntry> entry = service.getEntry(result.entryId());
            assertThat(entry).isPresent();
            
            assertThat(entry.get().createdAt())
                .as("Created timestamp must be set")
                .isNotNull()
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 9: Online Status Is Correctly Tracked
    // For any online/offline transitions, status must be accurate
    // ========================================================================
    
    @Property(tries = 30)
    @Label("Online status is correctly tracked")
    void onlineStatusIsCorrectlyTracked(
            @ForAll @Size(min = 1, max = 10) List<Boolean> transitions) throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            for (boolean online : transitions) {
                service.setOnline(online);
                QueueStatus status = service.getQueueStatus();
                
                assertThat(status.isOnline())
                    .as("Online status must match set value")
                    .isEqualTo(online);
            }
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Property 10: Non-Existent Entry Returns Empty
    // For any non-existent entry ID, getEntry must return empty
    // ========================================================================
    
    @Property(tries = 50)
    @Label("Non-existent entry returns empty")
    void nonExistentEntryReturnsEmpty() throws Exception {
        
        Path dir = Files.createTempDirectory("queue-test");
        OfflineQueueService service = new OfflineQueueService(dir);
        
        try {
            String randomId = UUID.randomUUID().toString();
            Optional<QueueEntry> entry = service.getEntry(randomId);
            
            assertThat(entry)
                .as("Non-existent entry must return empty")
                .isEmpty();
        } finally {
            service.shutdown();
            deleteDir(dir);
        }
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<OperationType> operationTypes() {
        return Arbitraries.of(OperationType.values());
    }

    @Provide
    Arbitrary<Priority> priorities() {
        return Arbitraries.of(Priority.values());
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) {}
                });
        } catch (Exception e) {}
    }
}
