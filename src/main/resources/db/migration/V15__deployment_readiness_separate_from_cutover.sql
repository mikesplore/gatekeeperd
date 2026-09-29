ALTER TABLE deployments
    ADD COLUMN ready_at TIMESTAMP;

ALTER TABLE deployments
    DROP CONSTRAINT deployments_status_check;

ALTER TABLE deployments
    ADD CONSTRAINT deployments_status_check CHECK (status IN (
        'queued', 'building', 'starting', 'health-checking', 'ready', 'active',
        'superseded', 'failed', 'cancelled', 'rolled-back'
    ));
