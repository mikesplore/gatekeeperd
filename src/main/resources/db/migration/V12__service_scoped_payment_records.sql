ALTER TABLE payments
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE SET NULL;

ALTER TABLE payment_events
    ADD COLUMN service_id UUID REFERENCES services(id) ON DELETE SET NULL;

CREATE INDEX idx_payments_service_id ON payments(service_id);
CREATE INDEX idx_payment_events_service_id ON payment_events(service_id);
