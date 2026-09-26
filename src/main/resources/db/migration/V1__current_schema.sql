
-- Source: src/main/resources/db/migration/V0__baseline_schema.sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'admin',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    domain TEXT NOT NULL,
    container_name TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('frontend', 'backend')),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked', 'manual_block')),
    block_reason TEXT,
    deployment_mode TEXT NOT NULL DEFAULT 'developer_hosted',
    service_mode TEXT NOT NULL DEFAULT 'development',
    lifecycle_status TEXT NOT NULL DEFAULT 'active',
    client_name TEXT,
    client_email TEXT,
    paystack_customer_code TEXT,
    amount_due NUMERIC(12, 2),
    currency TEXT NOT NULL DEFAULT 'KES',
    due_date DATE,
    grace_period_days INTEGER NOT NULL DEFAULT 3,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id),
    paystack_reference TEXT NOT NULL UNIQUE,
    authorization_url TEXT,
    amount NUMERIC(12, 2) NOT NULL,
    status TEXT NOT NULL,
    gateway_status TEXT NOT NULL DEFAULT 'pending',
    verified_via TEXT,
    verified_at TIMESTAMP,
    paid_at TIMESTAMP,
    raw_webhook_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dedupe_key TEXT UNIQUE,
    payment_id UUID REFERENCES payments(id),
    project_id UUID REFERENCES projects(id),
    event_type TEXT NOT NULL,
    provider TEXT NOT NULL DEFAULT 'paystack',
    paystack_reference TEXT,
    raw_payload TEXT NOT NULL,
    processing_status TEXT NOT NULL DEFAULT 'received',
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    processing_error TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id),
    action TEXT NOT NULL,
    actor TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payments_project_id ON payments(project_id);
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'paystack';
CREATE INDEX IF NOT EXISTS idx_payment_events_reference ON payment_events(paystack_reference);
CREATE INDEX IF NOT EXISTS idx_payment_events_provider_status_received ON payment_events(provider, processing_status, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_project_id ON audit_log(project_id);
CREATE INDEX IF NOT EXISTS idx_projects_due_date ON projects(due_date);

-- Source: src/main/resources/db/migration/V1__phase2_state_fields.sql
ALTER TABLE projects ADD COLUMN IF NOT EXISTS block_reason TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deployment_mode TEXT NOT NULL DEFAULT 'developer_hosted';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS service_mode TEXT NOT NULL DEFAULT 'development';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS lifecycle_status TEXT NOT NULL DEFAULT 'active';

ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS dedupe_key TEXT;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_status TEXT NOT NULL DEFAULT 'received';
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processing_error TEXT;
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS payment_events_dedupe_key_idx
    ON payment_events(dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- Source: src/main/resources/db/migration/V2__phase_5_customer_workflows.sql
CREATE TABLE IF NOT EXISTS support_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id),
    requester_name TEXT,
    requester_email TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_support_requests_project_id ON support_requests(project_id);

-- Source: src/main/resources/db/migration/V3__provider_neutral_payments.sql
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'paystack';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider_reference TEXT;
UPDATE payments SET provider_reference = paystack_reference WHERE provider_reference IS NULL;
CREATE INDEX IF NOT EXISTS idx_payments_provider_reference ON payments(provider, provider_reference);

ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'paystack';
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS provider_reference TEXT;
UPDATE payment_events SET provider_reference = paystack_reference WHERE provider_reference IS NULL;

-- Source: src/main/resources/db/migration/V4__integration_outbox.sql
CREATE TABLE IF NOT EXISTS integration_outbox (
    id UUID PRIMARY KEY,
    destination TEXT NOT NULL,
    event_type TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    delivered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_integration_outbox_due ON integration_outbox(delivered_at, next_attempt_at);

-- Source: src/main/resources/db/migration/V5__outbox_claims_and_dead_letters.sql
ALTER TABLE integration_outbox ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE integration_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_integration_outbox_claimable ON integration_outbox(status, next_attempt_at, lease_until);

-- Source: src/main/resources/db/migration/V6__password_reset_tokens.sql
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expiry ON password_reset_tokens(expires_at);

-- Source: src/main/resources/db/migration/V7__deployment_jobs.sql
CREATE TABLE IF NOT EXISTS deployment_jobs (
    id UUID PRIMARY KEY,
    repository TEXT NOT NULL,
    git_ref TEXT NOT NULL,
    registry TEXT NOT NULL,
    image_name TEXT NOT NULL,
    image_tag TEXT NOT NULL,
    container_name TEXT,
    host_port INTEGER,
    container_port INTEGER,
    network TEXT NOT NULL DEFAULT 'bridge',
    restart_policy TEXT NOT NULL DEFAULT 'unless-stopped',
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

-- Source: src/main/resources/db/migration/V8__deployment_container_options.sql
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS container_name TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS host_port INTEGER;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS container_port INTEGER;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS network TEXT NOT NULL DEFAULT 'bridge';
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS restart_policy TEXT NOT NULL DEFAULT 'unless-stopped';

-- Source: src/main/resources/db/migration/V9__github_deployment_mapping.sql
ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_repository TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS github_ref TEXT NOT NULL DEFAULT 'main';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deploy_image_name TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS deploy_image_tag TEXT NOT NULL DEFAULT 'latest';
ALTER TABLE projects ADD COLUMN IF NOT EXISTS auto_deploy BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_projects_github_deploy ON projects(github_repository, github_ref, auto_deploy);

-- Source: src/main/resources/db/migration/V10__deployment_reliability.sql
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS previous_container_name TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS previous_image TEXT;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS project_slug TEXT;
CREATE TABLE IF NOT EXISTS github_webhook_deliveries (
    delivery_id TEXT PRIMARY KEY,
    event TEXT NOT NULL,
    repository TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    queued_count INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_github_webhook_deliveries_received_at ON github_webhook_deliveries(received_at DESC);

-- Source: src/main/resources/db/migration/V11__github_app_installation.sql
CREATE TABLE IF NOT EXISTS github_app_installation (
    id INTEGER PRIMARY KEY,
    installation_id BIGINT NOT NULL,
    account_login TEXT,
    account_type TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- Source: src/main/resources/db/migration/V12__github_installation_state.sql
ALTER TABLE github_app_installation ADD COLUMN IF NOT EXISTS pending_state TEXT;


-- Source: src/main/resources/db/migration/V13__deployment_runtime_spec.sql
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS env_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS volumes_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS create_network_if_missing BOOLEAN NOT NULL DEFAULT FALSE;


-- Source: src/main/resources/db/migration/V14__encrypted_deployment_secrets.sql
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS secret_env_encrypted TEXT;

-- Source: src/main/resources/db/migration/V15__notifications.sql
CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    recipient TEXT NOT NULL,
    project_id UUID NULL REFERENCES projects(id),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    severity TEXT NOT NULL,
    action TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP NULL,
    dismissed_at TIMESTAMP NULL,
    archived_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created ON notifications(recipient, created_at DESC);

-- Source: src/main/resources/db/migration/V16__registry_credentials.sql
CREATE TABLE IF NOT EXISTS registry_credentials (
    registry VARCHAR(255) PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    password_encrypted TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Source: src/main/resources/db/migration/V17__user_profile_fields.sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url TEXT;

-- Source: src/main/resources/db/migration/V18__user_2fa_fields.sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS recovery_codes TEXT[] NOT NULL DEFAULT '{}';

-- Source: src/main/resources/db/migration/V19__immutable_project_base_amount_and_adjustments.sql
ALTER TABLE projects ADD COLUMN IF NOT EXISTS base_amount NUMERIC(12, 2);

UPDATE projects
SET base_amount = amount_due
WHERE base_amount IS NULL AND amount_due > 0;

ALTER TABLE projects
    ADD CONSTRAINT projects_base_amount_positive
    CHECK (base_amount IS NULL OR base_amount > 0);

CREATE OR REPLACE FUNCTION prevent_project_base_amount_change()
RETURNS trigger AS $$
BEGIN
    IF OLD.base_amount IS DISTINCT FROM NEW.base_amount THEN
        RAISE EXCEPTION 'base_amount is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS projects_base_amount_immutable ON projects;
CREATE TRIGGER projects_base_amount_immutable
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION prevent_project_base_amount_change();

CREATE TABLE IF NOT EXISTS project_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id),
    type TEXT NOT NULL CHECK (type IN ('ADDITIONAL_CHARGE', 'DISCOUNT')),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    actor TEXT NOT NULL CHECK (length(trim(actor)) > 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_project_adjustments_project_id ON project_adjustments(project_id);

-- Source: src/main/resources/db/migration/V20__deployment_trigger_source.sql
ALTER TABLE deployment_jobs ADD COLUMN IF NOT EXISTS trigger_source TEXT NOT NULL DEFAULT 'manual';

-- Source: src/main/resources/db/migration/V21__admin_user_lifecycle.sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

-- Source: src/main/resources/db/migration/V22__deployment_configurations_and_executions.sql
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
    auto_deploy BOOLEAN NOT NULL DEFAULT FALSE,
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

-- Source: src/main/resources/db/migration/V23__nginx_sites.sql
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

-- Source: src/main/resources/db/migration/V24__nginx_site_reconciliation_status.sql
ALTER TABLE sites
    ADD COLUMN IF NOT EXISTS reconciliation_status TEXT NOT NULL DEFAULT 'healthy',
    ADD COLUMN IF NOT EXISTS last_nginx_error TEXT,
    ADD COLUMN IF NOT EXISTS last_docker_error TEXT,
    ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'sites_reconciliation_status_check'
    ) THEN
        ALTER TABLE sites
            ADD CONSTRAINT sites_reconciliation_status_check
            CHECK (reconciliation_status IN ('healthy', 'drifted', 'docker_down', 'dead_config', 'disabled', 'error'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_sites_reconciliation_status ON sites(reconciliation_status);

-- Source: src/main/resources/db/migration/V25__customers_and_project_ownership.sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    contact_email TEXT,
    contact_phone TEXT,
    billing_status TEXT NOT NULL DEFAULT 'unknown',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE projects
    ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES customers(id);

CREATE INDEX IF NOT EXISTS idx_projects_customer_id ON projects(customer_id);

-- Source: src/main/resources/db/migration/V26__certificate_lifecycle.sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS certificates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain TEXT NOT NULL UNIQUE,
    issued_at TIMESTAMP,
    expires_at TIMESTAMP,
    renewal_status TEXT NOT NULL DEFAULT 'unknown',
    last_renewal_attempt TIMESTAMP,
    last_renewal_error TEXT
);

ALTER TABLE sites ADD COLUMN IF NOT EXISTS certificate_id UUID REFERENCES certificates(id);
CREATE INDEX IF NOT EXISTS idx_sites_certificate_id ON sites(certificate_id);

-- Source: src/main/resources/db/migration/V27__site_bypass_paths.sql
ALTER TABLE sites
    ADD COLUMN IF NOT EXISTS bypass_paths TEXT NOT NULL DEFAULT '["/api/gate/","/api/paystack/","/api/mpesa/"]';

-- Source: src/main/resources/db/migration/V28__project_billing_information.sql
ALTER TABLE projects ADD COLUMN IF NOT EXISTS billing_name TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS billing_email TEXT;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS billing_address TEXT;

-- Source: src/main/resources/db/migration/V29__unify_project_customer_identity.sql
-- Existing V14 is already used by deployment secrets; this is the next safe Flyway version.
-- Preserve legacy project identity in the canonical customer row before removing the columns.
UPDATE customers c
SET name = COALESCE(NULLIF(c.name, ''), p.client_name),
    contact_email = COALESCE(c.contact_email, p.client_email),
    updated_at = CURRENT_TIMESTAMP
FROM projects p
WHERE p.customer_id = c.id
  AND (NULLIF(p.client_name, '') IS NOT NULL OR NULLIF(p.client_email, '') IS NOT NULL);

ALTER TABLE projects DROP COLUMN IF EXISTS client_name;
ALTER TABLE projects DROP COLUMN IF EXISTS client_email;

-- Source: src/main/resources/db/migration/V30__foreign_key_indexes.sql
-- Keep joins and cascading operational queries index-backed.
CREATE INDEX IF NOT EXISTS idx_payment_events_payment_id ON payment_events(payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_project_id ON payment_events(project_id);
CREATE INDEX IF NOT EXISTS idx_support_requests_project_id ON support_requests(project_id);
CREATE INDEX IF NOT EXISTS idx_notifications_project_id ON notifications(project_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);


-- Consolidated current schema: final additive and retirement changes.
-- Consolidated from V31__deployment_project_ownership.sql
-- Add stable project ownership and environment metadata without changing
-- existing deployment rows or constraining legacy data.
ALTER TABLE deployment_configurations
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

ALTER TABLE deployment_executions
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

ALTER TABLE deployment_jobs
    ADD COLUMN IF NOT EXISTS project_id UUID,
    ADD COLUMN IF NOT EXISTS environment TEXT;

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_project_id
    ON deployment_configurations(project_id);

CREATE INDEX IF NOT EXISTS idx_deployment_executions_project_id
    ON deployment_executions(project_id);

CREATE INDEX IF NOT EXISTS idx_deployment_jobs_project_id
    ON deployment_jobs(project_id);

-- Consolidated from V32__projects_container_optional.sql
-- Projects may be created before a runtime container exists.
ALTER TABLE projects ALTER COLUMN container_name DROP NOT NULL;

-- Consolidated from V33__canonical_deployments_state_machine.sql
CREATE TABLE deployments (
    id UUID PRIMARY KEY,
    project_id UUID REFERENCES projects(id) ON DELETE RESTRICT,
    environment TEXT NOT NULL DEFAULT 'production',
    configuration_id UUID NOT NULL REFERENCES deployment_configurations(id) ON DELETE RESTRICT,
    execution_id UUID NOT NULL UNIQUE REFERENCES deployment_executions(id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN (
        'queued', 'building', 'starting', 'health-checking', 'active',
        'superseded', 'failed', 'cancelled', 'rolled-back'
    )),
    trigger_source TEXT NOT NULL,
    replaces_deployment_id UUID REFERENCES deployments(id) ON DELETE RESTRICT,
    rolled_back_to_deployment_id UUID REFERENCES deployments(id) ON DELETE RESTRICT,
    failure_reason TEXT,
    queued_at TIMESTAMP,
    building_at TIMESTAMP,
    starting_at TIMESTAMP,
    health_checking_at TIMESTAMP,
    active_at TIMESTAMP,
    superseded_at TIMESTAMP,
    failed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    rolled_back_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deployments_environment_nonblank CHECK (length(trim(environment)) > 0),
    CONSTRAINT deployments_not_self_referential CHECK (
        (replaces_deployment_id IS NULL OR replaces_deployment_id <> id)
        AND (rolled_back_to_deployment_id IS NULL OR rolled_back_to_deployment_id <> id)
    )
);

CREATE INDEX idx_deployments_project_environment_created
    ON deployments(project_id, environment, created_at DESC);
CREATE INDEX idx_deployments_status_created
    ON deployments(status, created_at);

-- Consolidated from V34__one_active_deployment_per_environment.sql
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

-- Consolidated from V35__deployment_candidate_runtime.sql
ALTER TABLE deployments
    ADD COLUMN runtime_container_name TEXT,
    ADD COLUMN runtime_host_port INTEGER;

ALTER TABLE deployments
    ADD CONSTRAINT deployments_runtime_host_port_valid
    CHECK (runtime_host_port IS NULL OR runtime_host_port BETWEEN 1 AND 65535);

-- Consolidated from V36__deployment_readiness_contract.sql
ALTER TABLE deployment_configurations
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;

ALTER TABLE deployment_executions
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;

ALTER TABLE deployment_jobs
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;


ALTER TABLE deployment_configurations
    ADD CONSTRAINT deployment_configurations_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_configurations_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_configurations_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_configurations_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);

ALTER TABLE deployment_executions
    ADD CONSTRAINT deployment_executions_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_executions_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_executions_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_executions_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);

ALTER TABLE deployment_jobs
    ADD CONSTRAINT deployment_jobs_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_jobs_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_jobs_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_jobs_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);

-- Consolidated from V37__deployment_runtime_port_mappings.sql
ALTER TABLE deployments
    ADD COLUMN runtime_ports_json TEXT NOT NULL DEFAULT '{}';

-- Consolidated from V38__allow_multiple_sites_per_project.sql
-- Keep the project lookup index while preparing the schema for multiple domains.
-- API and UI creation flows continue to enforce one site per project for now.
ALTER TABLE sites DROP CONSTRAINT IF EXISTS sites_project_id_key;

CREATE INDEX IF NOT EXISTS idx_sites_project_id ON sites(project_id);

-- Consolidated from V39__provider_credentials.sql
-- Provider-scoped infrastructure credentials. Existing registry credentials
-- remain available while application code dual-writes through the new model.
CREATE TABLE IF NOT EXISTS provider_credentials (
    id UUID PRIMARY KEY,
    provider TEXT NOT NULL,
    credential_type TEXT NOT NULL,
    display_name TEXT NOT NULL,
    scope TEXT NOT NULL,
    encrypted_payload TEXT NOT NULL,
    rotated_at TIMESTAMP NULL,
    rotated_by TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by TEXT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by TEXT NULL,
    CONSTRAINT uq_provider_credentials_identity UNIQUE (provider, credential_type, scope)
);

CREATE INDEX IF NOT EXISTS idx_provider_credentials_provider_type
    ON provider_credentials(provider, credential_type);

-- Consolidated from V40__project_secret_set_versions.sql
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

-- Consolidated from V41__deployment_credential_and_secret_references.sql
-- New deployments/configurations can point at immutable secret versions while
-- retaining encrypted compatibility snapshots for existing readers.
ALTER TABLE deployment_configurations
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

ALTER TABLE deployment_executions
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

ALTER TABLE deployments
    ADD COLUMN IF NOT EXISTS credential_set_id UUID,
    ADD COLUMN IF NOT EXISTS credential_set_version INTEGER,
    ADD COLUMN IF NOT EXISTS secret_set_id UUID,
    ADD COLUMN IF NOT EXISTS secret_set_version INTEGER;

CREATE INDEX IF NOT EXISTS idx_deployment_configurations_secret_set
    ON deployment_configurations(secret_set_id);
CREATE INDEX IF NOT EXISTS idx_deployment_executions_secret_set
    ON deployment_executions(secret_set_id);
CREATE INDEX IF NOT EXISTS idx_deployments_credential_set
    ON deployments(credential_set_id);
CREATE INDEX IF NOT EXISTS idx_deployments_secret_set
    ON deployments(secret_set_id);

-- Consolidated from V42__version_provider_credentials.sql
-- Preserve the original credential row on rotation and retain every encrypted version.
ALTER TABLE provider_credentials
    DROP CONSTRAINT IF EXISTS uq_provider_credentials_identity;

DROP INDEX IF EXISTS uq_provider_credentials_identity;

ALTER TABLE provider_credentials
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS superseded_by_id UUID NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_credentials_version
    ON provider_credentials(provider, credential_type, scope, version);

CREATE UNIQUE INDEX IF NOT EXISTS uq_provider_credentials_current
    ON provider_credentials(provider, credential_type, scope)
    WHERE superseded_at IS NULL;

-- Consolidated from V43__allow_containerless_site_drafts.sql
-- A Docker-discovery site can be saved before the project has a runtime.
-- Its upstream is resolved from the active deployment at render/cutover time.
ALTER TABLE sites
    DROP CONSTRAINT sites_upstream_configuration_check,
    ADD CONSTRAINT sites_upstream_configuration_check CHECK (
        (upstream_mode = 'docker_discovery' AND upstream_explicit_port IS NULL)
        OR
        (upstream_mode = 'explicit_port' AND upstream_container_name IS NULL AND upstream_explicit_port IS NOT NULL AND upstream_explicit_port BETWEEN 1 AND 65535)
    );

-- Consolidated from V44__retire_project_runtime_compatibility_columns.sql
-- Project identity and GitHub deployment settings now belong to the project FK
-- and its deployment configuration. Import a legacy active container only when
-- an existing completed execution provides an unambiguous matching config.
-- Never synthesize a deployment from project.container_name alone.
DO $$
DECLARE
    legacy RECORD;
    deployment_id UUID;
BEGIN
    FOR legacy IN
        SELECT p.id AS project_id, p.container_name, c.id AS configuration_id,
               c.environment, e.id AS execution_id
        FROM projects p
        JOIN deployment_configurations c ON c.project_id = p.id
        JOIN LATERAL (
            SELECT x.id
            FROM deployment_executions x
            WHERE x.configuration_id = c.id
              AND x.project_id = p.id
              AND x.status = 'succeeded'
              AND x.container_name = p.container_name
            ORDER BY COALESCE(x.completed_at, x.updated_at) DESC, x.created_at DESC
            LIMIT 1
        ) e ON TRUE
        WHERE p.container_name IS NOT NULL
          AND c.environment = 'production'
          AND NOT EXISTS (
              SELECT 1 FROM deployments d
              WHERE d.project_id = p.id
                AND d.environment = 'production'
                AND d.status = 'active'
          )
    LOOP
        deployment_id := gen_random_uuid();
        INSERT INTO deployments (
            id, project_id, environment, configuration_id, execution_id, status,
            trigger_source, runtime_container_name, runtime_host_port, runtime_ports_json,
            queued_at, building_at, starting_at, health_checking_at, active_at, created_at, updated_at
        )
        SELECT deployment_id, legacy.project_id, legacy.environment, legacy.configuration_id, legacy.execution_id,
               'active', COALESCE(x.trigger_source, 'legacy-import'), legacy.container_name, x.host_port,
               CASE
                   WHEN x.host_port IS NOT NULL AND x.container_port IS NOT NULL
                   THEN jsonb_build_object(x.container_port::text, x.host_port)::text
                   ELSE '{}' 
               END,
               x.created_at, x.started_at, x.started_at, x.started_at,
               COALESCE(x.completed_at, x.updated_at, CURRENT_TIMESTAMP),
               COALESCE(x.completed_at, x.updated_at, x.created_at, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP
        FROM deployment_executions x
        WHERE x.id = legacy.execution_id;
    END LOOP;

    IF EXISTS (
        SELECT 1 FROM projects p
        WHERE p.container_name IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM deployments d
              WHERE d.project_id = p.id
                AND d.environment = 'production'
                AND d.status = 'active'
                AND d.runtime_container_name = p.container_name
          )
    ) THEN
        RAISE EXCEPTION 'V44 blocked: project container references without an active canonical deployment require operator import';
    END IF;

    IF EXISTS (SELECT 1 FROM deployment_configurations WHERE project_id IS NULL)
       OR EXISTS (SELECT 1 FROM deployment_executions WHERE project_id IS NULL)
       OR EXISTS (SELECT 1 FROM deployment_jobs WHERE project_id IS NULL)
       OR EXISTS (SELECT 1 FROM deployments WHERE project_id IS NULL) THEN
        RAISE EXCEPTION 'V44 blocked: deployment rows without project_id must be repaired before removing slug ownership';
    END IF;

    IF EXISTS (
        SELECT 1 FROM projects p
        WHERE (p.github_repository IS NOT NULL
               OR p.github_ref <> 'main'
               OR p.deploy_image_name IS NOT NULL
               OR p.deploy_image_tag <> 'latest'
               OR p.auto_deploy)
          AND NOT EXISTS (
              SELECT 1 FROM deployment_configurations c
              WHERE c.project_id = p.id
          )
    ) THEN
        RAISE EXCEPTION 'V44 blocked: project-level deployment settings require a configuration before retirement';
    END IF;
END $$;

ALTER TABLE deployments ALTER COLUMN project_id SET NOT NULL;

UPDATE deployment_configurations c
SET repository = COALESCE(NULLIF(p.github_repository, ''), c.repository),
    git_ref = COALESCE(NULLIF(p.github_ref, ''), c.git_ref),
    image_name = COALESCE(NULLIF(p.deploy_image_name, ''), c.image_name),
    image_tag = COALESCE(NULLIF(p.deploy_image_tag, ''), c.image_tag),
    auto_deploy = p.auto_deploy
FROM projects p
WHERE c.project_id = p.id;

ALTER TABLE deployment_configurations ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE deployment_executions ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE deployment_jobs ALTER COLUMN project_id SET NOT NULL;

ALTER TABLE deployment_configurations ALTER COLUMN environment SET NOT NULL;
ALTER TABLE deployment_executions ALTER COLUMN environment SET NOT NULL;
ALTER TABLE deployment_jobs ALTER COLUMN environment SET NOT NULL;

ALTER TABLE deployment_configurations DROP COLUMN container_name;
ALTER TABLE deployment_executions DROP COLUMN container_name;
ALTER TABLE deployment_jobs DROP COLUMN container_name;
ALTER TABLE deployment_executions DROP COLUMN previous_container_name;
ALTER TABLE deployment_executions DROP COLUMN previous_image;
ALTER TABLE deployment_jobs DROP COLUMN previous_container_name;
ALTER TABLE deployment_jobs DROP COLUMN previous_image;

ALTER TABLE deployment_configurations DROP COLUMN project_slug;
ALTER TABLE deployment_executions DROP COLUMN project_slug;
ALTER TABLE deployment_jobs DROP COLUMN project_slug;

DROP INDEX IF EXISTS idx_projects_github_deploy;

ALTER TABLE projects
    DROP COLUMN container_name,
    DROP COLUMN github_repository,
    DROP COLUMN github_ref,
    DROP COLUMN deploy_image_name,
    DROP COLUMN deploy_image_tag,
    DROP COLUMN auto_deploy;

ALTER TABLE sites DROP CONSTRAINT IF EXISTS sites_upstream_configuration_check;
ALTER TABLE sites
    ADD CONSTRAINT sites_upstream_configuration_check CHECK (
        (upstream_mode = 'docker_discovery' AND upstream_explicit_port IS NULL)
        OR
        (upstream_mode = 'explicit_port' AND upstream_explicit_port IS NOT NULL AND upstream_explicit_port BETWEEN 1 AND 65535)
    ),
    DROP COLUMN upstream_container_name;
