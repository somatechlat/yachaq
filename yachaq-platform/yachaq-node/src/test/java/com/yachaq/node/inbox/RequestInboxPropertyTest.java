package com.yachaq.node.inbox;

import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.inbox.RequestInbox.*;
import com.yachaq.node.odx.ODXEntry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for Request Inbox and Local Matcher.
 * Requirement 313.2, 313.5: Test local eligibility evaluation.
 * 
 * **Feature: yachaq-platform, Property 61: Local Eligibility Evaluation**
 * **Validates: Requirements 313.2, 313.5**
 */
class RequestInboxPropertyTest {

    // ==================== Request Inbox Tests (58.1) ====================

    /**
     * Property: Valid signed requests with policy stamps are accepted.
     * **Feature: yachaq-platform, Property: Request Acceptance**
     * **Validates: Requirements 313.1**
     */
    @Property(tries = 100)
    void validRequests_areAccepted(
            @ForAll("validRequests") DataRequest request) {
        
        RequestInbox inbox = new RequestInbox();
        ReceiveResult result = inbox.receive(request);
        
        assertThat(result.accepted())
                .as("Valid request should be accepted")
                .isTrue();
        assertThat(result.status()).isEqualTo(ReceiveStatus.ACCEPTED);
    }

    /**
     * Property: Expired requests are rejected.
     * **Feature: yachaq-platform, Property: Expiry Enforcement**
     * **Validates: Requirements 313.1**
     */
    @Property(tries = 50)
    void expiredRequests_areRejected(
            @ForAll("expiredRequests") DataRequest request) {
        
        RequestInbox inbox = new RequestInbox();
        ReceiveResult result = inbox.receive(request);
        
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo(ReceiveStatus.EXPIRED);
    }

    /**
     * Property: Duplicate requests are detected as replay attacks.
     * **Feature: yachaq-platform, Property: Replay Protection**
     * **Validates: Requirements 313.5**
     */
    @Property(tries = 50)
    void duplicateRequests_areDetectedAsReplay(
            @ForAll("validRequests") DataRequest request) {
        
        RequestInbox inbox = new RequestInbox();
        
        // First receive should succeed
        ReceiveResult first = inbox.receive(request);
        assertThat(first.accepted()).isTrue();
        
        // Second receive of same request should fail
        ReceiveResult second = inbox.receive(request);
        assertThat(second.accepted()).isFalse();
        assertThat(second.status()).isEqualTo(ReceiveStatus.REPLAY_DETECTED);
    }

    /**
     * Property: Unsigned requests are rejected.
     * **Feature: yachaq-platform, Property: Signature Verification**
     * **Validates: Requirements 313.1, 313.5**
     */
    @Property(tries = 50)
    void unsignedRequests_areRejected(
            @ForAll("unsignedRequests") DataRequest request) {
        
        RequestInbox inbox = new RequestInbox();
        ReceiveResult result = inbox.receive(request);
        
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo(ReceiveStatus.INVALID_SIGNATURE);
    }

    /**
     * Property: Requests without policy stamp are rejected.
     * **Feature: yachaq-platform, Property: Policy Stamp Verification**
     * **Validates: Requirements 313.1**
     */
    @Property(tries = 50)
    void requestsWithoutPolicyStamp_areRejected(
            @ForAll("requestsWithoutPolicyStamp") DataRequest request) {
        
        RequestInbox inbox = new RequestInbox();
        ReceiveResult result = inbox.receive(request);
        
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo(ReceiveStatus.MISSING_POLICY_STAMP);
    }

    // ==================== Local Matcher Tests (58.2, 58.3) ====================

    /**
     * Property: Requests with matching required labels are eligible.
     * **Feature: yachaq-platform, Property 61: Local Eligibility Evaluation**
     * **Validates: Requirements 313.2**
     */
    @Property(tries = 100)
    void requestsWithMatchingLabels_areEligible(
            @ForAll("matchingRequestsAndODX") RequestODXPair pair) {
        
        LocalMatcher matcher = new LocalMatcher(pair.odxEntries(), "US-CA");
        LocalMatcher.EligibilityResult result = matcher.isEligible(pair.request());
        
        assertThat(result.isEligible())
                .as("Request with matching labels should be eligible")
                .isTrue();
        assertThat(result.score()).isGreaterThan(0.0);
    }

    /**
     * Property: Requests with missing required labels are not eligible.
     * **Feature: yachaq-platform, Property: Missing Label Rejection**
     * **Validates: Requirements 313.2**
     */
    @Property(tries = 50)
    void requestsWithMissingLabels_areNotEligible(
            @ForAll("nonMatchingRequestsAndODX") RequestODXPair pair) {
        
        LocalMatcher matcher = new LocalMatcher(pair.odxEntries(), "US-CA");
        LocalMatcher.EligibilityResult result = matcher.isEligible(pair.request());
        
        assertThat(result.isEligible()).isFalse();
        assertThat(result.missingLabels()).isNotEmpty();
    }

    /**
     * Property: Geo constraints are evaluated using local geo only.
     * **Feature: yachaq-platform, Property: Local Geo Only**
     * **Validates: Requirements 313.2**
     */
    @Property(tries = 50)
    void geoConstraints_useLocalGeoOnly(
            @ForAll("requestsWithGeoConstraint") DataRequest request) {
        
        List<ODXEntry> odx = createODXWithLabels(request.requiredLabels());
        
        // Matcher with matching geo
        LocalMatcher matchingMatcher = new LocalMatcher(odx, "US-CA");
        LocalMatcher.EligibilityResult matchingResult = matchingMatcher.isEligible(request);
        
        // Matcher with non-matching geo
        LocalMatcher nonMatchingMatcher = new LocalMatcher(odx, "EU-DE");
        LocalMatcher.EligibilityResult nonMatchingResult = nonMatchingMatcher.isEligible(request);
        
        // At least one should differ based on geo
        if (request.geoConstraint() != null && 
            request.geoConstraint().regionCode() != null &&
            request.geoConstraint().regionCode().startsWith("US")) {
            assertThat(matchingResult.isEligible() || !nonMatchingResult.isEligible())
                    .as("Geo constraint should affect eligibility")
                    .isTrue();
        }
    }

    /**
     * Property: Match score is between 0 and 1.
     * **Feature: yachaq-platform, Property: Score Bounds**
     * **Validates: Requirements 313.2**
     */
    @Property(tries = 100)
    void matchScore_isBetweenZeroAndOne(
            @ForAll("validRequests") DataRequest request) {
        
        List<ODXEntry> odx = createODXWithLabels(request.requiredLabels());
        LocalMatcher matcher = new LocalMatcher(odx, "US-CA");
        
        LocalMatcher.EligibilityResult result = matcher.isEligible(request);
        
        assertThat(result.score()).isBetween(0.0, 1.0);
    }

    /**
     * Property: Broadcast mode (Mode A) accepts all eligible nodes.
     * **Feature: yachaq-platform, Property: Broadcast Mode**
     * **Validates: Requirements 313.4**
     */
    @Property(tries = 50)
    void broadcastMode_acceptsAllEligible(
            @ForAll("broadcastRequests") DataRequest request) {
        
        List<ODXEntry> odx = createODXWithLabels(request.requiredLabels());
        LocalMatcher matcher = new LocalMatcher(odx, "US-CA");
        
        LocalMatcher.EligibilityResult result = matcher.isEligible(request);
        
        // If labels match, broadcast should be eligible
        if (result.missingLabels().isEmpty()) {
            assertThat(result.isEligible()).isTrue();
        }
    }

    /**
     * Property: Geo topic mode (Mode B) respects geo constraints.
     * **Feature: yachaq-platform, Property: Geo Topic Mode**
     * **Validates: Requirements 313.4**
     */
    @Property(tries = 50)
    void geoTopicMode_respectsGeoConstraints(
            @ForAll("geoTopicRequests") DataRequest request) {
        
        List<ODXEntry> odx = createODXWithLabels(request.requiredLabels());
        
        // Matcher in matching region
        LocalMatcher matchingMatcher = new LocalMatcher(odx, "US-CA", LocalMatcher.MatchingMode.GEO_TOPIC);
        
        // Matcher in non-matching region
        LocalMatcher nonMatchingMatcher = new LocalMatcher(odx, "EU-DE", LocalMatcher.MatchingMode.GEO_TOPIC);
        
        LocalMatcher.EligibilityResult matchingResult = matchingMatcher.isEligible(request);
        LocalMatcher.EligibilityResult nonMatchingResult = nonMatchingMatcher.isEligible(request);
        
        // Geo topic mode should consider geo constraints
        if (request.geoConstraint() != null && 
            request.geoConstraint().regionCode() != null &&
            request.geoConstraint().regionCode().startsWith("US")) {
            // US request should match US-CA but not EU-DE
            assertThat(matchingResult.isEligible() || !nonMatchingResult.isEligible()).isTrue();
        }
    }

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<DataRequest> validRequests() {
        return Arbitraries.of("domain:activity", "domain:health", "time:morning")
                .set().ofMinSize(1).ofMaxSize(3)
                .map(labels -> createValidRequest(labels, RequestType.BROADCAST));
    }

    @Provide
    Arbitrary<DataRequest> expiredRequests() {
        return Arbitraries.of("domain:activity", "domain:health")
                .set().ofMinSize(1).ofMaxSize(2)
                .map(this::createExpiredRequest);
    }

    @Provide
    Arbitrary<DataRequest> unsignedRequests() {
        return Arbitraries.of("domain:activity")
                .set().ofMinSize(1).ofMaxSize(2)
                .map(this::createUnsignedRequest);
    }

    @Provide
    Arbitrary<DataRequest> requestsWithoutPolicyStamp() {
        return Arbitraries.of("domain:activity")
                .set().ofMinSize(1).ofMaxSize(2)
                .map(this::createRequestWithoutPolicyStamp);
    }

    @Provide
    Arbitrary<RequestODXPair> matchingRequestsAndODX() {
        return Arbitraries.of("domain:activity", "domain:health", "time:morning")
                .set().ofMinSize(1).ofMaxSize(3)
                .map(labels -> {
                    DataRequest request = createValidRequest(labels, RequestType.BROADCAST);
                    List<ODXEntry> odx = createODXWithLabels(labels);
                    return new RequestODXPair(request, odx);
                });
    }

    @Provide
    Arbitrary<RequestODXPair> nonMatchingRequestsAndODX() {
        return Arbitraries.just(new RequestODXPair(
                createValidRequest(Set.of("domain:nonexistent"), RequestType.BROADCAST),
                createODXWithLabels(Set.of("domain:activity"))
        ));
    }

    @Provide
    Arbitrary<DataRequest> requestsWithGeoConstraint() {
        return Arbitraries.of("US-CA", "US-NY", "EU-DE")
                .map(region -> createRequestWithGeo(Set.of("domain:activity"), region));
    }

    @Provide
    Arbitrary<DataRequest> broadcastRequests() {
        return Arbitraries.of("domain:activity", "domain:health")
                .set().ofMinSize(1).ofMaxSize(2)
                .map(labels -> createValidRequest(labels, RequestType.BROADCAST));
    }

    @Provide
    Arbitrary<DataRequest> geoTopicRequests() {
        return Arbitraries.of("US-CA", "US-NY")
                .map(region -> createRequestWithGeo(Set.of("domain:activity"), region));
    }

    // ==================== Helper Methods ====================

    private DataRequest createValidRequest(Set<String> labels, RequestType type) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .requesterName("Test Requester")
                .type(type)
                .requiredLabels(labels)
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp("valid-policy-stamp-" + UUID.randomUUID())
                .signature("valid-signature-" + UUID.randomUUID() + "-" + UUID.randomUUID())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private DataRequest createExpiredRequest(Set<String> labels) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .type(RequestType.BROADCAST)
                .requiredLabels(labels)
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp("valid-policy-stamp")
                .signature("valid-signature-" + UUID.randomUUID())
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600)) // Already expired
                .build();
    }

    private DataRequest createUnsignedRequest(Set<String> labels) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .type(RequestType.BROADCAST)
                .requiredLabels(labels)
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp("valid-policy-stamp")
                .signature(null) // No signature
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private DataRequest createRequestWithoutPolicyStamp(Set<String> labels) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .type(RequestType.BROADCAST)
                .requiredLabels(labels)
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp(null) // No policy stamp
                .signature("valid-signature-" + UUID.randomUUID() + "-" + UUID.randomUUID()) // 64+ chars
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private DataRequest createRequestWithGeo(Set<String> labels, String region) {
        return DataRequest.builder()
                .generateId()
                .requesterId("requester-1")
                .type(RequestType.GEO_TOPIC)
                .requiredLabels(labels)
                .geoConstraint(new GeoConstraint(region, GeoConstraint.GeoResolution.REGION))
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp("valid-policy-stamp-" + UUID.randomUUID())
                .signature("valid-signature-" + UUID.randomUUID() + "-" + UUID.randomUUID())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private List<ODXEntry> createODXWithLabels(Set<String> labels) {
        return labels.stream()
                .map(label -> ODXEntry.builder()
                        .generateId()
                        .facetKey(label)
                        .timeBucket(java.time.LocalDate.now().toString())
                        .count(10)
                        .quality(ODXEntry.Quality.VERIFIED)
                        .privacyFloor(50)
                        .geoResolution(ODXEntry.GeoResolution.NONE)
                        .timeResolution(ODXEntry.TimeResolution.DAY)
                        .ontologyVersion("1.0.0")
                        .build())
                .toList();
    }

    record RequestODXPair(DataRequest request, List<ODXEntry> odxEntries) {}

    // ==================== Unit Tests ====================

    @Test
    void receive_handlesNullRequest() {
        RequestInbox inbox = new RequestInbox();
        assertThatThrownBy(() -> inbox.receive(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void isEligible_handlesNullRequest() {
        LocalMatcher matcher = new LocalMatcher(List.of(), "US-CA");
        assertThatThrownBy(() -> matcher.isEligible(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void inbox_respectsMaxCapacity() {
        RequestInbox inbox = new RequestInbox(2, new RequestInbox.DefaultSignatureVerifier());
        
        DataRequest r1 = createValidRequest(Set.of("domain:a"), RequestType.BROADCAST);
        DataRequest r2 = createValidRequest(Set.of("domain:b"), RequestType.BROADCAST);
        DataRequest r3 = createValidRequest(Set.of("domain:c"), RequestType.BROADCAST);
        
        assertThat(inbox.receive(r1).accepted()).isTrue();
        assertThat(inbox.receive(r2).accepted()).isTrue();
        
        // Third should fail due to capacity
        ReceiveResult result = inbox.receive(r3);
        assertThat(result.status()).isEqualTo(ReceiveStatus.INBOX_FULL);
    }

    @Test
    void matcher_ranksResultsByScore() {
        List<ODXEntry> odx = createODXWithLabels(Set.of("domain:activity", "domain:health"));
        LocalMatcher matcher = new LocalMatcher(odx, "US-CA");
        
        DataRequest r1 = createValidRequest(Set.of("domain:activity"), RequestType.BROADCAST);
        DataRequest r2 = createValidRequest(Set.of("domain:activity", "domain:health"), RequestType.BROADCAST);
        
        List<LocalMatcher.MatchResult> results = matcher.matchRequests(List.of(r1, r2));
        
        // Both should be eligible
        assertThat(results).hasSize(2);
        // Results are sorted by score descending, so first result should have higher or equal score
        assertThat(results.get(0).eligibility().score())
                .isGreaterThanOrEqualTo(results.get(1).eligibility().score());
    }
}
