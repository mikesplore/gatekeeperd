ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS container_name TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS host_port INTEGER;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS container_port INTEGER;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS network TEXT NOT NULL DEFAULT 'bridge';
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS restart_policy TEXT NOT NULL DEFAULT 'unless-stopped';
