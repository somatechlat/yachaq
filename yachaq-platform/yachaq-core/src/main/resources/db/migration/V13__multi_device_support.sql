-- Multi-Device Support Schema
-- Validates: Requirements 251.1, 251.2, 251.3, 251.4, 251.5
-- Property 24: Multi-Device Identity Linking

-- Add new columns to devices table for multi-device support
ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_name VARCHAR(100);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS os_version VARCHAR(50);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS hardware_class VARCHAR(50);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS is_primary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS replacement_device_id UUID REFERENCES devices(id);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS trust_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000 CHECK (trust_score >= 0 AND trust_score <= 1);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS data_categories TEXT; -- JSON array of data categories stored on device

-- Device slot limits per account type
CREATE TABLE device_slot_limits (
    account_type VARCHAR(20) PRIMARY KEY CHECK (account_type IN ('DS_IND', 'DS_COMP', 'DS_ORG')),
    max_devices INTEGER NOT NULL CHECK (max_devices > 0),
    max_mobile INTEGER NOT NULL CHECK (max_mobile > 0),
    max_desktop INTEGER NOT NULL CHECK (max_desktop >= 0),
    max_iot INTEGER NOT NULL CHECK (max_iot >= 0)
);

-- Insert default slot limits
INSERT INTO device_slot_limits (account_type, max_devices, max_mobile, max_desktop, max_iot) VALUES
    ('DS_IND', 5, 3, 2, 0),
    ('DS_COMP', 20, 10, 10, 10),
    ('DS_ORG', 100, 50, 50, 50);

-- Device health events for monitoring
CREATE TABLE device_health_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL CHECK (event_type IN (
        'ROOT_DETECTED', 'JAILBREAK_DETECTED', 'INTEGRITY_FAILED',
        'ATTESTATION_EXPIRED', 'SUSPICIOUS_ACTIVITY', 'OFFLINE_EXTENDED',
        'KEY_ROTATION', 'DEVICE_REPLACED', 'HEALTH_CHECK_PASSED'
    )),
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO')),
    details TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolution_notes TEXT
);

CREATE INDEX idx_health_device ON device_health_events(device_id);
CREATE INDEX idx_health_type ON device_health_events(event_type);
CREATE INDEX idx_health_severity ON device_health_events(severity);
CREATE INDEX idx_health_unresolved ON device_health_events(device_id, resolved_at) WHERE resolved_at IS NULL;

-- Device data location tracking (which data categories are on which device)
CREATE TABLE device_data_locations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    data_category VARCHAR(100) NOT NULL,
    last_sync_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    record_count BIGINT NOT NULL DEFAULT 0,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    UNIQUE(device_id, data_category)
);

CREATE INDEX idx_data_location_device ON device_data_locations(device_id);
CREATE INDEX idx_data_location_category ON device_data_locations(data_category);

-- Query routing log for multi-device queries
CREATE TABLE device_query_routing (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    query_plan_id UUID NOT NULL,
    ds_id UUID NOT NULL REFERENCES ds_profiles(id),
    target_devices UUID[] NOT NULL,
    routing_strategy VARCHAR(50) NOT NULL CHECK (routing_strategy IN (
        'PRIMARY_ONLY', 'ALL_DEVICES', 'DATA_LOCATION', 'ROUND_ROBIN', 'LEAST_LOADED'
    )),
    routed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    responses_received INTEGER NOT NULL DEFAULT 0,
    aggregation_status VARCHAR(20) CHECK (aggregation_status IN ('PENDING', 'PARTIAL', 'COMPLETE', 'FAILED'))
);

CREATE INDEX idx_routing_query ON device_query_routing(query_plan_id);
CREATE INDEX idx_routing_ds ON device_query_routing(ds_id);
CREATE INDEX idx_routing_status ON device_query_routing(aggregation_status);

-- Ensure only one primary device per DS
CREATE UNIQUE INDEX idx_device_primary_unique ON devices(ds_id) WHERE is_primary = TRUE AND disabled_at IS NULL;

-- Index for finding active devices
CREATE INDEX idx_device_active ON devices(ds_id, disabled_at) WHERE disabled_at IS NULL;

COMMENT ON TABLE device_slot_limits IS 'Device slot limits per DS account type';
COMMENT ON TABLE device_health_events IS 'Device health monitoring events for root/jailbreak detection and integrity checks';
COMMENT ON TABLE device_data_locations IS 'Tracks which data categories are stored on which device';
COMMENT ON TABLE device_query_routing IS 'Logs query routing decisions for multi-device queries';
COMMENT ON COLUMN devices.is_primary IS 'Primary device for the DS - receives notifications and is preferred for queries';
COMMENT ON COLUMN devices.trust_score IS 'Computed trust score based on attestation and health events (0-1)';
COMMENT ON COLUMN devices.replacement_device_id IS 'Points to the device that replaced this one';
