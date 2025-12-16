-- Device Attestation Schema
-- Validates: Requirements 217.1, 217.2, 217.3
-- Property 22: Device Attestation Collection

CREATE TABLE device_attestations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'DESKTOP')),
    attestation_type VARCHAR(20) NOT NULL CHECK (attestation_type IN ('SAFETYNET', 'DEVICECHECK', 'TPM', 'NONE')),
    attestation_proof TEXT NOT NULL,
    attestation_hash VARCHAR(64) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    trust_level VARCHAR(20) NOT NULL CHECK (trust_level IN ('HIGH', 'MEDIUM', 'LOW', 'UNVERIFIED')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED', 'EXPIRED')),
    failure_reason TEXT,
    nonce VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

-- Indexes for efficient queries
CREATE INDEX idx_attestation_device ON device_attestations(device_id);
CREATE INDEX idx_attestation_status ON device_attestations(status);
CREATE INDEX idx_attestation_expires ON device_attestations(expires_at);
CREATE INDEX idx_attestation_nonce ON device_attestations(nonce);
CREATE INDEX idx_attestation_trust ON device_attestations(trust_level);

-- Composite index for finding valid attestations
CREATE INDEX idx_attestation_valid ON device_attestations(device_id, status, expires_at)
    WHERE status = 'VERIFIED';

COMMENT ON TABLE device_attestations IS 'Stores device attestation proofs (SafetyNet, DeviceCheck, TPM)';
COMMENT ON COLUMN device_attestations.attestation_proof IS 'Raw attestation proof from platform API';
COMMENT ON COLUMN device_attestations.attestation_hash IS 'SHA-256 hash of attestation proof for integrity';
COMMENT ON COLUMN device_attestations.nonce IS 'Unique nonce for replay protection';
COMMENT ON COLUMN device_attestations.trust_level IS 'Computed trust level based on attestation verification';
