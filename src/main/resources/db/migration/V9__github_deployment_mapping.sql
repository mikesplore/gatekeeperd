ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_repository TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_ref TEXT NOT NULL DEFAULT 'main';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deploy_image_name TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deploy_image_tag TEXT NOT NULL DEFAULT 'latest';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS auto_deploy BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_projects_github_deploy ON projects(github_repository, github_ref, auto_deploy);
