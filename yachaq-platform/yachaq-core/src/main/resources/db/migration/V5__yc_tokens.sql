-- V5: YC Token Management
-- Requirements: 192.1, 192.2, 192.3, 192.4, 192.5, 192.6

-- YC Token operations table
CREATE TABLE yc_tokens (
    id UUID PRIMARY KEY,
    ds_id UUID NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    operation_type VARCHAR(20) NOT NULL CHECK (operation_type IN ('ISSUANCE', 'REDEMPTION', 'CLAWBACK', 'ADJUSTMENT')),
    reference_id UUID NOT NULL,
    reference_type VARCHAR(50) NOT NULL,
    escrow_id UUID,
    description VARCHAR(500),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    audit_receipt_id UUID,
    
    CONSTRAINT fk_yc_tokens_escrow FOREIGN KEY (escrow_id) REFERENCES escrow_accounts(id),
    CONSTRAINT fk_yc_tokens_audit FOREIGN KEY (audit_receipt_id) REFERENCES audit_receipts(id)
);

-- Indexes for efficient queries
CREATE INDEX idx_yc_tokens_ds_id ON yc_tokens(ds_id);
CREATE INDEX idx_yc_tokens_ds_id_created ON yc_tokens(ds_id, created_at DESC);
CREATE INDEX idx_yc_tokens_escrow_id ON yc_tokens(escrow_id);
CREATE INDEX idx_yc_tokens_reference ON yc_tokens(reference_id, reference_type);
CREATE INDEX idx_yc_tokens_operation_type ON yc_tokens(operation_type);

-- YC governance settings table
CREATE TABLE yc_governance_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value VARCHAR(500) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by UUID
);

-- Initialize governance settings (transfers disabled by default - Requirement 192.1)
INSERT INTO yc_governance_settings (setting_key, setting_value) VALUES
    ('transfers_enabled', 'false'),
    ('max_daily_issuance', '1000000'),
    ('max_single_issuance', '10000'),
    ('clawback_enabled', 'true');

-- Comments for documentation
COMMENT ON TABLE yc_tokens IS 'YC Token operations - non-speculative utility credits (Req 192)';
COMMENT ON COLUMN yc_tokens.operation_type IS 'ISSUANCE: from settlement, REDEMPTION: for payout, CLAWBACK: fraud/chargeback';
COMMENT ON COLUMN yc_tokens.idempotency_key IS 'Prevents duplicate operations';
COMMENT ON TABLE yc_governance_settings IS 'Governance settings for YC token constraints';
