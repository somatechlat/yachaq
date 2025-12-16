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

        // Execute transforms (in production, this would apply actual transforms)
        // For now, we just log the successful execution
        Object outputData = inputData; // Placeholder - actual transform logic would go here
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
