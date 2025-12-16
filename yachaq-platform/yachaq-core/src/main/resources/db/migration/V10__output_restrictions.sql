-- YACHAQ Platform - Output Restriction Enforcement
-- PostgreSQL 16
-- Validates: Requirements 221.1, 221.2, 221.3, 221.4

-- Add output_restrictions column to consent_contracts
-- Stores JSON array of output restriction types
ALTER TABLE consent_contracts 
ADD COLUMN output_restrictions TEXT;

-- Add delivery_mode column to consent_contracts
ALTER TABLE consent_contracts 
ADD COLUMN delivery_mode VARCHAR(30) DEFAULT 'CLEAN_ROOM';

-- Create clean_room_sessions table for tracking clean room access
CREATE TABLE clean_room_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    capsule_id UUID NOT NULL,
    requester_id UUID NOT NULL,
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    output_restrictions TEXT NOT NULL, -- JSON array of active restrictions
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    terminated_at TIMESTAMPTZ,
    export_attempts INTEGER NOT NULL DEFAULT 0,
    copy_attempts INTEGER NOT NULL DEFAULT 0,
    screenshot_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'TERMINATED', 'EXPIRED')),
    termination_reason TEXT
);

CREATE INDEX idx_clean_room_capsule ON clean_room_sessions(capsule_id);
CREATE INDEX idx_clean_room_requester ON clean_room_sessions(requester_id);
CREATE INDEX idx_clean_room_status ON clean_room_sessions(status);
CREATE INDEX idx_clean_room_expires ON clean_room_sessions(expires_at);

-- Create output_restriction_violations table for tracking violations
CREATE TABLE output_restriction_violations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES clean_room_sessions(id),
    violation_type VARCHAR(30) NOT NULL CHECK (violation_type IN (
        'EXPORT_ATTEMPT', 'COPY_ATTEMPT', 'SCREENSHOT_ATTEMPT', 
        'DOWNLOAD_ATTEMPT', 'PRINT_ATTEMPT', 'RAW_ACCESS_ATTEMPT'
    )),
    restriction_violated VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    blocked BOOLEAN NOT NULL DEFAULT TRUE,
    details TEXT,
    audit_receipt_id UUID REFERENCES audit_receipts(id)
);

CREATE INDEX idx_violation_session ON output_restriction_violations(session_id);
CREATE INDEX idx_violation_type ON output_restriction_violations(violation_type);
CREATE INDEX idx_violation_timestamp ON output_restriction_violations(occurred_at);

COMMENT ON COLUMN consent_contracts.output_restrictions IS 'JSON array of output restriction types (VIEW_ONLY, AGGREGATE_ONLY, NO_EXPORT)';
COMMENT ON COLUMN consent_contracts.delivery_mode IS 'Delivery mode: CLEAN_ROOM (default), DIRECT, ENCRYPTED';
COMMENT ON TABLE clean_room_sessions IS 'Tracks clean room sessions with output restriction enforcement';
COMMENT ON TABLE output_restriction_violations IS 'Audit log of output restriction violation attempts';
