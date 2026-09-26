-- Keep applied V1 immutable: existing databases have already recorded its checksum.
-- Email addresses are compared after trimming and lowercasing. Customer email is
-- optional, so only nonblank values participate in its unique index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_normalized
    ON users (lower(btrim(email)));

CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_contact_email_normalized
    ON customers (lower(btrim(contact_email)))
    WHERE contact_email IS NOT NULL AND btrim(contact_email) <> '';
