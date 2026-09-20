CREATE TABLE IF NOT EXISTS deployment_jobs (
    id UUID PRIMARY KEY,
    repository TEXT NOT NULL,
    git_ref TEXT NOT NULL,
    registry TEXT NOT NULL,
    image_name TEXT NOT NULL,
    image_tag TEXT NOT NULL,
    status TEXT NOT NULL,
    current_step TEXT NOT NULL,
    logs TEXT NOT NULL DEFAULT '',
    commit_sha TEXT,
    image_digest TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_deployment_jobs_created_at ON deployment_jobs(created_at DESC);
