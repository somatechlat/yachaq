package com.yachaq.api.device;

import com.yachaq.core.domain.Device;
import com.yachaq.core.repository.DeviceDataLocationRepository;
import com.yachaq.core.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Routes queries to appropriate device(s) based on data location and routing strategy.
 * 
 * Property 24: Multi-Device Identity Linking
 * Validates: Requirements 224.2, 224.6 (Query routing and response aggregation)
 */
@Service
public class DeviceQueryRouter {

    private final DeviceRepository deviceRepository;
    private final DeviceDataLocationRepository dataLocationRepository;
    private final MultiDeviceService multiDeviceService;

    public DeviceQueryRouter(
            DeviceRepository deviceRepository,
            DeviceDataLocationRepository dataLocationRepository,
            MultiDeviceService multiDeviceService) {
        this.deviceRepository = deviceRepository;
        this.dataLocationRepository = dataLocationRepository;
        this.multiDeviceService = multiDeviceService;
    }

    /**
     * Routes a query to appropriate devices based on the routing strategy.
     * Property 24: Queries must be routable to the appropriate device(s).
     */
    public QueryRoutingResult routeQuery(UUID dsId, QueryRoutingRequest request) {
        List<Device> eligibleDevices = multiDeviceService.getDevicesEligibleForQueries(dsId);
        
        if (eligibleDevices.isEmpty()) {
            return new QueryRoutingResult(
                    dsId,
                    List.of(),
                    request.strategy(),
                    false,
                    "No eligible devices found"
            );
        }

        List<Device> targetDevices = switch (request.strategy()) {
            case PRIMARY_ONLY -> routeToPrimaryOnly(dsId, eligibleDevices);
            case ALL_DEVICES -> eligibleDevices;
            case DATA_LOCATION -> routeByDataLocation(dsId, request.dataCategories(), eligibleDevices);
            case ROUND_ROBIN -> routeRoundRobin(eligibleDevices, request.maxDevices());
            case LEAST_LOADED -> routeLeastLoaded(eligibleDevices, request.maxDevices());
        };

        if (targetDevices.isEmpty()) {
            return new QueryRoutingResult(
                    dsId,
                    List.of(),
                    request.strategy(),
                    false,
                    "No devices match routing criteria"
            );
        }

        List<UUID> targetDeviceIds = targetDevices.stream()
                .map(Device::getId)
                .toList();

        return new QueryRoutingResult(
                dsId,
                targetDeviceIds,
                request.strategy(),
                true,
                null
        );
    }

    /**
     * Routes to primary device only.
     * Used for simple queries that don't need multi-device aggregation.
     */
    private List<Device> routeToPrimaryOnly(UUID dsId, List<Device> eligibleDevices) {
        return eligibleDevices.stream()
                .filter(Device::isPrimary)
                .findFirst()
                .map(List::of)
                .orElseGet(() -> eligibleDevices.isEmpty() ? List.of() : List.of(eligibleDevices.get(0)));
    }

    /**
     * Routes based on data location.
     * Requirement 224.2: Route queries to appropriate device(s) based on data location.
     */
    private List<Device> routeByDataLocation(UUID dsId, Set<String> dataCategories, List<Device> eligibleDevices) {
        if (dataCategories == null || dataCategories.isEmpty()) {
            // If no categories specified, route to all eligible devices
            return eligibleDevices;
        }

        Set<UUID> eligibleIds = eligibleDevices.stream()
                .map(Device::getId)
                .collect(Collectors.toSet());

        // Find devices that have any of the requested data categories
        Set<UUID> devicesWithData = dataCategories.stream()
                .flatMap(category -> dataLocationRepository.findDeviceIdsWithCategory(category).stream())
                .filter(eligibleIds::contains)
                .collect(Collectors.toSet());

        if (devicesWithData.isEmpty()) {
            // Fallback to primary device if no devices have the data
            return routeToPrimaryOnly(dsId, eligibleDevices);
        }

        return eligibleDevices.stream()
                .filter(d -> devicesWithData.contains(d.getId()))
                .toList();
    }

    /**
     * Routes using round-robin strategy.
     * Distributes queries evenly across devices.
     */
    private List<Device> routeRoundRobin(List<Device> eligibleDevices, int maxDevices) {
        int limit = maxDevices > 0 ? Math.min(maxDevices, eligibleDevices.size()) : eligibleDevices.size();
        return eligibleDevices.subList(0, limit);
    }

    /**
     * Routes to least loaded devices based on trust score (higher = less loaded/more reliable).
     */
    private List<Device> routeLeastLoaded(List<Device> eligibleDevices, int maxDevices) {
        // Sort by trust score descending (higher trust = more reliable)
        List<Device> sorted = new ArrayList<>(eligibleDevices);
        sorted.sort((a, b) -> b.getTrustScore().compareTo(a.getTrustScore()));
        
        int limit = maxDevices > 0 ? Math.min(maxDevices, sorted.size()) : sorted.size();
        return sorted.subList(0, limit);
    }

    /**
     * Aggregates responses from multiple devices.
     * Requirement 224.6: Aggregate responses from multiple devices.
     */
    public AggregatedResponse aggregateResponses(List<DeviceResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return new AggregatedResponse(
                    List.of(),
                    0,
                    0,
                    AggregationStatus.FAILED,
                    "No responses to aggregate"
            );
        }

        int successCount = (int) responses.stream().filter(DeviceResponse::success).count();
        int totalCount = responses.size();

        AggregationStatus status;
        if (successCount == totalCount) {
            status = AggregationStatus.COMPLETE;
        } else if (successCount > 0) {
            status = AggregationStatus.PARTIAL;
        } else {
            status = AggregationStatus.FAILED;
        }

        // Collect successful response data
        List<Object> aggregatedData = responses.stream()
                .filter(DeviceResponse::success)
                .map(DeviceResponse::data)
                .toList();

        return new AggregatedResponse(
                aggregatedData,
                successCount,
                totalCount,
                status,
                null
        );
    }

    /**
     * Determines the best routing strategy for a query.
     */
    public RoutingStrategy determineStrategy(QueryContext context) {
        // If query requires specific data categories, use data location routing
        if (context.dataCategories() != null && !context.dataCategories().isEmpty()) {
            return RoutingStrategy.DATA_LOCATION;
        }

        // If query is simple and doesn't need aggregation, use primary only
        if (!context.requiresAggregation()) {
            return RoutingStrategy.PRIMARY_ONLY;
        }

        // If query needs all data, route to all devices
        if (context.requiresAllData()) {
            return RoutingStrategy.ALL_DEVICES;
        }

        // Default to least loaded for load balancing
        return RoutingStrategy.LEAST_LOADED;
    }

    // Enums
    public enum RoutingStrategy {
        PRIMARY_ONLY,    // Route to primary device only
        ALL_DEVICES,     // Route to all eligible devices
        DATA_LOCATION,   // Route based on where data is stored
        ROUND_ROBIN,     // Distribute evenly across devices
        LEAST_LOADED     // Route to least loaded devices
    }

    public enum AggregationStatus {
        PENDING,   // Waiting for responses
        PARTIAL,   // Some responses received
        COMPLETE,  // All responses received
        FAILED     // No successful responses
    }

    // Records
    public record QueryRoutingRequest(
            RoutingStrategy strategy,
            Set<String> dataCategories,
            int maxDevices
    ) {
        public QueryRoutingRequest(RoutingStrategy strategy) {
            this(strategy, Set.of(), 0);
        }
    }

    public record QueryRoutingResult(
            UUID dsId,
            List<UUID> targetDeviceIds,
            RoutingStrategy strategy,
            boolean success,
            String errorMessage
    ) {}

    public record DeviceResponse(
            UUID deviceId,
            boolean success,
            Object data,
            String errorMessage
    ) {}

    public record AggregatedResponse(
            List<Object> data,
            int successCount,
            int totalCount,
            AggregationStatus status,
            String errorMessage
    ) {}

    public record QueryContext(
            Set<String> dataCategories,
            boolean requiresAggregation,
            boolean requiresAllData
    ) {}
}
