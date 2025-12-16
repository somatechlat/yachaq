package com.yachaq.core.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DS Contribution - Records a Data Sovereign's contribution to a model training job.
 * 
 * Validates: Requirements 230.2 (Track DS contributions at batch level)
 */
@Entity
@Table(name = "ds_contributions")
public class DSContribution {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lineage_id", nullable = false)
    private ModelDataLineage lineage;

    @Column(name = "ds_id", nullable = false)
    private UUID dsId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "field_categories", columnDefinition = "TEXT")
    private String fieldCategoriesJson;

    @Column(name = "contributed_at", nullable = false)
    private Instant contributedAt;

    @Column(name = "record_count")
    private Integer recordCount;

    @Column(name = "data_hash")
    private String dataHash;

    protected DSContribution() {}

    private DSContribution(ModelDataLineage lineage, UUID dsId, UUID batchId, List<String> fieldCategories) {
        this.id = UUID.randomUUID();
        this.lineage = lineage;
        this.dsId = dsId;
        this.batchId = batchId;
        this.fieldCategoriesJson = fieldCategories != null ? String.join(",", fieldCategories) : "";
        this.contributedAt = Instant.now();
    }

    /**
     * Creates a new DS contribution record.
     */
    public static DSContribution create(
            ModelDataLineage lineage, 
            UUID dsId, 
            UUID batchId, 
            List<String> fieldCategories) {
        if (lineage == null) {
            throw new IllegalArgumentException("Lineage cannot be null");
        }
        if (dsId == null) {
            throw new IllegalArgumentException("DS ID cannot be null");
        }
        if (batchId == null) {
            throw new IllegalArgumentException("Batch ID cannot be null");
        }
        return new DSContribution(lineage, dsId, batchId, fieldCategories);
    }

    /**
     * Gets field categories as a list.
     */
    public List<String> getFieldCategories() {
        if (fieldCategoriesJson == null || fieldCategoriesJson.isBlank()) {
            return List.of();
        }
        return List.of(fieldCategoriesJson.split(","));
    }

    /**
     * Sets the record count for this contribution.
     */
    public void setRecordCount(int count) {
        this.recordCount = count;
    }

    /**
     * Sets the data hash for verification.
     */
    public void setDataHash(String hash) {
        this.dataHash = hash;
    }

    // Getters
    public UUID getId() { return id; }
    public ModelDataLineage getLineage() { return lineage; }
    public UUID getDsId() { return dsId; }
    public UUID getBatchId() { return batchId; }
    public Instant getContributedAt() { return contributedAt; }
    public Integer getRecordCount() { return recordCount; }
    public String getDataHash() { return dataHash; }
}
