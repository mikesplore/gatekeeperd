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
