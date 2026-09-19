ALTER TABLE integration_outbox ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE integration_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_integration_outbox_claimable ON integration_outbox(status, next_attempt_at, lease_until);
