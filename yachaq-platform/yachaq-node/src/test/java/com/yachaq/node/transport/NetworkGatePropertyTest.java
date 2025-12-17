package com.yachaq.node.transport;

import com.yachaq.node.transport.NetworkGate.*;

import org.junit.jupiter.api.*;

import java.security.SecureRandom;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property tests for Network Gate.
 * Requirement 318.5: Pass "no raw egress" proofs ensuring forbidden payload types never leave.
 */
class NetworkGatePropertyTest {

    private NetworkGate gate;

    @BeforeEach
    void setUp() {
        gate = new NetworkGate();
    }

    // ==================== Allowlist Tests ====================

    /**
     * Property: Unknown destinations are blocked by default.
     * Requirement 318.3: Block unknown destinations by default.
     */
    @Test
    void send_blocksUnknownDestinations() {
        // Given
        NetworkRequest request = NetworkRequest.metadata("unknown.example.com", new byte[0]);

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("not in allowlist");
    }

    /**
     * Property: Allowed destinations pass through.
     * Requirement 318.4: Require explicit domain and purpose registration.
     */
    @Test
    void send_allowsRegisteredDestinations() throws Exception {
        // Given
        gate.allow("api.yachaq.io", "Platform API");
        NetworkRequest request = NetworkRequest.metadata("api.yachaq.io", "{}".getBytes());

        // When
        GateResult result = gate.send(request);

        // Then
        assertThat(result.allowed()).isTrue();
        assertThat(result.purpose()).isEqualTo("Platform API");
    }

    /**
     * Property: Domain registration requires purpose.
     * Requirement 318.4: Require explicit domain and purpose registration.
     */
    @Test
    void allow_requiresPurpose() {
        // When/Then
        assertThatThrownBy(() -> gate.allow("example.com", ""))
                .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> gate.allow("example.com", null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Property: Revoked destinations are blocked.
     */
    @Test
    void send_blocksRevokedDestinations() throws Exception {
        // Given
        gate.allow("api.example.com", "Test");
        gate.revoke("api.example.com");
        NetworkRequest request = NetworkRequest.metadata("api.example.com", new byte[0]);

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("not in allowlist");
    }

    /**
     * Property: Domain normalization handles various formats.
     */
    @Test
    void allow_normalizesDomains() throws Exception {
        // Given
        gate.allow("https://API.Example.COM/path", "Test");

        // When/Then - all variations should work
        assertThat(gate.isAllowed("api.example.com")).isTrue();
        assertThat(gate.isAllowed("https://api.example.com")).isTrue();
        assertThat(gate.isAllowed("http://api.example.com")).isTrue();
        assertThat(gate.isAllowed("API.EXAMPLE.COM")).isTrue();
    }

    // ==================== Payload Classification Tests ====================

    /**
     * Property: Ciphertext is correctly classified.
     * Requirement 318.2: Distinguish metadata-only from ciphertext capsule traffic.
     */
    @Test
    void classifyPayload_identifiesCiphertext() {
        // Given - high entropy data (encrypted)
        byte[] ciphertext = new byte[1024];
        new SecureRandom().nextBytes(ciphertext);

        // When
        PayloadClassification classification = gate.classifyPayload(ciphertext);

        // Then
        assertThat(classification).isEqualTo(PayloadClassification.CIPHERTEXT_CAPSULE);
    }

    /**
     * Property: Metadata is correctly classified.
     * Requirement 318.2: Distinguish metadata-only from ciphertext capsule traffic.
     */
    @Test
    void classifyPayload_identifiesMetadata() {
        // Given - JSON metadata
        byte[] metadata = "{\"type\":\"request\",\"id\":\"123\"}".getBytes();

        // When
        PayloadClassification classification = gate.classifyPayload(metadata);

        // Then
        assertThat(classification).isEqualTo(PayloadClassification.METADATA_ONLY);
    }

    /**
     * Property: Empty payload is classified as metadata.
     */
    @Test
    void classifyPayload_emptyIsMetadata() {
        // When
        PayloadClassification classification = gate.classifyPayload(new byte[0]);

        // Then
        assertThat(classification).isEqualTo(PayloadClassification.METADATA_ONLY);
    }

    /**
     * Property: Null payload is classified as metadata.
     */
    @Test
    void classifyPayload_nullIsMetadata() {
        // When
        PayloadClassification classification = gate.classifyPayload(null);

        // Then
        assertThat(classification).isEqualTo(PayloadClassification.METADATA_ONLY);
    }

    // ==================== Raw Egress Blocking Tests ====================

    /**
     * Property: Raw payload egress is blocked.
     * Requirement 318.5: Block raw payload egress.
     */
    @Test
    void send_blocksRawPayloadEgress() {
        // Given
        gate.allow("api.example.com", "Test");
        // Raw text that's not metadata format
        byte[] rawPayload = "This is raw unencrypted personal data content".getBytes();
        NetworkRequest request = NetworkRequest.capsule("api.example.com", rawPayload);

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("Raw payload egress blocked");
    }

    /**
     * Property: Ciphertext capsules are allowed.
     * Requirement 318.2: Allow ciphertext capsule traffic.
     */
    @Test
    void send_allowsCiphertextCapsules() throws Exception {
        // Given
        gate.allow("relay.yachaq.io", "Capsule relay");
        byte[] ciphertext = new byte[1024];
        new SecureRandom().nextBytes(ciphertext);
        NetworkRequest request = NetworkRequest.capsule("relay.yachaq.io", ciphertext);

        // When
        GateResult result = gate.send(request);

        // Then
        assertThat(result.allowed()).isTrue();
        assertThat(result.classification()).isEqualTo(PayloadClassification.CIPHERTEXT_CAPSULE);
    }

    /**
     * Property: Forbidden patterns are blocked.
     * Requirement 318.5: Block forbidden payload types.
     */
    @Test
    void send_blocksForbiddenPatterns() {
        // Given
        gate.allow("api.example.com", "Test");
        // Payload containing email (PII)
        byte[] piiPayload = "{\"email\":\"user@example.com\",\"data\":\"test\"}".getBytes();
        NetworkRequest request = NetworkRequest.metadata("api.example.com", piiPayload);

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("forbidden patterns");
    }

    /**
     * Property: Phone numbers are blocked.
     */
    @Test
    void send_blocksPhoneNumbers() {
        // Given
        gate.allow("api.example.com", "Test");
        byte[] phonePayload = "{\"phone\":\"555-123-4567\"}".getBytes();
        NetworkRequest request = NetworkRequest.metadata("api.example.com", phonePayload);

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("forbidden patterns");
    }

    // ==================== Logging Tests ====================

    /**
     * Property: Blocked attempts are logged.
     * Requirement 318.6: Log blocked attempts.
     */
    @Test
    void send_logsBlockedAttempts() {
        // Given
        NetworkRequest request = NetworkRequest.metadata("unknown.com", new byte[0]);

        // When
        try {
            gate.send(request);
        } catch (NetworkGateException ignored) {}

        // Then
        List<EgressAttempt> blocked = gate.getBlockedAttempts();
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).destination()).isEqualTo("unknown.com");
        assertThat(blocked.get(0).reason()).isEqualTo(BlockReason.UNKNOWN_DESTINATION);
    }

    /**
     * Property: Raw egress attempts are logged with correct reason.
     */
    @Test
    void send_logsRawEgressAttempts() {
        // Given
        gate.allow("api.example.com", "Test");
        byte[] rawPayload = "Raw unencrypted data that should not be sent".getBytes();
        NetworkRequest request = NetworkRequest.capsule("api.example.com", rawPayload);

        // When
        try {
            gate.send(request);
        } catch (NetworkGateException ignored) {}

        // Then
        List<EgressAttempt> blocked = gate.getBlockedAttempts(BlockReason.RAW_PAYLOAD_EGRESS);
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).reason()).isEqualTo(BlockReason.RAW_PAYLOAD_EGRESS);
    }

    // ==================== Statistics Tests ====================

    /**
     * Property: Statistics are tracked correctly.
     */
    @Test
    void statistics_trackedCorrectly() throws Exception {
        // Given
        gate.allow("api.yachaq.io", "API");
        
        // Send some requests
        gate.send(NetworkRequest.metadata("api.yachaq.io", "{}".getBytes()));
        gate.send(NetworkRequest.metadata("api.yachaq.io", "{}".getBytes()));
        
        try {
            gate.send(NetworkRequest.metadata("blocked.com", new byte[0]));
        } catch (NetworkGateException ignored) {}

        // When
        GateStatistics stats = gate.getStatistics();

        // Then
        assertThat(stats.totalRequests()).isEqualTo(3);
        assertThat(stats.blockedRequests()).isEqualTo(1);
        assertThat(stats.allowlistSize()).isEqualTo(1);
        assertThat(stats.blockedAttemptCount()).isEqualTo(1);
    }

    /**
     * Property: Block rate is calculated correctly.
     */
    @Test
    void statistics_blockRateCalculation() throws Exception {
        // Given
        gate.allow("api.yachaq.io", "API");
        
        // 2 successful, 2 blocked
        gate.send(NetworkRequest.metadata("api.yachaq.io", "{}".getBytes()));
        gate.send(NetworkRequest.metadata("api.yachaq.io", "{}".getBytes()));
        
        try { gate.send(NetworkRequest.metadata("blocked1.com", new byte[0])); } 
        catch (NetworkGateException ignored) {}
        try { gate.send(NetworkRequest.metadata("blocked2.com", new byte[0])); } 
        catch (NetworkGateException ignored) {}

        // When
        GateStatistics stats = gate.getStatistics();

        // Then
        assertThat(stats.blockRate()).isEqualTo(0.5); // 50%
    }

    // ==================== Gate Enable/Disable Tests ====================

    /**
     * Property: Disabled gate blocks all traffic.
     */
    @Test
    void send_blocksWhenDisabled() {
        // Given
        gate.allow("api.yachaq.io", "API");
        gate.setEnabled(false);
        NetworkRequest request = NetworkRequest.metadata("api.yachaq.io", "{}".getBytes());

        // When/Then
        assertThatThrownBy(() -> gate.send(request))
                .isInstanceOf(NetworkGateException.class)
                .hasMessageContaining("disabled");
    }

    /**
     * Property: Re-enabled gate allows traffic.
     */
    @Test
    void send_allowsWhenReEnabled() throws Exception {
        // Given
        gate.allow("api.yachaq.io", "API");
        gate.setEnabled(false);
        gate.setEnabled(true);
        NetworkRequest request = NetworkRequest.metadata("api.yachaq.io", "{}".getBytes());

        // When
        GateResult result = gate.send(request);

        // Then
        assertThat(result.allowed()).isTrue();
    }

    // ==================== Edge Cases ====================

    /**
     * Property: Multiple domains can be registered.
     */
    @Test
    void allow_supportsMultipleDomains() throws Exception {
        // Given
        gate.allow("api.yachaq.io", "API");
        gate.allow("relay.yachaq.io", "Relay");
        gate.allow("coordinator.yachaq.io", "Coordinator");

        // When/Then
        assertThat(gate.isAllowed("api.yachaq.io")).isTrue();
        assertThat(gate.isAllowed("relay.yachaq.io")).isTrue();
        assertThat(gate.isAllowed("coordinator.yachaq.io")).isTrue();
        assertThat(gate.getStatistics().allowlistSize()).isEqualTo(3);
    }

    /**
     * Property: Purpose can be retrieved for allowed domains.
     */
    @Test
    void getPurpose_returnsCorrectPurpose() {
        // Given
        gate.allow("api.yachaq.io", "Platform API");

        // When
        Optional<String> purpose = gate.getPurpose("api.yachaq.io");

        // Then
        assertThat(purpose).isPresent().contains("Platform API");
    }

    /**
     * Property: Purpose is empty for unknown domains.
     */
    @Test
    void getPurpose_emptyForUnknown() {
        // When
        Optional<String> purpose = gate.getPurpose("unknown.com");

        // Then
        assertThat(purpose).isEmpty();
    }

    /**
     * Property: Blocked attempts can be cleared.
     */
    @Test
    void clearBlockedAttempts_removesAll() {
        // Given
        try { gate.send(NetworkRequest.metadata("blocked.com", new byte[0])); } 
        catch (NetworkGateException ignored) {}
        assertThat(gate.getBlockedAttempts()).hasSize(1);

        // When
        gate.clearBlockedAttempts();

        // Then
        assertThat(gate.getBlockedAttempts()).isEmpty();
    }
}
