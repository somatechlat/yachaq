-- V4: Settlement and Payout
-- Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 110.1

-- DS Balances table
CREATE TABLE IF NOT EXISTS ds_balances (
    id UUID PRIMARY KEY,
    ds_id UUID NOT NULL UNIQUE,
    available_balance DECIMAL(19, 4) NOT NULL DEFAULT 0,
    pending_balance DECIMAL(19, 4) NOT NULL DEFAULT 0,
    total_earned DECIMAL(19, 4) NOT NULL DEFAULT 0,
    total_paid_out DECIMAL(19, 4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'YC',
    last_settlement_at TIMESTAMP WITH TIME ZONE,
    last_payout_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_balance_ds FOREIGN KEY (ds_id) REFERENCES ds_profiles(id)
);

CREATE INDEX idx_ds_balances_ds ON ds_balances(ds_id);

-- Payout Instructions table
CREATE TABLE IF NOT EXISTS payout_instructions (
    id UUID PRIMARY KEY,
    ds_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'YC',
    method VARCHAR(32) NOT NULL,
    destination_hash VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    receipt_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    CONSTRAINT fk_payout_ds FOREIGN KEY (ds_id) REFERENCES ds_profiles(id)
);

CREATE INDEX idx_payout_ds ON payout_instructions(ds_id);
CREATE INDEX idx_payout_status ON payout_instructions(status);
CREATE INDEX idx_payout_created ON payout_instructions(created_at);

-- Settlement Records table (for audit trail)
CREATE TABLE IF NOT EXISTS settlement_records (
    id UUID PRIMARY KEY,
    consent_contract_id UUID NOT NULL,
    ds_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    unit_count INTEGER NOT NULL,
    receipt_id UUID,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    CONSTRAINT fk_settlement_consent FOREIGN KEY (consent_contract_id) REFERENCES consent_contracts(id),
    CONSTRAINT fk_settlement_ds FOREIGN KEY (ds_id) REFERENCES ds_profiles(id)
);

CREATE INDEX idx_settlement_consent ON settlement_records(consent_contract_id);
CREATE INDEX idx_settlement_ds ON settlement_records(ds_id);
CREATE INDEX idx_settlement_date ON settlement_records(settled_at);
