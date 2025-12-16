-- Privacy Governor Service Schema
-- Validates: Requirements 204.1, 204.2, 204.3, 204.4, 202.1, 202.2, 203.1, 203.2

-- Privacy Risk Budget (PRB) table
-- Property 28: PRB Allocation and Lock
CREATE TABLE IF NOT EXISTS privacy_risk_budgets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL UNIQUE,
    allocated DECIMAL(19, 6) NOT NULL CHECK (allocated >= 0),
    consumed DECIMAL(19, 6) NOT NULL DEFAULT 0 CHECK (consumed >= 0),
    remaining DECIMAL(19, 6) NOT NULL CHECK (remaining >= 0),
    ruleset_version VARCHAR(50) NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LOCKED', 'EXHAUSTED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_prb_consumed_allocated CHECK (consumed <= allocated),
    CONSTRAINT chk_prb_remaining CHECK (remaining = allocated - consumed)
);

CREATE INDEX IF NOT EXISTS idx_prb_campaign ON privacy_risk_budgets(campaign_id);
CREATE INDEX IF NOT EXISTS idx_prb_status ON privacy_risk_budgets(status);

-- PRB Consumption Log - tracks each consumption event
CREATE TABLE IF NOT EXISTS prb_consumption_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prb_id UUID NOT NULL REFERENCES privacy_risk_budgets(id),
    transform_id VARCHAR(100),
    operation_type VARCHAR(50) NOT NULL,
    risk_cost DECIMAL(19, 6) NOT NULL CHECK (risk_cost >= 0),
    remaining_after DECIMAL(19, 6) NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    details_hash VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_prb_consumption_prb ON prb_consumption_logs(prb_id);
CREATE INDEX IF NOT EXISTS idx_prb_consumption_time ON prb_consumption_logs(consumed_at);

-- Linkage Rate Limit table - tracks query patterns per requester
-- Property 27: Linkage Rate Limiting
CREATE TABLE IF NOT EXISTS linkage_rate_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    similarity_score DECIMAL(5, 4),
    query_count INTEGER NOT NULL DEFAULT 1,
    first_query_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_query_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_linkage_requester_hash_window UNIQUE (requester_id, query_hash, window_start)
);

CREATE INDEX IF NOT EXISTS idx_linkage_requester ON linkage_rate_limits(requester_id);
CREATE INDEX IF NOT EXISTS idx_linkage_window ON linkage_rate_limits(window_start, window_end);
CREATE INDEX IF NOT EXISTS idx_linkage_blocked ON linkage_rate_limits(blocked) WHERE blocked = TRUE;

-- Cohort Size Cache - caches cohort size estimates for k-min enforcement
-- Property 26: K-Min Cohort Enforcement
CREATE TABLE IF NOT EXISTS cohort_size_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    criteria_hash VARCHAR(64) NOT NULL UNIQUE,
    estimated_size INTEGER NOT NULL,
    k_min_threshold INTEGER NOT NULL DEFAULT 50,
    meets_threshold BOOLEAN NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    computation_method VARCHAR(50) NOT NULL DEFAULT 'EXACT'
);

CREATE INDEX IF NOT EXISTS idx_cohort_criteria ON cohort_size_cache(criteria_hash);
CREATE INDEX IF NOT EXISTS idx_cohort_expires ON cohort_size_cache(expires_at);

-- Policy Decision Receipts - records privacy policy decisions
CREATE TABLE IF NOT EXISTS policy_decision_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_type VARCHAR(50) NOT NULL CHECK (decision_type IN ('COHORT_BLOCK', 'PRB_BLOCK', 'EXPORT_GATE', 'LINKAGE_BLOCK')),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('ALLOW', 'DENY', 'ESCALATE')),
    campaign_id UUID,
    requester_id UUID,
    reason_codes TEXT,
    policy_version VARCHAR(50) NOT NULL,
    model_version VARCHAR(50),
    details_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_policy_decision_type ON policy_decision_receipts(decision_type);
CREATE INDEX IF NOT EXISTS idx_policy_decision_campaign ON policy_decision_receipts(campaign_id);
CREATE INDEX IF NOT EXISTS idx_policy_decision_requester ON policy_decision_receipts(requester_id);
CREATE INDEX IF NOT EXISTS idx_policy_decision_time ON policy_decision_receipts(created_at);
