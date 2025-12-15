-- YACHAQ Platform - Request + Screening schema
-- Validates: Requirements 5.1, 5.2, 6.1, 6.2, 196.1, 196.2

-- Requests
CREATE TABLE requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    requester_id UUID NOT NULL,
    purpose TEXT NOT NULL,
    scope_json JSONB NOT NULL,
    eligibility_criteria_json JSONB NOT NULL,
    duration_start TIMESTAMPTZ NOT NULL,
    duration_end TIMESTAMPTZ NOT NULL,
    unit_type VARCHAR(30) NOT NULL CHECK (unit_type IN ('SURVEY', 'DATA_ACCESS', 'PARTICIPATION')),
    unit_price DECIMAL(19,4) NOT NULL CHECK (unit_price > 0),
    max_participants INTEGER NOT NULL CHECK (max_participants > 0),
    budget DECIMAL(19,4) NOT NULL CHECK (budget > 0),
    escrow_id UUID REFERENCES escrow_accounts(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'SCREENING', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_request_requester ON requests(requester_id);
CREATE INDEX idx_request_status ON requests(status);
CREATE INDEX idx_request_created ON requests(created_at);

-- Screening Results
CREATE TABLE screening_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id UUID NOT NULL UNIQUE REFERENCES requests(id),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'MANUAL_REVIEW')),
    reason_codes TEXT[] NOT NULL DEFAULT '{}',
    risk_score DECIMAL(5,4) NOT NULL CHECK (risk_score >= 0 AND risk_score <= 1),
    cohort_size_estimate INTEGER NOT NULL CHECK (cohort_size_estimate >= 0),
    policy_version VARCHAR(50) NOT NULL,
    screened_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    screened_by VARCHAR(50) NOT NULL CHECK (screened_by IN ('AUTOMATED', 'MANUAL')),
    reviewer_id UUID,
    appeal_status VARCHAR(20) CHECK (appeal_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED')),
    appeal_submitted_at TIMESTAMPTZ,
    appeal_resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_screening_request ON screening_results(request_id);
CREATE INDEX idx_screening_decision ON screening_results(decision);
CREATE INDEX idx_screening_appeal ON screening_results(appeal_status);

-- Policy Rules (deterministic)
CREATE TABLE policy_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    rule_code VARCHAR(50) NOT NULL UNIQUE,
    rule_name VARCHAR(255) NOT NULL,
    rule_description TEXT NOT NULL,
    rule_type VARCHAR(30) NOT NULL CHECK (rule_type IN ('BLOCKING', 'WARNING', 'INFO')),
    rule_category VARCHAR(50) NOT NULL,
    severity INTEGER NOT NULL CHECK (severity >= 1 AND severity <= 10),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_policy_rule_active ON policy_rules(is_active);
CREATE INDEX idx_policy_rule_category ON policy_rules(rule_category);

-- Seed only the rules we actually enforce in code
INSERT INTO policy_rules (rule_code, rule_name, rule_description, rule_type, rule_category, severity)
VALUES
('COHORT_MIN_SIZE', 'Minimum Cohort Size', 'Cohort must be at least the configured k to prevent targeting', 'BLOCKING', 'PRIVACY', 10),
('DURATION_REASONABLE', 'Request Duration Reasonable', 'Duration must not exceed 365 days', 'WARNING', 'OPERATIONAL', 5),
('BUDGET_ESCROW_FUNDED', 'Escrow Funded', 'Escrow must be funded and cover required budget before activation', 'BLOCKING', 'FINANCIAL', 10),
('STATUS_ALLOWED', 'Requester Status Allowed', 'Requester status must be ACTIVE', 'BLOCKING', 'COMPLIANCE', 9),
('ACCOUNT_TYPE_ALLOWED', 'Account Type Allowed', 'Requester account type must be permitted', 'WARNING', 'COMPLIANCE', 5);

-- RLS enablement (consistent with initial schema approach)
ALTER TABLE requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_results ENABLE ROW LEVEL SECURITY;
