ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS previous_container_name TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS previous_image TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS project_slug TEXT;
CREATE TABLE IF NOT EXISTS github_webhook_deliveries (
    delivery_id TEXT PRIMARY KEY,
    event TEXT NOT NULL,
    repository TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    queued_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_github_webhook_deliveries_received_at ON github_webhook_deliveries(received_at DESC);
