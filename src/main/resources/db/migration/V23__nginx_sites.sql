CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS sites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL UNIQUE REFERENCES projects(id),
    domain TEXT NOT NULL,
    upstream_host TEXT NOT NULL DEFAULT '127.0.0.1',
    upstream_mode TEXT NOT NULL CHECK (upstream_mode IN ('docker_discovery', 'explicit_port')),
    upstream_container_name TEXT,
    upstream_explicit_port INTEGER,
    tls_mode TEXT NOT NULL CHECK (tls_mode IN ('http_only', 'https', 'https_http2')),
    cert_mode TEXT NOT NULL CHECK (cert_mode IN ('auto_resolve', 'explicit_path')),
    cert_explicit_path TEXT,
    gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT sites_upstream_configuration_check CHECK (
        (upstream_mode = 'docker_discovery' AND upstream_container_name IS NOT NULL AND upstream_explicit_port IS NULL)
        OR
        (upstream_mode = 'explicit_port' AND upstream_container_name IS NULL AND upstream_explicit_port IS NOT NULL AND upstream_explicit_port BETWEEN 1 AND 65535)
    ),
    CONSTRAINT sites_cert_configuration_check CHECK (
        (cert_mode = 'auto_resolve' AND cert_explicit_path IS NULL)
        OR
        (cert_mode = 'explicit_path' AND cert_explicit_path IS NOT NULL)
    ),
    CONSTRAINT sites_config_version_positive CHECK (config_version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_sites_domain ON sites(domain);
