-- Project identity and GitHub deployment settings now belong to the project FK
-- and its deployment configuration. Refuse to drop data that has not been
-- migrated to those canonical records.
DO $$
BEGIN
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
