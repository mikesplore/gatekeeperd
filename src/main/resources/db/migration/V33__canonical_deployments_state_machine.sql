CREATE TABLE deployments (
    id UUID PRIMARY KEY,
    project_id UUID REFERENCES projects(id) ON DELETE RESTRICT,
    environment TEXT NOT NULL DEFAULT 'production',
    configuration_id UUID NOT NULL REFERENCES deployment_configurations(id) ON DELETE RESTRICT,
    execution_id UUID NOT NULL UNIQUE REFERENCES deployment_executions(id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN (
        'queued', 'building', 'starting', 'health-checking', 'active',
        'superseded', 'failed', 'cancelled', 'rolled-back'
    )),
    trigger_source TEXT NOT NULL,
    replaces_deployment_id UUID REFERENCES deployments(id) ON DELETE RESTRICT,
    rolled_back_to_deployment_id UUID REFERENCES deployments(id) ON DELETE RESTRICT,
    failure_reason TEXT,
    queued_at TIMESTAMP,
    building_at TIMESTAMP,
    starting_at TIMESTAMP,
    health_checking_at TIMESTAMP,
    active_at TIMESTAMP,
    superseded_at TIMESTAMP,
    failed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    rolled_back_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deployments_environment_nonblank CHECK (length(trim(environment)) > 0),
    CONSTRAINT deployments_not_self_referential CHECK (
        (replaces_deployment_id IS NULL OR replaces_deployment_id <> id)
        AND (rolled_back_to_deployment_id IS NULL OR rolled_back_to_deployment_id <> id)
    )
);

CREATE INDEX idx_deployments_project_environment_created
    ON deployments(project_id, environment, created_at DESC);
CREATE INDEX idx_deployments_status_created
    ON deployments(status, created_at);
