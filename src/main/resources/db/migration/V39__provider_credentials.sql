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
