-- V14: Model-Data Lineage Tables
-- Property 21: Model-Data Lineage Recording
-- Validates: Requirements 230.1, 230.2, 230.3, 230.4

-- Model Data Lineage table
CREATE TABLE model_data_lineage (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    training_job_id UUID NOT NULL UNIQUE,
    dataset_hashes TEXT,
    policy_version VARCHAR(50) NOT NULL,
    model_version VARCHAR(100),
    model_name VARCHAR(255),
    training_started_at TIMESTAMP WITH TIME ZONE,
    training_completed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL DEFAULT 'RECORDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_lineage_status CHECK (status IN ('RECORDING', 'COMPLETED', 'FAILED', 'ARCHIVED'))
);

-- DS Contributions table
CREATE TABLE ds_contributions (
    id UUID PRIMARY KEY,
    lineage_id UUID NOT NULL REFERENCES model_data_lineage(id) ON DELETE CASCADE,
    ds_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    field_categories TEXT,
    contributed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    record_count INTEGER,
    data_hash VARCHAR(128)
);

-- Indexes for efficient queries
CREATE INDEX idx_lineage_model_id ON model_data_lineage(model_id);
CREATE INDEX idx_lineage_training_job_id ON model_data_lineage(training_job_id);
CREATE INDEX idx_lineage_status ON model_data_lineage(status);
CREATE INDEX idx_lineage_policy_version ON model_data_lineage(policy_version);

CREATE INDEX idx_contribution_lineage_id ON ds_contributions(lineage_id);
CREATE INDEX idx_contribution_ds_id ON ds_contributions(ds_id);
CREATE INDEX idx_contribution_batch_id ON ds_contributions(batch_id);

-- Comments
COMMENT ON TABLE model_data_lineage IS 'Tracks which data contributed to which ML models (Property 21)';
COMMENT ON TABLE ds_contributions IS 'Records DS contributions to model training at batch level';
