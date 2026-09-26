-- Add stable project ownership and environment metadata without changing
-- existing deployment rows or constraining legacy data.
ALTER TABLE deployment_configurations
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

ALTER TABLE deployment_executions
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

ALTER TABLE deployment_jobs
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_project_id
    ON deployment_configurations(project_id);

CREATE INDEX IF NOT EXISTS idx_deployment_executions_project_id
    ON deployment_executions(project_id);

CREATE INDEX IF NOT EXISTS idx_deployment_jobs_project_id
    ON deployment_jobs(project_id);
