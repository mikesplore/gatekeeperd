-- A deployment may consume a prebuilt registry image without a source repository.
ALTER TABLE deployment_configurations ALTER COLUMN repository DROP NOT NULL;
ALTER TABLE deployment_executions ALTER COLUMN repository DROP NOT NULL;
ALTER TABLE deployment_jobs ALTER COLUMN repository DROP NOT NULL;
