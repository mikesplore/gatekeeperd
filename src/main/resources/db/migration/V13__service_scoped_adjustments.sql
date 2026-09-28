ALTER TABLE project_adjustments
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE RESTRICT;

CREATE INDEX ix_project_adjustments_service_id
    ON project_adjustments(service_id);
