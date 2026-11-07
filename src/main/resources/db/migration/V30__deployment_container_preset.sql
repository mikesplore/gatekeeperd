ALTER TABLE deployment_configurations ADD COLUMN preset TEXT;
ALTER TABLE deployment_executions ADD COLUMN preset TEXT;
ALTER TABLE deployment_jobs ADD COLUMN preset TEXT;
ALTER TABLE deployment_configurations ADD COLUMN runtime_options_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE deployment_executions ADD COLUMN runtime_options_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE deployment_jobs ADD COLUMN runtime_options_json TEXT NOT NULL DEFAULT '{}';
