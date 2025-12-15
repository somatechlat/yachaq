-- V3: Query Orchestrator and Time Capsules
-- Requirements: 205.1, 205.3, 206.1, 206.2, 207.2, 207.3
-- Property 13: Time Capsule TTL Enforcement
-- Property 15: Query Plan Signature Verification

-- Query Plans table
CREATE TABLE IF NOT EXISTS query_plans (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    consent_contract_id UUID NOT NULL,
    scope_hash VARCHAR(128) NOT NULL,
    allowed_transforms TEXT,
    output_restrictions TEXT,
    compensation DECIMAL(19, 4) NOT NULL,
    ttl TIMESTAMP WITH TIME ZONE NOT NULL,
    signature VARCHAR(512) NOT NULL,
    signed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    signing_key_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    executed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_query_plan_request FOREIGN KEY (request_id) REFERENCES requests(id),
    CONSTRAINT fk_query_plan_consent FOREIGN KEY (consent_contract_id) REFERENCES consent_contracts(id)
);

CREATE INDEX idx_query_plans_request ON query_plans(request_id);
CREATE INDEX idx_query_plans_consent ON query_plans(consent_contract_id);
CREATE INDEX idx_query_plans_status ON query_plans(status);
CREATE INDEX idx_query_plans_ttl ON query_plans(ttl);

-- Time Capsules table
CREATE TABLE IF NOT EXISTS time_capsules (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    consent_contract_id UUID NOT NULL,
    field_manifest_hash VARCHAR(128) NOT NULL,
    encrypted_payload BYTEA,
    encryption_key_id VARCHAR(128) NOT NULL,
    ttl TIMESTAMP WITH TIME ZONE NOT NULL,
    nonce VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    deletion_receipt_id UUID,
    CONSTRAINT fk_capsule_request FOREIGN KEY (request_id) REFERENCES requests(id),
    CONSTRAINT fk_capsule_consent FOREIGN KEY (consent_contract_id) REFERENCES consent_contracts(id)
);

CREATE INDEX idx_capsules_request ON time_capsules(request_id);
CREATE INDEX idx_capsules_consent ON time_capsules(consent_contract_id);
CREATE INDEX idx_capsules_status ON time_capsules(status);
CREATE INDEX idx_capsules_ttl ON time_capsules(ttl);
CREATE INDEX idx_capsules_nonce ON time_capsules(nonce);

-- Nonce Registry for replay protection (Property 16)
CREATE TABLE IF NOT EXISTS nonce_registry (
    nonce VARCHAR(64) PRIMARY KEY,
    capsule_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_nonce_capsule FOREIGN KEY (capsule_id) REFERENCES time_capsules(id)
);

CREATE INDEX idx_nonce_status ON nonce_registry(status);
CREATE INDEX idx_nonce_expires ON nonce_registry(expires_at);

-- Query Dispatch Log for tracking dispatched queries
CREATE TABLE IF NOT EXISTS query_dispatch_log (
    id UUID PRIMARY KEY,
    query_plan_id UUID NOT NULL,
    device_id UUID NOT NULL,
    dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    timeout_at TIMESTAMP WITH TIME ZONE NOT NULL,
    response_received_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(32) NOT NULL DEFAULT 'DISPATCHED',
    error_code VARCHAR(64),
    CONSTRAINT fk_dispatch_plan FOREIGN KEY (query_plan_id) REFERENCES query_plans(id)
);

CREATE INDEX idx_dispatch_plan ON query_dispatch_log(query_plan_id);
CREATE INDEX idx_dispatch_device ON query_dispatch_log(device_id);
CREATE INDEX idx_dispatch_status ON query_dispatch_log(status);
