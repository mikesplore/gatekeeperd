-- Keep the default service's identity stable even when its display name changes.
ALTER TABLE services
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE services
SET is_default = TRUE
WHERE name = 'default';

CREATE UNIQUE INDEX uq_services_project_default
    ON services (project_id)
    WHERE is_default = TRUE;
