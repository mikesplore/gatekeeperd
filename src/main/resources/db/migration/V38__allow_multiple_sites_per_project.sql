-- Keep the project lookup index while preparing the schema for multiple domains.
-- API and UI creation flows continue to enforce one site per project for now.
ALTER TABLE sites DROP CONSTRAINT IF EXISTS sites_project_id_key;

CREATE INDEX IF NOT EXISTS idx_sites_project_id ON sites(project_id);
