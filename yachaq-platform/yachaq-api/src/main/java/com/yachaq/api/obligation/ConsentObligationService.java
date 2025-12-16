package com.yachaq.api.obligation;

import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.ConsentContract;
import com.yachaq.core.domain.ConsentObligation;
import com.yachaq.core.domain.ConsentObligation.EnforcementLevel;
import com.yachaq.core.domain.ConsentObligation.ObligationStatus;
import com.yachaq.core.domain.ConsentObligation.ObligationType;
import com.yachaq.core.domain.ObligationViolation;
import com.yachaq.core.domain.ObligationViolation.Severity;
import com.yachaq.core.domain.ObligationViolation.ViolationType;
import com.yachaq.core.repository.AuditReceiptRepository;
import com.yachaq.core.repository.ConsentContractRepository;
import com.yachaq.core.repository.ConsentObligationRepository;
import com.yachaq.core.repository.ObligationViolationRepository;
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
 * Service for managing consent obligations and monitoring violations.
 * Property 23: Consent Obligation Specification
 * Validates: Requirements 223.1, 223.2, 223.3, 223.4
 */
@Service
public class ConsentObligationService {

    private final ConsentContractRepository consentRepository;
    private final ConsentObligationRepository obligationRepository;
    private final ObligationViolationRepository violationRepository;
    private final AuditReceiptRepository auditRepository;

    public ConsentObligationService(
            ConsentContractRepository consentRepository,
            ConsentObligationRepository obligationRepository,
            ObligationViolationRepository violationRepository,
            AuditReceiptRepository auditRepository) {
        this.consentRepository = consentRepository;
        this.obligationRepository = obligationRepository;
        this.violationRepository = violationRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * Creates obligations for a consent contract.
     * Property 23: Consent Obligation Specification
     * Validates: Requirements 223.1, 223.2
     */
    @Transactional
    public ObligationResult createObligations(UUID contractId, ObligationSpecification spec) {
        ConsentContract contract = consentRepository.findById(contractId)
                .orElseThrow(() -> new ContractNotFoundException("Contract not found: " + contractId));

        // Validate specification has all required obligations
        validateObligationSpecification(spec);

        // Create retention limit obligation
        ConsentObligation retentionObligation = ConsentObligation.create(
                contractId,
                ObligationType.RETENTION_LIMIT,
                String.format("{\"days\":%d,\"policy\":\"%s\"}", 
                        spec.retentionDays(), spec.retentionPolicy()),
                spec.retentionEnforcementLevel()
        );
        obligationRepository.save(retentionObligation);

        // Create usage restriction obligation
        ConsentObligation usageObligation = ConsentObligation.create(
                contractId,
                ObligationType.USAGE_RESTRICTION,
                spec.usageRestrictions(),
                spec.usageEnforcementLevel()
        );
        obligationRepository.save(usageObligation);

        // Create deletion requirement obligation
        ConsentObligation deletionObligation = ConsentObligation.create(
                contractId,
                ObligationType.DELETION_REQUIREMENT,
                spec.deletionRequirements(),
                spec.deletionEnforcementLevel()
        );
        obligationRepository.save(deletionObligation);

        // Update contract with obligation fields
        contract.setRetentionDays(spec.retentionDays());
        contract.setRetentionPolicy(ConsentContract.RetentionPolicy.valueOf(spec.retentionPolicy()));
        contract.setUsageRestrictions(spec.usageRestrictions());
        contract.setDeletionRequirements(spec.deletionRequirements());

        // Compute obligation hash for integrity
        String obligationHash = computeObligationHash(spec);
        contract.setObligationHash(obligationHash);
        consentRepository.save(contract);

        // Create audit receipt
        AuditReceipt receipt = createAuditReceipt(
                AuditReceipt.EventType.OBLIGATION_CREATED,
                contract.getDsId(),
                AuditReceipt.ActorType.SYSTEM,
                contractId,
                "ConsentObligation",
                obligationHash
        );
        auditRepository.save(receipt);

        return new ObligationResult(
                contractId,
                List.of(retentionObligation.getId(), usageObligation.getId(), deletionObligation.getId()),
                obligationHash,
                receipt.getId(),
                true
        );
    }

    /**
     * Validates that a consent contract has all required obligations.
     * Property 23: Consent Obligation Specification
     */
    public boolean validateContractObligations(UUID contractId) {
        ConsentContract contract = consentRepository.findById(contractId)
                .orElseThrow(() -> new ContractNotFoundException("Contract not found: " + contractId));

        // Check contract-level obligation fields
        if (!contract.hasRequiredObligations()) {
            return false;
        }

        // Check obligation entities exist
        long requiredCount = obligationRepository.countRequiredObligationTypes(contractId);
        return requiredCount >= 3; // RETENTION_LIMIT, USAGE_RESTRICTION, DELETION_REQUIREMENT
    }

    /**
     * Detects violations by checking obligations against current state.
     * Validates: Requirements 223.3
     */
    @Transactional
    public List<ObligationViolation> detectViolations(UUID contractId, ViolationCheckContext context) {
        List<ConsentObligation> activeObligations = obligationRepository
                .findByConsentContractIdAndStatus(contractId, ObligationStatus.ACTIVE);

        List<ObligationViolation> detectedViolations = new java.util.ArrayList<>();

        for (ConsentObligation obligation : activeObligations) {
            ObligationViolation violation = checkObligation(obligation, context);
            if (violation != null) {
                violation = violationRepository.save(violation);
                obligation.markViolated();
                obligationRepository.save(obligation);
                detectedViolations.add(violation);

                // Create audit receipt for violation
                AuditReceipt receipt = createAuditReceipt(
                        AuditReceipt.EventType.OBLIGATION_VIOLATED,
                        context.actorId(),
                        AuditReceipt.ActorType.SYSTEM,
                        violation.getId(),
                        "ObligationViolation",
                        violation.getEvidenceHash()
                );
                auditRepository.save(receipt);
                violation.setAuditReceiptId(receipt.getId());
                violationRepository.save(violation);
            }
        }

        return detectedViolations;
    }

    /**
     * Enforces penalties for violations.
     * Validates: Requirements 223.4
     */
    @Transactional
    public PenaltyResult enforcePenalty(UUID violationId, BigDecimal penaltyAmount) {
        ObligationViolation violation = violationRepository.findById(violationId)
                .orElseThrow(() -> new ViolationNotFoundException("Violation not found: " + violationId));

        if (violation.isPenaltyApplied()) {
            throw new PenaltyAlreadyAppliedException("Penalty already applied for violation: " + violationId);
        }

        violation.applyPenalty(penaltyAmount);
        violationRepository.save(violation);

        // Create audit receipt for penalty
        AuditReceipt receipt = createAuditReceipt(
                AuditReceipt.EventType.PENALTY_APPLIED,
                UUID.randomUUID(), // System actor
                AuditReceipt.ActorType.SYSTEM,
                violationId,
                "ObligationViolation",
                sha256(violationId.toString() + penaltyAmount.toPlainString())
        );
        auditRepository.save(receipt);

        return new PenaltyResult(violationId, penaltyAmount, receipt.getId(), true);
    }

    /**
     * Gets all obligations for a contract.
     */
    public List<ConsentObligation> getObligations(UUID contractId) {
        return obligationRepository.findByConsentContractId(contractId);
    }

    /**
     * Gets all violations for a contract.
     */
    public List<ObligationViolation> getViolations(UUID contractId) {
        return violationRepository.findByConsentContractId(contractId);
    }

    /**
     * Gets unresolved violations for a contract.
     */
    public List<ObligationViolation> getUnresolvedViolations(UUID contractId) {
        return violationRepository.findUnresolvedByContractId(contractId);
    }

    /**
     * Checks if a contract has any unresolved violations.
     */
    public boolean hasUnresolvedViolations(UUID contractId) {
        return violationRepository.hasUnresolvedViolations(contractId);
    }

    private void validateObligationSpecification(ObligationSpecification spec) {
        if (spec.retentionDays() == null || spec.retentionDays() <= 0) {
            throw new InvalidObligationException("Retention days must be positive");
        }
        if (spec.retentionPolicy() == null || spec.retentionPolicy().isBlank()) {
            throw new InvalidObligationException("Retention policy is required");
        }
        if (spec.usageRestrictions() == null || spec.usageRestrictions().isBlank()) {
            throw new InvalidObligationException("Usage restrictions are required");
        }
        if (spec.deletionRequirements() == null || spec.deletionRequirements().isBlank()) {
            throw new InvalidObligationException("Deletion requirements are required");
        }
    }

    private ObligationViolation checkObligation(ConsentObligation obligation, ViolationCheckContext context) {
        return switch (obligation.getObligationType()) {
            case RETENTION_LIMIT -> checkRetentionLimit(obligation, context);
            case USAGE_RESTRICTION -> checkUsageRestriction(obligation, context);
            case DELETION_REQUIREMENT -> checkDeletionRequirement(obligation, context);
            default -> null;
        };
    }

    private ObligationViolation checkRetentionLimit(ConsentObligation obligation, ViolationCheckContext context) {
        if (context.dataRetainedDays() != null && context.maxRetentionDays() != null) {
            if (context.dataRetainedDays() > context.maxRetentionDays()) {
                return ObligationViolation.create(
                        obligation.getConsentContractId(),
                        obligation.getId(),
                        ViolationType.RETENTION_EXCEEDED,
                        determineSeverity(obligation.getEnforcementLevel()),
                        String.format("Data retained for %d days, exceeds limit of %d days",
                                context.dataRetainedDays(), context.maxRetentionDays()),
                        sha256(obligation.getId().toString() + context.dataRetainedDays())
                );
            }
        }
        return null;
    }

    private ObligationViolation checkUsageRestriction(ConsentObligation obligation, ViolationCheckContext context) {
        if (context.unauthorizedUsageDetected() != null && context.unauthorizedUsageDetected()) {
            return ObligationViolation.create(
                    obligation.getConsentContractId(),
                    obligation.getId(),
                    ViolationType.UNAUTHORIZED_USAGE,
                    determineSeverity(obligation.getEnforcementLevel()),
                    "Unauthorized usage detected: " + context.usageDescription(),
                    sha256(obligation.getId().toString() + context.usageDescription())
            );
        }
        return null;
    }

    private ObligationViolation checkDeletionRequirement(ConsentObligation obligation, ViolationCheckContext context) {
        if (context.deletionRequired() != null && context.deletionRequired() && 
            context.deletionCompleted() != null && !context.deletionCompleted()) {
            return ObligationViolation.create(
                    obligation.getConsentContractId(),
                    obligation.getId(),
                    ViolationType.DELETION_FAILURE,
                    determineSeverity(obligation.getEnforcementLevel()),
                    "Required deletion not completed",
                    sha256(obligation.getId().toString() + "deletion_failure")
            );
        }
        return null;
    }

    private Severity determineSeverity(EnforcementLevel level) {
        return switch (level) {
            case STRICT -> Severity.CRITICAL;
            case MONITORED -> Severity.HIGH;
            case ADVISORY -> Severity.MEDIUM;
        };
    }

    private String computeObligationHash(ObligationSpecification spec) {
        String data = String.join("|",
                spec.retentionDays().toString(),
                spec.retentionPolicy(),
                spec.usageRestrictions(),
                spec.deletionRequirements()
        );
        return sha256(data);
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
    public record ObligationSpecification(
            Integer retentionDays,
            String retentionPolicy,
            EnforcementLevel retentionEnforcementLevel,
            String usageRestrictions,
            EnforcementLevel usageEnforcementLevel,
            String deletionRequirements,
            EnforcementLevel deletionEnforcementLevel
    ) {}

    public record ObligationResult(
            UUID contractId,
            List<UUID> obligationIds,
            String obligationHash,
            UUID auditReceiptId,
            boolean success
    ) {}

    public record ViolationCheckContext(
            UUID actorId,
            Integer dataRetainedDays,
            Integer maxRetentionDays,
            Boolean unauthorizedUsageDetected,
            String usageDescription,
            Boolean deletionRequired,
            Boolean deletionCompleted
    ) {}

    public record PenaltyResult(
            UUID violationId,
            BigDecimal penaltyAmount,
            UUID auditReceiptId,
            boolean success
    ) {}

    // Exceptions
    public static class ContractNotFoundException extends RuntimeException {
        public ContractNotFoundException(String message) { super(message); }
    }

    public static class ViolationNotFoundException extends RuntimeException {
        public ViolationNotFoundException(String message) { super(message); }
    }

    public static class InvalidObligationException extends RuntimeException {
        public InvalidObligationException(String message) { super(message); }
    }

    public static class PenaltyAlreadyAppliedException extends RuntimeException {
        public PenaltyAlreadyAppliedException(String message) { super(message); }
    }
}
