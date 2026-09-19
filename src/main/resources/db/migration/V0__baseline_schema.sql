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
