-- YACHAQ Platform - Consent Obligations Schema
-- PostgreSQL 16
-- Property 23: Consent Obligation Specification
-- Validates: Requirements 223.1, 223.2, 223.3, 223.4

-- Add obligation fields to consent_contracts table
ALTER TABLE consent_contracts
ADD COLUMN IF NOT EXISTS retention_days INTEGER,
ADD COLUMN IF NOT EXISTS retention_policy VARCHAR(50),
ADD COLUMN IF NOT EXISTS usage_restrictions TEXT,
ADD COLUMN IF NOT EXISTS deletion_requirements TEXT,
ADD COLUMN IF NOT EXISTS obligation_hash VARCHAR(64);

-- Obligation status tracking
CREATE TABLE IF NOT EXISTS consent_obligations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    obligation_type VARCHAR(50) NOT NULL CHECK (obligation_type IN (
        'RETENTION_LIMIT',
        'USAGE_RESTRICTION', 
        'DELETION_REQUIREMENT',
        'ACCESS_LIMIT',
        'SHARING_PROHIBITION',
        'PURPOSE_LIMITATION'
    )),
    specification TEXT NOT NULL,
    enforcement_level VARCHAR(20) NOT NULL CHECK (enforcement_level IN (
        'STRICT',      -- Automatic enforcement, immediate penalty
        'MONITORED',   -- Logged and reviewed, delayed penalty
        'ADVISORY'     -- Logged only, no automatic penalty
    )),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN (
        'ACTIVE',
        'SATISFIED',
        'VIOLATED',
        'EXPIRED'
    )),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    satisfied_at TIMESTAMPTZ,
    violated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_obligation_contract ON consent_obligations(consent_contract_id);
CREATE INDEX idx_obligation_type ON consent_obligations(obligation_type);
CREATE INDEX idx_obligation_status ON consent_obligations(status);

-- Obligation violations tracking
CREATE TABLE IF NOT EXISTS obligation_violations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    obligation_id UUID NOT NULL REFERENCES consent_obligations(id),
    violation_type VARCHAR(50) NOT NULL CHECK (violation_type IN (
        'RETENTION_EXCEEDED',
        'UNAUTHORIZED_USAGE',
        'DELETION_FAILURE',
        'ACCESS_EXCEEDED',
        'UNAUTHORIZED_SHARING',
        'PURPOSE_VIOLATION'
    )),
    severity VARCHAR(20) NOT NULL CHECK (severity IN (
        'CRITICAL',    -- Immediate action required
        'HIGH',        -- Action within 24 hours
        'MEDIUM',      -- Action within 7 days
        'LOW'          -- Advisory only
    )),
    description TEXT NOT NULL,
    evidence_hash VARCHAR(64),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    penalty_applied BOOLEAN NOT NULL DEFAULT FALSE,
    penalty_amount DECIMAL(19,4),
    audit_receipt_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'DETECTED' CHECK (status IN (
        'DETECTED',
        'ACKNOWLEDGED',
        'INVESTIGATING',
        'RESOLVED',
        'ESCALATED',
        'DISMISSED'
    )),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_violation_contract ON obligation_violations(consent_contract_id);
CREATE INDEX idx_violation_obligation ON obligation_violations(obligation_id);
CREATE INDEX idx_violation_type ON obligation_violations(violation_type);
CREATE INDEX idx_violation_status ON obligation_violations(status);
CREATE INDEX idx_violation_severity ON obligation_violations(severity);
CREATE INDEX idx_violation_detected ON obligation_violations(detected_at);

-- Obligation audit log for monitoring
CREATE TABLE IF NOT EXISTS obligation_audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    consent_contract_id UUID NOT NULL REFERENCES consent_contracts(id),
    obligation_id UUID REFERENCES consent_obligations(id),
    event_type VARCHAR(50) NOT NULL CHECK (event_type IN (
        'OBLIGATION_CREATED',
        'OBLIGATION_CHECKED',
        'OBLIGATION_SATISFIED',
        'VIOLATION_DETECTED',
        'VIOLATION_ACKNOWLEDGED',
        'VIOLATION_RESOLVED',
        'PENALTY_APPLIED',
        'RETENTION_CHECK',
        'DELETION_TRIGGERED'
    )),
    details TEXT,
    actor_id UUID NOT NULL,
    actor_type VARCHAR(20) NOT NULL CHECK (actor_type IN ('DS', 'REQUESTER', 'SYSTEM')),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_obligation_audit_contract ON obligation_audit_log(consent_contract_id);
CREATE INDEX idx_obligation_audit_event ON obligation_audit_log(event_type);
CREATE INDEX idx_obligation_audit_timestamp ON obligation_audit_log(timestamp);

-- Comments for documentation
COMMENT ON TABLE consent_obligations IS 'Data handling obligations attached to consent contracts - Property 23';
COMMENT ON TABLE obligation_violations IS 'Tracking of obligation violations for enforcement - Requirements 223.3, 223.4';
COMMENT ON TABLE obligation_audit_log IS 'Audit trail for obligation monitoring activities';
