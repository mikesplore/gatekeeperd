ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'paystack';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS provider_reference TEXT;
UPDATE payments SET provider_reference = paystack_reference WHERE provider_reference IS NULL;
CREATE INDEX IF NOT EXISTS idx_payments_provider_reference ON payments(provider, provider_reference);

ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'paystack';
ALTER TABLE payment_events ADD COLUMN IF NOT EXISTS provider_reference TEXT;
UPDATE payment_events SET provider_reference = paystack_reference WHERE provider_reference IS NULL;
