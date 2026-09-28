-- Establish a stable service identity for every project. Existing projects
-- receive one default service; repeated execution repairs only missing rows.
CREATE TABLE services (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    CONSTRAINT uq_services_project_name UNIQUE (project_id, name)
);

INSERT INTO services (project_id, name)
SELECT p.id, 'default'
FROM projects p
WHERE NOT EXISTS (
    SELECT 1 FROM services s WHERE s.project_id = p.id AND s.name = 'default'
);
