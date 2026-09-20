CREATE TABLE IF NOT EXISTS github_app_installation (
    id INTEGER PRIMARY KEY,
    installation_id BIGINT NOT NULL,
    account_login TEXT,
    account_type TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

