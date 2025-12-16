package com.yachaq.core.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks which data categories are stored on which device.
 * Used for data-location-based query routing.
 * 
 * Validates: Requirements 224.2 (Query routing based on data location)
 * Property 24: Multi-Device Identity Linking
 */
@Entity
@Table(name = "device_data_locations", indexes = {
    @Index(name = "idx_data_location_device", columnList = "device_id"),
    @Index(name = "idx_data_location_category", columnList = "data_category")
}, uniqueConstraints = {
    @UniqueConstraint(columnNames = {"device_id", "data_category"})
})
public class DeviceDataLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @NotNull
    @Column(name = "data_category", nullable = false, length = 100)
    private String dataCategory;

    @NotNull
    @Column(name = "last_sync_at", nullable = false)
    private Instant lastSyncAt;

    @NotNull
    @Column(name = "record_count", nullable = false)
    private long recordCount = 0;

    @NotNull
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes = 0;

    protected DeviceDataLocation() {}

    /**
     * Creates a new device data location record.
     */
    public static DeviceDataLocation create(UUID deviceId, String dataCategory) {
        if (deviceId == null) {
            throw new IllegalArgumentException("Device ID is required");
        }
        if (dataCategory == null || dataCategory.isBlank()) {
            throw new IllegalArgumentException("Data category is required");
        }

        var location = new DeviceDataLocation();
        location.deviceId = deviceId;
        location.dataCategory = dataCategory;
        location.lastSyncAt = Instant.now();
        return location;
    }

    /**
     * Updates the sync information for this data location.
     */
    public void updateSync(long recordCount, long sizeBytes) {
        if (recordCount < 0) {
            throw new IllegalArgumentException("Record count cannot be negative");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size bytes cannot be negative");
        }
        this.recordCount = recordCount;
        this.sizeBytes = sizeBytes;
        this.lastSyncAt = Instant.now();
    }

    /**
     * Checks if this data location has data.
     */
    public boolean hasData() {
        return recordCount > 0;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public String getDataCategory() { return dataCategory; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public long getRecordCount() { return recordCount; }
    public long getSizeBytes() { return sizeBytes; }
}
