package com.yachaq.api.lineage;

import com.yachaq.api.audit.AuditService;
import com.yachaq.core.domain.AuditReceipt;
import com.yachaq.core.domain.DSContribution;
import com.yachaq.core.domain.ModelDataLineage;
import com.yachaq.core.domain.ModelDataLineage.LineageStatus;
import com.yachaq.core.repository.ModelDataLineageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Model-Data Lineage Service - Tracks which data contributed to which ML models.
 * 
 * Property 21: Model-Data Lineage Recording
 * *For any* model training job using DS data, a lineage record must be created 
 * containing dataset hashes, training job ID, and policy version.
 * 
 * Validates: Requirements 230.1, 230.2, 230.3, 230.4
 */
@Service
public class ModelDataLineageService {

    private final ModelDataLineageRepository lineageRepository;
    private final AuditService auditService;

    public ModelDataLineageService(
            ModelDataLineageRepository lineageRepository,
            AuditService auditService) {
        this.lineageRepository = lineageRepository;
        this.auditService = auditService;
    }

    /**
     * Records a new training job lineage.
     * Requirement 230.1: Record dataset hashes, training job IDs, policy versions.
     * 
     * @param modelId The model being trained
     * @param trainingJobId The training job ID
     * @param policyVersion The policy version in effect
     * @param datasetHashes Hashes of datasets used
     * @return Created lineage record
     */
    @Transactional
    public ModelDataLineage recordTrainingJob(
            UUID modelId,
            UUID trainingJobId,
            String policyVersion,
            List<String> datasetHashes) {
        
        if (modelId == null) {
            throw new IllegalArgumentException("Model ID cannot be null");
        }
        if (trainingJobId == null) {
            throw new IllegalArgumentException("Training job ID cannot be null");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("Policy version cannot be null or blank");
        }
        if (datasetHashes == null || datasetHashes.isEmpty()) {
            throw new IllegalArgumentException("Dataset hashes cannot be null or empty");
        }

        // Check if lineage already exists for this training job
        if (lineageRepository.findByTrainingJobId(trainingJobId).isPresent()) {
            throw new LineageAlreadyExistsException(
                    "Lineage already exists for training job: " + trainingJobId);
        }

        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, policyVersion);
        lineage.recordDatasetHashes(datasetHashes);
        lineage.startTraining();

        lineage = lineageRepository.save(lineage);

        // Create audit receipt
        if (auditService != null) {
            auditService.appendReceipt(
                    AuditReceipt.EventType.MODEL_TRAINING_STARTED,
                    modelId,
                    AuditReceipt.ActorType.SYSTEM,
                    lineage.getId(),
                    "ModelDataLineage",
                    computeLineageHash(lineage));
        }

        return lineage;
    }


    /**
     * Records a DS contribution to a training job.
     * Requirement 230.2: Track DS contributions at batch level.
     * 
     * @param trainingJobId The training job ID
     * @param dsId The Data Sovereign ID
     * @param batchId The batch ID
     * @param fieldCategories Categories of fields contributed
     * @return Updated lineage record
     */
    @Transactional
    public ModelDataLineage recordContribution(
            UUID trainingJobId,
            UUID dsId,
            UUID batchId,
            List<String> fieldCategories) {
        
        if (trainingJobId == null) {
            throw new IllegalArgumentException("Training job ID cannot be null");
        }
        if (dsId == null) {
            throw new IllegalArgumentException("DS ID cannot be null");
        }
        if (batchId == null) {
            throw new IllegalArgumentException("Batch ID cannot be null");
        }

        ModelDataLineage lineage = lineageRepository.findByTrainingJobId(trainingJobId)
                .orElseThrow(() -> new LineageNotFoundException(
                        "No lineage found for training job: " + trainingJobId));

        if (lineage.getStatus() != LineageStatus.RECORDING) {
            throw new InvalidLineageStateException(
                    "Cannot add contribution to lineage in state: " + lineage.getStatus());
        }

        DSContribution contribution = lineage.addContribution(dsId, batchId, fieldCategories);
        lineage = lineageRepository.save(lineage);

        // Create audit receipt
        if (auditService != null) {
            auditService.appendReceipt(
                    AuditReceipt.EventType.DS_CONTRIBUTION_RECORDED,
                    dsId,
                    AuditReceipt.ActorType.DS,
                    contribution.getId(),
                    "DSContribution",
                    "batch:" + batchId);
        }

        return lineage;
    }

    /**
     * Completes a training job.
     * 
     * @param trainingJobId The training job ID
     * @param modelVersion The resulting model version
     * @return Updated lineage record
     */
    @Transactional
    public ModelDataLineage completeTraining(UUID trainingJobId, String modelVersion) {
        ModelDataLineage lineage = lineageRepository.findByTrainingJobId(trainingJobId)
                .orElseThrow(() -> new LineageNotFoundException(
                        "No lineage found for training job: " + trainingJobId));

        lineage.completeTraining(modelVersion);
        lineage = lineageRepository.save(lineage);

        if (auditService != null) {
            auditService.appendReceipt(
                    AuditReceipt.EventType.MODEL_TRAINING_COMPLETED,
                    lineage.getModelId(),
                    AuditReceipt.ActorType.SYSTEM,
                    lineage.getId(),
                    "ModelDataLineage",
                    "version:" + modelVersion);
        }

        return lineage;
    }

    /**
     * Gets complete lineage for a model.
     * Requirement 230.3: Query complete lineage for any model.
     * 
     * @param modelId The model ID
     * @return List of lineage records for the model
     */
    public List<ModelDataLineage> getLineage(UUID modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("Model ID cannot be null");
        }
        return lineageRepository.findByModelId(modelId);
    }

    /**
     * Gets all models that used data from a specific DS.
     * Requirement 230.4: Show DS which models used their data.
     * 
     * @param dsId The Data Sovereign ID
     * @return List of lineage records where DS contributed
     */
    public List<ModelDataLineage> getDSContributions(UUID dsId) {
        if (dsId == null) {
            throw new IllegalArgumentException("DS ID cannot be null");
        }
        return lineageRepository.findByDsContribution(dsId);
    }

    /**
     * Gets lineage by training job ID.
     */
    public ModelDataLineage getByTrainingJobId(UUID trainingJobId) {
        return lineageRepository.findByTrainingJobId(trainingJobId)
                .orElseThrow(() -> new LineageNotFoundException(
                        "No lineage found for training job: " + trainingJobId));
    }

    private String computeLineageHash(ModelDataLineage lineage) {
        String data = String.join("|",
                lineage.getId().toString(),
                lineage.getModelId().toString(),
                lineage.getTrainingJobId().toString(),
                lineage.getPolicyVersion(),
                String.join(",", lineage.getDatasetHashes())
        );
        return com.yachaq.api.audit.MerkleTree.sha256(data);
    }

    // Exception classes
    public static class LineageNotFoundException extends RuntimeException {
        public LineageNotFoundException(String message) { super(message); }
    }

    public static class LineageAlreadyExistsException extends RuntimeException {
        public LineageAlreadyExistsException(String message) { super(message); }
    }

    public static class InvalidLineageStateException extends RuntimeException {
        public InvalidLineageStateException(String message) { super(message); }
    }
}
