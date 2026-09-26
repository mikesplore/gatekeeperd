ALTER TABLE deployment_configurations
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;

ALTER TABLE deployment_executions
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;

ALTER TABLE deployment_jobs
    ADD COLUMN readiness_type TEXT,
    ADD COLUMN readiness_target TEXT,
    ADD COLUMN readiness_timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN readiness_interval_seconds INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN readiness_probe_timeout_millis INTEGER NOT NULL DEFAULT 1000;


ALTER TABLE deployment_configurations
    ADD CONSTRAINT deployment_configurations_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_configurations_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_configurations_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_configurations_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);

ALTER TABLE deployment_executions
    ADD CONSTRAINT deployment_executions_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_executions_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_executions_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_executions_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);

ALTER TABLE deployment_jobs
    ADD CONSTRAINT deployment_jobs_readiness_type_valid
        CHECK (readiness_type IS NULL OR readiness_type IN ('docker', 'http', 'tcp', 'process')),
    ADD CONSTRAINT deployment_jobs_readiness_timeout_valid
        CHECK (readiness_timeout_seconds BETWEEN 1 AND 600),
    ADD CONSTRAINT deployment_jobs_readiness_interval_valid
        CHECK (readiness_interval_seconds BETWEEN 1 AND 30),
    ADD CONSTRAINT deployment_jobs_readiness_probe_timeout_valid
        CHECK (readiness_probe_timeout_millis BETWEEN 100 AND 30000);
