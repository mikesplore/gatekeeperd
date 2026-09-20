ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS secret_env_encrypted TEXT;
