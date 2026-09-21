CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS deployment_configurations (
    id UUID PRIMARY KEY,
    repository TEXT NOT NULL,
    git_ref TEXT NOT NULL,
    registry TEXT NOT NULL,
    image_name TEXT NOT NULL,
    image_tag TEXT NOT NULL,
    container_name TEXT,
    host_port INTEGER,
    container_port INTEGER,
    network TEXT NOT NULL,
    restart_policy TEXT NOT NULL,
    env_json TEXT NOT NULL DEFAULT '{}',
    secret_env_encrypted TEXT,
    volumes_json TEXT NOT NULL DEFAULT '[]',
    create_network_if_missing BOOLEAN NOT NULL DEFAULT FALSE,
    project_slug TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deployment_executions (
    id UUID PRIMARY KEY,
    configuration_id UUID NOT NULL REFERENCES deployment_configurations(id),
    repository TEXT NOT NULL,
    git_ref TEXT NOT NULL,
    registry TEXT NOT NULL,
    image_name TEXT NOT NULL,
    image_tag TEXT NOT NULL,
    container_name TEXT,
    host_port INTEGER,
    container_port INTEGER,
    network TEXT NOT NULL,
    restart_policy TEXT NOT NULL,
    env_json TEXT NOT NULL DEFAULT '{}',
    secret_env_encrypted TEXT,
    volumes_json TEXT NOT NULL DEFAULT '[]',
    create_network_if_missing BOOLEAN NOT NULL DEFAULT FALSE,
    project_slug TEXT,
    trigger_source TEXT NOT NULL DEFAULT 'manual',
    status TEXT NOT NULL,
    current_step TEXT NOT NULL,
    logs TEXT NOT NULL DEFAULT '',
    commit_sha TEXT,
    image_digest TEXT,
    previous_container_name TEXT,
    previous_image TEXT,
    error_message TEXT,
    cancelled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_project ON deployment_configurations(project_slug);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_configuration ON deployment_executions(configuration_id);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_created_at ON deployment_executions(created_at DESC);

-- Backfill old mixed records once. Existing encrypted secret payloads are copied as ciphertext.
INSERT INTO deployment_configurations (
    id, repository, git_ref, registry, image_name, image_tag, container_name, host_port, container_port,
    network, restart_policy, env_json, secret_env_encrypted, volumes_json, create_network_if_missing,
    project_slug, created_at, updated_at
)
SELECT j.id, j.repository, j.git_ref, j.registry, j.image_name, j.image_tag, j.container_name, j.host_port, j.container_port,
       j.network, j.restart_policy, j.env_json, j.secret_env_encrypted, j.volumes_json, j.create_network_if_missing,
       j.project_slug, j.created_at, j.updated_at
FROM deployment_jobs j
ON CONFLICT (id) DO NOTHING;

INSERT INTO deployment_executions (
    id, configuration_id, repository, git_ref, registry, image_name, image_tag, container_name, host_port, container_port,
    network, restart_policy, env_json, secret_env_encrypted, volumes_json, create_network_if_missing, project_slug,
    trigger_source, status, current_step, logs, commit_sha, image_digest, previous_container_name, previous_image,
    error_message, cancelled_at, created_at, started_at, completed_at, updated_at
)
SELECT j.id, j.id, j.repository, j.git_ref, j.registry, j.image_name, j.image_tag, j.container_name, j.host_port, j.container_port,
       j.network, j.restart_policy, j.env_json, j.secret_env_encrypted, j.volumes_json, j.create_network_if_missing, j.project_slug,
       j.trigger_source, j.status, j.current_step, j.logs, j.commit_sha, j.image_digest, j.previous_container_name, j.previous_image,
       j.error_message, j.cancelled_at, j.created_at, j.started_at, j.completed_at, j.updated_at
FROM deployment_jobs j
ON CONFLICT (id) DO NOTHING;
