-- Archived projects are hidden from service management and their Nginx sites
-- are removed. Release their service-level domain reservations as well.
DELETE FROM service_domains sd
USING services s, projects p
WHERE sd.service_id = s.id
  AND s.project_id = p.id
  AND p.deleted_at IS NOT NULL;
