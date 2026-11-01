CREATE TABLE service_invoices (
    service_id UUID PRIMARY KEY REFERENCES services(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    due_date DATE,
    opening_paid NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (opening_paid >= 0),
    scribed_invoice_id TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_service_invoices_project_id ON service_invoices(project_id);
