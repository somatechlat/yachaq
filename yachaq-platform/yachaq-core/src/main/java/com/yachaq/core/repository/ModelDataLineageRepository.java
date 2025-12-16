package com.yachaq.core.repository;

import com.yachaq.core.domain.ModelDataLineage;
import com.yachaq.core.domain.ModelDataLineage.LineageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Model-Data Lineage records.
 * 
 * Validates: Requirements 230.3, 230.4
 */
@Repository
public interface ModelDataLineageRepository extends JpaRepository<ModelDataLineage, UUID> {

    /**
     * Finds lineage by model ID.
     * Requirement 230.3: Query complete lineage for any model.
     */
    List<ModelDataLineage> findByModelId(UUID modelId);

    /**
     * Finds lineage by training job ID.
     */
    Optional<ModelDataLineage> findByTrainingJobId(UUID trainingJobId);

    /**
     * Finds all lineage records for models that used data from a specific DS.
     * Requirement 230.4: Show DS which models used their data.
     */
    @Query("SELECT DISTINCT l FROM ModelDataLineage l JOIN l.contributions c WHERE c.dsId = :dsId")
    List<ModelDataLineage> findByDsContribution(@Param("dsId") UUID dsId);

    /**
     * Finds lineage records by status.
     */
    List<ModelDataLineage> findByStatus(LineageStatus status);

    /**
     * Finds lineage records by policy version.
     */
    List<ModelDataLineage> findByPolicyVersion(String policyVersion);

    /**
     * Checks if a model has any lineage records.
     */
    boolean existsByModelId(UUID modelId);

    /**
     * Counts contributions from a specific DS across all models.
     */
    @Query("SELECT COUNT(c) FROM DSContribution c WHERE c.dsId = :dsId")
    long countContributionsByDsId(@Param("dsId") UUID dsId);
}
