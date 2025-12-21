package com.yachaq.api.coordinator;

import com.yachaq.api.YachaqApiApplication;
import com.yachaq.api.audit.AuditService;
import com.yachaq.api.config.TestcontainersConfiguration;
import com.yachaq.api.coordinator.RendezvousService.*;
import com.yachaq.core.repository.AuditReceiptRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.spring.JqwikSpringSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Coordinator Rendezvous and Signaling Service.
 * Requirement 323: Coordinator Rendezvous and Signaling.
 * 
 * VIBE CODING RULES COMPLIANCE:
 * - Rule #1: NO MOCKS - Uses real PostgreSQL via Docker
 * - Rule #4: REAL IMPLEMENTATIONS - All services are real Spring beans
 * - Rule #7: REAL DATA & SERVERS - Tests against real Docker PostgreSQL
 */
@JqwikSpringSupport
@SpringBootTest(classes = YachaqApiApplication.class)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class RendezvousServicePropertyTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private RendezvousService service;

    @Autowired
    private AuditReceiptRepository auditReceiptRepository;

    @BeforeEach
    void setUp() {
        // Clean up for each test
        auditReceiptRepository.deleteAll();
    }

    // ==================== Requirement 323.1: Ephemeral Session Tokens ====================

    @Property(tries = 50)
    @Label("323.1: Session tokens are created with ephemeral IDs")
    void sessionTokensAreCreatedWithEphemeralIds(
            @ForAll @StringLength(min = 10, max = 50) String dsEphemeralId,
            @ForAll @StringLength(min = 10, max = 50) String requesterEphemeralId) {

        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                dsEphemeralId,
                requesterEphemeralId,
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of("stun:stun.l.google.com:19302")
        );

        SessionTokenResult result = service.createSessionToken(request);

        assertThat(result.success()).isTrue();
        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.token()).isNotBlank();
        assertThat(result.expiresAt()).isAfter(Instant.now());
        
        // Session ID should be ephemeral (random, not derived from stable IDs)
        assertThat(result.sessionId()).doesNotContain(dsEphemeralId);
        assertThat(result.sessionId()).doesNotContain(requesterEphemeralId);
    }

    @Test
    @Label("323.1: Session tokens can be validated")
    void sessionTokensCanBeValidated() {
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral-123",
                "rq-ephemeral-456",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult createResult = service.createSessionToken(request);
        assertThat(createResult.success()).isTrue();

        TokenValidationResult validateResult = service.validateToken(createResult.token());
        
        assertThat(validateResult.valid()).isTrue();
        assertThat(validateResult.sessionId()).isEqualTo(createResult.sessionId());
        assertThat(validateResult.status()).isEqualTo(SessionStatus.PENDING);
    }

    @Test
    @Label("323.1: Invalid tokens are rejected")
    void invalidTokensAreRejected() {
        TokenValidationResult result1 = service.validateToken(null);
        assertThat(result1.valid()).isFalse();

        TokenValidationResult result2 = service.validateToken("");
        assertThat(result2.valid()).isFalse();

        TokenValidationResult result3 = service.validateToken("invalid-token-format");
        assertThat(result3.valid()).isFalse();
    }

    // ==================== Requirement 323.2: Ciphertext-Only Relay ====================

    @Property(tries = 50)
    @Label("323.2: Relay accepts only ciphertext (high entropy data)")
    void relayAcceptsOnlyCiphertext() {
        // Create a session first
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );
        SessionTokenResult sessionResult = service.createSessionToken(request);
        assertThat(sessionResult.success()).isTrue();

        // Generate high-entropy ciphertext (simulated encrypted data)
        byte[] ciphertext = new byte[256];
        new SecureRandom().nextBytes(ciphertext);

        RelayResult result = service.relayMessage(
                sessionResult.sessionId(),
                ciphertext,
                "ds-ephemeral"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isNotBlank();
        assertThat(result.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @Label("323.2: Relay rejects plaintext (low entropy data)")
    void relayRejectsPlaintext() {
        // Create a session first
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );
        SessionTokenResult sessionResult = service.createSessionToken(request);
        assertThat(sessionResult.success()).isTrue();

        // Low entropy plaintext (repeated pattern)
        byte[] plaintext = "Hello World! This is plaintext data that should be rejected."
                .repeat(10).getBytes();

        RelayResult result = service.relayMessage(
                sessionResult.sessionId(),
                plaintext,
                "ds-ephemeral"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ciphertext");
    }


    // ==================== Requirement 323.3: Short TTL Enforcement ====================

    @Test
    @Label("323.3: Session tokens have enforced maximum TTL")
    void sessionTokensHaveEnforcedMaxTtl() {
        // Request with excessive TTL
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofHours(24), // Excessive TTL
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult result = service.createSessionToken(request);

        assertThat(result.success()).isTrue();
        // TTL should be capped at maximum (15 minutes)
        Duration actualTtl = Duration.between(Instant.now(), result.expiresAt());
        assertThat(actualTtl).isLessThanOrEqualTo(Duration.ofMinutes(16)); // Allow 1 min buffer
    }

    @Test
    @Label("323.3: Expired sessions are rejected")
    void expiredSessionsAreRejected() throws InterruptedException {
        // Create a session with very short TTL (we'll simulate expiry)
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofSeconds(1),
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult sessionResult = service.createSessionToken(request);
        assertThat(sessionResult.success()).isTrue();

        // Wait for expiry
        Thread.sleep(1500);

        // Try to relay message to expired session
        byte[] ciphertext = new byte[256];
        new SecureRandom().nextBytes(ciphertext);

        RelayResult result = service.relayMessage(
                sessionResult.sessionId(),
                ciphertext,
                "ds-ephemeral"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("expired");
    }

    // ==================== Requirement 323.4: No Stable Identifiers ====================

    @Property(tries = 100)
    @Label("323.4: Session IDs are unique and random (no stable identifiers)")
    void sessionIdsAreUniqueAndRandom() {
        Set<String> sessionIds = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            SessionRequest request = new SessionRequest(
                    UUID.randomUUID(),
                    "same-ds-id",
                    "same-rq-id",
                    Duration.ofMinutes(5),
                    "wss://relay.yachaq.io",
                    List.of()
            );

            SessionTokenResult result = service.createSessionToken(request);
            assertThat(result.success()).isTrue();
            sessionIds.add(result.sessionId());
        }

        // All session IDs should be unique
        assertThat(sessionIds).hasSize(100);
    }

    @Test
    @Label("323.4: Session IDs do not contain peer identifiers")
    void sessionIdsDoNotContainPeerIdentifiers() {
        String dsId = "ds-stable-identifier-12345";
        String rqId = "rq-stable-identifier-67890";

        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                dsId,
                rqId,
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult result = service.createSessionToken(request);

        assertThat(result.success()).isTrue();
        assertThat(result.sessionId()).doesNotContain("ds-stable");
        assertThat(result.sessionId()).doesNotContain("rq-stable");
        assertThat(result.sessionId()).doesNotContain("12345");
        assertThat(result.sessionId()).doesNotContain("67890");
    }

    // ==================== Requirement 323.5: Ephemeral Storage Only ====================

    @Test
    @Label("323.5: Sessions are stored ephemerally and can be closed")
    void sessionsAreStoredEphemerallyAndCanBeClosed() {
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult sessionResult = service.createSessionToken(request);
        assertThat(sessionResult.success()).isTrue();

        // Validate session exists
        TokenValidationResult validateBefore = service.validateToken(sessionResult.token());
        assertThat(validateBefore.valid()).isTrue();

        // Close session
        service.closeSession(sessionResult.sessionId());

        // Session should no longer be valid
        TokenValidationResult validateAfter = service.validateToken(sessionResult.token());
        assertThat(validateAfter.valid()).isFalse();
    }

    @Test
    @Label("323.5: Relay messages are ephemeral (single delivery)")
    void relayMessagesAreEphemeral() {
        // Create session
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );
        SessionTokenResult sessionResult = service.createSessionToken(request);

        // Send message
        byte[] ciphertext = new byte[256];
        new SecureRandom().nextBytes(ciphertext);
        RelayResult relayResult = service.relayMessage(
                sessionResult.sessionId(),
                ciphertext,
                "ds-ephemeral"
        );
        assertThat(relayResult.success()).isTrue();

        // Retrieve messages (should get the message)
        List<RelayMessage> messages1 = service.retrieveMessages(
                sessionResult.sessionId(),
                "rq-ephemeral"
        );
        assertThat(messages1).hasSize(1);

        // Retrieve again (should be empty - single delivery)
        List<RelayMessage> messages2 = service.retrieveMessages(
                sessionResult.sessionId(),
                "rq-ephemeral"
        );
        assertThat(messages2).isEmpty();
    }


    @Test
    @Label("323.5: Cleanup removes expired sessions and messages")
    void cleanupRemovesExpiredData() throws InterruptedException {
        // Create session with short TTL
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofSeconds(1),
                "wss://relay.yachaq.io",
                List.of()
        );
        SessionTokenResult sessionResult = service.createSessionToken(request);

        // Get stats before expiry
        SessionStats statsBefore = service.getStats();
        assertThat(statsBefore.activeSessions()).isGreaterThanOrEqualTo(1);

        // Wait for expiry
        Thread.sleep(1500);

        // Run cleanup
        service.cleanupExpiredData();

        // Session should be removed
        TokenValidationResult validateResult = service.validateToken(sessionResult.token());
        assertThat(validateResult.valid()).isFalse();
    }

    // ==================== Additional Tests ====================

    @Test
    @Label("Session can be marked as connected")
    void sessionCanBeMarkedAsConnected() {
        SessionRequest request = new SessionRequest(
                UUID.randomUUID(),
                "ds-ephemeral",
                "rq-ephemeral",
                Duration.ofMinutes(5),
                "wss://relay.yachaq.io",
                List.of()
        );

        SessionTokenResult sessionResult = service.createSessionToken(request);
        assertThat(sessionResult.success()).isTrue();

        // Initially PENDING
        TokenValidationResult validateBefore = service.validateToken(sessionResult.token());
        assertThat(validateBefore.status()).isEqualTo(SessionStatus.PENDING);

        // Mark as connected
        service.markSessionConnected(sessionResult.sessionId());

        // Now CONNECTED
        TokenValidationResult validateAfter = service.validateToken(sessionResult.token());
        assertThat(validateAfter.status()).isEqualTo(SessionStatus.CONNECTED);
    }

    @Test
    @Label("Stats return correct counts")
    void statsReturnCorrectCounts() {
        // Create multiple sessions
        for (int i = 0; i < 5; i++) {
            SessionRequest request = new SessionRequest(
                    UUID.randomUUID(),
                    "ds-ephemeral-" + i,
                    "rq-ephemeral-" + i,
                    Duration.ofMinutes(5),
                    "wss://relay.yachaq.io",
                    List.of()
            );
            service.createSessionToken(request);
        }

        SessionStats stats = service.getStats();
        assertThat(stats.activeSessions()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @Label("Relay to non-existent session fails")
    void relayToNonExistentSessionFails() {
        byte[] ciphertext = new byte[256];
        new SecureRandom().nextBytes(ciphertext);

        RelayResult result = service.relayMessage(
                "non-existent-session-id",
                ciphertext,
                "ds-ephemeral"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not found");
    }
}
