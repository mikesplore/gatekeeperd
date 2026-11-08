ALTER TABLE payments ADD COLUMN payer_fingerprint TEXT;

CREATE INDEX idx_payments_pending_mpesa_payer
    ON payments(service_id, payer_fingerprint)
    WHERE provider = 'mpesa' AND gateway_status = 'pending' AND payer_fingerprint IS NOT NULL;
