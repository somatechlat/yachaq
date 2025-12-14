-- YACHAQ Platform - Request and Screening Schema
-- PostgreSQL 16
-- Validates: Requirements 5.1, 5.2, 6.1, 6.2, 196.1

-- Requesters (Data Requesters)
CREATE TABLE requesters (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    organization VARCHAR(255),
    tier VARCHAR(20) NOT NULL CHECK (tier IN ('COMMUNITY', 'VERIFIED', 'ENTERPRISE')),
    kyb_status VARCHAR(20) NOT NULL CHECK (kyb_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    dua_version VARCHAR(50),
    dua_accepted_at TIMESTAMPTZ,
    reputation_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000 CHECK (reputation_score >= 0 AND reputation_score <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED')),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_requester_tier ON requesters(tier);
CREATE INDEX idx_requester_status ON requesters(status);
CREATE INDEX idx_requester_kyb ON requesters(kyb_status);

-- Requests (Data Access Requests)
CREATE TABLE requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    requester_id UUID NOT NULL REFERENCES requesters(id),
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
CREATE INDEX idx_request_unit_type ON requests(unit_type);
CREATE INDEX idx_request_created ON requests(created_at);

-- Screening Results
CREATE TABLE screening_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id UUID NOT NULL REFERENCES requests(id) UNIQUE,
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

-- Policy Rules (for deterministic screening)
CREATE TABLE policy_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    rule_code VARCHAR(50) NOT NULL UNIQUE,
    rule_name VARCHAR(255) NOT NULL,
    rule_description TEXT NOT NULL,
    rule_type VARCHAR(30) NOT NULL CHECK (rule_type IN ('BLOCKING', 'WARNING', 'INFO')),
    rule_category VARCHAR(50) NOT NULL,
    rule_expression TEXT NOT NULL,
    severity INTEGER NOT NULL CHECK (severity >= 1 AND severity <= 10),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_policy_rule_type ON policy_rules(rule_type);
CREATE INDEX idx_policy_rule_category ON policy_rules(rule_category);
CREATE INDEX idx_policy_rule_active ON policy_rules(is_active);

-- Insert default policy rules
INSERT INTO policy_rules (rule_code, rule_name, rule_description, rule_type, rule_category, rule_expression, severity) VALUES
('COHORT_MIN_SIZE', 'Minimum Cohort Size', 'Cohort must have at least 50 eligible participants (k-anonymity)', 'BLOCKING', 'PRIVACY', 'cohort_size >= 50', 10),
('BUDGET_ESCROW_MATCH', 'Budget Escrow Match', 'Budget must match escrow funding', 'BLOCKING', 'FINANCIAL', 'budget <= escrow_funded', 10),
('PURPOSE_PROHIBITED', 'Prohibited Purpose', 'Purpose contains prohibited keywords', 'BLOCKING', 'LEGAL', 'purpose NOT CONTAINS prohibited_keywords', 10),
('REQUESTER_VERIFIED', 'Requester Verification', 'Requester must be verified for this request type', 'BLOCKING', 'COMPLIANCE', 'requester_tier >= required_tier', 9),
('DURATION_REASONABLE', 'Reasonable Duration', 'Request duration must be reasonable', 'WARNING', 'OPERATIONAL', 'duration_days <= 365', 5),
('PRICE_FAIR', 'Fair Pricing', 'Unit price must be within fair range', 'WARNING', 'FINANCIAL', 'unit_price >= min_price AND unit_price <= max_price', 5),
('SCOPE_SENSITIVE', 'Sensitive Data Scope', 'Request includes sensitive data categories', 'INFO', 'PRIVACY', 'scope CONTAINS sensitive_categories', 3),
('REIDENTIFICATION_RISK', 'Re-identification Risk', 'Request may enable re-identification', 'BLOCKING', 'PRIVACY', 'reidentification_score < 0.3', 10);

-- Row Level Security
ALTER TABLE requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE screening_results ENABLE ROW LEVEL SECURITY;
