package com.yachaq.api.device;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.Device;
import com.yachaq.core.domain.Device.DeviceType;
import com.yachaq.core.domain.DeviceDataLocation;
import com.yachaq.core.domain.DeviceHealthEvent;
import com.yachaq.core.domain.DeviceHealthEvent.EventType;
import com.yachaq.core.domain.DeviceHealthEvent.Severity;
import com.yachaq.core.domain.DSProfile;
import com.yachaq.core.domain.DSProfile.DSAccountType;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.DeviceDataLocationRepository;
import com.yachaq.core.repository.DeviceHealthEventRepository;
import com.yachaq.core.repository.DeviceRepository;
import com.yachaq.core.repository.DSProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Service for multi-device management and identity linking.
 * 
 * Property 24: Multi-Device Identity Linking
 * Validates: Requirements 251.1, 251.2, 251.3, 251.4, 251.5
 */
@Service
public class MultiDeviceService {

    private final DeviceRepository deviceRepository;
    private final DSProfileRepository dsProfileRepository;
    private final DeviceHealthEventRepository healthEventRepository;
    private final DeviceDataLocationRepository dataLocationRepository;
    private final AuditReceiptRepository auditRepository;

    public MultiDeviceService(
            DeviceRepository deviceRepository,
            DSProfileRepository dsProfileRepository,
            DeviceHealthEventRepository healthEventRepository,
            DeviceDataLocationRepository dataLocationRepository,
            AuditReceiptRepository auditRepository) {
        this.deviceRepository = deviceRepository;
        this.dsProfileRepository = dsProfileRepository;
        this.healthEventRepository = healthEventRepository;
        this.dataLocationRepository = dataLocationRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * Links a new device to a DS identity.
     * Property 24: All devices must be linked to a single DS identity.
     * Requirement 251.2: Enforce device slot limits per DS tier.
     */
    @Transactional
    public DeviceLinkResult linkDevice(UUID dsId, DeviceLinkRequest request) {
        DSProfile profile = dsProfileRepository.findById(dsId)
                .orElseThrow(() -> new DSNotFoundException("DS not found: " + dsId));

        // Check device slot limits
        DeviceSlotLimits limits = getSlotLimits(profile.getAccountType());
        validateSlotLimits(dsId, request.deviceType(), limits);

        // Check if device already exists
        if (deviceRepository.existsByDsIdAndPublicKey(dsId, request.publicKey())) {
            throw new DeviceAlreadyLinkedException("Device already linked to this DS");
        }

        // Create and save device
        Device device = Device.enrollWithDetails(
                dsId,
                request.publicKey(),
                request.deviceType(),
                request.deviceName(),
                request.osVersion(),
                request.hardwareClass()
        );

        // Set as primary if this is the first device
        if (!deviceRepository.hasPrimaryDevice(dsId)) {
            device.setPrimary(true);
        }

        device = deviceRepository.save(device);

        // Create audit receipt
        AuditReceipt receipt = createAuditReceipt(
                AuditReceipt.EventType.DEVICE_ENROLLED,
                dsId,
                AuditReceipt.ActorType.DS,
                device.getId(),
                "Device",
                sha256(device.getId().toString() + device.getPublicKey())
        );
        auditRepository.save(receipt);

        // Record health event
        DeviceHealthEvent healthEvent = DeviceHealthEvent.create(
                device.getId(),
                EventType.HEALTH_CHECK_PASSED,
                Severity.INFO,
                "Device enrolled successfully"
        );
        healthEventRepository.save(healthEvent);

        return new DeviceLinkResult(
                device.getId(),
                dsId,
                device.isPrimary(),
                receipt.getId(),
                true
        );
    }

    /**
     * Gets all active devices for a DS.
     * Property 24: DS↔Device graph maintenance.
     */
    public List<Device> getActiveDevices(UUID dsId) {
        return deviceRepository.findActiveByDsId(dsId);
    }

    /**
     * Gets the primary device for a DS.
     */
    public Device getPrimaryDevice(UUID dsId) {
        return deviceRepository.findPrimaryByDsId(dsId)
                .orElseThrow(() -> new NoPrimaryDeviceException("No primary device for DS: " + dsId));
    }

    /**
     * Sets a device as the primary device for a DS.
     * Only one device can be primary per DS.
     */
    @Transactional
    public void setPrimaryDevice(UUID dsId, UUID deviceId) {
        Device newPrimary = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        if (!newPrimary.getDsId().equals(dsId)) {
            throw new DeviceNotOwnedException("Device does not belong to this DS");
        }

        if (!newPrimary.isActive()) {
            throw new DeviceDisabledException("Cannot set disabled device as primary");
        }

        // Remove primary from current primary device
        deviceRepository.findPrimaryByDsId(dsId).ifPresent(currentPrimary -> {
            currentPrimary.setPrimary(false);
            deviceRepository.save(currentPrimary);
        });

        // Set new primary
        newPrimary.setPrimary(true);
        deviceRepository.save(newPrimary);

        // Audit
        AuditReceipt receipt = createAuditReceipt(
                AuditReceipt.EventType.DEVICE_ENROLLED, // Using existing event type
                dsId,
                AuditReceipt.ActorType.DS,
                deviceId,
                "Device",
                sha256("primary_change:" + deviceId)
        );
        auditRepository.save(receipt);
    }

    /**
     * Disables a device (e.g., for compromise response).
     * Requirement 252.1: Remote disable of participation.
     */
    @Transactional
    public void disableDevice(UUID dsId, UUID deviceId, String reason) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        if (!device.getDsId().equals(dsId)) {
            throw new DeviceNotOwnedException("Device does not belong to this DS");
        }

        device.disable();
        deviceRepository.save(device);

        // If this was the primary device, promote another
        if (device.isPrimary()) {
            device.setPrimary(false);
            deviceRepository.save(device);
            promoteNextPrimaryDevice(dsId);
        }

        // Record health event
        DeviceHealthEvent healthEvent = DeviceHealthEvent.create(
                deviceId,
                EventType.SUSPICIOUS_ACTIVITY,
                Severity.HIGH,
                "Device disabled: " + reason
        );
        healthEventRepository.save(healthEvent);

        // Audit
        AuditReceipt receipt = createAuditReceipt(
                AuditReceipt.EventType.CONSENT_REVOKED, // Using existing event type for disable
                dsId,
                AuditReceipt.ActorType.DS,
                deviceId,
                "Device",
                sha256("disabled:" + deviceId + ":" + reason)
        );
        auditRepository.save(receipt);
    }

    /**
     * Replaces a device with a new one.
     * Requirement 251.5: Device replacement with key rotation.
     */
    @Transactional
    public DeviceLinkResult replaceDevice(UUID dsId, UUID oldDeviceId, DeviceLinkRequest newDeviceRequest) {
        Device oldDevice = deviceRepository.findById(oldDeviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + oldDeviceId));

        if (!oldDevice.getDsId().equals(dsId)) {
            throw new DeviceNotOwnedException("Device does not belong to this DS");
        }

        // Link new device (this validates slot limits)
        DeviceLinkResult result = linkDevice(dsId, newDeviceRequest);

        // Mark old device as replaced
        oldDevice.replaceWith(result.deviceId());
        deviceRepository.save(oldDevice);

        // Transfer primary status if needed
        if (oldDevice.isPrimary()) {
            Device newDevice = deviceRepository.findById(result.deviceId()).orElseThrow();
            newDevice.setPrimary(true);
            deviceRepository.save(newDevice);
        }

        // Record health event
        DeviceHealthEvent healthEvent = DeviceHealthEvent.create(
                oldDeviceId,
                EventType.DEVICE_REPLACED,
                Severity.INFO,
                "Replaced by device: " + result.deviceId()
        );
        healthEventRepository.save(healthEvent);

        return result;
    }

    /**
     * Records a health event for a device.
     * Requirement 251.4: Device health monitoring.
     */
    @Transactional
    public DeviceHealthEvent recordHealthEvent(UUID deviceId, EventType eventType, Severity severity, String details) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        DeviceHealthEvent event = DeviceHealthEvent.create(deviceId, eventType, severity, details);
        event = healthEventRepository.save(event);

        // Update trust score based on event
        updateTrustScore(device, eventType, severity);

        // Auto-disable on critical security events
        if (severity == Severity.CRITICAL && 
            (eventType == EventType.ROOT_DETECTED || eventType == EventType.JAILBREAK_DETECTED)) {
            device.disable();
            deviceRepository.save(device);
        }

        return event;
    }

    /**
     * Updates data location for a device.
     * Used for data-location-based query routing.
     */
    @Transactional
    public void updateDataLocation(UUID deviceId, String dataCategory, long recordCount, long sizeBytes) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found: " + deviceId));

        DeviceDataLocation location = dataLocationRepository
                .findByDeviceIdAndDataCategory(deviceId, dataCategory)
                .orElseGet(() -> DeviceDataLocation.create(deviceId, dataCategory));

        location.updateSync(recordCount, sizeBytes);
        dataLocationRepository.save(location);
    }

    /**
     * Gets devices that have a specific data category.
     * Property 24: Query routing to appropriate devices.
     */
    public List<Device> getDevicesWithDataCategory(UUID dsId, String dataCategory) {
        List<UUID> deviceIds = dataLocationRepository.findDeviceIdsWithCategory(dataCategory);
        return deviceRepository.findActiveByDsId(dsId).stream()
                .filter(d -> deviceIds.contains(d.getId()))
                .filter(Device::isEligibleForQueries)
                .toList();
    }

    /**
     * Gets devices eligible for queries.
     * Property 24: Query routing to appropriate devices.
     */
    public List<Device> getDevicesEligibleForQueries(UUID dsId) {
        return deviceRepository.findEligibleForQueries(dsId);
    }

    /**
     * Validates that a DS has not exceeded device slot limits.
     * Requirement 251.2: Device slot limits per DS tier.
     */
    public void validateSlotLimits(UUID dsId, DeviceType deviceType, DeviceSlotLimits limits) {
        long totalActive = deviceRepository.countActiveByDsId(dsId);
        if (totalActive >= limits.maxDevices()) {
            throw new DeviceSlotLimitExceededException(
                    "Maximum device limit reached: " + limits.maxDevices());
        }

        // Check per-type limits
        if (deviceType == DeviceType.MOBILE_ANDROID || deviceType == DeviceType.MOBILE_IOS) {
            long mobileCount = deviceRepository.countActiveMobileByDsId(dsId);
            if (mobileCount >= limits.maxMobile()) {
                throw new DeviceSlotLimitExceededException(
                        "Maximum mobile device limit reached: " + limits.maxMobile());
            }
        } else if (deviceType == DeviceType.DESKTOP) {
            long desktopCount = deviceRepository.countActiveDesktopByDsId(dsId);
            if (desktopCount >= limits.maxDesktop()) {
                throw new DeviceSlotLimitExceededException(
                        "Maximum desktop device limit reached: " + limits.maxDesktop());
            }
        } else if (deviceType == DeviceType.IOT) {
            long iotCount = deviceRepository.countActiveIoTByDsId(dsId);
            if (iotCount >= limits.maxIoT()) {
                throw new DeviceSlotLimitExceededException(
                        "Maximum IoT device limit reached: " + limits.maxIoT());
            }
        }
    }

    /**
     * Gets device slot limits for an account type.
     * Requirement 251.2: Policy-controlled max counts.
     */
    public DeviceSlotLimits getSlotLimits(DSAccountType accountType) {
        return switch (accountType) {
            case DS_IND -> new DeviceSlotLimits(5, 3, 2, 0);
            case DS_COMP -> new DeviceSlotLimits(20, 10, 10, 10);
            case DS_ORG -> new DeviceSlotLimits(100, 50, 50, 50);
        };
    }

    /**
     * Checks if all devices for a DS are linked to the same identity.
     * Property 24: All devices must be linked to a single DS identity.
     */
    public boolean validateDeviceIdentityLinking(UUID dsId) {
        List<Device> devices = deviceRepository.findByDsId(dsId);
        return devices.stream().allMatch(d -> d.getDsId().equals(dsId));
    }

    private void promoteNextPrimaryDevice(UUID dsId) {
        List<Device> activeDevices = deviceRepository.findEligibleForQueries(dsId);
        if (!activeDevices.isEmpty()) {
            Device newPrimary = activeDevices.get(0);
            newPrimary.setPrimary(true);
            deviceRepository.save(newPrimary);
        }
    }

    private void updateTrustScore(Device device, EventType eventType, Severity severity) {
        BigDecimal currentScore = device.getTrustScore();
        BigDecimal adjustment = switch (severity) {
            case CRITICAL -> new BigDecimal("-0.3");
            case HIGH -> new BigDecimal("-0.15");
            case MEDIUM -> new BigDecimal("-0.05");
            case LOW -> new BigDecimal("-0.01");
            case INFO -> eventType == EventType.HEALTH_CHECK_PASSED 
                    ? new BigDecimal("0.02") : BigDecimal.ZERO;
        };

        BigDecimal newScore = currentScore.add(adjustment);
        // Clamp between 0 and 1
        if (newScore.compareTo(BigDecimal.ZERO) < 0) {
            newScore = BigDecimal.ZERO;
        } else if (newScore.compareTo(BigDecimal.ONE) > 0) {
            newScore = BigDecimal.ONE;
        }

        device.updateTrustScore(newScore);
        deviceRepository.save(device);
    }

    private AuditReceipt createAuditReceipt(
            AuditReceipt.EventType eventType,
            UUID actorId,
            AuditReceipt.ActorType actorType,
            UUID resourceId,
            String resourceType,
            String detailsHash) {
        
        String previousHash = auditRepository.findMostRecentReceiptHash().orElse("GENESIS");
        
        AuditReceipt receipt = AuditReceipt.create(
                eventType,
                actorId,
                actorType,
                resourceId,
                resourceType,
                detailsHash,
                previousHash
        );
        
        String receiptData = String.join("|",
                eventType.name(),
                actorId.toString(),
                resourceId.toString(),
                detailsHash,
                previousHash
        );
        receipt.setReceiptHash(sha256(receiptData));
        
        return receipt;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // Records
    public record DeviceLinkRequest(
            String publicKey,
            DeviceType deviceType,
            String deviceName,
            String osVersion,
            String hardwareClass
    ) {}

    public record DeviceLinkResult(
            UUID deviceId,
            UUID dsId,
            boolean isPrimary,
            UUID auditReceiptId,
            boolean success
    ) {}

    public record DeviceSlotLimits(
            int maxDevices,
            int maxMobile,
            int maxDesktop,
            int maxIoT
    ) {}

    // Exceptions
    public static class DSNotFoundException extends RuntimeException {
        public DSNotFoundException(String message) { super(message); }
    }

    public static class DeviceNotFoundException extends RuntimeException {
        public DeviceNotFoundException(String message) { super(message); }
    }

    public static class DeviceAlreadyLinkedException extends RuntimeException {
        public DeviceAlreadyLinkedException(String message) { super(message); }
    }

    public static class DeviceNotOwnedException extends RuntimeException {
        public DeviceNotOwnedException(String message) { super(message); }
    }

    public static class DeviceDisabledException extends RuntimeException {
        public DeviceDisabledException(String message) { super(message); }
    }

    public static class DeviceSlotLimitExceededException extends RuntimeException {
        public DeviceSlotLimitExceededException(String message) { super(message); }
    }

    public static class NoPrimaryDeviceException extends RuntimeException {
        public NoPrimaryDeviceException(String message) { super(message); }
    }
}
