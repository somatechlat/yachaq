package com.yachaq.node.onboarding;

import com.yachaq.node.inbox.DataRequest;
import com.yachaq.node.inbox.DataRequest.*;
import com.yachaq.node.inbox.RequestInbox;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Marketplace Inbox for Provider App UI.
 * Displays approved requests with filters and detailed views.
 * 
 * Validates: Requirements 343.1, 343.2, 343.3, 343.4, 343.5
 */
public class MarketplaceInbox {

    private final RequestInbox requestInbox;
    private final RequesterProfileService profileService;
    private final Map<String, RequestNotification> notifications;

    public MarketplaceInbox(RequestInbox requestInbox, RequesterProfileService profileService) {
        this.requestInbox = Objects.requireNonNull(requestInbox, "RequestInbox cannot be null");
        this.profileService = Objects.requireNonNull(profileService, "ProfileService cannot be null");
        this.notifications = new LinkedHashMap<>();
    }

    /**
     * Gets the request list view with optional filters.
     * Requirement 343.1: Display approved requests with filters.
     * 
     * @param filter Optional filter criteria
     * @return RequestListView with filtered requests
     */
    public RequestListView getRequestList(RequestFilter filter) {
        List<DataRequest> requests = requestInbox.getPendingRequests();
        
        // Apply filters
        if (filter != null) {
            requests = applyFilters(requests, filter);
        }
        
        // Convert to view items
        List<RequestListItem> items = requests.stream()
                .map(this::toListItem)
                .sorted(Comparator.comparing(RequestListItem::createdAt).reversed())
                .toList();
        
        return new RequestListView(
                items,
                items.size(),
                countByRiskClass(items),
                Instant.now()
        );
    }

    /**
     * Gets detailed view for a specific request.
     * Requirement 343.2: Show requester profile, reputation, scopes, output mode, TTL, identity requirement.
     * 
     * @param requestId The request ID
     * @return RequestDetailView with full details
     */
    public Optional<RequestDetailView> getRequestDetail(String requestId) {
        return requestInbox.getRequest(requestId)
                .map(this::toDetailView);
    }


    /**
     * Adds a notification for a new request.
     * Requirement 343.4: Provide notifications for new requests.
     */
    public void addNotification(DataRequest request) {
        notifications.put(request.id(), new RequestNotification(
                request.id(),
                "New request from " + (request.requesterName() != null ? request.requesterName() : "Unknown"),
                determineRiskClass(request),
                Instant.now(),
                false
        ));
    }

    /**
     * Gets unread notifications.
     */
    public List<RequestNotification> getUnreadNotifications() {
        return notifications.values().stream()
                .filter(n -> !n.read())
                .sorted(Comparator.comparing(RequestNotification::timestamp).reversed())
                .toList();
    }

    /**
     * Marks a notification as read.
     */
    public void markNotificationRead(String requestId) {
        RequestNotification existing = notifications.get(requestId);
        if (existing != null) {
            notifications.put(requestId, new RequestNotification(
                    existing.requestId(),
                    existing.message(),
                    existing.riskClass(),
                    existing.timestamp(),
                    true
            ));
        }
    }

    /**
     * Gets the count of pending requests.
     */
    public int getPendingCount() {
        return requestInbox.getPendingCount();
    }

    // ==================== Private Helper Methods ====================

    private List<DataRequest> applyFilters(List<DataRequest> requests, RequestFilter filter) {
        return requests.stream()
                .filter(r -> matchesFilter(r, filter))
                .toList();
    }

    private boolean matchesFilter(DataRequest request, RequestFilter filter) {
        // Filter by request type
        if (filter.requestType() != null && request.type() != filter.requestType()) {
            return false;
        }
        
        // Filter by output mode
        if (filter.outputMode() != null && request.outputMode() != filter.outputMode()) {
            return false;
        }
        
        // Filter by risk class
        if (filter.riskClass() != null && determineRiskClass(request) != filter.riskClass()) {
            return false;
        }
        
        // Filter by minimum compensation
        if (filter.minCompensation() != null && request.compensation() != null) {
            if (request.compensation().amount() < filter.minCompensation()) {
                return false;
            }
        }
        
        // Filter by label pattern
        if (filter.labelPattern() != null) {
            boolean hasMatch = request.requiredLabels().stream()
                    .anyMatch(l -> l.matches(filter.labelPattern()));
            if (!hasMatch) {
                return false;
            }
        }
        
        return true;
    }

    private RequestListItem toListItem(DataRequest request) {
        RequesterProfile profile = profileService.getProfile(request.requesterId());
        RiskClass riskClass = determineRiskClass(request);
        
        return new RequestListItem(
                request.id(),
                request.requesterName() != null ? request.requesterName() : "Unknown Requester",
                profile != null ? profile.reputation() : 0.0,
                request.type(),
                request.outputMode(),
                riskClass,
                request.compensation() != null ? request.compensation().amount() : 0.0,
                request.compensation() != null ? request.compensation().currency() : "USD",
                request.createdAt(),
                request.expiresAt(),
                request.requiredLabels().size()
        );
    }

    private RequestDetailView toDetailView(DataRequest request) {
        RequesterProfile profile = profileService.getProfile(request.requesterId());
        RiskClass riskClass = determineRiskClass(request);
        
        // Determine identity requirement
        boolean identityRequired = request.outputMode() == OutputMode.RAW_EXPORT ||
                request.metadata().containsKey("identity_required");
        
        // Calculate TTL
        long ttlSeconds = request.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        
        return new RequestDetailView(
                request.id(),
                profile != null ? profile : createUnknownProfile(request.requesterId()),
                new ArrayList<>(request.requiredLabels()),
                new ArrayList<>(request.optionalLabels()),
                request.outputMode(),
                riskClass,
                request.compensation(),
                ttlSeconds,
                identityRequired,
                request.timeWindow(),
                request.geoConstraint(),
                request.createdAt(),
                request.expiresAt(),
                request.hasPolicyStamp(),
                generatePrivacyImpactSummary(request)
        );
    }

    /**
     * Determines risk class based on request characteristics.
     * Requirement 343.5: Display visual indicators for risk class (A/B/C).
     */
    private RiskClass determineRiskClass(DataRequest request) {
        // Class A: Low risk - aggregate only, no sensitive labels
        // Class B: Medium risk - clean room or some sensitive labels
        // Class C: High risk - export allowed or highly sensitive labels
        
        boolean hasSensitiveLabels = request.requiredLabels().stream()
                .anyMatch(this::isSensitiveLabel);
        
        if (request.outputMode() == OutputMode.RAW_EXPORT) {
            return RiskClass.C;
        }
        
        if (request.outputMode() == OutputMode.EXPORT_ALLOWED || hasSensitiveLabels) {
            return RiskClass.B;
        }
        
        if (request.outputMode() == OutputMode.CLEAN_ROOM) {
            return hasSensitiveLabels ? RiskClass.B : RiskClass.A;
        }
        
        return RiskClass.A;
    }

    private boolean isSensitiveLabel(String label) {
        String lower = label.toLowerCase();
        return lower.contains("health") || lower.contains("finance") ||
               lower.contains("location") || lower.contains("biometric") ||
               lower.contains("medical") || lower.contains("genetic");
    }

    private Map<RiskClass, Integer> countByRiskClass(List<RequestListItem> items) {
        return items.stream()
                .collect(Collectors.groupingBy(
                        RequestListItem::riskClass,
                        Collectors.summingInt(i -> 1)
                ));
    }

    private RequesterProfile createUnknownProfile(String requesterId) {
        return new RequesterProfile(
                requesterId,
                "Unknown Requester",
                RequesterTier.UNVERIFIED,
                0.0,
                0,
                0,
                null
        );
    }

    private String generatePrivacyImpactSummary(DataRequest request) {
        StringBuilder summary = new StringBuilder();
        
        summary.append("This request will access ");
        summary.append(request.requiredLabels().size());
        summary.append(" data categories");
        
        if (request.outputMode() == OutputMode.AGGREGATE_ONLY) {
            summary.append(" in aggregate form only.");
        } else if (request.outputMode() == OutputMode.CLEAN_ROOM) {
            summary.append(" in a secure clean room environment.");
        } else if (request.outputMode() == OutputMode.EXPORT_ALLOWED) {
            summary.append(" with export capability.");
        } else {
            summary.append(" with raw data export.");
        }
        
        return summary.toString();
    }


    // ==================== Inner Types ====================

    /**
     * Filter criteria for request list.
     */
    public record RequestFilter(
            RequestType requestType,
            OutputMode outputMode,
            RiskClass riskClass,
            Double minCompensation,
            String labelPattern
    ) {
        public static RequestFilter all() {
            return new RequestFilter(null, null, null, null, null);
        }

        public static RequestFilter byRiskClass(RiskClass riskClass) {
            return new RequestFilter(null, null, riskClass, null, null);
        }

        public static RequestFilter byOutputMode(OutputMode mode) {
            return new RequestFilter(null, mode, null, null, null);
        }
    }

    /**
     * Request list view.
     */
    public record RequestListView(
            List<RequestListItem> items,
            int totalCount,
            Map<RiskClass, Integer> countByRiskClass,
            Instant generatedAt
    ) {}

    /**
     * Request list item (summary view).
     */
    public record RequestListItem(
            String requestId,
            String requesterName,
            double requesterReputation,
            RequestType type,
            OutputMode outputMode,
            RiskClass riskClass,
            double compensationAmount,
            String compensationCurrency,
            Instant createdAt,
            Instant expiresAt,
            int labelCount
    ) {}

    /**
     * Request detail view.
     */
    public record RequestDetailView(
            String requestId,
            RequesterProfile requesterProfile,
            List<String> requiredLabels,
            List<String> optionalLabels,
            OutputMode outputMode,
            RiskClass riskClass,
            CompensationOffer compensation,
            long ttlSeconds,
            boolean identityRequired,
            TimeWindow timeWindow,
            GeoConstraint geoConstraint,
            Instant createdAt,
            Instant expiresAt,
            boolean hasPolicyStamp,
            String privacyImpactSummary
    ) {}

    /**
     * Risk class indicators.
     * Requirement 343.5: Display visual indicators for risk class (A/B/C).
     */
    public enum RiskClass {
        A("Low Risk", "Aggregate only, no sensitive data"),
        B("Medium Risk", "Clean room or some sensitive data"),
        C("High Risk", "Export allowed or highly sensitive data");

        private final String displayName;
        private final String description;

        RiskClass(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Requester profile information.
     */
    public record RequesterProfile(
            String requesterId,
            String name,
            RequesterTier tier,
            double reputation,
            int completedRequests,
            int disputeCount,
            String verificationStatus
    ) {}

    /**
     * Requester tier levels.
     */
    public enum RequesterTier {
        UNVERIFIED,
        BASIC,
        VERIFIED,
        PREMIUM,
        ENTERPRISE
    }

    /**
     * Request notification.
     */
    public record RequestNotification(
            String requestId,
            String message,
            RiskClass riskClass,
            Instant timestamp,
            boolean read
    ) {}

    /**
     * Interface for requester profile service.
     */
    public interface RequesterProfileService {
        RequesterProfile getProfile(String requesterId);
    }

    /**
     * Default profile service (returns null for unknown requesters).
     */
    public static class DefaultRequesterProfileService implements RequesterProfileService {
        private final Map<String, RequesterProfile> profiles = new HashMap<>();

        public void addProfile(RequesterProfile profile) {
            profiles.put(profile.requesterId(), profile);
        }

        @Override
        public RequesterProfile getProfile(String requesterId) {
            return profiles.get(requesterId);
        }
    }
}
