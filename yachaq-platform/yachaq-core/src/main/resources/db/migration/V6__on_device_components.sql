-- V6: On-Device Components (ODS and ODX)
-- Requirements: 201.1, 201.2, 202.1, 202.4, 203.1, 203.3
-- Property 12: Edge-First Data Locality
-- Property 25: ODX Minimization

-- On-Device Data Store (ODS) entries
CREATE TABLE ods_entries (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    ds_id UUID NOT NULL,
    category VARCHAR(100) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    encrypted_payload TEXT NOT NULL,
    encryption_key_id VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    storage_location VARCHAR(20) NOT NULL CHECK (storage_location IN ('DEVICE', 'CLOUD_WITH_CONSENT')),
    cloud_consent_id UUID,
    retention_policy VARCHAR(100) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP WITH TIME ZONE,
    
    -- Constraint: cloud storage requires consent
    CONSTRAINT chk_cloud_consent CHECK (
        storage_location != 'CLOUD_WITH_CONSENT' OR cloud_consent_id IS NOT NULL
    )
);

-- Indexes for ODS
CREATE INDEX idx_ods_device ON ods_entries(device_id);
CREATE INDEX idx_ods_ds ON ods_entries(ds_id);
CREATE INDEX idx_ods_category ON ods_entries(category);
CREATE INDEX idx_ods_timestamp ON ods_entries(timestamp);
CREATE INDEX idx_ods_storage ON ods_entries(storage_location);
CREATE INDEX idx_ods_expires ON ods_entries(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_ods_active ON ods_entries(ds_id, deleted_at) WHERE deleted_at IS NULL;

-- On-Device Label Index (ODX) entries
CREATE TABLE odx_entries (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    ds_id UUID NOT NULL,
    facet_key VARCHAR(100) NOT NULL,
    time_bucket VARCHAR(20) NOT NULL,
    geo_bucket VARCHAR(100),
    count INTEGER NOT NULL DEFAULT 0,
    aggregate_value DOUBLE PRECISION,
    quality VARCHAR(20) NOT NULL CHECK (quality IN ('VERIFIED', 'IMPORTED')),
    k_min INTEGER NOT NULL DEFAULT 50,
    geo_resolution VARCHAR(10) NOT NULL DEFAULT 'COARSE' CHECK (geo_resolution IN ('COARSE', 'FINE')),
    time_resolution VARCHAR(10) NOT NULL DEFAULT 'DAY' CHECK (time_resolution IN ('DAY', 'WEEK', 'MONTH')),
    device_signature TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ontology_version VARCHAR(10) NOT NULL DEFAULT 'v1',
    
    -- Constraint: facet key must follow namespace.label pattern
    CONSTRAINT chk_facet_pattern CHECK (facet_key ~ '^[a-z]+\.[a-z_]+$'),
    
    -- Constraint: k_min must be at least 50 (k-anonymity threshold)
    CONSTRAINT chk_k_min CHECK (k_min >= 50),
    
    -- Constraint: geo_resolution must be COARSE for ODX (no precise GPS)
    CONSTRAINT chk_geo_coarse CHECK (geo_resolution = 'COARSE'),
    
    -- Unique constraint for upsert
    CONSTRAINT uq_odx_device_facet_time UNIQUE (device_id, facet_key, time_bucket)
);

-- Indexes for ODX
CREATE INDEX idx_odx_device ON odx_entries(device_id);
CREATE INDEX idx_odx_ds ON odx_entries(ds_id);
CREATE INDEX idx_odx_facet ON odx_entries(facet_key);
CREATE INDEX idx_odx_time_bucket ON odx_entries(time_bucket);
CREATE INDEX idx_odx_quality ON odx_entries(quality);
CREATE INDEX idx_odx_k_min ON odx_entries(k_min);
CREATE INDEX idx_odx_signed ON odx_entries(device_id) WHERE device_signature IS NOT NULL;

-- Function to validate ODX entries don't contain raw data patterns
CREATE OR REPLACE FUNCTION validate_odx_no_raw_data()
RETURNS TRIGGER AS $$
BEGIN
    -- Check facet_key doesn't contain forbidden patterns
    IF NEW.facet_key ~* '(raw|payload|content|text|email|phone|address|name|ssn|password|secret|token)' THEN
        RAISE EXCEPTION 'ODX facet_key contains forbidden raw data pattern: %', NEW.facet_key;
    END IF;
    
    -- Check geo_bucket isn't too precise (no coordinates with 4+ decimal places)
    IF NEW.geo_bucket IS NOT NULL AND NEW.geo_bucket ~ '\d+\.\d{4,}' THEN
        RAISE EXCEPTION 'ODX geo_bucket too precise - use coarse location only: %', NEW.geo_bucket;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to enforce ODX privacy safety
CREATE TRIGGER trg_odx_privacy_safety
    BEFORE INSERT OR UPDATE ON odx_entries
    FOR EACH ROW
    EXECUTE FUNCTION validate_odx_no_raw_data();

-- Comments for documentation
COMMENT ON TABLE ods_entries IS 'On-Device Data Store - Encrypted local data storage (Property 12: Edge-First)';
COMMENT ON TABLE odx_entries IS 'On-Device Label Index - Privacy-safe discovery index (Property 25: ODX Minimization)';
COMMENT ON COLUMN ods_entries.storage_location IS 'DEVICE = edge-first (default), CLOUD_WITH_CONSENT = requires explicit consent';
COMMENT ON COLUMN odx_entries.facet_key IS 'Coarse label from approved ontology (namespace.label format)';
COMMENT ON COLUMN odx_entries.k_min IS 'Minimum k-anonymity threshold (must be >= 50)';
COMMENT ON COLUMN odx_entries.geo_resolution IS 'Must be COARSE - no precise GPS allowed in ODX';
