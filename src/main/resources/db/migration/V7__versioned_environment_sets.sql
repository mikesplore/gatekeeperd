-- Project-shared environment values are immutable encrypted versions. Service
-- versions continue to use project_secret_set_versions with service_id.
CREATE TABLE project_shared_environment_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    environment TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    encrypted_payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by TEXT,
    CONSTRAINT uq_project_shared_environment_version UNIQUE (project_id, environment, version)
);

ALTER TABLE deployment_configurations
    ADD COLUMN shared_environment_set_id UUID REFERENCES project_shared_environment_versions(id) ON DELETE RESTRICT,
    ADD COLUMN shared_environment_set_version INTEGER;

ALTER TABLE deployment_executions
    ADD COLUMN shared_environment_set_id UUID REFERENCES project_shared_environment_versions(id) ON DELETE RESTRICT,
    ADD COLUMN shared_environment_set_version INTEGER,
    ADD COLUMN resolved_environment_sources_json TEXT NOT NULL DEFAULT '{}';

CREATE INDEX idx_project_shared_environment_versions_owner
    ON project_shared_environment_versions(project_id, environment, version DESC);
