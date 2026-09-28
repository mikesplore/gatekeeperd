ALTER TABLE sites
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;

UPDATE sites s
SET service_id = svc.id
FROM services svc
WHERE svc.project_id = s.project_id
  AND svc.name = 'default'
  AND s.service_id IS NULL;

CREATE INDEX idx_sites_service_id ON sites(service_id);
