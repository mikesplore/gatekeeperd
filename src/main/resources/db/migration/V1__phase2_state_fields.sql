ALTER TABLE projects ADD COLUMN IF NOT EXISTS block_reason TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deployment_mode TEXT NOT NULL DEFAULT 'developer_hosted';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS service_mode TEXT NOT NULL DEFAULT 'development';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS lifecycle_status TEXT NOT NULL DEFAULT 'active';

ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS dedupe_key TEXT;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_status TEXT NOT NULL DEFAULT 'received';
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_error TEXT;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS payment_events_dedupe_key_idx
    ON payment_events(dedupe_key)
    WHERE dedupe_key IS NOT NULL;
