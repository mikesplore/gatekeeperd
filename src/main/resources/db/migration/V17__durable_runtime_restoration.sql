ALTER TABLE deployments
    ADD COLUMN runtime_image_reference TEXT,
    ADD COLUMN runtime_networks_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN runtime_port_host_ips_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN runtime_options_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN cutover_state TEXT NOT NULL DEFAULT 'not_started',
    ADD COLUMN cutover_previous_nginx_config TEXT,
    ADD COLUMN cutover_error TEXT;
