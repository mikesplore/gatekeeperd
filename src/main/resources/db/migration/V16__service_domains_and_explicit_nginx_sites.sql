ALTER TABLE services
    ADD COLUMN domain TEXT;

-- Preserve domains from service setup drafts. They are configuration for a
-- service; only the explicit Nginx flow creates Site rows/configuration.
UPDATE services svc
SET domain = site.domain
FROM sites site
WHERE site.service_id = svc.id
  AND svc.domain IS NULL;
