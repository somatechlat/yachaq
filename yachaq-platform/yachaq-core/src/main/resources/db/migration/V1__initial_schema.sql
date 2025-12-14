-- YACHAQ Platform Initial Schema
-- PostgreSQL 16
-- Validates: Requirements 51.1, 51.2

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- DS Profiles
CREATE TABLE ds_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pseudonym VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED')),
    preferences_hash VARCHAR(64),
    encryption_key_id VARCHAR(255),
    account_type VARCHAR(20) NOT NULL CHECK (account_type IN ('DS_IND', 'DS_COMP', 'DS_ORG')),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_ds_profiles_status ON ds_profiles(status);
CREATE INDEX idx_ds_profiles_account_type ON ds_profiles(account_type);

-- Consent Contracts
CREATE TABLE consent_contracts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ds_id UUID NOT NULL REFERENCES ds_profiles(id),
    requester_id UUID NOT NULL,
    request_id UUID NOT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    purpose_hash VARCHAR(64) NOT NULL,
    duration_start TIMESTAMPTZ NOT NULL,
    duration_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    compensation_amount DECIMAL(19,4) NOT NULL CHECK (compensation_amount > 0),
    blockchain_anchor_hash VARCHAR(66),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_consent_ds ON consent_contracts(ds_id);
CREATE INDEX idx_consent_requester ON consent_contracts(requester_id);
CREATE INDEX idx_consent_status ON consent_contracts(status);
CREATE INDEX idx_consent_request ON consent_contracts(request_id);

-- Audit Receipts (append-only)
CREATE TABLE audit_receipts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(50) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id UUID NOT NULL,
    actor_type VARCHAR(20) NOT NULL CHECK (actor_type IN ('DS', 'REQUESTER', 'SYSTEM')),
    resource_id UUID NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    details_hash VARCHAR(64) NOT NULL,
    merkle_proof TEXT,
    previous_receipt_hash VARCHAR(64),
    receipt_hash VARCHAR(64)
);

CREATE INDEX idx_audit_actor ON audit_receipts(actor_id);
CREATE INDEX idx_audit_resource ON audit_receipts(resource_id);
CREATE INDEX idx_audit_timestamp ON audit_receipts(timestamp);
CREATE INDEX idx_audit_event_type ON audit_receipts(event_type);

-- Escrow Accounts
CREATE TABLE escrow_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    requester_id UUID NOT NULL,
    request_id UUID NOT NULL UNIQUE,
    funded_amount DECIMAL(19,4) NOT NULL DEFAULT 0 CHECK (funded_amount >= 0),
    locked_amount DECIMAL(19,4) NOT NULL DEFAULT 0 CHECK (locked_amount >= 0),
    released_amount DECIMAL(19,4) NOT NULL DEFAULT 0 CHECK (released_amount >= 0),
    refunded_amount DECIMAL(19,4) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'FUNDED', 'LOCKED', 'SETTLED', 'REFUNDED')),
    blockchain_tx_hash VARCHAR(66),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_escrow_requester ON escrow_accounts(requester_id);
CREATE INDEX idx_escrow_request ON escrow_accounts(request_id);
CREATE INDEX idx_escrow_status ON escrow_accounts(status);

-- Journal Entries (double-entry accounting)
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    debit_account VARCHAR(100) NOT NULL,
    credit_account VARCHAR(100) NOT NULL,
    amount DECIMAL(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    reference VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE
);

CREATE INDEX idx_journal_debit ON journal_entries(debit_account);
CREATE INDEX idx_journal_credit ON journal_entries(credit_account);
CREATE INDEX idx_journal_timestamp ON journal_entries(timestamp);

-- Row Level Security for multi-tenancy
ALTER TABLE consent_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE escrow_accounts ENABLE ROW LEVEL SECURITY;


-- Devices
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ds_id UUID NOT NULL REFERENCES ds_profiles(id),
    public_key TEXT NOT NULL,
    risk_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000 CHECK (risk_score >= 0 AND risk_score <= 1),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attestation_status VARCHAR(20) NOT NULL CHECK (attestation_status IN ('PENDING', 'VERIFIED', 'FAILED')),
    device_type VARCHAR(20) NOT NULL CHECK (device_type IN ('MOBILE_ANDROID', 'MOBILE_IOS', 'DESKTOP', 'IOT')),
    device_fingerprint VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_device_ds ON devices(ds_id);
CREATE INDEX idx_device_status ON devices(attestation_status);
CREATE INDEX idx_device_type ON devices(device_type);

-- Refresh Tokens (for token rotation)
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ds_id UUID NOT NULL REFERENCES ds_profiles(id),
    device_id UUID NOT NULL REFERENCES devices(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID REFERENCES refresh_tokens(id)
);

CREATE INDEX idx_refresh_ds ON refresh_tokens(ds_id);
CREATE INDEX idx_refresh_device ON refresh_tokens(device_id);
CREATE INDEX idx_refresh_hash ON refresh_tokens(token_hash);
