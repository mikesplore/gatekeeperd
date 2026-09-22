-- Existing V14 is already used by deployment secrets; this is the next safe Flyway version.
-- Preserve legacy project identity in the canonical customer row before removing the columns.
UPDATE customers c
SET name = COALESCE(NULLIF(c.name, ''), p.client_name),
    contact_email = COALESCE(c.contact_email, p.client_email),
    updated_at = CURRENT_TIMESTAMP
FROM projects p
WHERE p.customer_id = c.id
  AND (NULLIF(p.client_name, '') IS NOT NULL OR NULLIF(p.client_email, '') IS NOT NULL);

ALTER TABLE projects DROP COLUMN IF EXISTS client_name;
ALTER TABLE projects DROP COLUMN IF EXISTS client_email;
