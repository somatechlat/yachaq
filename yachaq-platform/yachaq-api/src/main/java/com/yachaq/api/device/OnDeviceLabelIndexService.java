package com.yachaq.api.device;

import com.yachaq.api.audit.AuditService;
import com.yachaq.api.security.DataIntegrityService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.AuditReceipt.ActorType;
import com.yachaq.core.domain.AuditReceipt.EventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * On-Device Label Index Service - Privacy-safe discovery index.
 * 
 * Requirements: 201.1, 201.2, 203.1, 203.3
 * - Contains only coarse labels, timestamps, and availability bands
 * - NO raw payload content
 * - Signed with device key for authenticity
 * 
 * Property 12: Edge-First Data Locality
 * Property 25: ODX Minimization
 * For any ODX entry generated, the entry must contain only coarse labels,
 * timestamps, and availability bands - never raw payload content.
 * 
 * Key constraints:
 * - Only coarse labels from approved ontology
 * - No raw payload, reversible text, or precise GPS
 * - Minimum k-anonymity thresholds enforced
 * - Device signature for authenticity
 */
@Service
public class OnDeviceLabelIndexService {

    private static final int DEFAULT_K_MIN = 50;
    private static final Pattern FACET_PATTERN = Pattern.compile("^[a-z]+\\.[a-z_]+$");
    private static final Pattern TIME_BUCKET_PATTERN = Pattern.compile("^\\d{4}(-W\\d{2}|-\\d{2}(-\\d{2})?)$");
    
    // Forbidden patterns that indicate raw data
    private static final Set<String> FORBIDDEN_PATTERNS = Set.of(
        "raw", "payload", "content", "text", "email", "phone", 
        "address", "name", "ssn", "password", "secret", "token"
    );

    // Approved facet namespaces
    private static final Set<String> APPROVED_NAMESPACES = Set.of(
        "domain", "time", "geo", "quality", "privacy", "activity", "health", "finance"
    );

    private final ODXRepository odxRepository;
    private final DataIntegrityService integrityService;
    private final AuditService auditService;

    public OnDeviceLabelIndexService(
            ODXRepository odxRepository,
            DataIntegrityService integrityService,
            AuditService auditService) {
        this.odxRepository = odxRepository;
        this.integrityService = integrityService;
        this.auditService = auditService;
    }

    /**
     * Update the label index with new entries.
     * 
     * Requirements: 201.1, 201.2
     * - Only coarse labels, timestamps, availability bands
     * - NO raw payload content
     * 
     * @param deviceId Device identifier
     * @param dsId Data Sovereign identifier
     * @param updates List of index updates
     * @return Updated entries
     */
    @Transactional
    public List<ODXEntry> updateIndex(UUID deviceId, UUID dsId, List<IndexUpdate> updates) {
        List<ODXEntry> results = new ArrayList<>();

        for (IndexUpdate update : updates) {
            // Validate privacy safety
            validatePrivacySafety(update);

            // Check if entry exists for upsert
            Optional<ODXEntry> existing = odxRepository.findByDeviceIdAndFacetKeyAndTimeBucket(
                deviceId, update.facetKey(), update.timeBucket());

            ODXEntry entry;
            if (existing.isPresent()) {
                entry = existing.get();
                entry.setCount(update.count());
                if (update.aggregateValue() != null) {
                    entry.setAggregateValue(update.aggregateValue());
                }
            } else {
                entry = ODXEntry.create(
                    deviceId, dsId, update.facetKey(), update.timeBucket(),
                    update.count(), update.quality(), update.kMin() != null ? update.kMin() : DEFAULT_K_MIN);
                
                if (update.geoBucket() != null) {
                    validateGeoBucket(update.geoBucket());
                    entry.setGeoBucket(update.geoBucket(), ODXEntry.GeoResolution.COARSE);
                }
                if (update.aggregateValue() != null) {
                    entry.setAggregateValue(update.aggregateValue());
                }
            }

            results.add(odxRepository.save(entry));
        }

        // Generate audit receipt
        auditService.appendReceipt(
            EventType.DATA_ACCESS,
            dsId,
            ActorType.DS,
            deviceId,
            "odx_update",
            "ODX updated: " + updates.size() + " entries"
        );

        return results;
    }

    /**
     * Get the current index state for a device.
     */
    @Transactional(readOnly = true)
    public IndexState getIndex(UUID deviceId) {
        List<ODXEntry> entries = odxRepository.findByDeviceId(deviceId);
        
        Map<String, List<ODXEntry>> byFacet = entries.stream()
            .collect(Collectors.groupingBy(ODXEntry::getFacetKey));

        List<String> facetKeys = new ArrayList<>(byFacet.keySet());
        
        return new IndexState(deviceId, entries, facetKeys, Instant.now());
    }

    /**
     * Get index for a DS across all devices.
     */
    @Transactional(readOnly = true)
    public IndexState getIndexForDs(UUID dsId) {
        List<ODXEntry> entries = odxRepository.findByDsId(dsId);
        List<String> facetKeys = odxRepository.findDistinctFacetKeysByDsId(dsId);
        
        return new IndexState(null, entries, facetKeys, Instant.now());
    }

    /**
     * Sign the index with device key.
     * 
     * Requirement 203.3: Sign index updates with device key.
     * 
     * @param deviceId Device identifier
     * @param privateKey Device private key for signing
     * @return Signed entries count
     */
    @Transactional
    public SignResult signIndex(UUID deviceId, PrivateKey privateKey) {
        List<ODXEntry> entries = odxRepository.findByDeviceId(deviceId);
        
        int signedCount = 0;
        for (ODXEntry entry : entries) {
            String dataToSign = buildSignatureData(entry);
            String signature = sign(dataToSign, privateKey);
            entry.sign(signature);
            odxRepository.save(entry);
            signedCount++;
        }

        return new SignResult(signedCount, Instant.now());
    }

    /**
     * Verify index signature.
     * 
     * @param entryId Entry to verify
     * @param publicKey Device public key
     * @return Verification result
     */
    public boolean verifySignature(UUID entryId, PublicKey publicKey) {
        Optional<ODXEntry> entryOpt = odxRepository.findById(entryId);
        if (entryOpt.isEmpty()) {
            return false;
        }

        ODXEntry entry = entryOpt.get();
        if (entry.getDeviceSignature() == null) {
            return false;
        }

        String dataToSign = buildSignatureData(entry);
        return verify(dataToSign, entry.getDeviceSignature(), publicKey);
    }

    /**
     * Query index with k-min enforcement.
     * 
     * Requirement 202.1: Enforce k-min cohort thresholds (k ≥ 50).
     * 
     * @param dsId Data Sovereign identifier
     * @param facetKey Facet to query
     * @param minK Minimum k-anonymity threshold
     * @return Matching entries
     */
    @Transactional(readOnly = true)
    public List<ODXEntry> queryWithKMin(UUID dsId, String facetKey, int minK) {
        if (minK < DEFAULT_K_MIN) {
            throw new ODXQueryException(
                "k-min threshold too low: " + minK + " < " + DEFAULT_K_MIN);
        }

        return odxRepository.findByDsIdAndFacetKey(dsId, facetKey).stream()
            .filter(e -> e.getKMin() >= minK)
            .collect(Collectors.toList());
    }

    /**
     * Check if an entry is privacy-safe.
     * 
     * Property 25: ODX Minimization validation.
     */
    public boolean isPrivacySafe(UUID entryId) {
        return odxRepository.findById(entryId)
            .map(ODXEntry::isPrivacySafe)
            .orElse(false);
    }

    /**
     * Validate that a facet key contains no raw data.
     * 
     * Requirement 201.2: NO raw payload content.
     */
    public ValidationResult validateFacetKey(String facetKey) {
        List<String> violations = new ArrayList<>();

        if (facetKey == null || facetKey.isEmpty()) {
            violations.add("Facet key cannot be empty");
            return new ValidationResult(false, violations);
        }

        // Check pattern
        if (!FACET_PATTERN.matcher(facetKey).matches()) {
            violations.add("Facet key must follow namespace.label pattern");
        }

        // Check namespace
        String namespace = facetKey.contains(".") ? facetKey.split("\\.")[0] : "";
        if (!APPROVED_NAMESPACES.contains(namespace)) {
            violations.add("Namespace not approved: " + namespace);
        }

        // Check for forbidden patterns
        String lower = facetKey.toLowerCase();
        for (String forbidden : FORBIDDEN_PATTERNS) {
            if (lower.contains(forbidden)) {
                violations.add("Contains forbidden raw data indicator: " + forbidden);
            }
        }

        return new ValidationResult(violations.isEmpty(), violations);
    }

    /**
     * Get statistics for ODX entries.
     */
    public ODXStats getStats(UUID dsId) {
        List<ODXEntry> entries = odxRepository.findByDsId(dsId);
        
        long verifiedCount = odxRepository.countByDsIdAndQuality(dsId, ODXEntry.Quality.VERIFIED);
        long importedCount = odxRepository.countByDsIdAndQuality(dsId, ODXEntry.Quality.IMPORTED);
        List<String> facetKeys = odxRepository.findDistinctFacetKeysByDsId(dsId);

        return new ODXStats(entries.size(), verifiedCount, importedCount, facetKeys);
    }

    // Private helpers

    private void validatePrivacySafety(IndexUpdate update) {
        // Validate facet key
        ValidationResult facetResult = validateFacetKey(update.facetKey());
        if (!facetResult.valid()) {
            throw new ODXValidationException(
                "Invalid facet key: " + String.join(", ", facetResult.violations()));
        }

        // Validate time bucket
        if (!TIME_BUCKET_PATTERN.matcher(update.timeBucket()).matches()) {
            throw new ODXValidationException(
                "Invalid time bucket format: " + update.timeBucket());
        }

        // Validate k-min
        if (update.kMin() != null && update.kMin() < DEFAULT_K_MIN) {
            throw new ODXValidationException(
                "k-min below threshold: " + update.kMin() + " < " + DEFAULT_K_MIN);
        }
    }

    private void validateGeoBucket(String geoBucket) {
        // Geo bucket must be coarse (city/region level)
        // Reject precise coordinates
        if (geoBucket.matches(".*\\d+\\.\\d{4,}.*")) {
            throw new ODXValidationException(
                "Geo bucket too precise - use coarse location only");
        }
    }

    private String buildSignatureData(ODXEntry entry) {
        return entry.getDeviceId() + "|" +
               entry.getFacetKey() + "|" +
               entry.getTimeBucket() + "|" +
               entry.getCount() + "|" +
               entry.getUpdatedAt().toEpochMilli();
    }

    private String sign(String data, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new ODXSignatureException("Failed to sign data: " + e.getMessage());
        }
    }

    private boolean verify(String data, String signatureStr, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureStr));
        } catch (Exception e) {
            return false;
        }
    }

    // DTOs

    public record IndexUpdate(
        String facetKey,
        String timeBucket,
        String geoBucket,
        Integer count,
        Double aggregateValue,
        ODXEntry.Quality quality,
        Integer kMin
    ) {}

    public record IndexState(
        UUID deviceId,
        List<ODXEntry> entries,
        List<String> facetKeys,
        Instant retrievedAt
    ) {}

    public record SignResult(
        int signedCount,
        Instant signedAt
    ) {}

    public record ValidationResult(
        boolean valid,
        List<String> violations
    ) {}

    public record ODXStats(
        long totalEntries,
        long verifiedCount,
        long importedCount,
        List<String> facetKeys
    ) {}

    // Exceptions

    public static class ODXValidationException extends RuntimeException {
        public ODXValidationException(String message) { super(message); }
    }

    public static class ODXQueryException extends RuntimeException {
        public ODXQueryException(String message) { super(message); }
    }

    public static class ODXSignatureException extends RuntimeException {
        public ODXSignatureException(String message) { super(message); }
    }
}
