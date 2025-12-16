package com.yachaq.api.device;

import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.Device.DeviceType;
import com.yachaq.core.domain.DeviceDataLocation;
import com.yachaq.core.domain.DeviceHealthEvent;
import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.DSProfile.DSAccountType;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.DeviceDataLocationRepository;
import com.yachaq.core.repository.DeviceHealthEventRepository;
import com.yachaq.core.repository.DeviceRepository;
import com.yachaq.core.repository.DSProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Multi-Device Support.
 * 
 * **Feature: yachaq-platform, Property 24: Multi-Device Identity Linking**
 * *For any* DS with multiple devices, all devices must be linked to a single DS identity 
 * and queries must be routable to the appropriate device(s).
 * **Validates: Requirements 224.1, 251.1, 251.2**
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiDevicePropertyTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:55432/yachaq");
        registry.add("spring.datasource.username", () -> "yachaq");
        registry.add("spring.datasource.password", () -> "yachaq");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("yachaq.jwt.secret", () -> "test-secret-key-minimum-32-characters-long-for-testing");
        registry.add("yachaq.security.platform-key-id", () -> "test-platform-key-id");
    }

    @Autowired
    private MultiDeviceService multiDeviceService;

    @Autowired
    private DeviceQueryRouter deviceQueryRouter;

    @Autowired
    private DSProfileRepository dsProfileRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceHealthEventRepository healthEventRepository;

    @Autowired
    private DeviceDataLocationRepository dataLocationRepository;

    @Autowired
    private AuditReceiptRepository auditRepository;

    private final Random random = new Random();

    @BeforeEach
    void setUp() {
        dataLocationRepository.deleteAll();
        healthEventRepository.deleteAll();
        deviceRepository.deleteAll();
        auditRepository.deleteAll();
        dsProfileRepository.deleteAll();
    }

    /**
     * **Feature: yachaq-platform, Property 24: Multi-Device Identity Linking**
     * 
     * *For any* DS with multiple devices, all devices must be linked to a single DS identity.
     * 
     * **Validates: Requirements 224.1, 251.1**
     */
    @Test
    void property24_allDevicesMustBeLinkedToSingleDSIdentity() {
        // Run 25 iterations with random data (reduced for faster CI)
        for (int i = 0; i < 25; i++) {
            // Create a DS profile
            DSProfile profile = createTestProfile();
            profile = dsProfileRepository.save(profile);
            UUID dsId = profile.getId();

            // Link multiple devices (random count 1-3)
            int deviceCount = random.nextInt(3) + 1;
            for (int j = 0; j < deviceCount; j++) {
                MultiDeviceService.DeviceLinkRequest request = generateDeviceLinkRequest();
                MultiDeviceService.DeviceLinkResult result = multiDeviceService.linkDevice(dsId, request);
                
                assertTrue(result.success(), "Device linking must succeed");
                assertNotNull(result.deviceId(), "Device ID must be assigned");
                assertEquals(dsId, result.dsId(), "Device must be linked to correct DS");
            }

            // Property 24: All devices must be linked to the same DS identity
            List<Device> devices = deviceRepository.findByDsId(dsId);
            assertEquals(deviceCount, devices.size(), "All devices must be found");
            
            for (Device device : devices) {
                assertEquals(dsId, device.getDsId(), 
                        "Every device must be linked to the same DS identity");
            }

            // Verify identity linking validation
            assertTrue(multiDeviceService.validateDeviceIdentityLinking(dsId),
                    "Device identity linking validation must pass");

            // Clean up for next iteration
            dataLocationRepository.deleteAll();
            healthEventRepository.deleteAll();
            deviceRepository.deleteAll();
            auditRepository.deleteAll();
            dsProfileRepository.deleteAll();
        }
    }

    /**
     * Property: Device slot limits must be enforced per DS account type.
     * 
     * *For any* DS, the number of devices must not exceed the slot limits for their account type.
     * **Validates: Requirements 251.2**
     */
    @Test
    void property_deviceSlotLimitsMustBeEnforced() {
        for (int i = 0; i < 10; i++) {
            // Create DS_IND profile (limit: 5 devices, 3 mobile, 2 desktop, 0 IoT)
            DSProfile profile = new DSProfile("test-ds-" + UUID.randomUUID(), DSAccountType.DS_IND);
            profile = dsProfileRepository.save(profile);
            UUID dsId = profile.getId();

            MultiDeviceService.DeviceSlotLimits limits = multiDeviceService.getSlotLimits(DSAccountType.DS_IND);
            assertEquals(5, limits.maxDevices(), "DS_IND max devices must be 5");
            assertEquals(3, limits.maxMobile(), "DS_IND max mobile must be 3");
            assertEquals(2, limits.maxDesktop(), "DS_IND max desktop must be 2");
            assertEquals(0, limits.maxIoT(), "DS_IND max IoT must be 0");

            // Link 3 mobile devices (should succeed)
            for (int j = 0; j < 3; j++) {
                MultiDeviceService.DeviceLinkRequest request = new MultiDeviceService.DeviceLinkRequest(
                        generatePublicKey(),
                        DeviceType.MOBILE_ANDROID,
                        "Mobile " + j,
                        "Android 14",
                        "smartphone"
                );
                MultiDeviceService.DeviceLinkResult result = multiDeviceService.linkDevice(dsId, request);
                assertTrue(result.success(), "Mobile device " + j + " linking must succeed");
            }

            // Try to link 4th mobile device (should fail - exceeds mobile limit)
            MultiDeviceService.DeviceLinkRequest extraMobile = new MultiDeviceService.DeviceLinkRequest(
                    generatePublicKey(),
                    DeviceType.MOBILE_IOS,
                    "Extra Mobile",
                    "iOS 17",
                    "smartphone"
            );
            assertThrows(MultiDeviceService.DeviceSlotLimitExceededException.class, () -> {
                multiDeviceService.linkDevice(dsId, extraMobile);
            }, "Must reject device when mobile limit exceeded");

            // Link 2 desktop devices (should succeed)
            for (int j = 0; j < 2; j++) {
                MultiDeviceService.DeviceLinkRequest request = new MultiDeviceService.DeviceLinkRequest(
                        generatePublicKey(),
                        DeviceType.DESKTOP,
                        "Desktop " + j,
                        "macOS 14",
                        "laptop"
                );
                MultiDeviceService.DeviceLinkResult result = multiDeviceService.linkDevice(dsId, request);
                assertTrue(result.success(), "Desktop device " + j + " linking must succeed");
            }

            // Total is now 5 (3 mobile + 2 desktop) - at limit
            assertEquals(5, deviceRepository.countActiveByDsId(dsId), "Must have 5 active devices");

            // Try to link any more device (should fail - exceeds total limit)
            MultiDeviceService.DeviceLinkRequest extraDevice = new MultiDeviceService.DeviceLinkRequest(
                    generatePublicKey(),
                    DeviceType.DESKTOP,
                    "Extra Desktop",
                    "Windows 11",
                    "desktop"
            );
            assertThrows(MultiDeviceService.DeviceSlotLimitExceededException.class, () -> {
                multiDeviceService.linkDevice(dsId, extraDevice);
            }, "Must reject device when total limit exceeded");

            // Clean up
            dataLocationRepository.deleteAll();
            healthEventRepository.deleteAll();
            deviceRepository.deleteAll();
            auditRepository.deleteAll();
            dsProfileRepository.deleteAll();
        }
    }

    /**
     * Property: Queries must be routable to appropriate devices.
     * 
     * *For any* query, the system must route to devices that have the requested data.
     * **Validates: Requirements 224.2, 224.6**
     */
    @Test
    void property_queriesMustBeRoutableToAppropriateDevices() {
        for (int i = 0; i < 10; i++) {
            // Create DS with multiple devices
            DSProfile profile = createTestProfile();
            profile = dsProfileRepository.save(profile);
            UUID dsId = profile.getId();

            // Link 3 devices
            List<UUID> deviceIds = new java.util.ArrayList<>();
            for (int j = 0; j < 3; j++) {
                MultiDeviceService.DeviceLinkRequest request = generateDeviceLinkRequest();
                MultiDeviceService.DeviceLinkResult result = multiDeviceService.linkDevice(dsId, request);
                deviceIds.add(result.deviceId());
                
                // Verify attestation so device is eligible for queries
                Device device = deviceRepository.findById(result.deviceId()).orElseThrow();
                device.verifyAttestation();
                deviceRepository.save(device);
            }

            // Set up data locations - device 0 has "health", device 1 has "location", device 2 has both
            multiDeviceService.updateDataLocation(deviceIds.get(0), "health", 100, 10000);
            multiDeviceService.updateDataLocation(deviceIds.get(1), "location", 50, 5000);
            multiDeviceService.updateDataLocation(deviceIds.get(2), "health", 75, 7500);
            multiDeviceService.updateDataLocation(deviceIds.get(2), "location", 25, 2500);

            // Route query for "health" data - should route to devices 0 and 2
            DeviceQueryRouter.QueryRoutingRequest healthQuery = new DeviceQueryRouter.QueryRoutingRequest(
                    DeviceQueryRouter.RoutingStrategy.DATA_LOCATION,
                    Set.of("health"),
                    0
            );
            DeviceQueryRouter.QueryRoutingResult healthResult = deviceQueryRouter.routeQuery(dsId, healthQuery);
            
            assertTrue(healthResult.success(), "Health query routing must succeed");
            assertTrue(healthResult.targetDeviceIds().contains(deviceIds.get(0)), 
                    "Device 0 must be included for health query");
            assertTrue(healthResult.targetDeviceIds().contains(deviceIds.get(2)), 
                    "Device 2 must be included for health query");
            assertFalse(healthResult.targetDeviceIds().contains(deviceIds.get(1)), 
                    "Device 1 must NOT be included for health query");

            // Route query for "location" data - should route to devices 1 and 2
            DeviceQueryRouter.QueryRoutingRequest locationQuery = new DeviceQueryRouter.QueryRoutingRequest(
                    DeviceQueryRouter.RoutingStrategy.DATA_LOCATION,
                    Set.of("location"),
                    0
            );
            DeviceQueryRouter.QueryRoutingResult locationResult = deviceQueryRouter.routeQuery(dsId, locationQuery);
            
            assertTrue(locationResult.success(), "Location query routing must succeed");
            assertTrue(locationResult.targetDeviceIds().contains(deviceIds.get(1)), 
                    "Device 1 must be included for location query");
            assertTrue(locationResult.targetDeviceIds().contains(deviceIds.get(2)), 
                    "Device 2 must be included for location query");
            assertFalse(locationResult.targetDeviceIds().contains(deviceIds.get(0)), 
                    "Device 0 must NOT be included for location query");

            // Route to primary only
            DeviceQueryRouter.QueryRoutingRequest primaryQuery = new DeviceQueryRouter.QueryRoutingRequest(
                    DeviceQueryRouter.RoutingStrategy.PRIMARY_ONLY
            );
            DeviceQueryRouter.QueryRoutingResult primaryResult = deviceQueryRouter.routeQuery(dsId, primaryQuery);
            
            assertTrue(primaryResult.success(), "Primary query routing must succeed");
            assertEquals(1, primaryResult.targetDeviceIds().size(), "Primary routing must return exactly 1 device");

            // Clean up
            dataLocationRepository.deleteAll();
            healthEventRepository.deleteAll();
            deviceRepository.deleteAll();
            auditRepository.deleteAll();
            dsProfileRepository.deleteAll();
        }
    }

    /**
     * Property: Only one device can be primary per DS.
     * 
     * *For any* DS, there must be at most one primary device at any time.
     * **Validates: Requirements 251.1**
     */
    @Test
    void property_onlyOnePrimaryDevicePerDS() {
        for (int i = 0; i < 10; i++) {
            DSProfile profile = createTestProfile();
            profile = dsProfileRepository.save(profile);
            UUID dsId = profile.getId();

            // Link first device - should become primary
            MultiDeviceService.DeviceLinkRequest request1 = generateDeviceLinkRequest();
            MultiDeviceService.DeviceLinkResult result1 = multiDeviceService.linkDevice(dsId, request1);
            assertTrue(result1.isPrimary(), "First device must be primary");

            // Link second device - should NOT be primary
            MultiDeviceService.DeviceLinkRequest request2 = generateDeviceLinkRequest();
            MultiDeviceService.DeviceLinkResult result2 = multiDeviceService.linkDevice(dsId, request2);
            assertFalse(result2.isPrimary(), "Second device must NOT be primary");

            // Verify only one primary
            List<Device> devices = deviceRepository.findActiveByDsId(dsId);
            long primaryCount = devices.stream().filter(Device::isPrimary).count();
            assertEquals(1, primaryCount, "Must have exactly one primary device");

            // Change primary to second device
            multiDeviceService.setPrimaryDevice(dsId, result2.deviceId());

            // Verify primary changed
            Device device1 = deviceRepository.findById(result1.deviceId()).orElseThrow();
            Device device2 = deviceRepository.findById(result2.deviceId()).orElseThrow();
            assertFalse(device1.isPrimary(), "First device must no longer be primary");
            assertTrue(device2.isPrimary(), "Second device must now be primary");

            // Still only one primary
            devices = deviceRepository.findActiveByDsId(dsId);
            primaryCount = devices.stream().filter(Device::isPrimary).count();
            assertEquals(1, primaryCount, "Must still have exactly one primary device");

            // Clean up
            dataLocationRepository.deleteAll();
            healthEventRepository.deleteAll();
            deviceRepository.deleteAll();
            auditRepository.deleteAll();
            dsProfileRepository.deleteAll();
        }
    }

    /**
     * Property: Device replacement must maintain receipt continuity.
     * 
     * *For any* device replacement, the old device must be marked as replaced and disabled.
     * **Validates: Requirements 251.5**
     */
    @Test
    void property_deviceReplacementMustMaintainContinuity() {
        for (int i = 0; i < 10; i++) {
            DSProfile profile = createTestProfile();
            profile = dsProfileRepository.save(profile);
            UUID dsId = profile.getId();

            // Link original device
            MultiDeviceService.DeviceLinkRequest originalRequest = generateDeviceLinkRequest();
            MultiDeviceService.DeviceLinkResult originalResult = multiDeviceService.linkDevice(dsId, originalRequest);
            UUID originalDeviceId = originalResult.deviceId();

            // Replace with new device
            MultiDeviceService.DeviceLinkRequest replacementRequest = generateDeviceLinkRequest();
            MultiDeviceService.DeviceLinkResult replacementResult = 
                    multiDeviceService.replaceDevice(dsId, originalDeviceId, replacementRequest);

            assertTrue(replacementResult.success(), "Replacement must succeed");
            assertNotEquals(originalDeviceId, replacementResult.deviceId(), 
                    "Replacement device must have different ID");

            // Verify original device is disabled and marked as replaced
            Device originalDevice = deviceRepository.findById(originalDeviceId).orElseThrow();
            assertFalse(originalDevice.isActive(), "Original device must be disabled");
            assertNotNull(originalDevice.getDisabledAt(), "Original device must have disabled timestamp");
            assertEquals(replacementResult.deviceId(), originalDevice.getReplacementDeviceId(),
                    "Original device must point to replacement");

            // Verify replacement device is active
            Device replacementDevice = deviceRepository.findById(replacementResult.deviceId()).orElseThrow();
            assertTrue(replacementDevice.isActive(), "Replacement device must be active");

            // Verify health event was recorded
            List<DeviceHealthEvent> events = healthEventRepository.findByDeviceId(originalDeviceId);
            boolean hasReplacementEvent = events.stream()
                    .anyMatch(e -> e.getEventType() == DeviceHealthEvent.EventType.DEVICE_REPLACED);
            assertTrue(hasReplacementEvent, "Replacement health event must be recorded");

            // Clean up
            dataLocationRepository.deleteAll();
            healthEventRepository.deleteAll();
            deviceRepository.deleteAll();
            auditRepository.deleteAll();
            dsProfileRepository.deleteAll();
        }
    }

    /**
     * Property: Response aggregation must handle partial responses.
     * 
     * *For any* multi-device query, the system must correctly aggregate responses.
     * **Validates: Requirements 224.6**
     */
    @Test
    void property_responseAggregationMustHandlePartialResponses() {
        for (int i = 0; i < 10; i++) {
            // Simulate responses from multiple devices
            int deviceCount = random.nextInt(5) + 2; // 2-6 devices
            int successCount = random.nextInt(deviceCount) + 1; // At least 1 success

            List<DeviceQueryRouter.DeviceResponse> responses = new java.util.ArrayList<>();
            for (int j = 0; j < deviceCount; j++) {
                boolean success = j < successCount;
                responses.add(new DeviceQueryRouter.DeviceResponse(
                        UUID.randomUUID(),
                        success,
                        success ? "data-" + j : null,
                        success ? null : "Device offline"
                ));
            }

            // Aggregate responses
            DeviceQueryRouter.AggregatedResponse aggregated = deviceQueryRouter.aggregateResponses(responses);

            assertEquals(successCount, aggregated.successCount(), "Success count must match");
            assertEquals(deviceCount, aggregated.totalCount(), "Total count must match");
            assertEquals(successCount, aggregated.data().size(), "Aggregated data count must match successes");

            // Verify status
            if (successCount == deviceCount) {
                assertEquals(DeviceQueryRouter.AggregationStatus.COMPLETE, aggregated.status(),
                        "Status must be COMPLETE when all succeed");
            } else if (successCount > 0) {
                assertEquals(DeviceQueryRouter.AggregationStatus.PARTIAL, aggregated.status(),
                        "Status must be PARTIAL when some succeed");
            }
        }
    }

    private DSProfile createTestProfile() {
        DSAccountType[] types = DSAccountType.values();
        DSAccountType accountType = types[random.nextInt(types.length)];
        return new DSProfile("test-ds-" + UUID.randomUUID(), accountType);
    }

    private MultiDeviceService.DeviceLinkRequest generateDeviceLinkRequest() {
        DeviceType[] types = {DeviceType.MOBILE_ANDROID, DeviceType.MOBILE_IOS, DeviceType.DESKTOP};
        DeviceType deviceType = types[random.nextInt(types.length)];
        
        String[] osVersions = {"Android 14", "iOS 17", "macOS 14", "Windows 11"};
        String[] hardwareClasses = {"smartphone", "tablet", "laptop", "desktop"};

        return new MultiDeviceService.DeviceLinkRequest(
                generatePublicKey(),
                deviceType,
                "Device-" + UUID.randomUUID().toString().substring(0, 8),
                osVersions[random.nextInt(osVersions.length)],
                hardwareClasses[random.nextInt(hardwareClasses.length)]
        );
    }

    private String generatePublicKey() {
        // Generate a realistic-looking public key (base64 encoded)
        return "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA" + 
               UUID.randomUUID().toString().replace("-", "") +
               UUID.randomUUID().toString().replace("-", "") +
               "IDAQAB";
    }
}
