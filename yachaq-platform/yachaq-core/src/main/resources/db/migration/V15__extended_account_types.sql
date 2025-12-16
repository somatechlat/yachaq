-- V15: Extended Account Types
-- Validates: Requirements 225.1, 225.2, 225.3, 226.1, 226.2, 227.1, 227.2

-- Accounts table for all account types
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_type VARCHAR(20) NOT NULL,
    email VARCHAR(255) UNIQUE,
    display_name VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    organization_name VARCHAR(255),
    kyb_verified BOOLEAN DEFAULT FALSE,
    kyb_verification_hash VARCHAR(128),
    max_devices INTEGER DEFAULT 5,
    governance_constraints TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE,
    verified_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_account_type CHECK (account_type IN ('DS_IND', 'DS_COMP', 'DS_ORG', 'RQ_COM', 'RQ_AR', 'RQ_NGO')),
    CONSTRAINT chk_account_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'BANNED'))
);

-- Indexes for common queries
CREATE INDEX idx_accounts_email ON accounts(email);
CREATE INDEX idx_accounts_type ON accounts(account_type);
CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_accounts_kyb ON accounts(kyb_verified) WHERE account_type IN ('DS_COMP', 'DS_ORG');

-- Comments
COMMENT ON TABLE accounts IS 'All account types: DS-IND, DS-COMP, DS-ORG, RQ-COM, RQ-AR, RQ-NGO';
COMMENT ON COLUMN accounts.account_type IS 'DS_IND=Individual DS, DS_COMP=Company DS, DS_ORG=Organization DS, RQ_COM=Commercial Requester, RQ_AR=Academic Requester, RQ_NGO=NGO Requester';
COMMENT ON COLUMN accounts.kyb_verified IS 'KYB verification status for enterprise accounts (DS-COMP, DS-ORG)';
COMMENT ON COLUMN accounts.max_devices IS 'Maximum devices allowed for fleet management';
COMMENT ON COLUMN accounts.governance_constraints IS 'JSON governance constraints for NGO/Foundation accounts';
