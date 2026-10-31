-- Registry credentials are managed centrally and selected by registry at deploy time.
-- Remove the deprecated per-service credential pointer after preserving immutable
-- credential references on individual deployment executions.
ALTER TABLE deployment_configurations
    DROP COLUMN IF EXISTS registry_credential_id;
