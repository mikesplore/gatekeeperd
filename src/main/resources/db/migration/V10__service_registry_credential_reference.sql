ALTER TABLE deployment_configurations
    ADD COLUMN registry_credential_id UUID NULL REFERENCES provider_credentials(id) ON DELETE RESTRICT;

ALTER TABLE deployment_executions
    ADD COLUMN registry_credential_id UUID NULL REFERENCES provider_credentials(id) ON DELETE RESTRICT;

UPDATE deployment_configurations configuration
SET registry_credential_id = credentials.id
FROM provider_credentials credentials
WHERE credentials.provider = 'docker'
  AND credentials.credential_type = 'registry'
  AND credentials.scope = configuration.registry
  AND credentials.superseded_at IS NULL;

UPDATE deployment_executions execution
SET registry_credential_id = configuration.registry_credential_id
FROM deployment_configurations configuration
WHERE configuration.id = execution.configuration_id;
