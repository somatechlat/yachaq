-- YACHAQ Platform - Field-Level Access Controls
-- PostgreSQL 16
-- Validates: Requirements 219.1, 219.3
-- Property 17: Field-Level Access Enforcement

-- Add permitted_fields column to consent_contracts
-- Stores JSON array of exact permitted field names
ALTER TABLE consent_contracts 
ADD COLUMN permitted_fields TEXT;

-- Add sensitive_field_consents column for per-field consent decisions
-- Stores JSON object mapping sensitive fields to explicit consent status
ALTER TABLE consent_contracts 
ADD COLUMN sensitive_field_consents TEXT;

-- Add field_access_log table for tracking field access with hash
CREATE TABLE field_access_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    query_plan_id UUID NOT NULL,
    accessed_fields TEXT NOT NULL, -- JSON array of accessed field names
    field_hashes TEXT NOT NULL, -- JSON object mapping field names to hashes
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accessor_id UUID NOT NULL,
    audit_receipt_id UUID REFERENCES audit_receipts(id)
);

CREATE INDEX idx_field_access_consent ON field_access_logs(consent_contract_id);
CREATE INDEX idx_field_access_query_plan ON field_access_logs(query_plan_id);
CREATE INDEX idx_field_access_timestamp ON field_access_logs(accessed_at);
CREATE INDEX idx_field_access_accessor ON field_access_logs(accessor_id);

-- Add permitted_fields to query_plans for enforcement
ALTER TABLE query_plans
ADD COLUMN permitted_fields TEXT;

COMMENT ON COLUMN consent_contracts.permitted_fields IS 'JSON array of exact permitted field names for this consent';
COMMENT ON COLUMN consent_contracts.sensitive_field_consents IS 'JSON object mapping sensitive fields to explicit consent status';
COMMENT ON COLUMN query_plans.permitted_fields IS 'JSON array of permitted fields copied from consent contract';
COMMENT ON TABLE field_access_logs IS 'Audit log of field-level access with cryptographic hashes';
