-- V11: Secure Deletion Service
-- Property 19: Secure Deletion Verification
-- Validates: Requirements 222.1, 222.2, 222.3, 222.4, 222.9

-- Secure Deletion Certificates table
CREATE TABLE secure_deletion_certificates (
    id UUID PRIMARY KEY,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    deletion_method VARCHAR(50) NOT NULL,
    key_destroyed BOOLEAN NOT NULL DEFAULT FALSE,
    storage_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    storage_overwritten BOOLEAN NOT NULL DEFAULT FALSE,
    initiated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    verified_at TIMESTAMP,
    certificate_hash VARCHAR(64) NOT NULL,
    scope_description TEXT,
    storage_locations TEXT, -- JSON array of storage locations
    deletion_reason VARCHAR(255),
    requested_by UUID,
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Key Destruction Log table
CREATE TABLE key_destruction_log (
    id UUID PRIMARY KEY,
    key_id VARCHAR(255) NOT NULL,
    key_type VARCHAR(50) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    destroyed_at TIMESTAMP NOT NULL,
    destruction_method VARCHAR(50) NOT NULL,
    certificate_id UUID REFERENCES secure_deletion_certificates(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Storage Deletion Log table
CREATE TABLE storage_deletion_log (
    id UUID PRIMARY KEY,
    storage_location VARCHAR(500) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID NOT NULL,
    deleted_at TIMESTAMP NOT NULL,
    overwritten BOOLEAN NOT NULL DEFAULT FALSE,
    overwrite_passes INT DEFAULT 0,
    certificate_id UUID REFERENCES secure_deletion_certificates(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for efficient querying
CREATE INDEX idx_deletion_cert_resource ON secure_deletion_certificates(resource_type, resource_id);
CREATE INDEX idx_deletion_cert_status ON secure_deletion_certificates(status);
CREATE INDEX idx_deletion_cert_initiated ON secure_deletion_certificates(initiated_at);
CREATE INDEX idx_key_destruction_key ON key_destruction_log(key_id);
CREATE INDEX idx_key_destruction_resource ON key_destruction_log(resource_type, resource_id);
CREATE INDEX idx_storage_deletion_resource ON storage_deletion_log(resource_type, resource_id);
