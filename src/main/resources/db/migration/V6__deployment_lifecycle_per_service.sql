-- Canonical deployment lifecycle is owned by service/environment.
ALTER TABLE deployments
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;

UPDATE deployments d
SET service_id = COALESCE(de.service_id, dc.service_id, s.id)
FROM deployment_executions de
JOIN deployment_configurations dc ON dc.id = de.configuration_id
JOIN services s ON s.name = 'default'
WHERE d.execution_id = de.id AND s.project_id = d.project_id AND d.service_id IS NULL;

DROP INDEX uq_deployments_one_active_per_project_environment;
CREATE UNIQUE INDEX uq_deployments_one_active_per_service_environment
    ON deployments(service_id, environment)
    WHERE status = 'active' AND service_id IS NOT NULL;

CREATE INDEX idx_deployments_service_environment_created
    ON deployments(service_id, environment, created_at DESC);
