ALTER TABLE projects
    ADD COLUMN block_reason_code TEXT,
    ADD COLUMN block_reason_note TEXT,
    ADD CONSTRAINT chk_projects_block_reason_code
        CHECK (block_reason_code IS NULL OR block_reason_code IN ('payment', 'manual_hold', 'abuse_tos', 'suspended_by_request', 'other'));

ALTER TABLE services
    ADD COLUMN block_reason_code TEXT,
    ADD COLUMN block_reason_note TEXT,
    ADD CONSTRAINT chk_services_block_reason_code
        CHECK (block_reason_code IS NULL OR block_reason_code IN ('payment', 'manual_hold', 'abuse_tos', 'suspended_by_request', 'other'));

-- Preserve the legacy text fields and classify only values whose meaning is
-- already explicit. Unknown values remain available in block_reason.
UPDATE projects
SET block_reason_code = CASE lower(block_reason)
    WHEN 'overdue' THEN 'payment'
    WHEN 'payment_reversed' THEN 'payment'
    WHEN 'payment_overdue' THEN 'payment'
    WHEN 'manual' THEN 'manual_hold'
    ELSE NULL
END
WHERE block_reason IS NOT NULL;

UPDATE services
SET block_reason_code = CASE lower(block_reason)
    WHEN 'manual' THEN 'manual_hold'
    ELSE NULL
END
WHERE block_reason IS NOT NULL;
