-- Add service ownership alongside project ownership for a compatibility window.
ALTER TABLE deployment_configurations
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;
ALTER TABLE deployment_executions
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;
ALTER TABLE deployment_jobs
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;
ALTER TABLE project_secret_set_versions
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;

-- Existing records belong to their project's default service. Leave unresolved
-- legacy/orphan rows nullable rather than guessing a project association.
UPDATE deployment_configurations dc
SET service_id = s.id
FROM services s
WHERE dc.service_id IS NULL AND dc.project_id = s.project_id AND s.name = 'default';

UPDATE deployment_executions de
SET service_id = s.id
FROM services s
WHERE de.service_id IS NULL AND de.project_id = s.project_id AND s.name = 'default';

UPDATE deployment_jobs dj
SET service_id = s.id
FROM services s
WHERE dj.service_id IS NULL AND dj.project_id = s.project_id AND s.name = 'default';

UPDATE project_secret_set_versions ss
SET service_id = s.id
FROM services s
WHERE ss.service_id IS NULL AND ss.project_id = s.project_id AND s.name = 'default';

ALTER TABLE project_secret_set_versions
    DROP CONSTRAINT uq_project_secret_set_version;
CREATE UNIQUE INDEX uq_service_secret_set_version
    ON project_secret_set_versions(service_id, environment, version);

CREATE INDEX idx_deployment_configurations_service_id ON deployment_configurations(service_id);
CREATE INDEX idx_deployment_executions_service_id ON deployment_executions(service_id);
CREATE INDEX idx_deployment_jobs_service_id ON deployment_jobs(service_id);
CREATE INDEX idx_project_secret_set_versions_service_owner
    ON project_secret_set_versions(service_id, environment);
