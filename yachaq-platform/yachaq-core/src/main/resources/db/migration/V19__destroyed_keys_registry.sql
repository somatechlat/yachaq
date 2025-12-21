-- V19: Destroyed Keys Registry
-- Property 19: Secure Deletion Verification
-- Validates: Requirements 222.1, 222.2
-- Tracks destroyed encryption keys for crypto-shred verification

CREATE TABLE destroyed_keys_registry (
    id UUID PRIMARY KEY,
    key_id VARCHAR(255) NOT NULL UNIQUE,
    key_type VARCHAR(50) NOT NULL,
    associated_resource_type VARCHAR(100),
    associated_resource_id UUID,
    destroyed_at TIMESTAMP NOT NULL,
    destruction_method VARCHAR(50) NOT NULL,
    certificate_id UUID REFERENCES secure_deletion_certificates(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for efficient querying
CREATE INDEX idx_destroyed_keys_key_id ON destroyed_keys_registry(key_id);
CREATE INDEX idx_destroyed_keys_resource ON destroyed_keys_registry(associated_resource_type, associated_resource_id);
