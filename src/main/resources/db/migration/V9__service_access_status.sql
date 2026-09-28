ALTER TABLE services
    ADD COLUMN access_status TEXT NOT NULL DEFAULT 'active',
    ADD COLUMN block_reason TEXT;

ALTER TABLE services
    ADD CONSTRAINT chk_services_access_status
        CHECK (access_status IN ('active', 'blocked', 'manual_block'));
