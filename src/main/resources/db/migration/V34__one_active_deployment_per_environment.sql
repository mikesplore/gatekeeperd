-- Environment is a real ownership dimension. Legacy rows stay nullable in the
-- synchronized compatibility tables; canonical deployments always have it.
CREATE UNIQUE INDEX uq_deployments_one_active_per_project_environment
    ON deployments(project_id, environment)
    WHERE status = 'active' AND project_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_project_environment
    ON deployment_configurations(project_id, environment);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_project_environment
    ON deployment_executions(project_id, environment);
CREATE INDEX IF NOT EXISTS idx_deployment_jobs_project_environment
    ON deployment_jobs(project_id, environment);
