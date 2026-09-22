
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
CREATE INDEX IF NOT EXISTS idx_payment_events_reference ON payment_events(paystack_reference);
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
