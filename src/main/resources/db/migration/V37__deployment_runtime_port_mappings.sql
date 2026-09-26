ALTER TABLE deployments
    ADD COLUMN runtime_ports_json TEXT NOT NULL DEFAULT '{}';
