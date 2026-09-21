ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS trigger_source TEXT NOT NULL DEFAULT 'manual';
