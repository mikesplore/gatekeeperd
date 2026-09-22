ALTER TABLE sites
    ADD COLUMN IF NOT EXISTS reconciliation_status TEXT NOT NULL DEFAULT 'healthy',
    ADD COLUMN IF NOT EXISTS last_nginx_error TEXT,
    ADD COLUMN IF NOT EXISTS last_docker_error TEXT,
    ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'sites_reconciliation_status_check'
    ) THEN
        ALTER TABLE sites
            ADD CONSTRAINT sites_reconciliation_status_check
            CHECK (reconciliation_status IN ('healthy', 'drifted', 'docker_down', 'dead_config', 'disabled', 'error'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sites_reconciliation_status ON sites(reconciliation_status);
