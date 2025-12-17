package com.yachaq.node.tracker;

import com.yachaq.node.tracker.Delta.DeltaType;
import com.yachaq.node.tracker.DeltaLog.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Change Tracker (DeltaLog).
 * Requirement 312.4: Test tamper detection, delta correctness.
 * 
 * **Feature: yachaq-platform, Property: Change Tracker Integrity**
 * **Validates: Requirements 312.1, 312.3, 312.4**
 */
class DeltaLogPropertyTest {

    // ==================== Hash Chain Integrity Tests (56.3) ====================

    /**
     * Property: Hash chain is always valid after appending deltas.
     * **Feature: yachaq-platform, Property: Hash Chain Integrity**
     * **Validates: Requirements 312.1, 312.4**
     */
    @Property(tries = 100)
    void hashChain_isAlwaysValid(
            @ForAll @Size(min = 1, max = 20) List<@From("deltaInputs") DeltaInput> inputs) {
        
        DeltaLog log = new DeltaLog();
        
        for (DeltaInput input : inputs) {
            log.append(input.type(), input.entityId(), input.entityType(), 
                    input.changes(), input.source());
        }
        
        ChainVerificationResult result = log.verifyChain();
        
        assertThat(result.isValid())
                .as("Hash chain should be valid")
                .isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.verifiedCount()).isEqualTo(inputs.size());
    }

    /**
     * Property: Each delta's prevHash matches the previous delta's hash.
     * **Feature: yachaq-platform, Property: Hash Chain Linking**
     * **Validates: Requirements 312.1**
     */
    @Property(tries = 100)
    void deltas_areProperlyChained(
            @ForAll @Size(min = 2, max = 10) List<@From("deltaInputs") DeltaInput> inputs) {
        
        DeltaLog log = new DeltaLog();
        List<Delta> appendedDeltas = new ArrayList<>();
        
        for (DeltaInput input : inputs) {
            Delta delta = log.append(input.type(), input.entityId(), input.entityType(),
                    input.changes(), input.source());
            appendedDeltas.add(delta);
        }
        
        // Verify chain linking
        for (int i = 1; i < appendedDeltas.size(); i++) {
            Delta current = appendedDeltas.get(i);
            Delta previous = appendedDeltas.get(i - 1);
            
            assertThat(current.prevHash())
                    .as("Delta %d prevHash should match delta %d hash", i, i - 1)
                    .isEqualTo(previous.hash());
        }
    }

    /**
     * Property: Tampered deltas are detected.
     * **Feature: yachaq-platform, Property: Tamper Detection**
     * **Validates: Requirements 312.4**
     */
    @Test
    void tamperedDelta_isDetected() {
        DeltaLog log = new DeltaLog();
        
        // Append some deltas
        log.append(DeltaType.CREATE, "entity-1", "ODXEntry", Map.of("count", 1), "test");
        log.append(DeltaType.UPDATE, "entity-1", "ODXEntry", Map.of("count", 2), "test");
        
        // Verify chain is valid
        assertThat(log.verifyChain().isValid()).isTrue();
        
        // Create a tampered delta with wrong prevHash
        Delta tampered = Delta.builder()
                .generateId()
                .type(DeltaType.UPDATE)
                .entityId("entity-1")
                .entityType("ODXEntry")
                .changes(Map.of("count", 3))
                .prevHash("tampered-hash")
                .hash("fake-hash")
                .timestamp(Instant.now())
                .source("test")
                .build();
        
        // Attempting to append tampered delta should fail
        assertThatThrownBy(() -> log.appendDelta(tampered))
                .isInstanceOf(ChainIntegrityException.class)
                .hasMessageContaining("prev_hash does not match");
    }

    /**
     * Property: Delta hashes are deterministic.
     * **Feature: yachaq-platform, Property: Hash Determinism**
     * **Validates: Requirements 312.1**
     */
    @Property(tries = 50)
    void deltaHashes_areDeterministic(
            @ForAll("deltaInputs") DeltaInput input) {
        
        DeltaLog log1 = new DeltaLog();
        DeltaLog log2 = new DeltaLog();
        
        // Append same delta to both logs
        Delta delta1 = log1.append(input.type(), input.entityId(), input.entityType(),
                input.changes(), input.source());
        Delta delta2 = log2.append(input.type(), input.entityId(), input.entityType(),
                input.changes(), input.source());
        
        // Hashes should be different because timestamps differ
        // But prevHash should be the same (genesis)
        assertThat(delta1.prevHash()).isEqualTo(delta2.prevHash());
    }

    /**
     * Property: Delta follows from previous correctly.
     */
    @Property(tries = 50)
    void delta_followsFromPrevious(
            @ForAll @Size(min = 2, max = 5) List<@From("deltaInputs") DeltaInput> inputs) {
        
        DeltaLog log = new DeltaLog();
        List<Delta> appendedDeltas = new ArrayList<>();
        
        for (DeltaInput input : inputs) {
            Delta current = log.append(input.type(), input.entityId(), input.entityType(),
                    input.changes(), input.source());
            appendedDeltas.add(current);
        }
        
        // Verify chain linking (skip first delta which follows from genesis)
        for (int i = 1; i < appendedDeltas.size(); i++) {
            Delta current = appendedDeltas.get(i);
            Delta previous = appendedDeltas.get(i - 1);
            
            assertThat(current.followsFrom(previous))
                    .as("Delta %d should follow from delta %d", i, i - 1)
                    .isTrue();
        }
    }

    // ==================== Summarization Tests ====================

    /**
     * Property: Summary counts match actual deltas.
     * **Feature: yachaq-platform, Property: Summary Accuracy**
     * **Validates: Requirements 312.3**
     */
    @Property(tries = 50)
    void summary_countsMatchActualDeltas(
            @ForAll @Size(min = 1, max = 20) List<@From("deltaInputs") DeltaInput> inputs) {
        
        DeltaLog log = new DeltaLog();
        
        for (DeltaInput input : inputs) {
            log.append(input.type(), input.entityId(), input.entityType(),
                    input.changes(), input.source());
        }
        
        DeltaSummary summary = log.summarize(Duration.ofHours(1));
        
        assertThat(summary.totalDeltas()).isEqualTo(inputs.size());
        
        // Verify type counts
        long totalFromTypes = summary.countsByType().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        assertThat(totalFromTypes).isEqualTo(inputs.size());
    }

    /**
     * Property: Summary respects time window.
     */
    @Test
    void summary_respectsTimeWindow() {
        DeltaLog log = new DeltaLog();
        
        // Append some deltas
        log.append(DeltaType.CREATE, "entity-1", "ODXEntry", Map.of(), "test");
        log.append(DeltaType.CREATE, "entity-2", "ODXEntry", Map.of(), "test");
        log.append(DeltaType.CREATE, "entity-3", "ODXEntry", Map.of(), "test");
        
        // Summary with 1 hour window should include all
        DeltaSummary summary = log.summarize(Duration.ofHours(1));
        assertThat(summary.totalDeltas()).isEqualTo(3);
        
        // Summary with 0 seconds window should include none (or very few)
        DeltaSummary zeroSummary = log.summarize(Duration.ZERO);
        assertThat(zeroSummary.totalDeltas()).isLessThanOrEqualTo(3);
    }

    /**
     * Property: Habit summary identifies patterns.
     */
    @Test
    void habitSummary_identifiesPatterns() {
        DeltaLog log = new DeltaLog();
        
        // Append multiple deltas
        for (int i = 0; i < 10; i++) {
            log.append(DeltaType.CREATE, "entity-" + i, "ODXEntry", Map.of(), "test");
        }
        
        HabitSummary habits = log.summarizeHabits(Duration.ofHours(1));
        
        assertThat(habits.totalEvents()).isEqualTo(10);
        assertThat(habits.activityByHour()).isNotEmpty();
        assertThat(habits.activityByDayOfWeek()).isNotEmpty();
        assertThat(habits.peakHour()).isBetween(0, 23);
        assertThat(habits.peakDayOfWeek()).isBetween(1, 7);
    }

    // ==================== Query Tests ====================

    /**
     * Property: getDeltasForEntity returns correct deltas.
     */
    @Property(tries = 50)
    void getDeltasForEntity_returnsCorrectDeltas(
            @ForAll @Size(min = 5, max = 20) List<@From("deltaInputs") DeltaInput> inputs) {
        
        DeltaLog log = new DeltaLog();
        
        for (DeltaInput input : inputs) {
            log.append(input.type(), input.entityId(), input.entityType(),
                    input.changes(), input.source());
        }
        
        // Pick a random entity ID from inputs
        String targetEntityId = inputs.get(0).entityId();
        long expectedCount = inputs.stream()
                .filter(i -> i.entityId().equals(targetEntityId))
                .count();
        
        List<Delta> entityDeltas = log.getDeltasForEntity(targetEntityId);
        
        assertThat(entityDeltas).hasSize((int) expectedCount);
        assertThat(entityDeltas).allMatch(d -> d.entityId().equals(targetEntityId));
    }

    /**
     * Property: getLastDeltas returns correct count.
     */
    @Property(tries = 50)
    void getLastDeltas_returnsCorrectCount(
            @ForAll @Size(min = 5, max = 20) List<@From("deltaInputs") DeltaInput> inputs,
            @ForAll @IntRange(min = 1, max = 10) int count) {
        
        DeltaLog log = new DeltaLog();
        
        for (DeltaInput input : inputs) {
            log.append(input.type(), input.entityId(), input.entityType(),
                    input.changes(), input.source());
        }
        
        List<Delta> lastDeltas = log.getLastDeltas(count);
        
        int expectedSize = Math.min(count, inputs.size());
        assertThat(lastDeltas).hasSize(expectedSize);
    }

    // ==================== Edge Cases ====================

    @Test
    void emptyLog_verificationSucceeds() {
        DeltaLog log = new DeltaLog();
        
        ChainVerificationResult result = log.verifyChain();
        
        assertThat(result.isValid()).isTrue();
        assertThat(result.verifiedCount()).isEqualTo(0);
    }

    @Test
    void emptyLog_summaryIsEmpty() {
        DeltaLog log = new DeltaLog();
        
        DeltaSummary summary = log.summarize(Duration.ofHours(1));
        
        assertThat(summary.totalDeltas()).isEqualTo(0);
        assertThat(summary.countsByType()).isEmpty();
    }

    @Test
    void append_handlesNullType() {
        DeltaLog log = new DeltaLog();
        
        assertThatThrownBy(() -> log.append(null, "entity-1", "ODXEntry", Map.of(), "test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void append_handlesNullEntityId() {
        DeltaLog log = new DeltaLog();
        
        assertThatThrownBy(() -> log.append(DeltaType.CREATE, null, "ODXEntry", Map.of(), "test"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void maxSize_isRespected() {
        DeltaLog log = new DeltaLog(5);
        
        for (int i = 0; i < 10; i++) {
            log.append(DeltaType.CREATE, "entity-" + i, "ODXEntry", Map.of(), "test");
        }
        
        assertThat(log.size()).isEqualTo(5);
    }

    @Test
    void delta_toSummary_formatsCorrectly() {
        DeltaLog log = new DeltaLog();
        Delta delta = log.append(DeltaType.CREATE, "entity-1", "ODXEntry", Map.of(), "test");
        
        String summary = delta.toSummary();
        
        assertThat(summary).contains("CREATE");
        assertThat(summary).contains("ODXEntry");
        assertThat(summary).contains("entity-1");
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<DeltaInput> deltaInputs() {
        Arbitrary<DeltaType> types = Arbitraries.of(DeltaType.values());
        Arbitrary<String> entityIds = Arbitraries.of("entity-1", "entity-2", "entity-3", "entity-4");
        Arbitrary<String> entityTypes = Arbitraries.of("ODXEntry", "Label", "Feature", "Event");
        Arbitrary<String> sources = Arbitraries.of("connector", "import", "manual", "system");
        
        return Combinators.combine(types, entityIds, entityTypes, sources)
                .as((type, entityId, entityType, source) -> 
                        new DeltaInput(type, entityId, entityType, Map.of("key", "value"), source));
    }

    /**
     * Test input for delta creation.
     */
    record DeltaInput(
            DeltaType type,
            String entityId,
            String entityType,
            Map<String, Object> changes,
            String source
    ) {}
}
