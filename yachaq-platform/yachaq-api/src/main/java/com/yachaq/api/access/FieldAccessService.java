package com.yachaq.api.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.FieldAccessLog;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.FieldAccessLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Field-Level Access Service - Enforces field-level access controls.
 * 
 * Property 17: Field-Level Access Enforcement
 * For any consent contract specifying permitted fields, executing a query
 * must return only those permitted fields and no others.
 * 
 * Validates: Requirements 219.1, 219.2, 219.5
 */
@Service
public class FieldAccessService {

    private final ConsentContractRepository consentContractRepository;
    private final FieldAccessLogRepository fieldAccessLogRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public FieldAccessService(
            ConsentContractRepository consentContractRepository,
            FieldAccessLogRepository fieldAccessLogRepository,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.consentContractRepository = consentContractRepository;
        this.fieldAccessLogRepository = fieldAccessLogRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Sets permitted fields on a consent contract.
     * Requirement 219.1: Store exact permitted fields in consent contract.
     * 
     * @param contractId The consent contract ID
     * @param permittedFields Set of permitted field names
     * @return Updated consent contract
     */
    @Transactional
    public ConsentContract setPermittedFields(UUID contractId, Set<String> permittedFields) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }
        if (permittedFields == null || permittedFields.isEmpty()) {
            throw new IllegalArgumentException("Permitted fields cannot be null or empty");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        try {
            String fieldsJson = objectMapper.writeValueAsString(new ArrayList<>(permittedFields));
            contract.setPermittedFields(fieldsJson);
            return consentContractRepository.save(contract);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize permitted fields", e);
        }
    }

    /**
     * Sets sensitive field consents on a consent contract.
     * Requirement 219.3: Support per-field consent decisions for sensitive fields.
     * 
     * @param contractId The consent contract ID
     * @param sensitiveFieldConsents Map of sensitive field names to consent status
     * @return Updated consent contract
     */
    @Transactional
    public ConsentContract setSensitiveFieldConsents(
            UUID contractId, 
            Map<String, Boolean> sensitiveFieldConsents) {
        
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }
        if (sensitiveFieldConsents == null) {
            throw new IllegalArgumentException("Sensitive field consents cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        try {
            String consentsJson = objectMapper.writeValueAsString(sensitiveFieldConsents);
            contract.setSensitiveFieldConsents(consentsJson);
            return consentContractRepository.save(contract);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize sensitive field consents", e);
        }
    }

    /**
     * Gets permitted fields from a consent contract.
     * 
     * @param contractId The consent contract ID
     * @return Set of permitted field names
     */
    public Set<String> getPermittedFields(UUID contractId) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        return parsePermittedFields(contract.getPermittedFields());
    }

    /**
     * Parses permitted fields from JSON string.
     */
    public Set<String> parsePermittedFields(String permittedFieldsJson) {
        if (permittedFieldsJson == null || permittedFieldsJson.isBlank()) {
            return Collections.emptySet();
        }

        try {
            List<String> fields = objectMapper.readValue(
                    permittedFieldsJson, 
                    new TypeReference<List<String>>() {});
            return new HashSet<>(fields);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse permitted fields", e);
        }
    }

    /**
     * Validates that requested fields are within permitted fields.
     * Property 17: Field-Level Access Enforcement
     * 
     * @param permittedFields Set of permitted field names
     * @param requestedFields Set of requested field names
     * @return FieldAccessResult containing validation result
     */
    public FieldAccessResult validateFieldAccess(
            Set<String> permittedFields, 
            Set<String> requestedFields) {
        
        if (permittedFields == null || permittedFields.isEmpty()) {
            return new FieldAccessResult(false, Collections.emptySet(), requestedFields,
                    "No permitted fields defined in consent contract");
        }
        if (requestedFields == null || requestedFields.isEmpty()) {
            return new FieldAccessResult(true, Collections.emptySet(), Collections.emptySet(), null);
        }

        Set<String> allowedFields = new HashSet<>();
        Set<String> deniedFields = new HashSet<>();

        for (String field : requestedFields) {
            if (permittedFields.contains(field)) {
                allowedFields.add(field);
            } else {
                deniedFields.add(field);
            }
        }

        boolean valid = deniedFields.isEmpty();
        String reason = valid ? null : "Unauthorized fields requested: " + deniedFields;

        return new FieldAccessResult(valid, allowedFields, deniedFields, reason);
    }

    /**
     * Filters data to only include permitted fields.
     * Property 17: Executing a query must return only permitted fields and no others.
     * 
     * @param data Map of field names to values
     * @param permittedFields Set of permitted field names
     * @return Filtered map containing only permitted fields
     */
    public Map<String, Object> filterToPermittedFields(
            Map<String, Object> data, 
            Set<String> permittedFields) {
        
        if (data == null) {
            return Collections.emptyMap();
        }
        if (permittedFields == null || permittedFields.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String field : permittedFields) {
            if (data.containsKey(field)) {
                filtered.put(field, data.get(field));
            }
        }
        return filtered;
    }

    /**
     * Executes field-level access with full enforcement and logging.
     * Property 17: Field-Level Access Enforcement
     * Requirement 219.2: Extract only permitted fields during query execution.
     * Requirement 219.5: Log field access with hash in receipt.
     * 
     * @param queryPlan The query plan being executed
     * @param requestedFields Set of requested field names
     * @param data Map of field names to values
     * @param accessorId The requester accessing the fields
     * @return FieldAccessExecutionResult containing filtered data and access log
     */
    @Transactional
    public FieldAccessExecutionResult executeFieldAccess(
            QueryPlan queryPlan,
            Set<String> requestedFields,
            Map<String, Object> data,
            UUID accessorId) {
        
        if (queryPlan == null) {
            throw new IllegalArgumentException("Query plan cannot be null");
        }
        if (accessorId == null) {
            throw new IllegalArgumentException("Accessor ID cannot be null");
        }

        // Get permitted fields from query plan
        Set<String> permittedFields = parsePermittedFields(queryPlan.getPermittedFields());

        // Validate field access
        FieldAccessResult validation = validateFieldAccess(permittedFields, requestedFields);

        if (!validation.isValid()) {
            // Log unauthorized access attempt
            auditService.appendReceipt(
                    AuditReceipt.EventType.UNAUTHORIZED_FIELD_ACCESS_ATTEMPT,
                    accessorId,
                    AuditReceipt.ActorType.REQUESTER,
                    queryPlan.getId(),
                    "QueryPlan",
                    computeHash(validation.deniedFields().toString()));

            return new FieldAccessExecutionResult(
                    false, 
                    Collections.emptyMap(), 
                    null, 
                    validation.reason());
        }

        // Filter data to permitted fields only
        Map<String, Object> filteredData = filterToPermittedFields(data, permittedFields);

        // Compute field hashes for audit
        Map<String, String> fieldHashes = computeFieldHashes(filteredData);

        // Create access log
        try {
            String accessedFieldsJson = objectMapper.writeValueAsString(new ArrayList<>(filteredData.keySet()));
            String fieldHashesJson = objectMapper.writeValueAsString(fieldHashes);

            FieldAccessLog accessLog = FieldAccessLog.create(
                    queryPlan.getConsentContractId(),
                    queryPlan.getId(),
                    accessedFieldsJson,
                    fieldHashesJson,
                    accessorId);

            accessLog = fieldAccessLogRepository.save(accessLog);

            // Create audit receipt
            AuditReceipt receipt = auditService.appendReceipt(
                    AuditReceipt.EventType.FIELD_ACCESS_GRANTED,
                    accessorId,
                    AuditReceipt.ActorType.REQUESTER,
                    queryPlan.getId(),
                    "QueryPlan",
                    computeHash(fieldHashesJson));

            // Link access log to audit receipt
            if (receipt != null && receipt.getId() != null) {
                accessLog.linkToAuditReceipt(receipt.getId());
                fieldAccessLogRepository.save(accessLog);
            }

            return new FieldAccessExecutionResult(true, filteredData, accessLog, null);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize field access data", e);
        }
    }

    /**
     * Copies permitted fields from consent contract to query plan.
     * 
     * @param contract The consent contract
     * @param queryPlan The query plan to update
     */
    public void copyPermittedFieldsToQueryPlan(ConsentContract contract, QueryPlan queryPlan) {
        if (contract == null) {
            throw new IllegalArgumentException("Consent contract cannot be null");
        }
        if (queryPlan == null) {
            throw new IllegalArgumentException("Query plan cannot be null");
        }

        queryPlan.setPermittedFields(contract.getPermittedFields());
    }

    /**
     * Computes cryptographic hashes for field values.
     * Requirement 219.5: Log field access with hash in receipt.
     */
    private Map<String, String> computeFieldHashes(Map<String, Object> data) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String valueStr = entry.getValue() != null ? entry.getValue().toString() : "";
            hashes.put(entry.getKey(), computeHash(valueStr));
        }
        return hashes;
    }

    /**
     * Computes SHA-256 hash of a string.
     */
    private String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Result of field access validation.
     */
    public record FieldAccessResult(
            boolean valid,
            Set<String> allowedFields,
            Set<String> deniedFields,
            String reason) {
        
        public boolean isValid() { return valid; }
    }

    /**
     * Result of field access execution.
     */
    public record FieldAccessExecutionResult(
            boolean success,
            Map<String, Object> filteredData,
            FieldAccessLog accessLog,
            String errorReason) {
        
        public boolean isSuccess() { return success; }
    }
}
