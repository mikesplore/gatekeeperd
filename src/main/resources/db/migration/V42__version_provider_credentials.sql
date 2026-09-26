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
