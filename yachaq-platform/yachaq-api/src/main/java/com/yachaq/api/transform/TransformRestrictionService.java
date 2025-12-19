package com.yachaq.api.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.QueryPlan;
import com.yachaq.core.domain.TransformExecutionLog;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.TransformExecutionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Transform Restriction Service - Enforces transform restrictions.
 * 
 * Property 18: Transform Restriction Enforcement
 * For any consent contract specifying allowed transforms, applying a transform
 * not in the allowed list must be rejected.
 * 
 * Validates: Requirements 220.1, 220.2, 220.3, 220.6
 */
@Service
public class TransformRestrictionService {

    private final ConsentContractRepository consentContractRepository;
    private final TransformExecutionLogRepository transformExecutionLogRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TransformRestrictionService(
            ConsentContractRepository consentContractRepository,
            TransformExecutionLogRepository transformExecutionLogRepository,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.consentContractRepository = consentContractRepository;
        this.transformExecutionLogRepository = transformExecutionLogRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Sets allowed transforms on a consent contract.
     * Requirement 220.1: Store allowed transforms in consent contract.
     * 
     * @param contractId The consent contract ID
     * @param allowedTransforms Set of allowed transform names
     * @return Updated consent contract
     */
    @Transactional
    public ConsentContract setAllowedTransforms(UUID contractId, Set<String> allowedTransforms) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }
        if (allowedTransforms == null) {
            throw new IllegalArgumentException("Allowed transforms cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        try {
            String transformsJson = objectMapper.writeValueAsString(new ArrayList<>(allowedTransforms));
            contract.setAllowedTransforms(transformsJson);
            return consentContractRepository.save(contract);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize allowed transforms", e);
        }
    }

    /**
     * Sets transform chain rules on a consent contract.
     * Requirement 220.3: Support transform chaining validation.
     * 
     * @param contractId The consent contract ID
     * @param chainRules Map of transform names to allowed successor transforms
     * @return Updated consent contract
     */
    @Transactional
    public ConsentContract setTransformChainRules(
            UUID contractId, 
            Map<String, List<String>> chainRules) {
        
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }
        if (chainRules == null) {
            throw new IllegalArgumentException("Chain rules cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        try {
            String rulesJson = objectMapper.writeValueAsString(chainRules);
            contract.setTransformChainRules(rulesJson);
            return consentContractRepository.save(contract);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize chain rules", e);
        }
    }

    /**
     * Gets allowed transforms from a consent contract.
     * 
     * @param contractId The consent contract ID
     * @return Set of allowed transform names
     */
    public Set<String> getAllowedTransforms(UUID contractId) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        return parseAllowedTransforms(contract.getAllowedTransforms());
    }

    /**
     * Parses allowed transforms from JSON string.
     */
    public Set<String> parseAllowedTransforms(String allowedTransformsJson) {
        if (allowedTransformsJson == null || allowedTransformsJson.isBlank()) {
            return Collections.emptySet();
        }

        try {
            List<String> transforms = objectMapper.readValue(
                    allowedTransformsJson, 
                    new TypeReference<List<String>>() {});
            return new HashSet<>(transforms);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse allowed transforms", e);
        }
    }

    /**
     * Parses transform chain rules from JSON string.
     */
    public Map<String, List<String>> parseChainRules(String chainRulesJson) {
        if (chainRulesJson == null || chainRulesJson.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(
                    chainRulesJson, 
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse chain rules", e);
        }
    }

    /**
     * Validates that requested transforms are within allowed transforms.
     * Property 18: Transform Restriction Enforcement
     * 
     * @param allowedTransforms Set of allowed transform names
     * @param requestedTransforms List of requested transform names (in order)
     * @return TransformValidationResult containing validation result
     */
    public TransformValidationResult validateTransforms(
            Set<String> allowedTransforms, 
            List<String> requestedTransforms) {
        
        if (allowedTransforms == null || allowedTransforms.isEmpty()) {
            if (requestedTransforms == null || requestedTransforms.isEmpty()) {
                return new TransformValidationResult(true, Collections.emptyList(), 
                        Collections.emptyList(), null);
            }
            return new TransformValidationResult(false, Collections.emptyList(), 
                    requestedTransforms, "No transforms allowed in consent contract");
        }
        if (requestedTransforms == null || requestedTransforms.isEmpty()) {
            return new TransformValidationResult(true, Collections.emptyList(), 
                    Collections.emptyList(), null);
        }

        List<String> allowedList = new ArrayList<>();
        List<String> deniedList = new ArrayList<>();

        for (String transform : requestedTransforms) {
            if (allowedTransforms.contains(transform)) {
                allowedList.add(transform);
            } else {
                deniedList.add(transform);
            }
        }

        boolean valid = deniedList.isEmpty();
        String reason = valid ? null : "Unauthorized transforms requested: " + deniedList;

        return new TransformValidationResult(valid, allowedList, deniedList, reason);
    }

    /**
     * Validates transform chain against chain rules.
     * Requirement 220.3: Support transform chaining validation.
     * 
     * @param chainRules Map of transform names to allowed successor transforms
     * @param transformChain List of transforms in execution order
     * @return TransformChainValidationResult containing validation result
     */
    public TransformChainValidationResult validateTransformChain(
            Map<String, List<String>> chainRules,
            List<String> transformChain) {
        
        if (chainRules == null || chainRules.isEmpty()) {
            // No chain rules means any order is allowed
            return new TransformChainValidationResult(true, null, -1, -1);
        }
        if (transformChain == null || transformChain.size() < 2) {
            // Single transform or empty chain doesn't need chain validation
            return new TransformChainValidationResult(true, null, -1, -1);
        }

        for (int i = 0; i < transformChain.size() - 1; i++) {
            String current = transformChain.get(i);
            String next = transformChain.get(i + 1);

            List<String> allowedSuccessors = chainRules.get(current);
            if (allowedSuccessors != null && !allowedSuccessors.contains(next)) {
                return new TransformChainValidationResult(
                        false,
                        "Transform '" + next + "' cannot follow '" + current + "'",
                        i, i + 1);
            }
        }

        return new TransformChainValidationResult(true, null, -1, -1);
    }

    /**
     * Executes transforms with full enforcement and logging.
     * Property 18: Transform Restriction Enforcement
     * Requirement 220.2: Verify each transform against allowed list.
     * Requirement 220.6: Reject unauthorized transforms with logging.
     * 
     * @param queryPlan The query plan being executed
     * @param requestedTransforms List of requested transform names (in order)
     * @param inputData The input data to transform
     * @param executorId The requester executing the transforms
     * @return TransformExecutionResult containing execution result
     */
    @Transactional
    public TransformExecutionResult executeTransforms(
            QueryPlan queryPlan,
            List<String> requestedTransforms,
            Object inputData,
            UUID executorId) {
        
        if (queryPlan == null) {
            throw new IllegalArgumentException("Query plan cannot be null");
        }
        if (executorId == null) {
            throw new IllegalArgumentException("Executor ID cannot be null");
        }

        // Get allowed transforms from query plan
        Set<String> allowedTransforms = parseAllowedTransforms(queryPlan.getAllowedTransforms());

        // Validate transforms
        TransformValidationResult validation = validateTransforms(allowedTransforms, requestedTransforms);

        String inputHash = computeHash(inputData != null ? inputData.toString() : "");

        if (!validation.isValid()) {
            // Log unauthorized transform attempt
            try {
                String transformsJson = objectMapper.writeValueAsString(requestedTransforms);
                
                TransformExecutionLog log = TransformExecutionLog.createRejected(
                        queryPlan.getConsentContractId(),
                        queryPlan.getId(),
                        transformsJson,
                        transformsJson,
                        inputHash,
                        executorId,
                        validation.reason());

                log = transformExecutionLogRepository.save(log);

                auditService.appendReceipt(
                        AuditReceipt.EventType.UNAUTHORIZED_TRANSFORM_ATTEMPT,
                        executorId,
                        AuditReceipt.ActorType.REQUESTER,
                        queryPlan.getId(),
                        "QueryPlan",
                        computeHash(validation.deniedTransforms().toString()));

                return new TransformExecutionResult(false, null, log, validation.reason());

            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize transforms", e);
            }
        }

        // Execute transforms by applying each transform in sequence
        Object outputData = applyTransforms(inputData, requestedTransforms);
        String outputHash = computeHash(outputData != null ? outputData.toString() : "");

        try {
            String transformsJson = objectMapper.writeValueAsString(requestedTransforms);
            
            TransformExecutionLog log = TransformExecutionLog.createSuccess(
                    queryPlan.getConsentContractId(),
                    queryPlan.getId(),
                    transformsJson,
                    transformsJson,
                    inputHash,
                    outputHash,
                    executorId);

            log = transformExecutionLogRepository.save(log);

            AuditReceipt receipt = auditService.appendReceipt(
                    AuditReceipt.EventType.TRANSFORM_EXECUTED,
                    executorId,
                    AuditReceipt.ActorType.REQUESTER,
                    queryPlan.getId(),
                    "QueryPlan",
                    computeHash(transformsJson));

            if (receipt != null && receipt.getId() != null) {
                log.linkToAuditReceipt(receipt.getId());
                transformExecutionLogRepository.save(log);
            }

            return new TransformExecutionResult(true, outputData, log, null);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize transforms", e);
        }
    }

    /**
     * Copies allowed transforms from consent contract to query plan.
     */
    public void copyAllowedTransformsToQueryPlan(ConsentContract contract, QueryPlan queryPlan) {
        if (contract == null) {
            throw new IllegalArgumentException("Consent contract cannot be null");
        }
        if (queryPlan == null) {
            throw new IllegalArgumentException("Query plan cannot be null");
        }

        queryPlan.setAllowedTransforms(contract.getAllowedTransforms());
    }

    /**
     * Applies a sequence of transforms to input data.
     * Supported transforms: aggregate, anonymize, filter, project, hash, truncate
     */
    @SuppressWarnings("unchecked")
    private Object applyTransforms(Object inputData, List<String> transforms) {
        if (inputData == null || transforms == null || transforms.isEmpty()) {
            return inputData;
        }
        
        Object result = inputData;
        for (String transform : transforms) {
            result = applySingleTransform(result, transform);
        }
        return result;
    }
    
    /**
     * Applies a single transform to data.
     */
    @SuppressWarnings("unchecked")
    private Object applySingleTransform(Object data, String transform) {
        if (data == null) {
            return null;
        }
        
        return switch (transform.toLowerCase()) {
            case "aggregate" -> applyAggregate(data);
            case "anonymize" -> applyAnonymize(data);
            case "hash" -> applyHash(data);
            case "truncate" -> applyTruncate(data);
            case "filter" -> applyFilter(data);
            case "project" -> applyProject(data);
            case "count" -> applyCount(data);
            case "sum" -> applySum(data);
            case "average" -> applyAverage(data);
            default -> data; // Unknown transform passes through
        };
    }
    
    private Object applyAggregate(Object data) {
        if (data instanceof List<?> list) {
            return Map.of("count", list.size(), "aggregated", true);
        }
        return Map.of("count", 1, "aggregated", true);
    }
    
    private Object applyAnonymize(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                Object value = entry.getValue();
                // Anonymize PII fields
                if (isPiiField(key)) {
                    result.put(key, "[ANONYMIZED]");
                } else if (value instanceof Map) {
                    result.put(key, applyAnonymize(value));
                } else {
                    result.put(key, value);
                }
            }
            return result;
        }
        return data;
    }
    
    private boolean isPiiField(String fieldName) {
        String lower = fieldName.toLowerCase();
        return lower.contains("name") || lower.contains("email") || 
               lower.contains("phone") || lower.contains("address") ||
               lower.contains("ssn") || lower.contains("dob");
    }
    
    private Object applyHash(Object data) {
        return computeHash(data.toString());
    }
    
    private Object applyTruncate(Object data) {
        if (data instanceof String str) {
            return str.length() > 100 ? str.substring(0, 100) + "..." : str;
        }
        if (data instanceof List<?> list) {
            return list.size() > 10 ? list.subList(0, 10) : list;
        }
        return data;
    }
    
    private Object applyFilter(Object data) {
        // Filter removes null values
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return result;
        }
        if (data instanceof List<?> list) {
            return list.stream().filter(item -> item != null).toList();
        }
        return data;
    }
    
    private Object applyProject(Object data) {
        // Project returns only non-sensitive fields
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                if (!isPiiField(key)) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return data;
    }
    
    private Object applyCount(Object data) {
        if (data instanceof List<?> list) {
            return Map.of("count", list.size());
        }
        if (data instanceof Map<?, ?> map) {
            return Map.of("count", map.size());
        }
        return Map.of("count", 1);
    }
    
    private Object applySum(Object data) {
        if (data instanceof List<?> list) {
            double sum = list.stream()
                    .filter(item -> item instanceof Number)
                    .mapToDouble(item -> ((Number) item).doubleValue())
                    .sum();
            return Map.of("sum", sum);
        }
        return data;
    }
    
    private Object applyAverage(Object data) {
        if (data instanceof List<?> list) {
            double avg = list.stream()
                    .filter(item -> item instanceof Number)
                    .mapToDouble(item -> ((Number) item).doubleValue())
                    .average()
                    .orElse(0.0);
            return Map.of("average", avg);
        }
        return data;
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
     * Result of transform validation.
     */
    public record TransformValidationResult(
            boolean valid,
            List<String> allowedTransforms,
            List<String> deniedTransforms,
            String reason) {
        
        public boolean isValid() { return valid; }
    }

    /**
     * Result of transform chain validation.
     */
    public record TransformChainValidationResult(
            boolean valid,
            String reason,
            int invalidFromIndex,
            int invalidToIndex) {
        
        public boolean isValid() { return valid; }
    }

    /**
     * Result of transform execution.
     */
    public record TransformExecutionResult(
            boolean success,
            Object outputData,
            TransformExecutionLog executionLog,
            String errorReason) {
        
        public boolean isSuccess() { return success; }
    }
}
