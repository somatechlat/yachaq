-- V16: Canonical Event System
-- Validates: Requirement 191 (Canonical Event Bus)

-- Canonical events table for system-wide event bus
CREATE TABLE canonical_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL,
    event_name VARCHAR(100) NOT NULL,
    trace_id UUID NOT NULL,
    correlation_id UUID,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    actor_id UUID NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    resource_id UUID,
    resource_type VARCHAR(100),
    consent_contract_id UUID,
    policy_version VARCHAR(50),
    payload_hash VARCHAR(128) NOT NULL,
    payload_summary VARCHAR(1000),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    processed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    schema_version VARCHAR(20) NOT NULL DEFAULT '1.0',
    CONSTRAINT chk_event_type CHECK (event_type IN (
        'REQUEST', 'CONSENT', 'TOKEN', 'DATA', 'SETTLEMENT', 'PAYOUT',
        'P2P', 'DEVICE', 'MATCH', 'CAPSULE', 'CLEAN_ROOM', 'ACCOUNT', 'INDEX', 'TRAINING'
    )),
    CONSTRAINT chk_actor_type CHECK (actor_type IN ('DS', 'REQUESTER', 'SYSTEM', 'GUARDIAN')),
    CONSTRAINT chk_event_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'))
);

-- Indexes for common queries
CREATE INDEX idx_event_type ON canonical_events(event_type);
CREATE INDEX idx_event_trace_id ON canonical_events(trace_id);
CREATE INDEX idx_event_correlation_id ON canonical_events(correlation_id);
CREATE INDEX idx_event_idempotency_key ON canonical_events(idempotency_key);
CREATE INDEX idx_event_timestamp ON canonical_events(timestamp);
CREATE INDEX idx_event_status ON canonical_events(status);
CREATE INDEX idx_event_actor ON canonical_events(actor_id);
CREATE INDEX idx_event_resource ON canonical_events(resource_id);

-- Partial index for pending events (processing queue)
CREATE INDEX idx_event_pending ON canonical_events(timestamp) WHERE status = 'PENDING';

-- Partial index for failed events (retry queue)
CREATE INDEX idx_event_failed ON canonical_events(timestamp, retry_count) WHERE status = 'FAILED';

-- Partial index for dead-letter events (alerting)
CREATE INDEX idx_event_dead_letter ON canonical_events(timestamp) WHERE status = 'DEAD_LETTER';

-- Comments
COMMENT ON TABLE canonical_events IS 'Canonical event bus for system-wide communication (Requirement 191)';
COMMENT ON COLUMN canonical_events.trace_id IS 'Distributed tracing ID for request correlation';
COMMENT ON COLUMN canonical_events.idempotency_key IS 'Unique key for at-least-once delivery with idempotent processing';
COMMENT ON COLUMN canonical_events.payload_hash IS 'Hash of event payload (no raw PII in events)';
COMMENT ON COLUMN canonical_events.schema_version IS 'Event schema version for backward compatibility';
