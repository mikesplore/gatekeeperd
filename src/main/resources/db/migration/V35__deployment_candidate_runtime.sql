ALTER TABLE deployments
    ADD COLUMN runtime_container_name TEXT,
    ADD COLUMN runtime_host_port INTEGER;

ALTER TABLE deployments
    ADD CONSTRAINT deployments_runtime_host_port_valid
    CHECK (runtime_host_port IS NULL OR runtime_host_port BETWEEN 1 AND 65535);
