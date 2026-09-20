ALTER TABLE projects ADD COLUMN IF NOT EXISTS base_amount NUMERIC(12, 2);

UPDATE projects
SET base_amount = amount_due
WHERE base_amount IS NULL AND amount_due > 0;

ALTER TABLE projects
    ADD CONSTRAINT projects_base_amount_positive
    CHECK (base_amount IS NULL OR base_amount > 0);

CREATE OR REPLACE FUNCTION prevent_project_base_amount_change()
RETURNS trigger AS $$
BEGIN
    IF OLD.base_amount IS DISTINCT FROM NEW.base_amount THEN
        RAISE EXCEPTION 'base_amount is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS projects_base_amount_immutable ON projects;
CREATE TRIGGER projects_base_amount_immutable
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION prevent_project_base_amount_change();

CREATE TABLE IF NOT EXISTS project_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id),
    type TEXT NOT NULL CHECK (type IN ('ADDITIONAL_CHARGE', 'DISCOUNT')),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
    actor TEXT NOT NULL CHECK (length(trim(actor)) > 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_project_adjustments_project_id ON project_adjustments(project_id);
