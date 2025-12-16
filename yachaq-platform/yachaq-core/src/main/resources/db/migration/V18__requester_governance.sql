-- V18: Requester Governance Service
-- Validates: Requirements 207.1, 207.2, 208.1, 208.2, 209.1, 209.2, 210.1, 210.2, 211.1, 211.2, 211.3

-- Requester Tiers (Requirement 207)
CREATE TABLE IF NOT EXISTS requester_tiers (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL UNIQUE,
    tier VARCHAR(50) NOT NULL,
    verification_level VARCHAR(50) NOT NULL,
    max_budget DECIMAL(19,6) NOT NULL,
    allowed_products TEXT NOT NULL,
    export_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_tier_requester FOREIGN KEY (requester_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_requester_tiers_requester ON requester_tiers(requester_id);
CREATE INDEX IF NOT EXISTS idx_requester_tiers_tier ON requester_tiers(tier);

-- DUA Acceptances (Requirement 208)
CREATE TABLE IF NOT EXISTS dua_acceptances (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    dua_version VARCHAR(20) NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    ip_address VARCHAR(50),
    user_agent TEXT,
    signature_hash VARCHAR(128) NOT NULL,
    CONSTRAINT fk_dua_requester FOREIGN KEY (requester_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_dua_requester ON dua_acceptances(requester_id);
CREATE INDEX IF NOT EXISTS idx_dua_version ON dua_acceptances(dua_version);
CREATE INDEX IF NOT EXISTS idx_dua_accepted_at ON dua_acceptances(accepted_at);


-- Requester Reputation (Requirement 209)
CREATE TABLE IF NOT EXISTS requester_reputation (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL UNIQUE,
    score DECIMAL(5,2) NOT NULL DEFAULT 100.00,
    dispute_count INTEGER NOT NULL DEFAULT 0,
    violation_count INTEGER NOT NULL DEFAULT 0,
    targeting_attempts INTEGER NOT NULL DEFAULT 0,
    last_updated TIMESTAMP NOT NULL,
    CONSTRAINT fk_reputation_requester FOREIGN KEY (requester_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_reputation_requester ON requester_reputation(requester_id);
CREATE INDEX IF NOT EXISTS idx_reputation_score ON requester_reputation(score);

-- Misuse Reports (Requirement 210)
CREATE TABLE IF NOT EXISTS misuse_reports (
    id UUID PRIMARY KEY,
    reporter_id UUID,
    requester_id UUID NOT NULL,
    description TEXT NOT NULL,
    evidence_hashes TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    enforcement_action VARCHAR(50),
    resolution TEXT,
    filed_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP,
    CONSTRAINT fk_misuse_reporter FOREIGN KEY (reporter_id) REFERENCES accounts(id),
    CONSTRAINT fk_misuse_requester FOREIGN KEY (requester_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_misuse_requester ON misuse_reports(requester_id);
CREATE INDEX IF NOT EXISTS idx_misuse_status ON misuse_reports(status);
CREATE INDEX IF NOT EXISTS idx_misuse_filed_at ON misuse_reports(filed_at);

-- Enforcement History (Requirement 210.2)
CREATE TABLE IF NOT EXISTS enforcement_history (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL,
    details TEXT,
    executed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enforcement_report FOREIGN KEY (report_id) REFERENCES misuse_reports(id)
);

CREATE INDEX IF NOT EXISTS idx_enforcement_report ON enforcement_history(report_id);

-- Export Requests (Requirement 211)
CREATE TABLE IF NOT EXISTS export_requests (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    dataset_id UUID NOT NULL,
    format VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    watermark_id VARCHAR(50) NOT NULL,
    verification_token VARCHAR(255),
    requested_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT fk_export_requester FOREIGN KEY (requester_id) REFERENCES accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_export_requester ON export_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_export_status ON export_requests(status);
CREATE INDEX IF NOT EXISTS idx_export_watermark ON export_requests(watermark_id);
