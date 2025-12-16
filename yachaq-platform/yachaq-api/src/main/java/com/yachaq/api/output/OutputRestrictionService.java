package com.yachaq.api.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.CleanRoomSession;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.OutputRestrictionViolation;
import com.yachaq.core.domain.OutputRestrictionViolation.ViolationType;
import com.yachaq.core.repository.CleanRoomSessionRepository;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.OutputRestrictionViolationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Output Restriction Service - Enforces output restrictions in clean room sessions.
 * 
 * Validates: Requirements 221.1, 221.2, 221.3, 221.4
 * 
 * Supports restriction types:
 * - VIEW_ONLY: Disable download, copy, screenshot
 * - AGGREGATE_ONLY: Return only aggregated results
 * - NO_EXPORT: Block all export attempts
 */
@Service
public class OutputRestrictionService {

    private final ConsentContractRepository consentContractRepository;
    private final CleanRoomSessionRepository cleanRoomSessionRepository;
    private final OutputRestrictionViolationRepository violationRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Output restriction types.
     */
    public enum RestrictionType {
        VIEW_ONLY,      // Disable download, copy, screenshot
        AGGREGATE_ONLY, // Return only aggregated results
        NO_EXPORT,      // Block all export attempts
        NO_DOWNLOAD,    // Block download specifically
        NO_COPY,        // Block copy specifically
        NO_SCREENSHOT,  // Block screenshot specifically
        NO_PRINT        // Block print specifically
    }

    public OutputRestrictionService(
            ConsentContractRepository consentContractRepository,
            CleanRoomSessionRepository cleanRoomSessionRepository,
            OutputRestrictionViolationRepository violationRepository,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.consentContractRepository = consentContractRepository;
        this.cleanRoomSessionRepository = cleanRoomSessionRepository;
        this.violationRepository = violationRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Sets output restrictions on a consent contract.
     * Requirement 221.1: Support view-only, aggregate-only, no-export restrictions.
     * 
     * @param contractId The consent contract ID
     * @param restrictions Set of restriction types
     * @return Updated consent contract
     */
    @Transactional
    public ConsentContract setOutputRestrictions(UUID contractId, Set<RestrictionType> restrictions) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }
        if (restrictions == null) {
            throw new IllegalArgumentException("Restrictions cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        try {
            List<String> restrictionNames = restrictions.stream()
                    .map(Enum::name)
                    .toList();
            String restrictionsJson = objectMapper.writeValueAsString(restrictionNames);
            contract.setOutputRestrictions(restrictionsJson);
            return consentContractRepository.save(contract);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize output restrictions", e);
        }
    }

    /**
     * Gets output restrictions from a consent contract.
     */
    public Set<RestrictionType> getOutputRestrictions(UUID contractId) {
        if (contractId == null) {
            throw new IllegalArgumentException("Contract ID cannot be null");
        }

        ConsentContract contract = consentContractRepository.findById(contractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + contractId));

        return parseOutputRestrictions(contract.getOutputRestrictions());
    }

    /**
     * Parses output restrictions from JSON string.
     */
    public Set<RestrictionType> parseOutputRestrictions(String restrictionsJson) {
        if (restrictionsJson == null || restrictionsJson.isBlank()) {
            return Collections.emptySet();
        }

        try {
            List<String> restrictionNames = objectMapper.readValue(
                    restrictionsJson, 
                    new TypeReference<List<String>>() {});
            Set<RestrictionType> restrictions = new HashSet<>();
            for (String name : restrictionNames) {
                try {
                    restrictions.add(RestrictionType.valueOf(name));
                } catch (IllegalArgumentException e) {
                    // Skip unknown restriction types
                }
            }
            return restrictions;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse output restrictions", e);
        }
    }

    /**
     * Creates a clean room session for controlled data access.
     * Requirement 221.3: Disable download, copy, screenshot in view-only mode.
     * 
     * @param capsuleId The time capsule being accessed
     * @param requesterId The requester accessing the data
     * @param consentContractId The consent contract authorizing access
     * @param ttlMinutes Session time-to-live in minutes
     * @return Created clean room session
     */
    @Transactional
    public CleanRoomSession createCleanRoomSession(
            UUID capsuleId,
            UUID requesterId,
            UUID consentContractId,
            int ttlMinutes) {
        
        if (capsuleId == null) {
            throw new IllegalArgumentException("Capsule ID cannot be null");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("Requester ID cannot be null");
        }
        if (consentContractId == null) {
            throw new IllegalArgumentException("Consent contract ID cannot be null");
        }

        // Get output restrictions from consent contract
        ConsentContract contract = consentContractRepository.findById(consentContractId)
                .orElseThrow(() -> new IllegalArgumentException("Consent contract not found: " + consentContractId));

        String outputRestrictions = contract.getOutputRestrictions();
        if (outputRestrictions == null || outputRestrictions.isBlank()) {
            outputRestrictions = "[]";
        }

        CleanRoomSession session = CleanRoomSession.create(
                capsuleId,
                requesterId,
                consentContractId,
                outputRestrictions,
                ttlMinutes);

        session = cleanRoomSessionRepository.save(session);

        // Create audit receipt
        auditService.appendReceipt(
                AuditReceipt.EventType.CLEAN_ROOM_SESSION_STARTED,
                requesterId,
                AuditReceipt.ActorType.REQUESTER,
                session.getId(),
                "CleanRoomSession",
                "session_started");

        return session;
    }

    /**
     * Checks if an action is allowed based on output restrictions.
     * Requirement 221.2: Enforce restrictions based on consent specification.
     * 
     * @param sessionId The clean room session ID
     * @param actionType The action being attempted
     * @return ActionCheckResult containing whether action is allowed
     */
    @Transactional
    public ActionCheckResult checkAction(UUID sessionId, ViolationType actionType) {
        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID cannot be null");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("Action type cannot be null");
        }

        CleanRoomSession session = cleanRoomSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (!session.isActive()) {
            return new ActionCheckResult(false, "Session is not active", null);
        }

        Set<RestrictionType> restrictions = parseOutputRestrictions(session.getOutputRestrictions());

        // Check if action is blocked by restrictions
        boolean blocked = isActionBlocked(actionType, restrictions);

        if (blocked) {
            // Record violation
            String restrictionViolated = getViolatedRestriction(actionType, restrictions);
            OutputRestrictionViolation violation = OutputRestrictionViolation.create(
                    sessionId,
                    actionType,
                    restrictionViolated,
                    true,
                    "Action blocked by output restriction");

            violation = violationRepository.save(violation);

            // Update session counters
            updateSessionCounters(session, actionType);
            cleanRoomSessionRepository.save(session);

            // Create audit receipt
            AuditReceipt.EventType eventType = getAuditEventType(actionType);
            AuditReceipt receipt = auditService.appendReceipt(
                    eventType,
                    session.getRequesterId(),
                    AuditReceipt.ActorType.REQUESTER,
                    sessionId,
                    "CleanRoomSession",
                    actionType.name());

            if (receipt != null && receipt.getId() != null) {
                violation.linkToAuditReceipt(receipt.getId());
                violationRepository.save(violation);
            }

            return new ActionCheckResult(false, "Action blocked: " + restrictionViolated, violation);
        }

        return new ActionCheckResult(true, null, null);
    }

    /**
     * Terminates a clean room session.
     */
    @Transactional
    public CleanRoomSession terminateSession(UUID sessionId, String reason) {
        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID cannot be null");
        }

        CleanRoomSession session = cleanRoomSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.terminate(reason);
        session = cleanRoomSessionRepository.save(session);

        auditService.appendReceipt(
                AuditReceipt.EventType.CLEAN_ROOM_SESSION_TERMINATED,
                session.getRequesterId(),
                AuditReceipt.ActorType.REQUESTER,
                sessionId,
                "CleanRoomSession",
                reason != null ? reason : "manual_termination");

        return session;
    }

    /**
     * Expires old sessions.
     */
    @Transactional
    public int expireOldSessions() {
        List<CleanRoomSession> expiredSessions = cleanRoomSessionRepository.findExpiredSessions(Instant.now());
        for (CleanRoomSession session : expiredSessions) {
            session.markExpired();
            cleanRoomSessionRepository.save(session);
        }
        return expiredSessions.size();
    }

    /**
     * Filters data for aggregate-only mode.
     * Requirement 221.4: Return only aggregated results in aggregate-only mode.
     */
    public Map<String, Object> filterForAggregateOnly(
            Map<String, Object> data, 
            Set<RestrictionType> restrictions) {
        
        if (!restrictions.contains(RestrictionType.AGGREGATE_ONLY)) {
            return data;
        }

        // In aggregate-only mode, only return aggregate values
        Map<String, Object> aggregated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                // Keep numeric values (likely aggregates)
                aggregated.put(entry.getKey(), value);
            } else if (value instanceof Collection) {
                // Replace collections with count
                aggregated.put(entry.getKey() + "_count", ((Collection<?>) value).size());
            } else {
                // Replace other values with placeholder
                aggregated.put(entry.getKey(), "[AGGREGATE_ONLY]");
            }
        }
        return aggregated;
    }

    private boolean isActionBlocked(ViolationType actionType, Set<RestrictionType> restrictions) {
        if (restrictions.contains(RestrictionType.VIEW_ONLY)) {
            // VIEW_ONLY blocks download, copy, screenshot
            return actionType == ViolationType.DOWNLOAD_ATTEMPT ||
                   actionType == ViolationType.COPY_ATTEMPT ||
                   actionType == ViolationType.SCREENSHOT_ATTEMPT;
        }

        if (restrictions.contains(RestrictionType.NO_EXPORT)) {
            // NO_EXPORT blocks all export-related actions
            return actionType == ViolationType.EXPORT_ATTEMPT ||
                   actionType == ViolationType.DOWNLOAD_ATTEMPT;
        }

        // Check specific restrictions
        return switch (actionType) {
            case DOWNLOAD_ATTEMPT -> restrictions.contains(RestrictionType.NO_DOWNLOAD);
            case COPY_ATTEMPT -> restrictions.contains(RestrictionType.NO_COPY);
            case SCREENSHOT_ATTEMPT -> restrictions.contains(RestrictionType.NO_SCREENSHOT);
            case PRINT_ATTEMPT -> restrictions.contains(RestrictionType.NO_PRINT);
            case EXPORT_ATTEMPT -> restrictions.contains(RestrictionType.NO_EXPORT);
            case RAW_ACCESS_ATTEMPT -> restrictions.contains(RestrictionType.AGGREGATE_ONLY);
        };
    }

    private String getViolatedRestriction(ViolationType actionType, Set<RestrictionType> restrictions) {
        if (restrictions.contains(RestrictionType.VIEW_ONLY)) {
            return RestrictionType.VIEW_ONLY.name();
        }
        if (restrictions.contains(RestrictionType.NO_EXPORT) && 
            (actionType == ViolationType.EXPORT_ATTEMPT || actionType == ViolationType.DOWNLOAD_ATTEMPT)) {
            return RestrictionType.NO_EXPORT.name();
        }
        return switch (actionType) {
            case DOWNLOAD_ATTEMPT -> RestrictionType.NO_DOWNLOAD.name();
            case COPY_ATTEMPT -> RestrictionType.NO_COPY.name();
            case SCREENSHOT_ATTEMPT -> RestrictionType.NO_SCREENSHOT.name();
            case PRINT_ATTEMPT -> RestrictionType.NO_PRINT.name();
            case EXPORT_ATTEMPT -> RestrictionType.NO_EXPORT.name();
            case RAW_ACCESS_ATTEMPT -> RestrictionType.AGGREGATE_ONLY.name();
        };
    }

    private void updateSessionCounters(CleanRoomSession session, ViolationType actionType) {
        switch (actionType) {
            case EXPORT_ATTEMPT, DOWNLOAD_ATTEMPT -> session.recordExportAttempt();
            case COPY_ATTEMPT -> session.recordCopyAttempt();
            case SCREENSHOT_ATTEMPT -> session.recordScreenshotAttempt();
            default -> {} // No counter for other types
        }
    }

    private AuditReceipt.EventType getAuditEventType(ViolationType actionType) {
        return switch (actionType) {
            case EXPORT_ATTEMPT, DOWNLOAD_ATTEMPT -> AuditReceipt.EventType.EXPORT_BLOCKED;
            case COPY_ATTEMPT -> AuditReceipt.EventType.COPY_BLOCKED;
            case SCREENSHOT_ATTEMPT -> AuditReceipt.EventType.SCREENSHOT_BLOCKED;
            default -> AuditReceipt.EventType.OUTPUT_RESTRICTION_VIOLATION;
        };
    }

    /**
     * Result of action check.
     */
    public record ActionCheckResult(
            boolean allowed,
            String reason,
            OutputRestrictionViolation violation) {
        
        public boolean isAllowed() { return allowed; }
    }
}
