package com.yachaq.api.lineage;

import com.yachaq.core.domain.DSContribution;
import com.yachaq.core.domain.ModelDataLineage;
import com.yachaq.core.domain.ModelDataLineage.LineageStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

/**
 * Property-based tests for Model-Data Lineage Service.
 * 
 * **Feature: yachaq-platform, Property 21: Model-Data Lineage Recording**
 * *For any* model training job using DS data, a lineage record must be created 
 * containing dataset hashes, training job ID, and policy version.
 * 
 * **Validates: Requirements 230.1**
 */
class ModelDataLineagePropertyTest {

    // ==================== Property 21: Lineage Recording ====================

    /**
     * Property 21: Lineage record must contain all required fields.
     * **Feature: yachaq-platform, Property 21: Model-Data Lineage Recording**
     * **Validates: Requirements 230.1**
     */
    @Property(tries = 100)
    void property21_lineageContainsAllRequiredFields(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll("policyVersion") String policyVersion) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, policyVersion);

        // Verify all required fields are present
        assert lineage.getId() != null : "Lineage ID must not be null";
        assert lineage.getModelId() != null : "Model ID must not be null";
        assert lineage.getModelId().equals(modelId) : "Model ID must match input";
        assert lineage.getTrainingJobId() != null : "Training job ID must not be null";
        assert lineage.getTrainingJobId().equals(trainingJobId) : "Training job ID must match input";
        assert lineage.getPolicyVersion() != null : "Policy version must not be null";
        assert lineage.getPolicyVersion().equals(policyVersion) : "Policy version must match input";
        assert lineage.getCreatedAt() != null : "Created timestamp must not be null";
        assert lineage.getStatus() != null : "Status must not be null";
    }

    /**
     * Property 21: Dataset hashes must be recorded correctly.
     * **Feature: yachaq-platform, Property 21: Model-Data Lineage Recording**
     * **Validates: Requirements 230.1**
     */
    @Property(tries = 100)
    void property21_datasetHashesRecordedCorrectly(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll("hashList") List<String> hashes) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        lineage.recordDatasetHashes(hashes);

        List<String> recordedHashes = lineage.getDatasetHashes();
        
        assert recordedHashes.size() == hashes.size() : 
                "Number of recorded hashes must match input. Expected: " + hashes.size() + ", Got: " + recordedHashes.size();
        
        for (int i = 0; i < hashes.size(); i++) {
            assert recordedHashes.get(i).equals(hashes.get(i)) : 
                    "Hash at index " + i + " must match input";
        }
    }

    /**
     * Property 21: DS contributions must be tracked at batch level.
     * **Feature: yachaq-platform, Property 21: Model-Data Lineage Recording**
     * **Validates: Requirements 230.2**
     */
    @Property(tries = 100)
    void property21_dsContributionsTrackedAtBatchLevel(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll("validUUID") UUID dsId,
            @ForAll("validUUID") UUID batchId,
            @ForAll("categoryList") List<String> fieldCategories) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        DSContribution contribution = lineage.addContribution(dsId, batchId, fieldCategories);

        // Verify contribution is recorded
        assert lineage.getContributions().size() == 1 : "Should have exactly one contribution";
        assert contribution.getDsId().equals(dsId) : "DS ID must match";
        assert contribution.getBatchId().equals(batchId) : "Batch ID must match";
        assert contribution.getContributedAt() != null : "Contribution timestamp must not be null";
        
        List<String> recordedCategories = contribution.getFieldCategories();
        assert recordedCategories.size() == fieldCategories.size() : 
                "Field categories count must match";
    }


    /**
     * Property: Multiple DS contributions can be added to same lineage.
     * **Feature: yachaq-platform, Property 21: Model-Data Lineage Recording**
     * **Validates: Requirements 230.2**
     */
    @Property(tries = 50)
    void multipleContributionsCanBeAdded(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll @IntRange(min = 2, max = 10) int contributionCount) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        
        Set<UUID> dsIds = new HashSet<>();
        for (int i = 0; i < contributionCount; i++) {
            UUID dsId = UUID.randomUUID();
            UUID batchId = UUID.randomUUID();
            dsIds.add(dsId);
            lineage.addContribution(dsId, batchId, List.of("category" + i));
        }

        assert lineage.getContributions().size() == contributionCount : 
                "Should have " + contributionCount + " contributions";
        
        // Verify each DS can be found
        for (UUID dsId : dsIds) {
            assert lineage.hasContributionFrom(dsId) : 
                    "Lineage should have contribution from DS: " + dsId;
        }
    }

    /**
     * Property: Lineage status transitions correctly.
     */
    @Property(tries = 50)
    void lineageStatusTransitionsCorrectly(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll @StringLength(min = 1, max = 10) String modelVersion) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        
        // Initial status should be RECORDING
        assert lineage.getStatus() == LineageStatus.RECORDING : 
                "Initial status should be RECORDING";

        // Start training
        lineage.startTraining();
        assert lineage.getStatus() == LineageStatus.RECORDING : 
                "Status after startTraining should be RECORDING";
        assert lineage.getTrainingStartedAt() != null : 
                "Training start time should be set";

        // Complete training
        lineage.completeTraining(modelVersion);
        assert lineage.getStatus() == LineageStatus.COMPLETED : 
                "Status after completeTraining should be COMPLETED";
        assert lineage.getTrainingCompletedAt() != null : 
                "Training completion time should be set";
        assert lineage.getModelVersion().equals(modelVersion) : 
                "Model version should be set";
    }

    /**
     * Property: Failed training sets correct status.
     */
    @Property(tries = 50)
    void failedTrainingSetsCorrectStatus(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        lineage.startTraining();
        lineage.failTraining();

        assert lineage.getStatus() == LineageStatus.FAILED : 
                "Status after failTraining should be FAILED";
        assert lineage.getTrainingCompletedAt() != null : 
                "Completion time should be set even for failed training";
    }

    /**
     * Property: getContributionsFrom returns only contributions from specified DS.
     */
    @Property(tries = 50)
    void getContributionsFromReturnsCorrectContributions(
            @ForAll("validUUID") UUID modelId,
            @ForAll("validUUID") UUID trainingJobId,
            @ForAll("validUUID") UUID targetDsId,
            @ForAll @IntRange(min = 1, max = 5) int targetContributions,
            @ForAll @IntRange(min = 1, max = 5) int otherContributions) {
        
        ModelDataLineage lineage = ModelDataLineage.create(modelId, trainingJobId, "1.0.0");
        
        // Add contributions from target DS
        for (int i = 0; i < targetContributions; i++) {
            lineage.addContribution(targetDsId, UUID.randomUUID(), List.of("cat" + i));
        }
        
        // Add contributions from other DSs
        for (int i = 0; i < otherContributions; i++) {
            lineage.addContribution(UUID.randomUUID(), UUID.randomUUID(), List.of("other" + i));
        }

        List<DSContribution> targetResults = lineage.getContributionsFrom(targetDsId);
        
        assert targetResults.size() == targetContributions : 
                "Should return exactly " + targetContributions + " contributions from target DS";
        
        for (DSContribution c : targetResults) {
            assert c.getDsId().equals(targetDsId) : 
                    "All returned contributions should be from target DS";
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<UUID> validUUID() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> policyVersion() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .withChars('.')
                .ofMinLength(3)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<List<String>> hashList() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofLength(64)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<String>> categoryList() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5);
    }
}
