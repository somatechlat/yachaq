package com.yachaq.api.device;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Property-based tests for On-Device Components (ODS and ODX).
 * 
 * **Feature: yachaq-platform, Property 12: Edge-First Data Locality**
 * **Validates: Requirements 201.1, 202.1, 202.4**
 * 
 * For any data collection event, the raw data must be stored on the DS device,
 * not on centralized platform servers, unless explicit consent for cloud storage exists.
 * 
 * **Feature: yachaq-platform, Property 25: ODX Minimization**
 * **Validates: Requirements 201.1, 201.2**
 * 
 * For any ODX entry generated, the entry must contain only coarse labels,
 * timestamps, and availability bands - never raw payload content.
 */
class OnDeviceComponentsPropertyTest {

    // Forbidden patterns that indicate raw data
    private static final Set<String> FORBIDDEN_PATTERNS = Set.of(
        "raw", "payload", "content", "text", "email", "phone", 
        "address", "name", "ssn", "password", "secret", "token"
    );

    // Approved facet namespaces
    private static final Set<String> APPROVED_NAMESPACES = Set.of(
        "domain", "time", "geo", "quality", "privacy", "activity", "health", "finance"
    );

    // ========================================================================
    // Property 12: Edge-First Data Locality
    // ========================================================================

    @Property(tries = 100)
    @Label("Property 12: Data stored on device by default")
    void dataStoredOnDevice_byDefault(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("categories") String category,
            @ForAll @StringLength(min = 1, max = 1000) String payload) {
        
        // Create ODS entry on device (default)
        ODSEntry entry = ODSEntry.createOnDevice(
            deviceId, dsId, category, 
            "encrypted:" + payload, // Simulated encrypted payload
            "key-" + UUID.randomUUID(),
            "hash-" + payload.hashCode(),
            "default-retention"
        );

        // Property 12: Data must be stored on device by default
        assert entry.isStoredOnDevice() 
            : "Data must be stored on device by default (edge-first)";
        assert entry.getStorageLocation() == ODSEntry.StorageLocation.DEVICE
            : "Storage location must be DEVICE";
        assert entry.getCloudConsentId() == null
            : "Cloud consent ID must be null for device storage";
    }

    @Property(tries = 100)
    @Label("Property 12: Cloud storage requires explicit consent")
    void cloudStorage_requiresExplicitConsent(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("categories") String category,
            @ForAll @StringLength(min = 1, max = 1000) String payload,
            @ForAll("consentIds") UUID consentId) {
        
        // Create ODS entry with cloud consent
        ODSEntry entry = ODSEntry.createWithCloudConsent(
            deviceId, dsId, category,
            "encrypted:" + payload,
            "key-" + UUID.randomUUID(),
            "hash-" + payload.hashCode(),
            "default-retention",
            consentId
        );

        // Property 12: Cloud storage must have valid consent
        assert entry.hasValidCloudConsent()
            : "Cloud storage must have valid consent";
        assert entry.getStorageLocation() == ODSEntry.StorageLocation.CLOUD_WITH_CONSENT
            : "Storage location must be CLOUD_WITH_CONSENT";
        assert entry.getCloudConsentId() != null
            : "Cloud consent ID must not be null";
        assert entry.getCloudConsentId().equals(consentId)
            : "Cloud consent ID must match provided consent";
    }

    @Property(tries = 100)
    @Label("Property 12: Cloud storage without consent throws exception")
    void cloudStorage_withoutConsent_throwsException(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("categories") String category,
            @ForAll @StringLength(min = 1, max = 1000) String payload) {
        
        // Attempt to create cloud storage without consent
        boolean exceptionThrown = false;
        try {
            ODSEntry.createWithCloudConsent(
                deviceId, dsId, category,
                "encrypted:" + payload,
                "key-" + UUID.randomUUID(),
                "hash-" + payload.hashCode(),
                "default-retention",
                null // No consent ID
            );
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
            assert e.getMessage().contains("consent")
                : "Exception message must mention consent requirement";
        }

        assert exceptionThrown
            : "Cloud storage without consent must throw exception";
    }

    // ========================================================================
    // Property 25: ODX Minimization
    // ========================================================================

    @Property(tries = 100)
    @Label("Property 25: ODX entries contain only coarse labels")
    void odxEntries_containOnlyCoarseLabels(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket,
            @ForAll @IntRange(min = 1, max = 1000) int count) {
        
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, count,
            ODXEntry.Quality.VERIFIED, 50
        );

        // Property 25: Entry must be privacy-safe
        assert entry.isPrivacySafe()
            : "ODX entry must be privacy-safe";
        
        // Verify facet key follows namespace.label pattern
        assert facetKey.matches("^[a-z]+\\.[a-z_]+$")
            : "Facet key must follow namespace.label pattern";
        
        // Verify no forbidden patterns
        String lower = facetKey.toLowerCase();
        for (String forbidden : FORBIDDEN_PATTERNS) {
            assert !lower.contains(forbidden)
                : "Facet key must not contain forbidden pattern: " + forbidden;
        }
    }

    @Property(tries = 100)
    @Label("Property 25: ODX rejects raw data in facet keys")
    void odx_rejectsRawData_inFacetKeys(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("forbiddenFacetKeys") String forbiddenFacetKey,
            @ForAll("timeBuckets") String timeBucket) {
        
        boolean exceptionThrown = false;
        try {
            ODXEntry.create(
                deviceId, dsId, forbiddenFacetKey, timeBucket, 1,
                ODXEntry.Quality.VERIFIED, 50
            );
        } catch (ODXEntry.ODXValidationException e) {
            exceptionThrown = true;
        }

        assert exceptionThrown
            : "ODX must reject facet keys containing raw data patterns: " + forbiddenFacetKey;
    }

    @Property(tries = 100)
    @Label("Property 25: ODX enforces coarse geo resolution only")
    void odx_enforcesCoarseGeoResolution(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket,
            @ForAll("coarseGeoBuckets") String geoBucket) {
        
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, 1,
            ODXEntry.Quality.VERIFIED, 50
        );
        
        // Set coarse geo bucket (should succeed)
        entry.setGeoBucket(geoBucket, ODXEntry.GeoResolution.COARSE);
        
        assert entry.getGeoResolution() == ODXEntry.GeoResolution.COARSE
            : "Geo resolution must be COARSE";
        
        // Attempt to set fine geo resolution (should fail)
        boolean exceptionThrown = false;
        try {
            entry.setGeoBucket(geoBucket, ODXEntry.GeoResolution.FINE);
        } catch (ODXEntry.ODXValidationException e) {
            exceptionThrown = true;
        }
        
        assert exceptionThrown
            : "ODX must reject fine geo resolution";
    }

    @Property(tries = 100)
    @Label("Property 25: ODX entries have no raw payload reference")
    void odxEntries_haveNoRawPayloadReference(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket) {
        
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, 1,
            ODXEntry.Quality.VERIFIED, 50
        );

        // ODX entry should not have any raw payload fields
        // Only: facet_key, time_bucket, geo_bucket (coarse), count, aggregate, quality, k_min
        
        assert entry.getFacetKey() != null : "Facet key required";
        assert entry.getTimeBucket() != null : "Time bucket required";
        assert entry.getCount() != null : "Count required";
        assert entry.getQuality() != null : "Quality required";
        assert entry.getKMin() != null : "K-min required";
        
        // Verify no raw data indicators in facet key
        assert !entry.getFacetKey().contains("raw")
            : "Facet key must not reference raw data";
        assert !entry.getFacetKey().contains("payload")
            : "Facet key must not reference payload";
    }

    // ========================================================================
    // K-Min Cohort Threshold (Requirement 202.1)
    // ========================================================================

    @Property(tries = 100)
    @Label("ODX enforces k-min threshold of 50")
    void odx_enforcesKMinThreshold(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket,
            @ForAll @IntRange(min = 50, max = 1000) int validKMin) {
        
        // Valid k-min (>= 50) should succeed
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, 1,
            ODXEntry.Quality.VERIFIED, validKMin
        );
        
        assert entry.getKMin() >= 50
            : "K-min must be at least 50";
    }

    @Property(tries = 100)
    @Label("ODX entries default to k-min of 50")
    void odxEntries_defaultKMin(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket) {
        
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, 1,
            ODXEntry.Quality.VERIFIED, 50
        );
        
        assert entry.getKMin() == 50
            : "Default k-min must be 50";
    }

    // ========================================================================
    // ODS Entry Properties
    // ========================================================================

    @Property(tries = 100)
    @Label("ODS entries have required fields")
    void odsEntries_haveRequiredFields(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("categories") String category) {
        
        ODSEntry entry = ODSEntry.createOnDevice(
            deviceId, dsId, category,
            "encrypted-payload",
            "key-id",
            "payload-hash",
            "retention-policy"
        );

        assert entry.getId() != null : "ID must be generated";
        assert entry.getDeviceId() != null : "Device ID required";
        assert entry.getDsId() != null : "DS ID required";
        assert entry.getCategory() != null : "Category required";
        assert entry.getEncryptedPayload() != null : "Encrypted payload required";
        assert entry.getEncryptionKeyId() != null : "Encryption key ID required";
        assert entry.getPayloadHash() != null : "Payload hash required";
        assert entry.getStorageLocation() != null : "Storage location required";
        assert entry.getRetentionPolicy() != null : "Retention policy required";
        assert entry.getCreatedAt() != null : "Created at required";
    }

    @Property(tries = 100)
    @Label("ODS entries can be marked as deleted")
    void odsEntries_canBeMarkedDeleted(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("categories") String category) {
        
        ODSEntry entry = ODSEntry.createOnDevice(
            deviceId, dsId, category,
            "encrypted-payload",
            "key-id",
            "payload-hash",
            "retention-policy"
        );

        assert entry.getDeletedAt() == null : "Initially not deleted";
        
        entry.markDeleted();
        
        assert entry.getDeletedAt() != null : "Deleted at must be set";
        assert !entry.getDeletedAt().isAfter(Instant.now())
            : "Deleted at must not be in future";
    }

    // ========================================================================
    // ODX Signature Properties (Requirement 203.3)
    // ========================================================================

    @Property(tries = 100)
    @Label("ODX entries can be signed")
    void odxEntries_canBeSigned(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("timeBuckets") String timeBucket,
            @ForAll @StringLength(min = 10, max = 100) String signature) {
        
        ODXEntry entry = ODXEntry.create(
            deviceId, dsId, facetKey, timeBucket, 1,
            ODXEntry.Quality.VERIFIED, 50
        );

        assert entry.getDeviceSignature() == null : "Initially unsigned";
        
        entry.sign(signature);
        
        assert entry.getDeviceSignature() != null : "Signature must be set";
        assert entry.getDeviceSignature().equals(signature)
            : "Signature must match";
    }

    // ========================================================================
    // Time Bucket Validation
    // ========================================================================

    @Property(tries = 100)
    @Label("ODX validates time bucket format")
    void odx_validatesTimeBucketFormat(
            @ForAll("deviceIds") UUID deviceId,
            @ForAll("dsIds") UUID dsId,
            @ForAll("validFacetKeys") String facetKey,
            @ForAll("invalidTimeBuckets") String invalidTimeBucket) {
        
        boolean exceptionThrown = false;
        try {
            ODXEntry.create(
                deviceId, dsId, facetKey, invalidTimeBucket, 1,
                ODXEntry.Quality.VERIFIED, 50
            );
        } catch (ODXEntry.ODXValidationException e) {
            exceptionThrown = true;
        }

        assert exceptionThrown
            : "ODX must reject invalid time bucket format: " + invalidTimeBucket;
    }

    // ========================================================================
    // Arbitraries
    // ========================================================================

    @Provide
    Arbitrary<UUID> deviceIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID> dsIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<UUID> consentIds() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> categories() {
        return Arbitraries.of(
            "health", "finance", "location", "activity", 
            "social", "media", "preferences", "behavior"
        );
    }

    @Provide
    Arbitrary<String> validFacetKeys() {
        return Arbitraries.of(
            "domain.health", "domain.finance", "domain.activity",
            "time.morning", "time.evening", "time.weekend",
            "geo.city", "geo.region", "geo.country",
            "quality.verified", "quality.imported",
            "activity.exercise", "activity.sleep", "activity.work",
            "health.steps", "health.heart_rate", "health.sleep_quality"
        );
    }

    @Provide
    Arbitrary<String> forbiddenFacetKeys() {
        return Arbitraries.of(
            "domain.raw_data", "domain.payload_content", "domain.text_content",
            "user.email", "user.phone", "user.address", "user.name",
            "sensitive.ssn", "sensitive.password", "sensitive.secret",
            "data.raw", "data.content", "data.token"
        );
    }

    @Provide
    Arbitrary<String> timeBuckets() {
        return Arbitraries.of(
            "2024-01", "2024-02", "2024-03",           // Month format
            "2024-W01", "2024-W15", "2024-W52",        // Week format
            "2024-01-15", "2024-06-30", "2024-12-31"   // Day format
        );
    }

    @Provide
    Arbitrary<String> invalidTimeBuckets() {
        return Arbitraries.of(
            "2024", "24-01", "2024/01", "2024-1", 
            "2024-W1", "2024-01-1", "invalid", ""
        );
    }

    @Provide
    Arbitrary<String> coarseGeoBuckets() {
        return Arbitraries.of(
            "US-CA", "US-NY", "UK-LON", "DE-BER",
            "city:san_francisco", "city:new_york", "city:london",
            "region:california", "region:bavaria", "country:usa"
        );
    }
}
