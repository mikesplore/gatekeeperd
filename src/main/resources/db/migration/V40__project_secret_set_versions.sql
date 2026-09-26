-- Store each project's complete encrypted environment map as an immutable version.
-- Deployment references are added later, after the version rows are established.
CREATE TABLE IF NOT EXISTS project_secret_set_versions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE RESTRICT,
    environment TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    encrypted_payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by TEXT NULL,
    CONSTRAINT uq_project_secret_set_version UNIQUE (project_id, environment, version)
);

CREATE INDEX IF NOT EXISTS idx_project_secret_set_versions_owner
    ON project_secret_set_versions(project_id, environment);
