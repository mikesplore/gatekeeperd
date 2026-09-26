-- A Docker-discovery site can be saved before the project has a runtime.
-- Its upstream is resolved from the active deployment at render/cutover time.
ALTER TABLE sites
    DROP CONSTRAINT sites_upstream_configuration_check,
    ADD CONSTRAINT sites_upstream_configuration_check CHECK (
        (upstream_mode = 'docker_discovery' AND upstream_explicit_port IS NULL)
        OR
        (upstream_mode = 'explicit_port' AND upstream_container_name IS NULL AND upstream_explicit_port IS NOT NULL AND upstream_explicit_port BETWEEN 1 AND 65535)
    );
