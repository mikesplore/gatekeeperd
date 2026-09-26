-- New deployments/configurations can point at immutable secret versions while
-- retaining encrypted compatibility snapshots for existing readers.
ALTER TABLE deployment_configurations
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

ALTER TABLE deployment_executions
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

ALTER TABLE deployments
    ADD COLUMN IF NOT EXISTS credential_set_id UUID,
    ADD COLUMN IF NOT EXISTS credential_set_version INTEGER,
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_secret_set
    ON deployment_configurations(secret_set_id);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_secret_set
    ON deployment_executions(secret_set_id);
CREATE INDEX IF NOT EXISTS idx_deployments_credential_set
    ON deployments(credential_set_id);
CREATE INDEX IF NOT EXISTS idx_deployments_secret_set
    ON deployments(secret_set_id);
