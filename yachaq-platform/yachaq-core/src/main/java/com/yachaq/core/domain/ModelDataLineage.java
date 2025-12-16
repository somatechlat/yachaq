package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model-Data Lineage - Tracks which data contributed to which ML models.
 * 
 * Property 21: Model-Data Lineage Recording
 * *For any* model training job using DS data, a lineage record must be created 
 * containing dataset hashes, training job ID, and policy version.
 * 
 * Validates: Requirements 230.1, 230.2, 230.3, 230.4
 */
@Entity
@Table(name = "model_data_lineage")
public class ModelDataLineage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(name = "training_job_id", nullable = false)
    private UUID trainingJobId;

    @Column(name = "dataset_hashes", columnDefinition = "TEXT")
    private String datasetHashesJson;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "training_started_at")
    private Instant trainingStartedAt;

    @Column(name = "training_completed_at")
    private Instant trainingCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LineageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "lineage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DSContribution> contributions = new ArrayList<>();

    public enum LineageStatus {
        RECORDING,   // Training in progress
        COMPLETED,   // Training completed successfully
        FAILED,      // Training failed
        ARCHIVED     // Lineage archived
    }

    protected ModelDataLineage() {}

    private ModelDataLineage(UUID modelId, UUID trainingJobId, String policyVersion) {
        this.id = UUID.randomUUID();
        this.modelId = modelId;
        this.trainingJobId = trainingJobId;
        this.policyVersion = policyVersion;
        this.status = LineageStatus.RECORDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }


    /**
     * Creates a new lineage record for a training job.
     * Requirement 230.1: Record dataset hashes, training job IDs, policy versions.
     */
    public static ModelDataLineage create(UUID modelId, UUID trainingJobId, String policyVersion) {
        if (modelId == null) {
            throw new IllegalArgumentException("Model ID cannot be null");
        }
        if (trainingJobId == null) {
            throw new IllegalArgumentException("Training job ID cannot be null");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("Policy version cannot be null or blank");
        }
        return new ModelDataLineage(modelId, trainingJobId, policyVersion);
    }

    /**
     * Records dataset hashes used in training.
     */
    public void recordDatasetHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            throw new IllegalArgumentException("Dataset hashes cannot be null or empty");
        }
        this.datasetHashesJson = String.join(",", hashes);
        this.updatedAt = Instant.now();
    }

    /**
     * Gets dataset hashes as a list.
     */
    public List<String> getDatasetHashes() {
        if (datasetHashesJson == null || datasetHashesJson.isBlank()) {
            return List.of();
        }
        return List.of(datasetHashesJson.split(","));
    }

    /**
     * Adds a DS contribution to this lineage.
     * Requirement 230.2: Track DS contributions at batch level.
     */
    public DSContribution addContribution(UUID dsId, UUID batchId, List<String> fieldCategories) {
        DSContribution contribution = DSContribution.create(this, dsId, batchId, fieldCategories);
        this.contributions.add(contribution);
        this.updatedAt = Instant.now();
        return contribution;
    }

    /**
     * Marks training as started.
     */
    public void startTraining() {
        this.trainingStartedAt = Instant.now();
        this.status = LineageStatus.RECORDING;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks training as completed.
     */
    public void completeTraining(String modelVersion) {
        this.trainingCompletedAt = Instant.now();
        this.modelVersion = modelVersion;
        this.status = LineageStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    /**
     * Marks training as failed.
     */
    public void failTraining() {
        this.trainingCompletedAt = Instant.now();
        this.status = LineageStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    /**
     * Archives this lineage record.
     */
    public void archive() {
        this.status = LineageStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getModelId() { return modelId; }
    public UUID getTrainingJobId() { return trainingJobId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getModelVersion() { return modelVersion; }
    public String getModelName() { return modelName; }
    public Instant getTrainingStartedAt() { return trainingStartedAt; }
    public Instant getTrainingCompletedAt() { return trainingCompletedAt; }
    public LineageStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DSContribution> getContributions() { return contributions; }

    public void setModelName(String modelName) {
        this.modelName = modelName;
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if this lineage contains a contribution from a specific DS.
     */
    public boolean hasContributionFrom(UUID dsId) {
        return contributions.stream()
                .anyMatch(c -> c.getDsId().equals(dsId));
    }

    /**
     * Gets all contributions from a specific DS.
     */
    public List<DSContribution> getContributionsFrom(UUID dsId) {
        return contributions.stream()
                .filter(c -> c.getDsId().equals(dsId))
                .toList();
    }
}
