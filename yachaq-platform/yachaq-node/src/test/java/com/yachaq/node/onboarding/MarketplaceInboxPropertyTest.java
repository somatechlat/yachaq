package com.yachaq.node.onboarding;

import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.inbox.RequestInbox;
import com.yachaq.node.onboarding.MarketplaceInbox.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for MarketplaceInbox.
 * 
 * Validates: Requirements 343.1, 343.2, 343.3, 343.4, 343.5
 */
class MarketplaceInboxPropertyTest {

    /**
     * Test fixture that provides both RequestInbox and MarketplaceInbox.
     */
    private static class TestFixture {
        final RequestInbox requestInbox;
        final DefaultRequesterProfileService profileService;
        final MarketplaceInbox marketplaceInbox;

        TestFixture() {
            this.requestInbox = new RequestInbox();
            this.profileService = new DefaultRequesterProfileService();
            this.marketplaceInbox = new MarketplaceInbox(requestInbox, profileService);
        }

        void addProfile(RequesterProfile profile) {
            profileService.addProfile(profile);
        }

        void receiveRequest(DataRequest request) {
            requestInbox.receive(request);
        }
    }

    private TestFixture createFixture() {
        return new TestFixture();
    }

    private DataRequest createValidRequest(String id, String requesterId, OutputMode outputMode) {
        // Create signature with at least 64 characters for validation
        String signature = "valid-signature-" + UUID.randomUUID().toString() + UUID.randomUUID().toString();
        // Create policy stamp with at least 32 characters
        String policyStamp = "valid-policy-stamp-" + UUID.randomUUID().toString();
        
        // Use non-sensitive labels to get predictable risk class
        return DataRequest.builder()
                .id(id)
                .requesterId(requesterId)
                .requesterName("Test Requester")
                .type(RequestType.BROADCAST)
                .requiredLabels(Set.of("media:music", "entertainment:podcasts"))
                .outputMode(outputMode)
                .compensation(new CompensationOffer(10.0, "USD", "escrow-123"))
                .policyStamp(policyStamp)
                .signature(signature)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
    }

    // ==================== Task 86.1: Request List View Tests ====================

    @Test
    void requestList_displaysApprovedRequests() {
        // Requirement 343.1: Display approved requests with filters
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items()).hasSize(1);
        assertThat(view.totalCount()).isEqualTo(1);
    }

    @Test
    void requestList_filtersbyRiskClass() {
        // Add requests with different risk classes
        TestFixture fixture = createFixture();
        DataRequest lowRisk = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        DataRequest highRisk = createValidRequest("req-2", "requester-2", OutputMode.RAW_EXPORT);
        
        fixture.receiveRequest(lowRisk);
        fixture.receiveRequest(highRisk);
        
        RequestListView filtered = fixture.marketplaceInbox.getRequestList(RequestFilter.byRiskClass(RiskClass.A));
        
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).riskClass()).isEqualTo(RiskClass.A);
    }

    @Test
    void requestList_filtersByOutputMode() {
        TestFixture fixture = createFixture();
        DataRequest aggregate = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        DataRequest cleanRoom = createValidRequest("req-2", "requester-2", OutputMode.CLEAN_ROOM);
        
        fixture.receiveRequest(aggregate);
        fixture.receiveRequest(cleanRoom);
        
        RequestListView filtered = fixture.marketplaceInbox.getRequestList(RequestFilter.byOutputMode(OutputMode.CLEAN_ROOM));
        
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).outputMode()).isEqualTo(OutputMode.CLEAN_ROOM);
    }

    @Test
    void requestList_countsbyRiskClass() {
        TestFixture fixture = createFixture();
        DataRequest lowRisk1 = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        DataRequest lowRisk2 = createValidRequest("req-2", "requester-2", OutputMode.AGGREGATE_ONLY);
        DataRequest highRisk = createValidRequest("req-3", "requester-3", OutputMode.RAW_EXPORT);
        
        fixture.receiveRequest(lowRisk1);
        fixture.receiveRequest(lowRisk2);
        fixture.receiveRequest(highRisk);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.countByRiskClass().get(RiskClass.A)).isEqualTo(2);
        assertThat(view.countByRiskClass().get(RiskClass.C)).isEqualTo(1);
    }

    // ==================== Task 86.2: Request Detail View Tests ====================

    @Test
    void requestDetail_showsRequesterProfile() {
        // Requirement 343.2: Show requester profile, reputation, scopes, output mode, TTL, identity requirement
        TestFixture fixture = createFixture();
        String requesterId = "requester-1";
        fixture.addProfile(new RequesterProfile(
                requesterId, "Acme Research", RequesterTier.VERIFIED, 4.5, 100, 2, "verified"
        ));
        
        DataRequest request = createValidRequest("req-1", requesterId, OutputMode.CLEAN_ROOM);
        fixture.receiveRequest(request);
        
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("req-1");
        
        assertThat(detail).isPresent();
        assertThat(detail.get().requesterProfile().name()).isEqualTo("Acme Research");
        assertThat(detail.get().requesterProfile().reputation()).isEqualTo(4.5);
        assertThat(detail.get().requesterProfile().tier()).isEqualTo(RequesterTier.VERIFIED);
    }

    @Test
    void requestDetail_showsScopes() {
        TestFixture fixture = createFixture();
        DataRequest request = DataRequest.builder()
                .id("req-1")
                .requesterId("requester-1")
                .requesterName("Test")
                .type(RequestType.BROADCAST)
                .requiredLabels(Set.of("health:steps", "fitness:activities"))
                .optionalLabels(Set.of("media:music"))
                .outputMode(OutputMode.AGGREGATE_ONLY)
                .policyStamp("valid-policy-stamp-123456789012345678901234567890")
                .signature("valid-signature-123456789012345678901234567890123456789012345678901234")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        
        fixture.receiveRequest(request);
        
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("req-1");
        
        assertThat(detail).isPresent();
        assertThat(detail.get().requiredLabels()).containsExactlyInAnyOrder("health:steps", "fitness:activities");
        assertThat(detail.get().optionalLabels()).containsExactly("media:music");
    }

    @Test
    void requestDetail_showsOutputMode() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.CLEAN_ROOM);
        fixture.receiveRequest(request);
        
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("req-1");
        
        assertThat(detail).isPresent();
        assertThat(detail.get().outputMode()).isEqualTo(OutputMode.CLEAN_ROOM);
    }

    @Test
    void requestDetail_showsTTL() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        fixture.receiveRequest(request);
        
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("req-1");
        
        assertThat(detail).isPresent();
        assertThat(detail.get().ttlSeconds()).isGreaterThan(0);
    }

    @Test
    void requestDetail_showsIdentityRequirement() {
        // RAW_EXPORT requires identity
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.RAW_EXPORT);
        fixture.receiveRequest(request);
        
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("req-1");
        
        assertThat(detail).isPresent();
        assertThat(detail.get().identityRequired()).isTrue();
    }

    @Test
    void requestDetail_returnsEmptyForUnknownRequest() {
        TestFixture fixture = createFixture();
        Optional<RequestDetailView> detail = fixture.marketplaceInbox.getRequestDetail("unknown-id");
        
        assertThat(detail).isEmpty();
    }


    // ==================== Task 86.3: Risk Class Indicators Tests ====================

    @Test
    void riskClass_aggregateOnlyIsClassA() {
        // Requirement 343.5: Display visual indicators for risk class (A/B/C)
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items().get(0).riskClass()).isEqualTo(RiskClass.A);
    }

    @Test
    void riskClass_cleanRoomIsClassAOrB() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.CLEAN_ROOM);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        // Clean room without sensitive labels is A, with sensitive labels is B
        assertThat(view.items().get(0).riskClass()).isIn(RiskClass.A, RiskClass.B);
    }

    @Test
    void riskClass_exportAllowedIsClassB() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.EXPORT_ALLOWED);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items().get(0).riskClass()).isEqualTo(RiskClass.B);
    }

    @Test
    void riskClass_rawExportIsClassC() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.RAW_EXPORT);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items().get(0).riskClass()).isEqualTo(RiskClass.C);
    }

    @Test
    void riskClass_sensitiveLabelsIncreaseRisk() {
        // Request with health labels (sensitive)
        TestFixture fixture = createFixture();
        DataRequest request = DataRequest.builder()
                .id("req-1")
                .requesterId("requester-1")
                .requesterName("Test")
                .type(RequestType.BROADCAST)
                .requiredLabels(Set.of("health:medical_records"))
                .outputMode(OutputMode.CLEAN_ROOM)
                .policyStamp("valid-policy-stamp-123456789012345678901234567890")
                .signature("valid-signature-123456789012345678901234567890123456789012345678901234")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
        
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        // Health labels should increase risk to B
        assertThat(view.items().get(0).riskClass()).isEqualTo(RiskClass.B);
    }

    @Property
    void riskClass_alwaysAssigned(@ForAll("outputModes") OutputMode outputMode) {
        // Property: Every request always has a risk class assigned
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-" + UUID.randomUUID(), "requester-1", outputMode);
        fixture.receiveRequest(request);
        
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items()).isNotEmpty();
        assertThat(view.items().get(0).riskClass()).isNotNull();
    }

    // ==================== Notification Tests ====================

    @Test
    void notifications_addedForNewRequests() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        
        fixture.marketplaceInbox.addNotification(request);
        
        List<RequestNotification> notifications = fixture.marketplaceInbox.getUnreadNotifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).requestId()).isEqualTo("req-1");
        assertThat(notifications.get(0).read()).isFalse();
    }

    @Test
    void notifications_canBeMarkedAsRead() {
        TestFixture fixture = createFixture();
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        fixture.marketplaceInbox.addNotification(request);
        
        fixture.marketplaceInbox.markNotificationRead("req-1");
        
        List<RequestNotification> unread = fixture.marketplaceInbox.getUnreadNotifications();
        assertThat(unread).isEmpty();
    }

    @Test
    void notifications_includeRiskClass() {
        TestFixture fixture = createFixture();
        DataRequest highRisk = createValidRequest("req-1", "requester-1", OutputMode.RAW_EXPORT);
        
        fixture.marketplaceInbox.addNotification(highRisk);
        
        List<RequestNotification> notifications = fixture.marketplaceInbox.getUnreadNotifications();
        assertThat(notifications.get(0).riskClass()).isEqualTo(RiskClass.C);
    }

    // ==================== Edge Case Tests ====================

    @Test
    void constructor_rejectsNullRequestInbox() {
        DefaultRequesterProfileService profileService = new DefaultRequesterProfileService();
        assertThatThrownBy(() -> new MarketplaceInbox(null, profileService))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void constructor_rejectsNullProfileService() {
        RequestInbox requestInbox = new RequestInbox();
        assertThatThrownBy(() -> new MarketplaceInbox(requestInbox, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void requestList_emptyWhenNoRequests() {
        TestFixture fixture = createFixture();
        RequestListView view = fixture.marketplaceInbox.getRequestList(null);
        
        assertThat(view.items()).isEmpty();
        assertThat(view.totalCount()).isEqualTo(0);
    }

    @Test
    void pendingCount_tracksRequests() {
        TestFixture fixture = createFixture();
        assertThat(fixture.marketplaceInbox.getPendingCount()).isEqualTo(0);
        
        DataRequest request = createValidRequest("req-1", "requester-1", OutputMode.AGGREGATE_ONLY);
        fixture.receiveRequest(request);
        
        assertThat(fixture.marketplaceInbox.getPendingCount()).isEqualTo(1);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<OutputMode> outputModes() {
        return Arbitraries.of(OutputMode.values());
    }
}
