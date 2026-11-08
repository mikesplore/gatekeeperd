-- Archived projects are no longer exposed through site management. Remove
-- retained site records so their domains cannot be rediscovered as live sites.
DELETE FROM sites s
USING projects p
WHERE s.project_id = p.id
  AND p.deleted_at IS NOT NULL;
