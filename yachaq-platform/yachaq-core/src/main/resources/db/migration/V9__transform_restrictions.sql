-- YACHAQ Platform - Transform Restrictions
-- PostgreSQL 16
-- Validates: Requirements 220.1, 220.3
-- Property 18: Transform Restriction Enforcement

-- Add allowed_transforms column to consent_contracts
-- Stores JSON array of allowed transform names
ALTER TABLE consent_contracts 
ADD COLUMN allowed_transforms TEXT;

-- Add transform_chain_validation column for transform chaining rules
-- Stores JSON object with chaining validation rules
ALTER TABLE consent_contracts 
ADD COLUMN transform_chain_rules TEXT;

-- Add transform_execution_log table for tracking transform applications
CREATE TABLE transform_execution_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    query_plan_id UUID NOT NULL,
    applied_transforms TEXT NOT NULL, -- JSON array of applied transforms
    transform_chain TEXT NOT NULL, -- JSON array showing transform order
    input_hash VARCHAR(64) NOT NULL,
    output_hash VARCHAR(64) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    executor_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS', 'REJECTED', 'FAILED')),
    rejection_reason TEXT,
    audit_receipt_id UUID REFERENCES audit_receipts(id)
);

CREATE INDEX idx_transform_exec_consent ON transform_execution_logs(consent_contract_id);
CREATE INDEX idx_transform_exec_query_plan ON transform_execution_logs(query_plan_id);
CREATE INDEX idx_transform_exec_timestamp ON transform_execution_logs(executed_at);
CREATE INDEX idx_transform_exec_status ON transform_execution_logs(status);

COMMENT ON COLUMN consent_contracts.allowed_transforms IS 'JSON array of allowed transform names for this consent';
COMMENT ON COLUMN consent_contracts.transform_chain_rules IS 'JSON object with transform chaining validation rules';
COMMENT ON TABLE transform_execution_logs IS 'Audit log of transform executions with input/output hashes';
